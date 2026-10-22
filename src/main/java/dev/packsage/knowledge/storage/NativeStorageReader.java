package dev.packsage.knowledge.storage;

import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.inventory.InventoryReader;
import dev.packsage.knowledge.nativegame.NativeInventoryReader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.FurnaceFuelSlot;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.inventory.NonInteractiveResultSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.ref.WeakReference;
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
    private final LinkedHashMap<String, Reference> references = new LinkedHashMap<>();
    private WeakReference<AbstractContainerMenu> openedMenuIdentityMenu;
    private String openedMenuIdentity;
    private Observation fixtureOpened;

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

    static OpenedEntries openedEntries(AbstractContainerMenu menu, Container playerInventory, Evidence.Scope scope,
                                       Instant capturedAt, HolderLookup.Provider registries) {
        return openedEntries(menu, playerInventory, scope, capturedAt, registries, InventoryReader.Filter.empty(), null);
    }

    private static OpenedEntries openedEntries(AbstractContainerMenu menu, Container playerInventory, Evidence.Scope scope,
                                                Instant capturedAt, HolderLookup.Provider registries,
                                                InventoryReader.Filter filter, net.minecraft.world.entity.player.Player player) {
        var entries = new ArrayList<Evidence.Record>();
        var truncated = false;
        var coverage = Evidence.Coverage.COMPLETE;
        var projections = new ArrayList<String>();
        var combinerResultSlot = menu instanceof ItemCombinerMenu combiner ? combiner.getResultSlot() : -1;
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            var slotType = slot.getClass();
            if (slot.container == playerInventory) continue;
            var projection = projectionKind(menu.getClass(), slotType, index, combinerResultSlot);
            if (projection != null) {
                projections.add(index + "=" + projection);
                continue;
            }
            if (slotType != Slot.class && !supportedStockSlot(menu.getClass(), slot)) {
                coverage = Evidence.Coverage.PARTIAL;
                continue;
            }
            ItemStack stack = slot.getItem().copy();
            if (stack.isEmpty()) continue;
            if (entries.size() == MAX_ENTRIES) {
                truncated = true;
                coverage = Evidence.Coverage.PARTIAL;
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
            var name = stack.getHoverName().getString();
            boolean nameTruncated = name.length() > 256;
            var entryPartial = metadataTruncated || nameTruncated && !filter.names().isEmpty();
            if (entryPartial) coverage = Evidence.Coverage.PARTIAL;
            payload.put("id", new Evidence.JsonString(id == null ? "unknown:item" : id.toString()));
            payload.put("name", new Evidence.JsonString(nameTruncated ? name.substring(0, 256) : name));
            payload.put("count", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
            payload.put("menuSlot", new Evidence.JsonNumber(BigDecimal.valueOf(index)));
            payload.put("observation", new Evidence.JsonString("live_opened_menu"));
            payload.put("section", new Evidence.JsonString(menu.getClass() != HorseInventoryMenu.class ? "menu"
                    : index == 0 ? "saddle" : index == 1
                    ? stack.is(ItemTags.WOOL_CARPETS) ? "body_carpet" : "body_armor" : "cargo"));
            if (!filter.tags().isEmpty()) {
                try {
                    var tags = stack.getTags().limit(129).map(tag -> new Evidence.JsonString(tag.location().toString())).toList();
                    if (tags.size() > 128) {
                        entryPartial = true;
                        coverage = Evidence.Coverage.PARTIAL;
                    }
                    payload.put("tags", new Evidence.JsonArray(tags.stream().map(value -> (Evidence.JsonValue) value).toList()));
                } catch (RuntimeException failure) {
                    payload.put("tags", new Evidence.JsonArray(List.of()));
                    entryPartial = true;
                    coverage = Evidence.Coverage.PARTIAL;
                }
            }
            if (!filter.tooltip().isEmpty()) {
                try {
                    var lines = player == null ? null : stack.getTooltipLines(
                            net.minecraft.world.item.Item.TooltipContext.of(player.level()), player,
                            net.minecraft.world.item.TooltipFlag.NORMAL);
                    if (lines == null) entryPartial = true;
                    var tooltipLines = lines == null ? List.<net.minecraft.network.chat.Component>of() : lines;
                    if (tooltipLines.size() > 32 || tooltipLines.stream().anyMatch(line -> line.getString().length() > 256)) {
                        entryPartial = true;
                    }
                    payload.put("tooltip", new Evidence.JsonArray(tooltipLines.stream().limit(33).map(net.minecraft.network.chat.Component::getString)
                            .map(value -> new Evidence.JsonString(value.length() <= 256 ? value : value.substring(0, 256)))
                            .map(value -> (Evidence.JsonValue) value).toList()));
                } catch (RuntimeException failure) {
                    payload.put("tooltip", new Evidence.JsonArray(List.of()));
                    entryPartial = true;
                    coverage = Evidence.Coverage.PARTIAL;
                }
            }
            if (entryPartial) coverage = Evidence.Coverage.PARTIAL;
            if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)) {
                payload.put("nestedStorageContentsOmitted", new Evidence.JsonBoolean(true));
            }
            payload.put("components", metadataTruncated
                    ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated")))
                    : encodedComponents);
            if (dev.packsage.knowledge.nativegame.NativeItemFacts.enrich(payload, stack, registries)) {
                entryPartial = true;
                coverage = Evidence.Coverage.PARTIAL;
            }
            if (metadataTruncated) payload.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
            if (!metadataTruncated && Evidence.serialize(new Evidence.JsonObject(payload)).getBytes(StandardCharsets.UTF_8).length
                    > MAX_ENTRY_METADATA) {
                metadataTruncated = true;
                entryPartial = true;
                coverage = Evidence.Coverage.PARTIAL;
                payload.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
                payload.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
            }
            var source = new Evidence.Source(scope, "native", "1.21.1", "storage-entry:opened:" + index,
                    scope.requestGeneration(), capturedAt, Evidence.Activity.ACTIVE,
                    entryPartial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    Evidence.Authority.VIEWER, List.of("client-visible-menu-slot"));
            entries.add(new Evidence.Record(source, Evidence.RecordKind.ITEM,
                    id == null ? "unknown:item" : id.toString(), new Evidence.JsonObject(payload), List.of()));
        }
        return new OpenedEntries(entries, truncated, coverage, projections);
    }

    static String projectionKind(Class<?> menuType, Class<?> slotType, int menuSlot, int combinerResultSlot) {
        if (slotType == ResultSlot.class || slotType == NonInteractiveResultSlot.class) return "recipe_preview";
        if (slotType == MerchantResultSlot.class) return "trade_preview";
        if (menuType == GrindstoneMenu.class && menuSlot == 2 || menuType == StonecutterMenu.class && menuSlot == 1
                || menuType == LoomMenu.class && menuSlot == 3 || menuType == CartographyTableMenu.class && menuSlot == 2
                || (menuType == AnvilMenu.class || menuType == SmithingMenu.class) && menuSlot == combinerResultSlot) {
            return "recipe_preview";
        }
        return null;
    }

    static boolean supportedStockSlot(Class<?> menuType, Slot slot) {
        var slotType = slot.getClass();
        var slotName = slotType.getName();
        return (slotType == FurnaceResultSlot.class || slotType == FurnaceFuelSlot.class)
                && (menuType == FurnaceMenu.class || menuType == BlastFurnaceMenu.class || menuType == SmokerMenu.class)
                || menuType == BrewingStandMenu.class && (slotName.equals("net.minecraft.world.inventory.BrewingStandMenu$PotionSlot")
                || slotName.equals("net.minecraft.world.inventory.BrewingStandMenu$IngredientsSlot")
                || slotName.equals("net.minecraft.world.inventory.BrewingStandMenu$FuelSlot"))
                || menuType == HorseInventoryMenu.class && (slotName.equals("net.minecraft.world.inventory.HorseInventoryMenu$1")
                || slotName.equals("net.minecraft.world.inventory.HorseInventoryMenu$2"));
    }

    synchronized void storeOpenedFixture(List<Evidence.Record> entries, Evidence.Scope scope, Instant capturedAt,
                                         long capturedNanos, String title, Map<String, Evidence.JsonValue> details,
                                         Evidence.Coverage coverage) {
        if (client != null) throw new IllegalStateException("fixture opened observations are unavailable at runtime");
        var fixtureDetails = new LinkedHashMap<>(details);
        fixtureDetails.putIfAbsent("ownerIdentity", new Evidence.JsonString("fixture-opened-menu"));
        fixtureOpened = new Observation(entries, scope, capturedAt, capturedNanos,
                title.length() <= 256 ? title : title.substring(0, 256), fixtureDetails, coverage,
                List.of("fixture-client-storage-observation"));
    }

    public synchronized void clear() {
        fixtureOpened = null;
        cursors.clear();
        references.clear();
        openedMenuIdentityMenu = null;
        openedMenuIdentity = null;
    }

    public CompletionStage<Evidence.Result> readEvidence(String sourceHandle, Evidence.Scope scope) {
        Reference reference;
        synchronized (this) {
            reference = references.get(sourceHandle);
            if (reference == null || monotonicClock.getAsLong() - reference.createdNanos() >= TTL_NANOS) {
                references.remove(sourceHandle);
                return CompletableFuture.failedFuture(new IllegalArgumentException("storage evidence reference is unknown or expired"));
            }
        }
        if (!reference.scope().equals(scope) || !scope.equals(currentScope.get())) return CompletableFuture.failedFuture(
                new IllegalArgumentException("storage evidence scope changed"));
        return client == null ? CompletableFuture.supplyAsync(() -> readEvidenceOnClient(reference, scope), executor)
                .thenCompose(result -> result)
                : client.submit(() -> readEvidenceOnClient(reference, scope)).thenCompose(result -> result);
    }

    private CompletionStage<Evidence.Result> readEvidenceOnClient(Reference reference, Evidence.Scope scope) {
        if (!scope.equals(currentScope.get())) return CompletableFuture.completedFuture(error(reference.request(), "storage evidence scope changed"));
        var request = reference.request();
        if (request.target() != Target.OPENED && request.target() != Target.ITEM && client != null) {
            return captureTargeted(request, scope).thenApply(observation -> observation == null
                    || !reference.ownerId().equals(ownerId(observation)) ? error(request, "storage target changed or is no longer available")
                    : makePage(filter(observation.entries(), request), request, reference.offset(), reference.observationId(), observation,
                    new Cursor(reference.offset(), reference.observationId(), reference.ownerId(), request, reference.createdNanos())));
        }
        var observation = request.target() == Target.OPENED ? validOpened(scope, request.filter()) : request.target() == Target.ITEM
                ? captureItem(request, scope) : unknown(request, scope, "native client unavailable");
        if (observation == null || !reference.ownerId().equals(ownerId(observation))) return CompletableFuture.completedFuture(error(request,
                "storage target changed or is no longer available"));
        return CompletableFuture.completedFuture(makePage(filter(observation.entries(), request), request, reference.offset(), reference.observationId(), observation,
                new Cursor(reference.offset(), reference.observationId(), reference.ownerId(), request, reference.createdNanos())));
    }

    @Override
    public CompletionStage<Evidence.Result> read(Request request) {
        Objects.requireNonNull(request);
        if (request.limit() > MAX_PAGE) {
            return CompletableFuture.completedFuture(error(request, "limit must be between 1 and 32"));
        }
        return client == null ? CompletableFuture.supplyAsync(() -> readOnClient(request), executor).thenCompose(result -> result)
                : client.submit(() -> readOnClient(request)).thenCompose(result -> result);
    }

    private CompletionStage<Evidence.Result> readOnClient(Request request) {
        synchronized (this) {
            expireCursors();
        }
        var scope = currentScope.get();
        if (scope == null || request.scope() == null || !scope.worldSession().equals(request.scope().worldSession())
                || !scope.resourcesLocale().equals(request.scope().resourcesLocale())
                || !scope.playerTeam().equals(request.scope().playerTeam())
                || !scope.providerRuntime().equals(request.scope().providerRuntime())) {
            return CompletableFuture.completedFuture(error(request, "evidence scope changed; repeat the lookup"));
        }
        if (request.cursor() != null) return readCursor(request, scope);
        Observation observation = null;
        if (request.target() == Target.OPENED) {
            observation = validOpened(scope, request.filter());
        } else if (request.target() == Target.ITEM) {
            observation = captureItem(request, scope);
        } else if (client == null) {
            observation = unknown(request, scope, "native client unavailable");
        } else {
            return captureTargeted(request, scope).thenApply(result -> result == null ? error(request, "selected inventory source is unavailable")
                    : firstPage(result, request));
        }
        if (observation == null) return CompletableFuture.completedFuture(error(request, "selected inventory source is unavailable"));
        return CompletableFuture.completedFuture(firstPage(observation, request));
    }

    private synchronized Observation validOpened(Evidence.Scope scope, InventoryReader.Filter filter) {
        if (client != null) {
            if (!client.isSameThread()) throw new IllegalStateException("opened inventory must be read on the client thread");
            if (client.player == null || client.level == null || client.player.containerMenu == client.player.inventoryMenu) return null;
            var menu = client.player.containerMenu;
            var horseMenu = menu.getClass() == HorseInventoryMenu.class;
            var menuType = horseMenu ? "HorseInventoryMenu" : null;
            if (!horseMenu) {
                net.minecraft.world.inventory.MenuType<?> nativeMenuType;
                try {
                    nativeMenuType = menu.getType();
                } catch (UnsupportedOperationException failure) {
                    return unknown(new Request(Target.OPENED, null, null, List.of(), 1, null, scope), scope,
                            "opened menu is unsupported or no longer valid");
                }
                var type = nativeMenuType == null ? null : BuiltInRegistries.MENU.getKey(nativeMenuType);
                if (type == null || !type.getNamespace().equals("minecraft")
                        || !menu.getClass().getName().startsWith("net.minecraft.world.inventory.")
                        || !menu.stillValid(client.player)) return unknown(new Request(Target.OPENED, null, null, List.of(), 1, null, scope),
                        scope, "opened menu is unsupported or no longer valid");
                menuType = type.toString();
            }
            var capturedAt = Instant.now();
            var capturedNanos = monotonicClock.getAsLong();
            var captured = openedEntries(menu, client.player.getInventory(), scope, capturedAt, client.level.registryAccess(),
                    filter, client.player);
            if (openedMenuIdentityMenu == null || openedMenuIdentityMenu.get() != menu) {
                openedMenuIdentityMenu = new WeakReference<>(menu);
                openedMenuIdentity = UUID.randomUUID().toString();
            }
            var identity = client.level.dimension().location() + ":" + client.player.getUUID() + ":" + menuType + ":"
                    + menu.containerId + ":" + openedMenuIdentity;
            var openedDetails = new LinkedHashMap<String, Evidence.JsonValue>();
            openedDetails.put("observation", new Evidence.JsonString("live_opened_menu"));
            openedDetails.put("completeness", new Evidence.JsonString(captured.coverage().name().toLowerCase(java.util.Locale.ROOT)));
            openedDetails.put("menuType", new Evidence.JsonString(menuType));
            openedDetails.put("ownerIdentity", new Evidence.JsonString(identity));
            if (!captured.projections().isEmpty()) {
                openedDetails.put("excludedProjections", new Evidence.JsonArray(captured.projections().stream()
                        .<Evidence.JsonValue>map(Evidence.JsonString::new).toList()));
            }
            return new Observation(captured.entries(), scope, capturedAt, capturedNanos, "Opened inventory",
                    openedDetails, captured.coverage(),
                    List.of("current-client-menu-slots"));
        }
        if (fixtureOpened == null) return null;
        if (monotonicClock.getAsLong() - fixtureOpened.createdNanos() >= TTL_NANOS
                || !scope.worldSession().equals(fixtureOpened.scope().worldSession())
                || !scope.resourcesLocale().equals(fixtureOpened.scope().resourcesLocale())
                || !scope.playerTeam().equals(fixtureOpened.scope().playerTeam())
                || !scope.providerRuntime().equals(fixtureOpened.scope().providerRuntime())) {
            fixtureOpened = null;
            return null;
        }
        return fixtureOpened;
    }

    private CompletionStage<Observation> captureTargeted(Request request, Evidence.Scope scope) {
        boolean openedLive = client != null && client.player != null ? client.player.containerMenu != client.player.inventoryMenu
                : validOpened(scope, request.filter()) != null;
        return NativeServerStorageReader.read(client, request, scope, monotonicClock, currentScope)
                .thenApply(observation -> withOpenedMenuHint(observation, openedLive));
    }

    static Observation withOpenedMenuHint(Observation observation, boolean openedLive) {
        if (!openedLive || observation == null || !(observation.details().get("detail") instanceof Evidence.JsonString detail)
                || !detail.value().equals("target block inventory is unsupported")) return observation;
        var details = new LinkedHashMap<>(observation.details());
        details.put("detail", new Evidence.JsonString(detail.value() + "; an opened menu is available; use target opened for its contents"));
        return new Observation(observation.entries(), observation.scope(), observation.capturedAt(), observation.createdNanos(),
                observation.title(), details, observation.coverage(), observation.provenance());
    }

    private Observation captureItem(Request request, Evidence.Scope scope) {
        if (itemResolver == null || registries.get() == null) return unknown(request, scope, "native inventory reader unavailable");
        try {
            return itemObservation(itemResolver.apply(request.slotReference(), scope), request.slotReference(), scope,
                    registries.get(), Instant.now(), monotonicClock.getAsLong(), request.filter(), client == null ? null : client.player,
                    client == null ? null : client.level);
        } catch (IllegalArgumentException failure) {
            return unknown(request, scope, failure.getMessage());
        }
    }

    static Observation itemObservation(NativeInventoryReader.LocatedItem location, String slotReference, Evidence.Scope scope,
                                       net.minecraft.core.HolderLookup.Provider registries, Instant capturedAt,
                                       long capturedNanos) {
        return itemObservation(location, slotReference, scope, registries, capturedAt, capturedNanos, InventoryReader.Filter.empty(), null, null);
    }

    private static Observation itemObservation(NativeInventoryReader.LocatedItem location, String slotReference, Evidence.Scope scope,
                                               net.minecraft.core.HolderLookup.Provider registries, Instant capturedAt,
                                               long capturedNanos, InventoryReader.Filter filter,
                                               net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level) {
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
        var selectedPartial = false;
        if (!stack.isEmpty()) {
            var selected = selectedItemRecord(stack, ownerIdentity, location, slotReference, scope, registries, capturedAt);
            entries.add(selected);
            selectedPartial = selected.source().coverage() == Evidence.Coverage.PARTIAL;
        }
        if (container == null && bundle == null) {
            var owner = new LinkedHashMap<String, Evidence.JsonValue>();
            owner.put("slotReference", new Evidence.JsonString(slotReference));
            owner.put("section", new Evidence.JsonString(location.section()));
            owner.put("slot", new Evidence.JsonNumber(BigDecimal.valueOf(location.slot())));
            owner.put("ownerId", new Evidence.JsonString(ownerIdentity));
            owner.put("ownerName", new Evidence.JsonString(ownerName));
            owner.put("ownerCount", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
            owner.put("storageStatus", new Evidence.JsonString(stack.isEmpty() ? "unknown" : "none"));
            owner.put("storageType", new Evidence.JsonString(stack.isEmpty() ? "unknown" : "none"));
            owner.put("contentsStatus", new Evidence.JsonString(stack.isEmpty() ? "unknown" : "none"));
            owners.add(new Evidence.JsonObject(owner));
        }
        if (container != null) {
            supported = true;
            truncated |= addItemComponent(entries, owners, stack, ownerIdentity, ownerName, location, slotReference, "container",
                    container, null, scope, registries, capturedAt, filter, player, level);
        }
        if (bundle != null) {
            supported = true;
            truncated |= addItemComponent(entries, owners, stack, ownerIdentity, ownerName, location, slotReference, "bundle",
                    null, bundle, scope, registries, capturedAt, filter, player, level);
        }
        truncated |= selectedPartial;
        var coverage = supported || !stack.isEmpty() ? truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE
                : Evidence.Coverage.UNAVAILABLE;
        var details = new LinkedHashMap<String, Evidence.JsonValue>();
        details.put("observation", new Evidence.JsonString("inventory_item_storage"));
        details.put("slotReference", new Evidence.JsonString(slotReference));
        details.put("ownerIdentity", new Evidence.JsonString(slotReference + "|" + ownerIdentity + "|"
                + (container != null ? "container" : "") + (bundle != null ? "bundle" : "")));
        details.put("sourceKind", new Evidence.JsonString("item_container"));
        details.put("completeness", new Evidence.JsonString(coverage == Evidence.Coverage.COMPLETE ? "complete"
                : coverage == Evidence.Coverage.PARTIAL ? "partial" : "unknown"));
        details.put("owners", new Evidence.JsonArray(owners));
        if (truncated) details.put("truncated", new Evidence.JsonBoolean(true));
        return new Observation(entries, scope, capturedAt, capturedNanos, "Item storage", details, coverage,
                List.of("synchronized-item-components", location.section() + ":" + location.slot()));
    }

    private static Evidence.Record selectedItemRecord(ItemStack stack, String identity, NativeInventoryReader.LocatedItem location,
                                                      String slotReference, Evidence.Scope scope,
                                                      net.minecraft.core.HolderLookup.Provider registries, Instant capturedAt) {
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
        payload.put("section", new Evidence.JsonString("item"));
        payload.put("inventorySection", new Evidence.JsonString(location.section()));
        payload.put("slot", new Evidence.JsonNumber(BigDecimal.valueOf(location.slot())));
        if (location.equipmentSlot() != null) payload.put("equipmentSlot", new Evidence.JsonString(location.equipmentSlot()));
        payload.put("name", new Evidence.JsonString(itemName));
        payload.put("count", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
        payload.put("components", metadataTruncated
                ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))) : components);
        var partial = dev.packsage.knowledge.nativegame.NativeItemFacts.enrich(payload, stack, registries) || metadataTruncated;
        Evidence.JsonObject eligibility;
        try {
            eligibility = dev.packsage.knowledge.nativegame.NativeEnchantmentEligibility.read(stack,
                    registries.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT));
            partial |= !dev.packsage.knowledge.nativegame.NativeEnchantmentEligibility.complete(eligibility);
        } catch (RuntimeException failure) {
            eligibility = new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unavailable"),
                    "detail", new Evidence.JsonString("enchantment registry unavailable")));
            partial = true;
        }
        payload.put("enchantmentEligibility", eligibility);
        if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)) {
            payload.put("nestedStorageContentsOmitted", new Evidence.JsonBoolean(true));
        }
        if (metadataTruncated) payload.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
        var source = new Evidence.Source(scope, "native", "1.21.1", "storage-entry:item:" + slotReference,
                scope.requestGeneration(), capturedAt, Evidence.Activity.ACTIVE,
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                Evidence.Authority.VIEWER, List.of("synchronized-item-components"));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, identity, new Evidence.JsonObject(payload), List.of());
    }

    private static boolean addItemComponent(List<Evidence.Record> entries, List<Evidence.JsonValue> owners,
                                            ItemStack ownerStack, String ownerId, String ownerName, NativeInventoryReader.LocatedItem location,
                                            String slotReference,
                                            String storageType, ItemContainerContents container, BundleContents bundle,
                                            Evidence.Scope scope, net.minecraft.core.HolderLookup.Provider registries,
                                            Instant capturedAt, InventoryReader.Filter filter,
                                            net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level) {
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
        var ownerFactsPartial = dev.packsage.knowledge.nativegame.NativeItemFacts.enrich(owner, ownerStack, registries);
        if (ownerMetadataTruncated) owner.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
        if (!ownerMetadataTruncated && Evidence.serialize(new Evidence.JsonObject(owner)).getBytes(StandardCharsets.UTF_8).length
                > MAX_ENTRY_METADATA) {
            ownerMetadataTruncated = true;
            owner.put("storageStatus", new Evidence.JsonString("unknown_truncated"));
            owner.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
            owner.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
        }
        var partial = ownerMetadataTruncated || ownerFactsPartial;
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
                        ownerStack.getCount(), registries, capturedAt, filter, player, level);
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
                        ownerStack.getCount(), registries, capturedAt, filter, player, level);
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
                                                 Instant capturedAt, InventoryReader.Filter filter,
                                                 net.minecraft.world.entity.player.Player player, net.minecraft.world.level.Level level) {
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
        boolean nameTruncated = itemName.length() > 256;
        if (itemName.length() > 256) itemName = itemName.substring(0, 256);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("name", new Evidence.JsonString(itemName));
        payload.put("count", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
        payload.put("components", metadataTruncated
                ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))) : components);
        var factsPartial = dev.packsage.knowledge.nativegame.NativeItemFacts.enrich(payload, stack, registries);
        var partial = factsPartial || metadataTruncated || nameTruncated && !filter.names().isEmpty();
        if (nameTruncated) payload.put("nameStatus", new Evidence.JsonString("truncated"));
        if (!filter.tags().isEmpty()) {
            try {
                var tags = stack.getTags().limit(129).map(tag -> tag.location().toString()).toList();
                payload.put("tags", new Evidence.JsonArray(tags.stream().limit(128).map(Evidence.JsonString::new)
                        .map(value -> (Evidence.JsonValue) value).toList()));
                partial |= tags.size() > 128;
            } catch (RuntimeException failure) {
                payload.put("tags", new Evidence.JsonArray(List.of()));
                partial = true;
            }
        }
        if (!filter.tooltip().isEmpty()) {
            try {
                var lines = level == null || player == null ? List.<net.minecraft.network.chat.Component>of()
                        : stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(level), player,
                        net.minecraft.world.item.TooltipFlag.NORMAL);
                payload.put("tooltip", new Evidence.JsonArray(lines.stream().limit(32)
                        .map(net.minecraft.network.chat.Component::getString)
                        .map(value -> new Evidence.JsonString(value.length() <= 256 ? value : value.substring(0, 256)))
                        .map(value -> (Evidence.JsonValue) value).toList()));
                partial |= lines.size() > 32 || lines.stream().map(net.minecraft.network.chat.Component::getString)
                        .anyMatch(value -> value.length() > 256);
            } catch (RuntimeException failure) {
                payload.put("tooltip", new Evidence.JsonArray(List.of()));
                partial = true;
            }
        }
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
                partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
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

    private CompletionStage<Evidence.Result> readCursor(Request request, Evidence.Scope scope) {
        Cursor cursor;
        synchronized (this) {
            cursor = cursors.get(request.cursor());
            if (cursor == null) return CompletableFuture.completedFuture(error(request, "storage cursor is unknown or expired; repeat the lookup"));
            if (!cursor.request().target().equals(request.target()) || !Objects.equals(cursor.request().text(), request.text())
                    || !Objects.equals(cursor.request().slotReference(), request.slotReference())
                    || !cursor.request().identities().equals(request.identities())
                    || !cursor.request().filter().equals(request.filter()) || !cursor.request().scope().equals(request.scope())
                    || !scope.equals(request.scope())) {
                return CompletableFuture.completedFuture(error(request, "storage cursor does not match this query or scope"));
            }
            cursors.remove(request.cursor());
        }
        if (request.target() != Target.OPENED && request.target() != Target.ITEM && client != null) {
            return captureTargeted(request, scope).thenApply(observation -> observation == null
                    ? error(request, "inventory target is no longer available; repeat the lookup")
                    : !cursor.ownerId().equals(ownerId(observation)) ? error(request, "inventory target changed; repeat the lookup")
                    : makePage(filter(observation.entries(), request), request, cursor.offset(), cursor.observationId(), observation, cursor));
        }
        var observation = request.target() == Target.OPENED ? validOpened(scope, request.filter()) : request.target() == Target.ITEM
                ? captureItem(request, scope) : unknown(request, scope, "native client unavailable");
        if (observation == null) return CompletableFuture.completedFuture(error(request, "inventory target is no longer available; repeat the lookup"));
        if (!cursor.ownerId().equals(ownerId(observation))) return CompletableFuture.completedFuture(error(request, "inventory target changed; repeat the lookup"));
        return CompletableFuture.completedFuture(makePage(filter(observation.entries(), request), request, cursor.offset(), cursor.observationId(), observation, cursor));
    }

    private Evidence.Result makePage(List<Evidence.Record> entries, Request request, int offset, String observationId,
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
                cursors.put(next, new Cursor(end, observationId, ownerId(observation), request, observation.createdNanos()));
                while (cursors.size() > MAX_RETAINED) cursors.remove(cursors.keySet().iterator().next());
            }
        }
        var payload = envelope(request, observation, pageEntries, next);
        var sourceHandle = "storage:" + observationId + ":" + (previous == null ? 0 : previous.offset());
        synchronized (this) {
            references.put(sourceHandle, new Reference(observationId, previous == null ? 0 : previous.offset(),
                    ownerId(observation), request, observation.scope(), observation.createdNanos()));
            while (references.size() > MAX_ENTRIES) references.remove(references.keySet().iterator().next());
        }
        var source = new Evidence.Source(request.scope(), "native", "1.21.1",
                sourceHandle,
                observation.scope().requestGeneration(), observation.capturedAt(), Evidence.Activity.ACTIVE,
                observation.coverage(), authority(observation), observation.provenance());
        var record = new Evidence.Record(source, Evidence.RecordKind.OTHER, observation.title(), payload, List.of());
        var outcome = switch (observation.coverage()) {
            case COMPLETE -> Evidence.Outcome.COMPLETE;
            case PARTIAL, OPAQUE -> Evidence.Outcome.PARTIAL;
            case UNAVAILABLE -> Evidence.Outcome.UNKNOWN;
            case ERROR -> Evidence.Outcome.ERROR;
        };
        return new Evidence.Result(List.of(record), observation.coverage(), outcome, next);
    }

    static Evidence.Result pageRecords(List<Evidence.Record> entries, Request request, int offset, String observationId,
                                       Evidence.Coverage coverage, Instant capturedAt) {
        var scope = entries.isEmpty() ? request.scope() : entries.getFirst().source().scope();
        var observation = new Observation(entries, scope, capturedAt, 0, "Storage contents",
                Map.of("completeness", new Evidence.JsonString(coverage.name().toLowerCase(java.util.Locale.ROOT))),
                coverage, List.of("client-storage-observation"));
        var reader = new NativeStorageReader(Runnable::run, request::scope, () -> 0L);
        return reader.makePage(entries, request, offset, observationId, observation, null);
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
        var filter = request.filter();
        return entries.stream().filter(record -> {
            var payload = record.payload().values();
            var id = payload.get("id") instanceof Evidence.JsonString value ? value.value() : "";
            var name = payload.get("name") instanceof Evidence.JsonString value ? value.value() : record.title();
            var tags = payload.get("tags") instanceof Evidence.JsonArray values ? values.values().stream().filter(Evidence.JsonString.class::isInstance)
                    .map(Evidence.JsonString.class::cast).map(Evidence.JsonString::value).toList() : List.<String>of();
            var tooltip = payload.get("tooltip") instanceof Evidence.JsonArray values ? values.values().stream().filter(Evidence.JsonString.class::isInstance)
                    .map(Evidence.JsonString.class::cast).map(Evidence.JsonString::value).toList() : List.<String>of();
            var section = payload.get("section") instanceof Evidence.JsonString value ? value.value()
                    : payload.get("storageType") instanceof Evidence.JsonString value ? value.value()
                    : payload.get("componentSlot") == null ? "menu" : "item";
            return (request.identities().isEmpty() || request.identities().contains(id))
                    && (filter.ids().isEmpty() || filter.ids().contains(id))
                    && (filter.names().isEmpty() || filter.names().stream().anyMatch(value -> name.toLowerCase(java.util.Locale.ROOT).contains(value.toLowerCase(java.util.Locale.ROOT))))
                    && (filter.tags().isEmpty() || filter.tags().stream().anyMatch(tags::contains))
                    && (filter.tooltip().isEmpty() || filter.tooltip().stream().anyMatch(needle -> tooltip.stream()
                    .anyMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT)))))
                    && (filter.sections().isEmpty() || filter.sections().contains(section));
        }).limit(MAX_ENTRIES).toList();
    }

    private static String ownerId(Observation observation) {
        return observation.details().get("ownerIdentity") instanceof Evidence.JsonString value ? value.value() : observation.title();
    }

    private static Evidence.Authority authority(Observation observation) {
        if (observation.coverage() == Evidence.Coverage.UNAVAILABLE) return Evidence.Authority.UNKNOWN;
        if (observation.provenance().contains("integrated_server_read")) return Evidence.Authority.SERVER_READ;
        if (observation.provenance().contains("synchronized-item-components")) return Evidence.Authority.LOADED;
        if (observation.coverage() == Evidence.Coverage.UNAVAILABLE) return Evidence.Authority.UNKNOWN;
        return Evidence.Authority.VIEWER;
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

    record OpenedEntries(List<Evidence.Record> entries, boolean truncated, Evidence.Coverage coverage,
                         List<String> projections) {
        OpenedEntries {
            entries = List.copyOf(entries);
            projections = List.copyOf(projections);
        }

        OpenedEntries(List<Evidence.Record> entries, boolean truncated, Evidence.Coverage coverage) {
            this(entries, truncated, coverage, List.of());
        }
    }

    private record Cursor(int offset, String observationId, String ownerId, Request request, long createdNanos) { }
    private record Reference(String observationId, int offset, String ownerId, Request request, Evidence.Scope scope,
                            long createdNanos) { }
}
