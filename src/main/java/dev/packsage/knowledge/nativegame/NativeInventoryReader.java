package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.inventory.InventoryReader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

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
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class NativeInventoryReader implements InventoryReader {
    private static final long OBSERVATION_TTL_MILLIS = 5 * 60 * 1000;
    private static final int MAX_REFERENCES = 256;
    private static final int MAX_CURSORS = 8;
    private static final int MAX_COMPONENT_BYTES = 8192;
    private static final int MAX_PAGE_BYTES = 96 * 1024;
    private static final int MAX_TAGS = 128;
    private static final int MAX_TOOLTIP_LINES = 32;
    private static final int MAX_TOOLTIP_LINE_LENGTH = 256;
    private static final List<String> SUPPORTED_SECTIONS = List.of("hotbar", "main", "armor", "offhand");

    private final Minecraft client;
    private final Executor executor;
    private final Supplier<Evidence.Scope> currentScope;
    private final Function<Request, Capture> captureSource;
    private final Function<SlotAddress, LocatedItem> lookupSource;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Reference> references = new LinkedHashMap<>();
    private final LinkedHashMap<String, CursorState> cursors = new LinkedHashMap<>();

    public NativeInventoryReader(Minecraft client, Executor executor, Supplier<Evidence.Scope> currentScope) {
        this(Objects.requireNonNull(client), executor, currentScope, request -> capture(client, request, () -> System.nanoTime() / 1_000_000L),
                address -> locate(client, address), () -> System.nanoTime() / 1_000_000L);
    }

    NativeInventoryReader(Minecraft client, Executor executor, Supplier<Evidence.Scope> currentScope,
                          Function<Request, Capture> captureSource,
                          Function<SlotAddress, LocatedItem> lookupSource, LongSupplier clock) {
        this.client = client;
        this.executor = Objects.requireNonNull(executor);
        this.currentScope = Objects.requireNonNull(currentScope);
        this.captureSource = Objects.requireNonNull(captureSource);
        this.lookupSource = Objects.requireNonNull(lookupSource);
        this.clock = Objects.requireNonNull(clock);
    }

    NativeInventoryReader(Executor executor, Supplier<Evidence.Scope> currentScope,
                          Function<Request, Capture> captureSource, Function<SlotAddress, LocatedItem> lookupSource,
                          LongSupplier clock) {
        this(null, executor, currentScope, captureSource, lookupSource, clock);
    }

    @Override
    public CompletionStage<Evidence.Result> read(Request request) {
        Objects.requireNonNull(request);
        if (!request.scope().equals(currentScope.get())) return CompletableFuture.completedFuture(
                new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null,
                        "inventory request scope is no longer current"));
        var unsupportedSections = request.filter().sections().stream().filter(section -> !SUPPORTED_SECTIONS.contains(section)).toList();
        if (!unsupportedSections.isEmpty() && request.filter().sections().stream().noneMatch(SUPPORTED_SECTIONS::contains)) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.UNAVAILABLE,
                    Evidence.Outcome.UNKNOWN, null, "unsupported native inventory sections were not inspected: "
                    + String.join(", ", unsupportedSections) + "; native sections covered: " + String.join(", ", SUPPORTED_SECTIONS)));
        }
        if (request.cursor() != null) return continueRead(request);
        CompletionStage<Capture> captured;
        try {
            captured = client == null ? CompletableFuture.supplyAsync(() -> captureSource.apply(request), executor)
                    : client.submit(() -> captureSource.apply(request));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null, "inventory capture could not start: " + failure.getClass().getSimpleName()));
        }
        return captured.handle((value, failure) -> new CaptureResult(value, failure)).thenApplyAsync(result -> {
            if (result.failure() != null || result.capture() == null) return new Evidence.Result(List.of(),
                    Evidence.Coverage.ERROR, Evidence.Outcome.ERROR, null, "inventory capture failed");
            if (!request.scope().equals(currentScope.get())) return new Evidence.Result(List.of(),
                    Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null, "inventory request scope changed during capture");
            synchronized (this) {
                if (result.capture().coverage() == Evidence.Coverage.UNAVAILABLE) return new Evidence.Result(
                        List.of(result.capture().summary()), Evidence.Coverage.UNAVAILABLE, Evidence.Outcome.UNKNOWN, null,
                        "vanilla player inventory is unavailable");
                return page(request, result.capture(), 0);
            }
        }, executor);
    }

    public void clear() {
        synchronized (this) {
            references.clear();
            cursors.clear();
        }
    }

    public LocatedItem resolve(String slotReference, Evidence.Scope scope) {
        Objects.requireNonNull(slotReference);
        Objects.requireNonNull(scope);
        if (client != null && !client.isSameThread()) throw new IllegalStateException("inventory reference must be resolved on the client thread");
        Reference reference;
        synchronized (this) {
            reference = references.get(slotReference);
            if (reference == null || reference.expiresAt() <= clock.getAsLong()) {
                references.remove(slotReference);
                throw new IllegalArgumentException("unknown or expired inventory slot reference");
            }
        }
        if (!reference.scope().equals(scope) || !reference.scope().equals(currentScope.get())) {
            throw new IllegalArgumentException("inventory slot reference scope changed");
        }
        var current = lookupSource.apply(reference.address());
        if (current == null || !current.section().equals(reference.address().section()) || current.slot() != reference.address().slot()) {
            throw new IllegalArgumentException("inventory slot location is no longer available");
        }
        return current;
    }

    public Evidence.Record readEvidence(String sourceHandle, Evidence.Scope scope) {
        Objects.requireNonNull(sourceHandle);
        Objects.requireNonNull(scope);
        if (client != null && !client.isSameThread()) throw new IllegalStateException("inventory evidence must be read on the client thread");
        if (!scope.equals(currentScope.get())) throw new IllegalArgumentException("inventory evidence scope changed");
        if (sourceHandle.startsWith("inventory-summary:")) {
            var capture = captureSource.apply(new Request(Operation.SEARCH, Filter.empty(), 32, null, scope));
            if (capture == null || capture.summary() == null) return null;
            var source = capture.summary().source();
            var current = new Evidence.Source(scope, source.providerId(), source.providerVersion(), sourceHandle,
                    scope.requestGeneration(), Instant.now(), source.activity(), source.coverage(), source.authority(), source.provenance());
            return new Evidence.Record(current, capture.summary().kind(), capture.summary().title(), capture.summary().payload(),
                    capture.summary().targets());
        }
        var location = resolve("native:" + sourceHandle, scope);
        if (location.stack().isEmpty()) return null;
        if (client == null || client.level == null) return null;
        var itemRegistry = client.level.registryAccess().registryOrThrow(Registries.ITEM);
        var current = NativeSnapshot.inventoryRecord(scope, location.stack(), itemRegistry, client.level.registryAccess(),
                location.section(), location.slot(), location.equipmentSlot(), location.selectedHotbar());
        var payload = new LinkedHashMap<>(current.payload().values());
        payload.put("slotReference", new Evidence.JsonString("native:" + sourceHandle));
        var source = current.source();
        return new Evidence.Record(new Evidence.Source(scope, source.providerId(), source.providerVersion(), sourceHandle,
                scope.requestGeneration(), Instant.now(), source.activity(), source.coverage(), source.authority(),
                source.provenance()), current.kind(), current.title(), new Evidence.JsonObject(payload), current.targets());
    }

    public String selectedReference(String hand, Evidence.Scope scope) {
        if (client != null && !client.isSameThread()) throw new IllegalStateException("selected inventory slot must be resolved on the client thread");
        if (!scope.equals(currentScope.get())) throw new IllegalArgumentException("inventory scope is no longer current");
        if (client == null || client.player == null) throw new IllegalArgumentException("player inventory unavailable");
        var inventory = client.player.getInventory();
        var address = switch (hand) {
            case "mainhand" -> new SlotAddress("hotbar", inventory.selected);
            case "offhand" -> new SlotAddress("offhand", 0);
            default -> throw new IllegalArgumentException("item slot must be mainhand or offhand");
        };
        var reference = "native:inventory:slot:" + UUID.nameUUIDFromBytes((scope + "|" + address.section() + "|" + address.slot())
                .getBytes(StandardCharsets.UTF_8));
        synchronized (this) {
            references.put(reference, new Reference(scope, address, clock.getAsLong() + OBSERVATION_TTL_MILLIS));
            while (references.size() > MAX_REFERENCES) references.remove(references.keySet().iterator().next());
        }
        return reference;
    }

    private CompletionStage<Evidence.Result> continueRead(Request request) {
        CursorState state;
        synchronized (this) {
            state = cursors.get(request.cursor());
            if (state == null || state.createdAt() + OBSERVATION_TTL_MILLIS <= clock.getAsLong()) {
                cursors.remove(request.cursor());
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null,
                        "inventory cursor is unknown or expired"));
            }
            if (!state.request().equals(new Request(request.operation(), request.filter(), request.limit(), null, request.scope()))
                    || !request.scope().equals(currentScope.get())) {
                return CompletableFuture.completedFuture(new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL, Evidence.Outcome.UNKNOWN, null,
                        "inventory cursor filters or scope changed"));
            }
            cursors.remove(request.cursor());
        }
        CompletionStage<Capture> captured = client == null ? CompletableFuture.supplyAsync(() -> captureSource.apply(
                new Request(request.operation(), request.filter(), request.limit(), null, request.scope())), executor)
                : client.submit(() -> captureSource.apply(new Request(request.operation(), request.filter(), request.limit(), null, request.scope())));
        return captured.handle((value, failure) -> new CaptureResult(value, failure)).thenApplyAsync(result -> {
            if (result.failure() != null || result.capture() == null) return new Evidence.Result(List.of(), Evidence.Coverage.ERROR,
                    Evidence.Outcome.ERROR, null, "inventory continuation capture failed");
            if (!request.scope().equals(currentScope.get())) return new Evidence.Result(List.of(), Evidence.Coverage.PARTIAL,
                    Evidence.Outcome.UNKNOWN, null, "inventory scope changed during continuation");
            synchronized (this) { return page(request, result.capture(), state.offset()); }
        }, executor);
    }

    private Evidence.Result page(Request request, Capture capture, int offset) {
        var all = new ArrayList<Evidence.Record>();
        for (var slot : capture.slots()) {
            if (slot.record() == null) continue;
            if (!matches(slot, request)) continue;
            all.add(slot.record());
        }
        var records = new ArrayList<Evidence.Record>();
        var end = Math.min(offset, all.size());
        var outputBytes = 0;
        if (offset == 0 && request.operation() == Operation.READ) {
            records.add(capture.summary());
            outputBytes = Evidence.serialize(capture.summary().payload()).getBytes(StandardCharsets.UTF_8).length + 256;
        }
        while (end < all.size() && end - offset < request.limit()) {
            var record = all.get(end);
            var recordBytes = Evidence.serialize(record.payload()).getBytes(StandardCharsets.UTF_8).length
                    + record.title().getBytes(StandardCharsets.UTF_8).length
                    + record.source().sourceHandle().getBytes(StandardCharsets.UTF_8).length
                    + record.source().provenance().stream().mapToInt(value -> value.getBytes(StandardCharsets.UTF_8).length).sum()
                    + 256;
            if (end > offset && outputBytes + recordBytes > MAX_PAGE_BYTES) break;
            records.add(record);
            outputBytes += recordBytes;
            end++;
        }
        for (var slot : capture.slots()) {
            if (records.contains(slot.record()) || records.contains(capture.summary())
                    && (slot.selectedHotbar() || "offhand".equals(slot.equipmentSlot()))) {
                references.put(slot.slotReference(), new Reference(request.scope(), new SlotAddress(slot.section(), slot.slot()),
                        capture.capturedAtMillis() + OBSERVATION_TTL_MILLIS));
            }
        }
        while (references.size() > MAX_REFERENCES) references.remove(references.keySet().iterator().next());
        String next = null;
        if (end < all.size()) {
            next = UUID.randomUUID().toString();
            cursors.put(next, new CursorState(new Request(request.operation(), request.filter(), request.limit(), null, request.scope()), end,
                    capture.capturedAtMillis()));
            while (cursors.size() > MAX_CURSORS) cursors.remove(cursors.keySet().iterator().next());
        }
        var unsupportedSections = request.filter().sections().stream().filter(section -> !SUPPORTED_SECTIONS.contains(section)).toList();
        var coverage = unsupportedSections.isEmpty() || capture.coverage() != Evidence.Coverage.COMPLETE
                ? capture.coverage() : Evidence.Coverage.PARTIAL;
        var outcome = coverage == Evidence.Coverage.COMPLETE ? Evidence.Outcome.COMPLETE : Evidence.Outcome.PARTIAL;
        var detail = "scope=direct vanilla player slots and equipment; nested storage contents are not inspected"
                + "; source=vanilla-player-inventory-and-equipment; capturedAt=" + capture.capturedAt()
                + (unsupportedSections.isEmpty() ? "" : "; unsupported native inventory sections were not inspected: "
                + String.join(", ", unsupportedSections) + "; native sections covered: " + String.join(", ", SUPPORTED_SECTIONS));
        return new Evidence.Result(records, coverage, outcome, next, detail);
    }

    private static boolean matches(CapturedSlot slot, Request request) {
        var filter = request.filter();
        return (filter.ids().isEmpty() || filter.ids().contains(slot.identity()))
                && (filter.sections().isEmpty() || filter.sections().contains(slot.section()))
                && matchesText(filter.names(), List.of(slot.name()))
                && (filter.tags().isEmpty() || filter.tags().stream().anyMatch(slot.tags()::contains))
                && matchesText(filter.tooltip(), slot.tooltip());
    }

    private static boolean matchesText(List<String> requested, List<String> values) {
        return requested.isEmpty() || requested.stream().anyMatch(needle -> values.stream()
                .anyMatch(value -> value.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT))));
    }

    private static Capture capture(Minecraft client, Request request, LongSupplier clock) {
        var scope = request.scope();
        if (!client.isSameThread()) throw new IllegalStateException("inventory capture must run on the client thread");
        if (client.level == null || client.player == null) return new Capture(List.of(), unavailable(scope,
                "client player or level unavailable"), Evidence.Coverage.UNAVAILABLE, Instant.now(), clock.getAsLong());
        var level = client.level;
        Registry<Item> itemRegistry;
        try {
            itemRegistry = level.registryAccess().registryOrThrow(Registries.ITEM);
        } catch (RuntimeException failure) {
            return new Capture(List.of(), unavailable(scope, "client item registry unavailable"),
                    Evidence.Coverage.UNAVAILABLE, Instant.now(), clock.getAsLong());
        }
        var observation = UUID.randomUUID().toString();
        var capturedAt = Instant.now();
        var capturedAtMillis = clock.getAsLong();
        var slots = new ArrayList<CapturedSlot>();
        var inventory = client.player.getInventory();
        var allSlots = new ArrayList<SlotInput>();
        for (int index = 0; index < inventory.items.size(); index++) {
            var section = index < 9 ? "hotbar" : "main";
            allSlots.add(new SlotInput(section, index < 9 ? index : index - 9,
                    index == inventory.selected ? "mainhand" : null,
                    index == inventory.selected, inventory.items.get(index)));
        }
        for (int index = 0; index < inventory.armor.size(); index++) {
            allSlots.add(new SlotInput("armor", index, List.of("feet", "legs", "chest", "head").get(index), false,
                    inventory.armor.get(index)));
        }
        for (int index = 0; index < inventory.offhand.size(); index++) {
            allSlots.add(new SlotInput("offhand", index, "offhand", false, inventory.offhand.get(index)));
        }
        var coverage = Evidence.Coverage.COMPLETE;
        for (var input : allSlots) {
            var stack = input.stack().copy();
            var ref = "native:inventory:" + observation + ":" + input.section() + ":" + input.slot();
            if (stack.isEmpty()) {
                slots.add(new CapturedSlot(input.section(), input.slot(), input.equipmentSlot(), input.selectedHotbar(),
                        "", "", ref, null, List.of(), List.of(), stack));
                continue;
            }
            var record = NativeSnapshot.inventoryRecord(scope, stack, itemRegistry, level.registryAccess(), input.section(),
                    input.slot(), input.equipmentSlot(), input.selectedHotbar());
            var payload = new LinkedHashMap<>(record.payload().values());
            var components = (Evidence.JsonObject) payload.get("components");
            var itemCoverage = record.source().coverage();
            if (Evidence.serialize(components).getBytes(StandardCharsets.UTF_8).length > MAX_COMPONENT_BYTES) {
                payload.put("components", new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))));
                coverage = Evidence.Coverage.PARTIAL;
                itemCoverage = Evidence.Coverage.PARTIAL;
            }
            payload.put("slotReference", new Evidence.JsonString(ref));
            var tags = new ArrayList<String>();
            var tooltip = new ArrayList<String>();
            var selectedExtractionFailed = false;
            try {
                if (!request.filter().tags().isEmpty()) {
                    tags.addAll(stack.getTags().map(tag -> tag.location().toString()).limit(MAX_TAGS + 1L).toList());
                    if (tags.size() > MAX_TAGS) {
                        tags.subList(MAX_TAGS, tags.size()).clear();
                        coverage = Evidence.Coverage.PARTIAL;
                        selectedExtractionFailed = true;
                    }
                }
            } catch (RuntimeException failure) {
                coverage = Evidence.Coverage.PARTIAL;
                selectedExtractionFailed = true;
            }
            try {
                if (!request.filter().tooltip().isEmpty()) {
                    var lines = stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(level), client.player,
                            TooltipFlag.NORMAL).stream().map(net.minecraft.network.chat.Component::getString).toList();
                    tooltip.addAll(lines.stream()
                            .limit(MAX_TOOLTIP_LINES + 1L).map(line -> line.length() > MAX_TOOLTIP_LINE_LENGTH
                                    ? line.substring(0, MAX_TOOLTIP_LINE_LENGTH) : line).toList());
                    if (lines.size() > MAX_TOOLTIP_LINES || lines.stream().anyMatch(line -> line.length() > MAX_TOOLTIP_LINE_LENGTH)) {
                        coverage = Evidence.Coverage.PARTIAL;
                        selectedExtractionFailed = true;
                    }
                    if (tooltip.size() > MAX_TOOLTIP_LINES) tooltip.subList(MAX_TOOLTIP_LINES, tooltip.size()).clear();
                }
            } catch (RuntimeException failure) {
                coverage = Evidence.Coverage.PARTIAL;
                selectedExtractionFailed = true;
            }
            if (!request.filter().tags().isEmpty()) {
                payload.put("tags", new Evidence.JsonArray(tags.stream().map(Evidence.JsonString::new)
                        .map(value -> (Evidence.JsonValue) value).toList()));
            }
            if (!request.filter().tooltip().isEmpty()) {
                payload.put("tooltip", new Evidence.JsonArray(tooltip.stream().map(Evidence.JsonString::new)
                        .map(value -> (Evidence.JsonValue) value).toList()));
            }
            if (selectedExtractionFailed) payload.put("selectedFieldExtractionPartial", new Evidence.JsonBoolean(true));
            var source = record.source();
            if (selectedExtractionFailed) itemCoverage = Evidence.Coverage.PARTIAL;
            if (itemCoverage != Evidence.Coverage.COMPLETE) coverage = Evidence.Coverage.PARTIAL;
            record = new Evidence.Record(new Evidence.Source(scope, source.providerId(), source.providerVersion(),
                    ref.substring("native:".length()), source.observedGeneration(), capturedAt, source.activity(), itemCoverage,
                    source.authority(), source.provenance()),
                    record.kind(), record.title(), new Evidence.JsonObject(payload), record.targets());
            slots.add(new CapturedSlot(input.section(), input.slot(), input.equipmentSlot(), input.selectedHotbar(),
                    itemRegistry.getKey(stack.getItem()).toString(), stack.getHoverName().getString(), ref, record, tags, tooltip, stack.copy()));
        }
        var summaryPayload = new LinkedHashMap<String, Evidence.JsonValue>();
        summaryPayload.put("sourceScope", new Evidence.JsonString("vanilla-player-inventory-and-equipment"));
        summaryPayload.put("capturedAt", new Evidence.JsonString(capturedAt.toString()));
        for (var slot : allSlots) if (slot.selectedHotbar() || "offhand".equals(slot.equipmentSlot())) {
            var reference = "native:inventory:" + observation + ":" + slot.section() + ":" + slot.slot();
            var location = new LinkedHashMap<String, Evidence.JsonValue>();
            location.put("section", new Evidence.JsonString(slot.section()));
            location.put("slot", new Evidence.JsonNumber(java.math.BigDecimal.valueOf(slot.slot())));
            location.put("slotReference", new Evidence.JsonString(reference));
            location.put("empty", new Evidence.JsonBoolean(slot.stack().isEmpty()));
            var key = slot.selectedHotbar() ? "mainhand" : "offhand";
            summaryPayload.put(key + "Location", new Evidence.JsonObject(location));
            summaryPayload.put(key + "SlotReference", new Evidence.JsonString(reference));
        }
        var summarySource = new Evidence.Source(scope, "native", "1.21.1", "inventory-summary:" + observation,
                scope.requestGeneration(), capturedAt, Evidence.Activity.ACTIVE, coverage, Evidence.Authority.LOADED,
                List.of("player-inventory", "vanilla-player-inventory-and-equipment"));
        var summary = new Evidence.Record(summarySource, Evidence.RecordKind.OTHER, "Player inventory observation",
                new Evidence.JsonObject(summaryPayload), List.of());
        return new Capture(slots, summary, coverage, capturedAt, capturedAtMillis);
    }

    private static LocatedItem locate(Minecraft client, SlotAddress address) {
        if (!client.isSameThread()) throw new IllegalStateException("inventory lookup must run on the client thread");
        if (client.player == null) return null;
        var inventory = client.player.getInventory();
        var stack = switch (address.section()) {
            case "hotbar" -> address.slot() < 0 || address.slot() >= 9 ? null : inventory.items.get(address.slot());
            case "main" -> address.slot() < 0 || address.slot() >= 27 ? null : inventory.items.get(address.slot() + 9);
            case "armor" -> address.slot() < 0 || address.slot() >= inventory.armor.size() ? null : inventory.armor.get(address.slot());
            case "offhand" -> address.slot() < 0 || address.slot() >= inventory.offhand.size() ? null : inventory.offhand.get(address.slot());
            default -> null;
        };
        if (stack == null) return null;
        var selected = address.section().equals("hotbar") && address.slot() == inventory.selected;
        return new LocatedItem(address.section(), address.slot(), address.section().equals("armor")
                ? List.of("feet", "legs", "chest", "head").get(address.slot())
                : address.section().equals("offhand") ? "offhand" : selected ? "mainhand" : null,
                selected, stack);
    }

    private static Evidence.Record unavailable(Evidence.Scope scope, String reason) {
        return new Evidence.Record(new Evidence.Source(scope, "native", "1.21.1", "inventory", scope.requestGeneration(),
                Instant.now(), Evidence.Activity.ACTIVE, Evidence.Coverage.UNAVAILABLE, Evidence.Authority.UNKNOWN,
                List.of(reason)), Evidence.RecordKind.OTHER, "Player inventory unavailable", new Evidence.JsonObject(Map.of()), List.of());
    }

    record SlotAddress(String section, int slot) { }

    record SlotInput(String section, int slot, String equipmentSlot, boolean selectedHotbar, ItemStack stack) { }

    record Capture(List<CapturedSlot> slots, Evidence.Record summary, Evidence.Coverage coverage,
                   Instant capturedAt, long capturedAtMillis) {
        Capture {
            slots = List.copyOf(slots);
        }
    }

    record CapturedSlot(String section, int slot, String equipmentSlot, boolean selectedHotbar, String identity, String name,
                        String slotReference, Evidence.Record record, List<String> tags, List<String> tooltip, ItemStack stack) {
        CapturedSlot {
            tags = List.copyOf(tags);
            tooltip = List.copyOf(tooltip);
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    public record LocatedItem(String section, int slot, String equipmentSlot, boolean selectedHotbar, ItemStack stack) {
        public LocatedItem {
            Objects.requireNonNull(section);
            Objects.requireNonNull(stack);
            stack = stack.copy();
        }
        @Override public ItemStack stack() { return stack.copy(); }
    }

    private record Reference(Evidence.Scope scope, SlotAddress address, long expiresAt) { }
    private record CursorState(Request request, int offset, long createdAt) { }
    private record CaptureResult(Capture capture, Throwable failure) { }
}
