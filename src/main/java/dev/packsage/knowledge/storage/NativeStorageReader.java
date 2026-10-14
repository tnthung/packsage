package dev.packsage.knowledge.storage;

import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.nativegame.NativeInventoryReader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Reads bounded, client-visible storage observations without opening or mutating a container. */
public final class NativeStorageReader implements StorageReader {
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_PAGE = 32;
    private static final int MAX_RETAINED = 8;
    private static final int MAX_ENTRY_METADATA = 12 * 1024;
    private static final int MAX_PAGE_PAYLOAD = 96 * 1024;
    private static final long TTL_NANOS = TimeUnit.MINUTES.toNanos(5);
    private final Minecraft client;
    private final Executor executor;
    private final Supplier<Evidence.Scope> currentScope;
    private final LongSupplier monotonicClock;
    private final BiFunction<String, Evidence.Scope, NativeInventoryReader.LocatedItem> itemResolver;
    private final Supplier<HolderLookup.Provider> registries;
    private final LinkedHashMap<String, Cursor> cursors = new LinkedHashMap<>();
    private Observation opened;

    public NativeStorageReader(Minecraft client, Executor executor, Supplier<Evidence.Scope> currentScope,
                               BiFunction<String, Evidence.Scope, NativeInventoryReader.LocatedItem> itemResolver) {
        this(client, executor, currentScope, System::nanoTime, itemResolver,
                () -> client.level == null ? null : client.level.registryAccess());
    }

    NativeStorageReader(Executor executor, Supplier<Evidence.Scope> currentScope, LongSupplier monotonicClock) {
        this(null, executor, currentScope, monotonicClock, null, () -> null);
    }

    NativeStorageReader(Executor executor, Supplier<Evidence.Scope> currentScope, LongSupplier monotonicClock,
                        BiFunction<String, Evidence.Scope, NativeInventoryReader.LocatedItem> itemResolver,
                        Supplier<HolderLookup.Provider> registries) {
        this(null, executor, currentScope, monotonicClock, itemResolver, registries);
    }

    private NativeStorageReader(Minecraft client, Executor executor, Supplier<Evidence.Scope> currentScope,
                                LongSupplier monotonicClock,
                                BiFunction<String, Evidence.Scope, NativeInventoryReader.LocatedItem> itemResolver,
                                Supplier<HolderLookup.Provider> registries) {
        this.client = client;
        this.executor = Objects.requireNonNull(executor);
        this.currentScope = Objects.requireNonNull(currentScope);
        this.monotonicClock = Objects.requireNonNull(monotonicClock);
        this.itemResolver = itemResolver;
        this.registries = Objects.requireNonNull(registries);
    }

    /** Must be called on Minecraft's client thread, before the container screen closes. */
    public void captureOpened(AbstractContainerScreen<?> screen, Evidence.Scope scope) {
        if (!client.isSameThread()) throw new IllegalStateException("opened storage must be captured on the client thread");
        Objects.requireNonNull(screen);
        Objects.requireNonNull(scope);
        if (client.player == null || client.level == null || screen.getMenu() == client.player.inventoryMenu) return;

        var capturedAt = Instant.now();
        var capturedNanos = monotonicClock.getAsLong();
        var menu = screen.getMenu();
        var capturedEntries = openedEntries(menu, client.player.getInventory(), scope, capturedAt,
                client.level.registryAccess());
        var menuTypeId = "unknown:menu";
        try {
            var menuType = BuiltInRegistries.MENU.getKey(menu.getType());
            if (menuType != null) menuTypeId = menuType.toString();
        } catch (RuntimeException ignored) {
        }
        var details = new LinkedHashMap<String, Evidence.JsonValue>();
        details.put("observation", new Evidence.JsonString("last_opened_menu"));
        details.put("completeness", new Evidence.JsonString("partial"));
        details.put("menuType", new Evidence.JsonString(menuTypeId));
        details.put("containerId", new Evidence.JsonNumber(BigDecimal.valueOf(menu.containerId)));
        if (capturedEntries.truncated()) details.put("truncated", new Evidence.JsonBoolean(true));
        var title = screen.getTitle().getString();
        storeOpened(capturedEntries.entries(), scope, capturedAt, capturedNanos, title, details,
                Evidence.Coverage.PARTIAL);
    }

    static OpenedEntries openedEntries(AbstractContainerMenu menu, Container playerInventory, Evidence.Scope scope,
                                       Instant capturedAt, HolderLookup.Provider registries) {
        var entries = new ArrayList<Evidence.Record>();
        var truncated = false;
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            if (slot.container == playerInventory) continue;
            ItemStack stack = slot.getItem().copy();
            if (stack.isEmpty()) continue;
            if (entries.size() == MAX_ENTRIES) {
                truncated = true;
                break;
            }
            Evidence.JsonObject encodedComponents;
            try {
                encodedComponents = net.minecraft.core.component.DataComponentMap.CODEC.encodeStart(
                        registries.createSerializationContext(JsonOps.INSTANCE), stack.getComponents().filter(type ->
                                type != DataComponents.CONTAINER && type != DataComponents.BUNDLE_CONTENTS))
                        .result().filter(com.google.gson.JsonElement::isJsonObject)
                        .map(value -> Evidence.deserializeObject(value.toString())).orElseGet(() ->
                                new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown"))));
            } catch (RuntimeException ignored) {
                encodedComponents = new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown")));
            }
            var metadataUnavailable = encodedComponents.values().containsKey("status");
            var metadataTruncated = metadataUnavailable
                    || Evidence.serialize(encodedComponents).getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_METADATA;
            var payload = new LinkedHashMap<String, Evidence.JsonValue>();
            var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            payload.put("id", new Evidence.JsonString(id == null ? "unknown:item" : id.toString()));
            payload.put("name", new Evidence.JsonString(stack.getHoverName().getString().length() <= 256
                    ? stack.getHoverName().getString() : stack.getHoverName().getString().substring(0, 256)));
            payload.put("count", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
            payload.put("menuSlot", new Evidence.JsonNumber(BigDecimal.valueOf(index)));
            payload.put("observation", new Evidence.JsonString("last_opened_menu"));
            if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)) {
                payload.put("nestedStorageContentsOmitted", new Evidence.JsonBoolean(true));
            }
            payload.put("components", metadataTruncated
                    ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated")))
                    : encodedComponents);
            if (metadataTruncated) payload.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
            if (!metadataTruncated && Evidence.serialize(new Evidence.JsonObject(payload)).getBytes(StandardCharsets.UTF_8).length
                    > MAX_ENTRY_METADATA) {
                metadataTruncated = true;
                payload.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
                payload.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
            }
            var source = new Evidence.Source(scope, "native", "1.21.1", "storage-entry:opened:" + index,
                    scope.requestGeneration(), capturedAt, Evidence.Activity.ACTIVE,
                    metadataTruncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    Evidence.Authority.VIEWER, List.of("client-visible-menu-slot"));
            entries.add(new Evidence.Record(source, Evidence.RecordKind.ITEM,
                    id == null ? "unknown:item" : id.toString(), new Evidence.JsonObject(payload), List.of()));
        }
        return new OpenedEntries(entries, truncated);
    }

    synchronized void storeOpened(List<Evidence.Record> entries, Evidence.Scope scope, Instant capturedAt,
                                  long capturedNanos, String title, Map<String, Evidence.JsonValue> details,
                                  Evidence.Coverage coverage) {
        opened = new Observation(entries, scope, capturedAt, capturedNanos,
                title.length() <= 256 ? title : title.substring(0, 256), details, coverage,
                List.of("client-storage-observation", "last-opened-menu"));
    }

    public synchronized void clear() {
        opened = null;
        cursors.clear();
    }

    @Override
    public CompletionStage<Evidence.Result> read(Request request) {
        Objects.requireNonNull(request);
        if (request.limit() > MAX_PAGE) {
            return CompletableFuture.completedFuture(error(request, "limit must be between 1 and 32"));
        }
        return CompletableFuture.supplyAsync(() -> client == null || client.isSameThread() ? readOnClient(request)
                : client.submit(() -> readOnClient(request)).join(), executor);
    }

    private Evidence.Result readOnClient(Request request) {
        synchronized (this) {
            expireCursors();
        }
        var scope = currentScope.get();
        if (scope == null || request.scope() == null || !scope.worldSession().equals(request.scope().worldSession())
                || !scope.resourcesLocale().equals(request.scope().resourcesLocale())
                || !scope.playerTeam().equals(request.scope().playerTeam())
                || !scope.providerRuntime().equals(request.scope().providerRuntime())) {
            return error(request, "evidence scope changed; repeat the lookup");
        }
        if (request.cursor() != null) return readCursor(request, scope);
        Observation observation;
        if (request.target() == Target.OPENED) {
            observation = validOpened(scope);
        } else if (request.target() == Target.ITEM) {
            observation = captureItem(request, scope);
        } else if (client == null) {
            observation = unknown(request, scope, "native client unavailable");
        } else {
            observation = captureTargeted(request, scope);
        }
        if (observation == null) return error(request, "no recent opened-container observation is available");
        return firstPage(observation, request);
    }

    private synchronized Observation validOpened(Evidence.Scope scope) {
        if (opened == null) return null;
        if (monotonicClock.getAsLong() - opened.createdNanos() >= TTL_NANOS
                || !scope.worldSession().equals(opened.scope().worldSession())
                || !scope.resourcesLocale().equals(opened.scope().resourcesLocale())
                || !scope.playerTeam().equals(opened.scope().playerTeam())
                || !scope.providerRuntime().equals(opened.scope().providerRuntime())) {
            opened = null;
            return null;
        }
        return opened;
    }

    private Observation captureTargeted(Request request, Evidence.Scope scope) {
        if (!(client.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK || client.level == null) {
            return unknown(request, scope, "no block target");
        }
        var pos = hit.getBlockPos();
        var state = client.level.getBlockState(pos);
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var identity = id == null ? "unknown:block" : id.toString();
        var capturedAt = Instant.now();
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("contentsStatus", new Evidence.JsonString("unavailable"));
        payload.put("position", new Evidence.JsonObject(Map.of("x", new Evidence.JsonNumber(BigDecimal.valueOf(pos.getX())),
                "y", new Evidence.JsonNumber(BigDecimal.valueOf(pos.getY())),
                "z", new Evidence.JsonNumber(BigDecimal.valueOf(pos.getZ())))));
        var entrySource = new Evidence.Source(scope, "native", "1.21.1", "storage-entry:targeted:" + pos,
                scope.requestGeneration(), capturedAt, Evidence.Activity.ACTIVE, Evidence.Coverage.UNAVAILABLE,
                Evidence.Authority.VIEWER, List.of("client-hit-result"));
        var entry = new Evidence.Record(entrySource, Evidence.RecordKind.BLOCK, identity,
                new Evidence.JsonObject(payload), List.of());
        return new Observation(List.of(entry), scope, capturedAt, monotonicClock.getAsLong(), "Targeted storage",
                Map.of("contentsStatus", new Evidence.JsonString("unavailable"),
                        "completeness", new Evidence.JsonString("unknown")),
                Evidence.Coverage.UNAVAILABLE, List.of("client-hit-result"));
    }

    private Observation captureItem(Request request, Evidence.Scope scope) {
        if (itemResolver == null || registries.get() == null) return unknown(request, scope, "native inventory reader unavailable");
        try {
            return itemObservation(itemResolver.apply(request.slotReference(), scope), request.slotReference(), scope,
                    registries.get(), Instant.now(), monotonicClock.getAsLong());
        } catch (IllegalArgumentException failure) {
            return unknown(request, scope, failure.getMessage());
        }
    }

    static Observation itemObservation(NativeInventoryReader.LocatedItem location, String slotReference, Evidence.Scope scope,
                                       net.minecraft.core.HolderLookup.Provider registries, Instant capturedAt,
                                       long capturedNanos) {
        var entries = new ArrayList<Evidence.Record>();
        var owners = new ArrayList<Evidence.JsonValue>();
        var supported = false;
        var truncated = false;
        var stack = location.stack();
        var ownerId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        var ownerIdentity = ownerId == null ? "unknown:item" : ownerId.toString();
        var ownerName = stack.getHoverName().getString();
        if (ownerName.length() > 256) ownerName = ownerName.substring(0, 256);
        var container = stack.get(DataComponents.CONTAINER);
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (container == null && bundle == null) {
            var owner = new LinkedHashMap<String, Evidence.JsonValue>();
            owner.put("slotReference", new Evidence.JsonString(slotReference));
            owner.put("section", new Evidence.JsonString(location.section()));
            owner.put("slot", new Evidence.JsonNumber(BigDecimal.valueOf(location.slot())));
            owner.put("ownerId", new Evidence.JsonString(ownerIdentity));
            owner.put("ownerName", new Evidence.JsonString(ownerName));
            owner.put("ownerCount", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
            owner.put("storageStatus", new Evidence.JsonString("unknown"));
            owner.put("storageType", new Evidence.JsonString("unknown"));
            owner.put("contentsStatus", new Evidence.JsonString("unknown"));
            owners.add(new Evidence.JsonObject(owner));
        }
        if (container != null) {
            supported = true;
            truncated |= addItemComponent(entries, owners, stack, ownerIdentity, ownerName, location, slotReference, "container",
                    container, null, scope, registries, capturedAt);
        }
        if (bundle != null) {
            supported = true;
            truncated |= addItemComponent(entries, owners, stack, ownerIdentity, ownerName, location, slotReference, "bundle",
                    null, bundle, scope, registries, capturedAt);
        }
        var coverage = supported ? truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE
                : Evidence.Coverage.UNAVAILABLE;
        var details = new LinkedHashMap<String, Evidence.JsonValue>();
        details.put("observation", new Evidence.JsonString("inventory_item_storage"));
        details.put("slotReference", new Evidence.JsonString(slotReference));
        details.put("sourceKind", new Evidence.JsonString("item_container"));
        details.put("completeness", new Evidence.JsonString(coverage == Evidence.Coverage.COMPLETE ? "complete"
                : coverage == Evidence.Coverage.PARTIAL ? "partial" : "unknown"));
        details.put("owners", new Evidence.JsonArray(owners));
        if (truncated) details.put("truncated", new Evidence.JsonBoolean(true));
        return new Observation(entries, scope, capturedAt, capturedNanos, "Item storage", details, coverage,
                List.of("synchronized-item-components", location.section() + ":" + location.slot()));
    }

    private static boolean addItemComponent(List<Evidence.Record> entries, List<Evidence.JsonValue> owners,
                                            ItemStack ownerStack, String ownerId, String ownerName, NativeInventoryReader.LocatedItem location,
                                            String slotReference,
                                            String storageType, ItemContainerContents container, BundleContents bundle,
                                            Evidence.Scope scope, net.minecraft.core.HolderLookup.Provider registries,
                                            Instant capturedAt) {
        Evidence.JsonObject ownerComponents;
        try {
            ownerComponents = net.minecraft.core.component.DataComponentMap.CODEC.encodeStart(
                    registries.createSerializationContext(JsonOps.INSTANCE), ownerStack.getComponents().filter(type ->
                            type != DataComponents.CONTAINER && type != DataComponents.BUNDLE_CONTENTS))
                    .result().filter(com.google.gson.JsonElement::isJsonObject)
                    .map(value -> Evidence.deserializeObject(value.toString())).orElseGet(() ->
                            new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown"))));
        } catch (RuntimeException ignored) {
            ownerComponents = new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown")));
        }
        var ownerMetadataTruncated = ownerComponents.values().containsKey("status")
                || Evidence.serialize(ownerComponents).getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_METADATA;
        var owner = new LinkedHashMap<String, Evidence.JsonValue>();
        owner.put("slotReference", new Evidence.JsonString(slotReference));
        owner.put("section", new Evidence.JsonString(location.section()));
        owner.put("slot", new Evidence.JsonNumber(BigDecimal.valueOf(location.slot())));
        if (location.equipmentSlot() != null) owner.put("equipmentSlot", new Evidence.JsonString(location.equipmentSlot()));
        owner.put("ownerId", new Evidence.JsonString(ownerId));
        owner.put("ownerName", new Evidence.JsonString(ownerName));
        owner.put("ownerCount", new Evidence.JsonNumber(BigDecimal.valueOf(ownerStack.getCount())));
        owner.put("storageType", new Evidence.JsonString(storageType));
        owner.put("storageStatus", new Evidence.JsonString(ownerMetadataTruncated ? "unknown_truncated" : "supported"));
        owner.put("contentsStatus", new Evidence.JsonString("complete"));
        owner.put("components", ownerMetadataTruncated
                ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))) : ownerComponents);
        if (ownerMetadataTruncated) owner.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
        if (!ownerMetadataTruncated && Evidence.serialize(new Evidence.JsonObject(owner)).getBytes(StandardCharsets.UTF_8).length
                > MAX_ENTRY_METADATA) {
            ownerMetadataTruncated = true;
            owner.put("storageStatus", new Evidence.JsonString("unknown_truncated"));
            owner.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
            owner.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
        }
        var partial = ownerMetadataTruncated;
        if (container != null) {
            for (int slot = 0; slot < container.getSlots(); slot++) {
                var stack = container.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                if (entries.size() == MAX_ENTRIES) {
                    owner.put("contentsStatus", new Evidence.JsonString("truncated"));
                    owners.add(new Evidence.JsonObject(owner));
                    return true;
                }
                var child = storageItemRecord(stack, scope, slotReference, storageType, slot, ownerId, ownerName,
                        ownerStack.getCount(), registries, capturedAt);
                partial |= child.source().coverage() == Evidence.Coverage.PARTIAL;
                entries.add(child);
            }
        } else if (bundle != null) {
            var slot = 0;
            for (var stack : bundle.itemsCopy()) {
                if (stack.isEmpty()) {
                    slot++;
                    continue;
                }
                if (entries.size() == MAX_ENTRIES) {
                    owner.put("contentsStatus", new Evidence.JsonString("truncated"));
                    owners.add(new Evidence.JsonObject(owner));
                    return true;
                }
                var child = storageItemRecord(stack, scope, slotReference, storageType, slot, ownerId, ownerName,
                        ownerStack.getCount(), registries, capturedAt);
                partial |= child.source().coverage() == Evidence.Coverage.PARTIAL;
                entries.add(child);
                slot++;
            }
        }
        owners.add(new Evidence.JsonObject(owner));
        return partial;
    }

    private static Evidence.Record storageItemRecord(ItemStack stack, Evidence.Scope scope, String slotReference,
                                                 String storageType, int slot, String ownerId, String ownerName,
                                                 int ownerCount, net.minecraft.core.HolderLookup.Provider registries,
                                                 Instant capturedAt) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        var identity = id == null ? "unknown:item" : id.toString();
        Evidence.JsonObject components;
        try {
            components = net.minecraft.core.component.DataComponentMap.CODEC.encodeStart(
                    registries.createSerializationContext(JsonOps.INSTANCE), stack.getComponents().filter(type ->
                            type != DataComponents.CONTAINER && type != DataComponents.BUNDLE_CONTENTS))
                    .result().filter(com.google.gson.JsonElement::isJsonObject)
                    .map(value -> Evidence.deserializeObject(value.toString())).orElseGet(() ->
                            new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown"))));
        } catch (RuntimeException ignored) {
            components = new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown")));
        }
        var metadataTruncated = components.values().containsKey("status")
                || Evidence.serialize(components).getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_METADATA;
        var itemName = stack.getHoverName().getString();
        if (itemName.length() > 256) itemName = itemName.substring(0, 256);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("name", new Evidence.JsonString(itemName));
        payload.put("count", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
        payload.put("components", metadataTruncated
                ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))) : components);
        payload.put("slotReference", new Evidence.JsonString(slotReference));
        payload.put("storageType", new Evidence.JsonString(storageType));
        payload.put("componentSlot", new Evidence.JsonNumber(BigDecimal.valueOf(slot)));
        payload.put("owner", new Evidence.JsonObject(Map.of("id", new Evidence.JsonString(ownerId),
                "name", new Evidence.JsonString(ownerName), "count", new Evidence.JsonNumber(BigDecimal.valueOf(ownerCount)))));
        if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)) {
            payload.put("nestedStorageContentsOmitted", new Evidence.JsonBoolean(true));
        }
        if (metadataTruncated) payload.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
        if (!metadataTruncated && Evidence.serialize(new Evidence.JsonObject(payload)).getBytes(StandardCharsets.UTF_8).length
                > MAX_ENTRY_METADATA) {
            metadataTruncated = true;
            payload.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
            payload.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
        }
        var source = new Evidence.Source(scope, "native", "1.21.1",
                "storage-entry:item:" + slotReference + ":" + storageType + ":" + slot,
                scope.requestGeneration(), capturedAt, Evidence.Activity.ACTIVE,
                metadataTruncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                Evidence.Authority.VIEWER, List.of("synchronized-item-component"));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, identity, new Evidence.JsonObject(payload), List.of());
    }

    private Observation unknown(Request request, Evidence.Scope scope, String reason) {
        var target = request.target().name().toLowerCase(java.util.Locale.ROOT);
        return new Observation(List.of(), scope, Instant.now(), monotonicClock.getAsLong(), target,
                Map.of("status", new Evidence.JsonString("unknown"), "detail", new Evidence.JsonString(reason),
                        "completeness", new Evidence.JsonString("unknown")), Evidence.Coverage.UNAVAILABLE,
                List.of("client-storage-observation"));
    }

    private Evidence.Result firstPage(Observation observation, Request request) {
        return makePage(filter(observation.entries(), request), request, 0, UUID.randomUUID().toString(), observation, null);
    }

    private Evidence.Result readCursor(Request request, Evidence.Scope scope) {
        Cursor cursor;
        synchronized (this) {
            cursor = cursors.get(request.cursor());
            if (cursor == null) return error(request, "storage cursor is unknown or expired; repeat the lookup");
            if (!cursor.request().target().equals(request.target()) || !Objects.equals(cursor.request().text(), request.text())
                    || !Objects.equals(cursor.request().slotReference(), request.slotReference())
                    || !cursor.request().identities().equals(request.identities())
                    || !cursor.request().scope().equals(request.scope()) || !scope.equals(request.scope())) {
                return error(request, "storage cursor does not match this query or scope");
            }
            cursors.remove(request.cursor());
        }
        return makePage(cursor.entries(), request, cursor.offset(), cursor.snapshotId(), cursor.observation(), cursor);
    }

    private Evidence.Result makePage(List<Evidence.Record> entries, Request request, int offset, String snapshotId,
                                     Observation observation, Cursor previous) {
        if (offset < 0 || offset > entries.size()) return error(request, "storage cursor offset is invalid");
        var end = Math.min(entries.size(), offset + request.limit());
        var pageEntries = new ArrayList<Evidence.JsonValue>();
        for (int index = offset; index < end; index++) {
            var entry = entries.get(index);
            var payload = entry.payload();
            if (Evidence.serialize(payload).getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_METADATA) {
                var trimmed = new LinkedHashMap<>(payload.values());
                trimmed.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
                trimmed.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
                payload = new Evidence.JsonObject(trimmed);
            }
            var item = new LinkedHashMap<String, Evidence.JsonValue>();
            item.put("kind", new Evidence.JsonString(entry.kind().name().toLowerCase(java.util.Locale.ROOT)));
            item.put("title", new Evidence.JsonString(entry.title().length() <= 256 ? entry.title() : entry.title().substring(0, 256)));
            item.put("payload", payload);
            pageEntries.add(new Evidence.JsonObject(item));
        }
        while (!pageEntries.isEmpty()) {
            var testPayload = envelope(request, observation, pageEntries, end < entries.size() ? "pending" : null);
            if (Evidence.serialize(testPayload).getBytes(StandardCharsets.UTF_8).length <= MAX_PAGE_PAYLOAD) break;
            pageEntries.removeLast();
            end--;
        }
        if (offset < entries.size() && pageEntries.isEmpty()) {
            return error(request, "storage entry exceeds the output budget; repeat the lookup with narrower filters");
        }
        String next = null;
        if (end < entries.size()) {
            next = UUID.randomUUID().toString();
            synchronized (this) {
                cursors.put(next, new Cursor(entries, end, snapshotId, observation, request, observation.createdNanos()));
                while (cursors.size() > MAX_RETAINED) cursors.remove(cursors.keySet().iterator().next());
            }
        }
        var payload = envelope(request, observation, pageEntries, next);
        var source = new Evidence.Source(request.scope(), "native", "1.21.1",
                "storage:" + snapshotId + ":" + (previous == null ? 0 : previous.offset()),
                observation.scope().requestGeneration(), observation.capturedAt(), Evidence.Activity.ACTIVE,
                observation.coverage(), Evidence.Authority.VIEWER, observation.provenance());
        var record = new Evidence.Record(source, Evidence.RecordKind.OTHER, observation.title(), payload, List.of());
        var outcome = switch (observation.coverage()) {
            case COMPLETE -> Evidence.Outcome.COMPLETE;
            case PARTIAL, OPAQUE -> Evidence.Outcome.PARTIAL;
            case UNAVAILABLE -> Evidence.Outcome.UNKNOWN;
            case ERROR -> Evidence.Outcome.ERROR;
        };
        return new Evidence.Result(List.of(record), observation.coverage(), outcome, next);
    }

    static Evidence.Result pageRecords(List<Evidence.Record> entries, Request request, int offset, String snapshotId,
                                       Evidence.Coverage coverage, Instant capturedAt) {
        var scope = entries.isEmpty() ? request.scope() : entries.getFirst().source().scope();
        var observation = new Observation(entries, scope, capturedAt, 0, "Storage contents",
                Map.of("completeness", new Evidence.JsonString(coverage.name().toLowerCase(java.util.Locale.ROOT))),
                coverage, List.of("client-storage-observation"));
        var reader = new NativeStorageReader(Runnable::run, request::scope, () -> 0L);
        return reader.makePage(entries, request, offset, snapshotId, observation, null);
    }

    private static Evidence.JsonObject envelope(Request request, Observation observation, List<Evidence.JsonValue> entries,
                                                String next) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        values.put("target", new Evidence.JsonString(request.target().name().toLowerCase(java.util.Locale.ROOT)));
        values.put("capturedAt", new Evidence.JsonString(observation.capturedAt().toString()));
        values.put("entries", new Evidence.JsonArray(entries));
        values.put("hasMore", new Evidence.JsonBoolean(next != null));
        values.putAll(observation.details());
        return new Evidence.JsonObject(values);
    }

    private static List<Evidence.Record> filter(List<Evidence.Record> entries, Request request) {
        var text = request.text() == null ? null : request.text().toLowerCase(java.util.Locale.ROOT);
        return entries.stream().filter(record -> request.identities().isEmpty()
                || request.identities().contains(String.valueOf(record.payload().values().get("id") instanceof Evidence.JsonString id ? id.value() : "")))
                .filter(record -> {
                    if (text == null) return true;
                    var id = record.payload().values().get("id") instanceof Evidence.JsonString value ? value.value() : "";
                    var name = record.payload().values().get("name") instanceof Evidence.JsonString value ? value.value() : "";
                    return (record.title() + " " + name + " " + id).toLowerCase(java.util.Locale.ROOT).contains(text);
                })
                .limit(MAX_ENTRIES).toList();
    }

    private synchronized void expireCursors() {
        var now = monotonicClock.getAsLong();
        cursors.entrySet().removeIf(entry -> now - entry.getValue().createdNanos() >= TTL_NANOS);
    }

    private synchronized Evidence.Result error(Request request, String detail) {
        var source = new Evidence.Source(request.scope(), "native", "1.21.1", "storage:error", request.scope().requestGeneration(),
                Instant.now(), Evidence.Activity.UNKNOWN, Evidence.Coverage.UNAVAILABLE, Evidence.Authority.UNKNOWN, List.of(detail));
        var record = new Evidence.Record(source, Evidence.RecordKind.OTHER, "Storage unavailable",
                new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown"),
                        "detail", new Evidence.JsonString(detail))), List.of());
        return new Evidence.Result(List.of(record), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null, detail);
    }

    record Observation(List<Evidence.Record> entries, Evidence.Scope scope, Instant capturedAt, long createdNanos,
                       String title, Map<String, Evidence.JsonValue> details, Evidence.Coverage coverage,
                       List<String> provenance) {
        Observation {
            entries = List.copyOf(entries);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(capturedAt);
            details = Map.copyOf(details);
            Objects.requireNonNull(coverage);
            provenance = List.copyOf(provenance);
        }
    }

    record OpenedEntries(List<Evidence.Record> entries, boolean truncated) {
        OpenedEntries {
            entries = List.copyOf(entries);
        }
    }

    private record Cursor(List<Evidence.Record> entries, int offset, String snapshotId, Observation observation,
                          Request request, long createdNanos) {
        private Cursor {
            entries = List.copyOf(entries);
        }
    }
}
