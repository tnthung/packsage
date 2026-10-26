package dev.packsage.world;

import dev.packsage.knowledge.Evidence;
import dev.packsage.model.PackSageTools;
import net.minecraft.server.level.ServerLevel;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static dev.packsage.world.WorldPaging.*;
import static dev.packsage.world.WorldTools.*;

final class WorldAreaTools {
    private record EntityRead(Center center, Page<EntityHandle> page, List<String> cursors) {
    }

    private record AreaRead(Center center, Page<BlockSummary> blocks, Page<EntityHandle> entities) {
    }

    private final WorldTools world;

    WorldAreaTools(WorldTools world) {
        this.world = world;
    }

    CompletionStage<Evidence.Result> inspectArea(PackSageTools.ReadRequest request) {
        var anchor = anchor(argument(request, "center"));
        var radius = radius(argument(request, "radius"));
        var server = world.server();
        return world.run(server, spanned(gameTime(server), then(() -> world.center(server, anchor), center -> {
            var level = level(server, center.dimension());
            return mapped(both(world.scan.summary(level, center.position(), radius),
                            world.scan.listEntities(level, center.position(), radius, KindFilter.ANY, null)),
                    pages -> new AreaRead(center, pages.getKey(), pages.getValue()));
        }))).thenApply(read -> area(request.scope(), anchor, radius, read.value(), read.span()));
    }

    CompletionStage<Evidence.Result> listBlocks(PackSageTools.ReadRequest request) {
        var token = world.paging.cursor(request);
        if (token != null && state(token, ListState.class).pending() != null) {
            world.server();
            return CompletableFuture.completedFuture(blocks(request, null, null, null));
        }
        return list(request, (level, center, radius, filter, cursor) -> mapped(
                world.scan.listBlocks(level, center.position(), radius, filter, cursor), page -> Map.entry(center, page)))
                .thenApply(read -> blocks(request, read.value().getKey(), read.value().getValue(), read.span()));
    }

    CompletionStage<Evidence.Result> listEntities(PackSageTools.ReadRequest request) {
        return list(request, (level, center, radius, filter, cursor) -> {
            var slice = world.scan.listEntities(level, center.position(), radius, filter, cursor);
            return mapped(slice, page -> new EntityRead(center, page, List.copyOf(slice.cursors)));
        }).thenApply(read -> {
            var page = read.value().page();
            var records = new ArrayList<Evidence.Record>();
            var bytes = 0;
            var nextScan = page.nextCursor();
            var coverage = page.coverage();
            var notes = new LinkedHashSet<String>();
            if (page.note() != null) notes.add(page.note());
            for (var entity : page.items()) {
                var record = entityRecord(request.scope(), entity, read.span());
                var recordBytes = PackSageTools.encodeRecord(record).toString().getBytes(StandardCharsets.UTF_8).length
                        + (records.isEmpty() ? 0 : 1);
                if (bytes + recordBytes > 64 * 1024) {
                    if (records.isEmpty()) throw new Failure(Code.EXTRACTION_FAILED, "one entity record exceeds the page byte budget");
                    nextScan = read.value().cursors().get(records.size() - 1);
                    coverage = Evidence.Coverage.PARTIAL;
                    notes.add("Entity page byte budget reached; continue with the cursor");
                    break;
                }
                records.add(record);
                bytes += recordBytes;
                if (record.source().coverage() == Evidence.Coverage.PARTIAL) {
                    coverage = Evidence.Coverage.PARTIAL;
                    notes.add("Entity names longer than 1,024 characters were truncated; nameTruncated marks the affected records");
                }
            }
            var next = nextScan == null ? null : world.paging.remember(request, new ListState(read.value().center(), nextScan));
            return result(records, coverage, next, notes.isEmpty() ? null : String.join("; ", notes));
        });
    }

    @FunctionalInterface
    private interface ListScan<T> {
        ServerWork.Slice<T> scan(ServerLevel level, Center center, int radius, KindFilter filter, String cursor);
    }

    private <T> CompletionStage<Spanned<T>> list(PackSageTools.ReadRequest request, ListScan<T> listing) {
        var anchor = anchor(argument(request, "center"));
        var radius = radius(argument(request, "radius"));
        var filter = filter(argument(request, "filter"), false);
        var cursor = world.paging.cursor(request);
        var server = world.server();
        var state = cursor == null ? null : state(cursor, ListState.class);
        return world.run(server, spanned(gameTime(server), then(() -> state == null ? world.center(server, anchor) : state.center(), center ->
                listing.scan(level(server, center.dimension()), center, radius, filter, state == null ? null : state.scan()))));
    }

    private Evidence.Result blocks(PackSageTools.ReadRequest request, Center center, Page<BlockHandle> page, ReadSpan span) {
        var cursor = world.paging.cursor(request);
        if (cursor != null) {
            var state = state(cursor, ListState.class);
            if (state.pending() != null) {
                center = state.center();
                page = state.pending();
                span = state.span();
            }
        }
        var records = new ArrayList<Evidence.Record>();
        var bytes = 0;
        for (var block : page.items()) {
            var record = blockRecord(request.scope(), block, span);
            var recordBytes = PackSageTools.encodeRecord(record).toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes + recordBytes > 64 * 1024) {
                if (records.isEmpty()) throw new Failure(Code.EXTRACTION_FAILED, "one block record exceeds the page byte budget");
                break;
            }
            records.add(record);
            bytes += recordBytes;
        }
        var cut = records.size() < page.items().size();
        var next = cut ? world.paging.remember(request, new ListState(center, page.nextCursor(),
                new Page<>(page.items().subList(records.size(), page.items().size()), page.nextCursor(), page.coverage(), page.note()), span))
                : page.nextCursor() == null ? null : world.paging.remember(request, new ListState(center, page.nextCursor()));
        var note = page.note();
        if (cut) note = (note == null ? "" : note + "; ") + "Block page byte budget reached; continue with the cursor";
        return result(records, page.coverage(), next, note);
    }

    static List<String> areaNotes(Page<BlockSummary> blocks, Page<EntityHandle> entities) {
        var notes = new LinkedHashSet<String>();
        if (blocks.note() != null) notes.add(blocks.note());
        if (blocks.items().getFirst().notable().size() >= AreaScan.SUMMARY_NOTABLE) {
            notes.add("Notable blocks reached the " + AreaScan.SUMMARY_NOTABLE + " cap; use list_blocks with a filter for the rest");
        }
        // inspect_area returns no cursor, so translate paging instructions and keep concrete omission reasons.
        if (entities.nextCursor() != null) {
            notes.add(entities.items().size() >= AreaScan.LIST_PAGE
                    ? "More than " + AreaScan.LIST_PAGE + " entities are in range; use list_entities with a filter for the rest"
                    : "The entity scan stopped at the per-call work limit; use list_entities for the rest");
        }
        if (entities.note() != null) {
            for (var part : entities.note().split("; ")) {
                if (part.equals(AreaScan.PAGE_LIMIT_NOTE.split("; ")[0]) || part.equals("continue with the cursor")
                        || part.startsWith("Stopped at the per-call work limit") || part.startsWith("The read spanned multiple server ticks")
                        || part.startsWith("game-time span:")) continue;
                notes.add(part);
            }
        }
        return List.copyOf(notes);
    }

    private Evidence.Result area(Evidence.Scope scope, Anchor anchor, int radius, AreaRead read, ReadSpan span) {
        var summary = read.blocks().items().getFirst();
        var notes = new LinkedHashSet<>(areaNotes(read.blocks(), read.entities()));
        var notable = new TreeMap<String, Map<String, List<Evidence.JsonValue>>>();
        // Block ids and positions are ASCII, so string lengths bound their encoded bytes; entities get the remaining budget.
        int notableBytes = 0, notableListed = 0;
        for (var block : summary.notable()) {
            var place = block.handle().place();
            var position = place.x() + "," + place.y() + "," + place.z();
            notableBytes += position.length() + 3 + (notable.containsKey(block.role()) && notable.get(block.role()).containsKey(block.handle().kind())
                    ? 0 : block.role().length() + block.handle().kind().length() + 8);
            if (notableBytes > AREA_NOTABLE_BYTES) break;
            notable.computeIfAbsent(block.role(), key -> new TreeMap<>()).computeIfAbsent(block.handle().kind(), key -> new ArrayList<>())
                    .add(text(position));
            notableListed++;
        }
        if (notableListed < summary.notable().size()) {
            notes.add("Area notable-block byte budget reached; " + (summary.notable().size() - notableListed)
                    + " notable blocks omitted from this summary; use list_blocks with a filter for the rest");
        }
        var roles = new LinkedHashMap<String, Evidence.JsonValue>();
        notable.forEach((role, kinds) -> {
            var byKind = new LinkedHashMap<String, Evidence.JsonValue>();
            kinds.forEach((kind, places) -> byKind.put(kind, new Evidence.JsonArray(places)));
            roles.put(role, new Evidence.JsonObject(byKind));
        });
        var counts = new LinkedHashMap<String, Evidence.JsonValue>();
        int countBytes = 0;
        var byCount = summary.counts().entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey())).toList();
        for (var entry : byCount) {
            countBytes += entry.getKey().length() + 16;
            if (countBytes > AREA_COUNT_BYTES) {
                notes.add("Area block-count byte budget reached; the " + (byCount.size() - counts.size())
                        + " least common block ids are omitted; use list_blocks with a filter for them");
                break;
            }
            counts.put(entry.getKey(), number(entry.getValue()));
        }
        var center = new LinkedHashMap<String, Evidence.JsonValue>();
        center.put("dimension", text(read.center().dimension()));
        center.put("x", decimal(read.center().position().x));
        center.put("y", decimal(read.center().position().y));
        center.put("z", decimal(read.center().position().z));
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("center", new Evidence.JsonObject(center));
        payload.put("radius", number(radius));
        payload.put("blockCounts", new Evidence.JsonObject(counts));
        payload.put("notable", new Evidence.JsonObject(roles));
        payload.put("notableCount", number(summary.notable().size()));
        payload.put("unloadedChunks", number(summary.unloadedChunks()));
        putSpan(payload, span);
        var entities = new ArrayList<Evidence.JsonValue>();
        var handle = "area|" + anchorText(anchor) + "|" + radius;
        var title = "Area within " + radius + " blocks of " + anchorText(anchor);
        for (var entity : read.entities().items()) {
            var record = entityRecord(scope, entity, null);
            entities.add(record.payload());
            payload.put("entities", new Evidence.JsonArray(entities));
            // Measure the entire record with the real encoder, including escaped names and the block summary.
            if (PackSageTools.encodeRecord(evidence(scope, handle, Evidence.RecordKind.OTHER, title, payload,
                    Evidence.Coverage.PARTIAL, null), Integer.MAX_VALUE).toString().getBytes(StandardCharsets.UTF_8).length > PackSageTools.MAX_RECORD_BYTES - 512) {
                entities.removeLast();
                notes.add("Area entity byte budget reached; " + (read.entities().items().size() - entities.size())
                        + " entities omitted from this summary; use list_entities for the rest");
                break;
            }
            if (record.source().coverage() == Evidence.Coverage.PARTIAL) {
                notes.add("Entity names longer than 1,024 characters were truncated; nameTruncated marks the affected records");
            }
        }
        payload.put("entities", new Evidence.JsonArray(entities));
        var coverage = notes.isEmpty() && read.blocks().coverage() == Evidence.Coverage.COMPLETE
                && read.entities().coverage() == Evidence.Coverage.COMPLETE ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL;
        var record = evidence(scope, handle, Evidence.RecordKind.OTHER, title, payload, coverage, null);
        return result(List.of(record), coverage, null, notes.isEmpty() ? null : String.join("; ", notes));
    }

    private Evidence.Record blockRecord(Evidence.Scope scope, BlockHandle block, ReadSpan span) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("block", text(block.kind()));
        payload.put("place", text(block.place().text()));
        putSpan(payload, span);
        return evidence(scope, "block|" + block.place().text(), Evidence.RecordKind.BLOCK, block.kind() + " at " + block.place().text(),
                payload, Evidence.Coverage.COMPLETE, WorldNavigation.blockId(block.place()));
    }

    Evidence.Record entityRecord(Evidence.Scope scope, EntityHandle entity, ReadSpan span) {
        var truncated = entity.name() != null && entity.name().length() > 1024;
        if (truncated) entity = new EntityHandle(entity.kind(), entity.id(), entity.dimension(), entity.x(), entity.y(), entity.z(),
                entity.name().substring(0, Character.isHighSurrogate(entity.name().charAt(1023)) ? 1023 : 1024));
        var payload = new LinkedHashMap<>(entityFacts(entity));
        payload.put("nameTruncated", new Evidence.JsonBoolean(truncated));
        putSpan(payload, span);
        return evidence(scope, "entity|" + entity.id(), Evidence.RecordKind.OTHER, entityTitle(entity), payload,
                truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, WorldNavigation.entityId(entity.id()));
    }

    private static Map<String, Evidence.JsonValue> entityFacts(EntityHandle entity) {
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("entity", text(entity.kind()));
        payload.put("id", text(entity.id().toString()));
        payload.put("name", text(entity.name()));
        payload.put("dimension", text(entity.dimension()));
        payload.put("x", decimal(entity.x()));
        payload.put("y", decimal(entity.y()));
        payload.put("z", decimal(entity.z()));
        return payload;
    }

    static String entityTitle(EntityHandle entity) {
        return entity.name() + " (" + entity.kind() + ") at " + entity.dimension() + "|"
                + net.minecraft.util.Mth.floor(entity.x()) + "," + net.minecraft.util.Mth.floor(entity.y()) + "," + net.minecraft.util.Mth.floor(entity.z());
    }

    private static String anchorText(Anchor anchor) {
        return switch (anchor) {
            case Anchor.Player player -> "player";
            case Anchor.OfEntity of -> "entity:" + of.id();
            case Anchor.OfBlock of -> "block:" + of.place().text();
            case Anchor.OfNote of -> "note:" + of.id();
        };
    }

    private static Evidence.JsonNumber decimal(double value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(Math.round(value * 10) / 10.0));
    }
}
