package dev.packsage.knowledge.patchouli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.locale.Language;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.BookPage;
import vazkii.patchouli.client.book.page.PageText;
import vazkii.patchouli.client.book.page.PageSpotlight;
import vazkii.patchouli.client.book.page.PageCrafting;
import vazkii.patchouli.client.book.page.PageSmelting;
import vazkii.patchouli.client.book.page.PageBlasting;
import vazkii.patchouli.client.book.page.PageSmoking;
import vazkii.patchouli.client.book.page.PageCampfireCooking;
import vazkii.patchouli.client.book.page.PageSmithing;
import vazkii.patchouli.client.book.page.PageStonecutting;
import vazkii.patchouli.client.book.page.abstr.PageWithText;
import vazkii.patchouli.common.book.Book;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stateless bounded reads of the already loaded Patchouli objects. */
final class PatchouliLiveReader {
    private PatchouliLiveReader() { }

    record Position(int page, int node, int textOffset, int mapping) { }
    record Entry(PatchouliProvider.EntryRecord record, Position next) { }
    record Page(PatchouliProvider.PageRecord record, int nextNode, int nextTextOffset, boolean complete) { }

    static Entry entry(Book book, BookEntry entry, Position position, Toolbox.DocumentBudget budget) {
        try {
            if (!budget.value() || !budget.bytes(2048)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Patchouli entry metadata exceeds the remaining read budget");
            var nameField = BookEntry.class.getDeclaredField("name");
            nameField.setAccessible(true);
            var name = (String) nameField.get(entry);
            if (book.i18n) name = Language.getInstance().getOrDefault(name);
            boolean partial = name.length() > 256 || position != null;
            if (name.length() > 256) name = name.substring(0, 256);
            // getPages() filters and materializes the entire list in these supported releases.
            // Read its already materialized backing list and apply the same unlock predicate lazily.
            var pagesField = BookEntry.class.getDeclaredField("realPages");
            pagesField.setAccessible(true);
            var loadedPages = (List<?>) pagesField.get(entry);
            var pages = new ArrayList<PatchouliProvider.PageRecord>();
            Position next = null;
            int pageIndex = 0;
            int displayIndex = 0;
            while (pageIndex < loadedPages.size()) {
                if (!budget.value()) { next = new Position(pageIndex, 0, 0, 0); break; }
                var page = (BookPage) loadedPages.get(pageIndex);
                boolean visible = !book.advancementsEnabled() || page.isPageUnlocked();
                int displayPage = displayIndex;
                if (visible) displayIndex++;
                if (position != null && pageIndex < position.page()) { pageIndex++; continue; }
                if (!visible) { partial = true; pageIndex++; continue; }
                var fragment = page(page, displayPage, book.i18n,
                        position != null && pageIndex == position.page() ? position.node() : 0,
                        position != null && pageIndex == position.page() ? position.textOffset() : 0, budget);
                pages.add(fragment.record());
                if (!fragment.complete()) {
                    next = new Position(pageIndex, fragment.nextNode(), fragment.nextTextOffset(), 0);
                    break;
                }
                pageIndex++;
            }
            if (position != null && pageIndex < position.page()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Patchouli page seek exceeded its budget or the entry changed");
            var mappings = new ArrayList<String>();
            if (next == null) {
                var mappingsField = BookEntry.class.getDeclaredField("extraRecipeMappings");
                mappingsField.setAccessible(true);
                var loadedMappings = (Map<?, ?>) mappingsField.get(entry);
                var iterator = loadedMappings.entrySet().iterator();
                int mappingIndex = 0;
                while (iterator.hasNext()) {
                    if (!budget.value()) { next = new Position(pageIndex, 0, 0, mappingIndex); break; }
                    var mapping = iterator.next();
                    if (position != null && mappingIndex < position.mapping()) { mappingIndex++; continue; }
                    if (!(mapping.getKey() instanceof String item) || !(mapping.getValue() instanceof Integer page)) {
                        throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "unsupported Patchouli recipe mapping contract");
                    }
                    if (item.length() > 1024 || !budget.bytes(item.length() * 6 + 32)) {
                        next = new Position(pageIndex, 0, 0, mappingIndex);
                        break;
                    }
                    mappings.add(item + "@" + page);
                    mappingIndex++;
                }
                if (position != null && mappingIndex < position.mapping()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                        "Patchouli mapping seek exceeded its budget or the entry changed");
            }
            partial |= next != null;
            var provenance = entry.getAddedBy();
            var sources = provenance == null || provenance.isBlank() ? List.<String>of()
                    : List.of("patchouli:resource:" + provenance.substring(0, Math.min(256, provenance.length())));
            return new Entry(new PatchouliProvider.EntryRecord(book.id.toString(), entry.getId().toString(), name,
                    pages, true, false, entry.isSecret(), false, mappings, sources,
                    partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    partial ? "bounded live guide fragment; source paths and text offsets identify partial fields" : null), next);
        } catch (ReflectiveOperationException failure) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "Patchouli loaded entry contract is unavailable");
        }
    }

    static Page page(BookPage page, int pageIndex, boolean translated, int startNode, int startOffset,
                     Toolbox.DocumentBudget budget) {
        if (startNode < 0 || startOffset < 0) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid page position");
        var root = new JsonObject();
        var source = page.sourceObject == null ? new JsonObject() : page.sourceObject;
        root.add("source", source);
        if (page instanceof PageWithText && source.has("text") && source.get("text").isJsonPrimitive()) {
            var text = source.get("text").getAsString();
            // Language lookup returns the existing translated string; avoid I18n's unbounded formatting copy.
            root.addProperty("text", translated ? Language.getInstance().getOrDefault(text) : text);
        }
        class Walker {
            int node;
            int nextNode = -1;
            int nextOffset;
            final List<Evidence.JsonValue> fragments = new ArrayList<>();
            final List<String> items = new ArrayList<>();
            final List<String> recipes = new ArrayList<>();
            final StringBuilder text = new StringBuilder();

            void read(JsonElement value, List<Evidence.JsonValue> path, int depth) {
                if (nextNode >= 0) return;
                if (depth > 32) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "Patchouli page nesting exceeds the read bound");
                int currentNode = node++;
                if (!budget.value()) { nextNode = currentNode; return; }
                if (value.isJsonObject()) {
                    var iterator = value.getAsJsonObject().entrySet().iterator();
                    while (iterator.hasNext() && nextNode < 0) {
                        if (!budget.value()) { nextNode = node; break; }
                        var field = iterator.next();
                        if (field.getKey().length() > 1024 || !budget.bytes(field.getKey().length() * 6 + 16)) {
                            nextNode = node;
                            break;
                        }
                        var childPath = new ArrayList<>(path);
                        childPath.add(new Evidence.JsonString(field.getKey()));
                        read(field.getValue(), childPath, depth + 1);
                    }
                    return;
                }
                if (value.isJsonArray()) {
                    var array = value.getAsJsonArray();
                    for (int index = 0; index < array.size() && nextNode < 0; index++) {
                        if (!budget.value()) { nextNode = node; break; }
                        var childPath = new ArrayList<>(path);
                        childPath.add(new Evidence.JsonNumber(BigDecimal.valueOf(index)));
                        read(array.get(index), childPath, depth + 1);
                    }
                    return;
                }
                if (currentNode < startNode) return;
                var fragment = new LinkedHashMap<String, Evidence.JsonValue>();
                int pathBytes = 256;
                for (var step : path) pathBytes += step instanceof Evidence.JsonString field ? field.value().length() * 6 + 16 : 32;
                if (!budget.bytes(pathBytes)) { nextNode = currentNode; return; }
                fragment.put("path", new Evidence.JsonArray(path));
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    String raw = value.getAsString();
                    int offset = currentNode == startNode ? startOffset : 0;
                    if (offset > raw.length()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Patchouli text changed; restart the lookup");
                    int end = offset + Math.min(raw.length() - offset, Math.max(0, (budget.remainingBytes() - 16) / 12));
                    if (end < raw.length() && end > offset && Character.isHighSurrogate(raw.charAt(end - 1))) end--;
                    if (end == offset && end < raw.length()) { nextNode = currentNode; nextOffset = offset; return; }
                    if (!budget.bytes((end - offset) * 12 + 16)) { nextNode = currentNode; nextOffset = offset; return; }
                    String part = raw.substring(offset, end);
                    fragment.put("value", new Evidence.JsonString(part));
                    fragment.put("textOffset", new Evidence.JsonNumber(BigDecimal.valueOf(offset)));
                    fragment.put("complete", new Evidence.JsonBoolean(offset == 0 && end == raw.length()));
                    if (path.equals(List.of(new Evidence.JsonString("text")))) text.append(part);
                    if (offset == 0 && end == raw.length() && path.stream().anyMatch(key -> key instanceof Evidence.JsonString field
                            && List.of("item", "items").contains(field.value()))) items.add(part);
                    if (offset == 0 && end == raw.length() && path.stream().anyMatch(key -> key instanceof Evidence.JsonString field
                            && List.of("recipe", "recipes", "recipe_id", "recipeId").contains(field.value()))) recipes.add(part);
                    if (end < raw.length()) { nextNode = currentNode; nextOffset = end; }
                } else {
                    if (!value.isJsonNull() && value.getAsString().length() > 1024)
                        throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "Patchouli primitive exceeds the read bound");
                    if (!budget.bytes(value.isJsonNull() ? 16 : value.getAsString().length() * 6 + 16)) { nextNode = currentNode; return; }
                    fragment.put("value", value.isJsonNull() ? new Evidence.JsonNull() : value.getAsJsonPrimitive().isBoolean()
                            ? new Evidence.JsonBoolean(value.getAsBoolean()) : new Evidence.JsonNumber(value.getAsBigDecimal()));
                }
                fragments.add(new Evidence.JsonObject(fragment));
            }
        }
        var walker = new Walker();
        walker.read(root, List.of(), 0);
        if (walker.nextNode >= 0 && walker.nextNode <= startNode && walker.nextOffset <= startOffset
                || walker.node <= startNode) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Patchouli page continuation cannot advance within the bounded seek; narrow the lookup");
        boolean complete = walker.nextNode < 0;
        var type = source.has("type") && source.get("type").isJsonPrimitive() ? source.get("type").getAsString() : page.getClass().getSimpleName();
        if (type.length() > 128) type = type.substring(0, 128);
        var supported = page instanceof PageText || page instanceof PageSpotlight || page instanceof PageCrafting
                || page instanceof PageSmelting || page instanceof PageBlasting || page instanceof PageSmoking
                || page instanceof PageCampfireCooking || page instanceof PageSmithing || page instanceof PageStonecutting;
        var payload = complete && startNode == 0 && startOffset == 0 ? Evidence.deserializeObject(source.toString())
                : new Evidence.JsonObject(Map.of("fragments", new Evidence.JsonArray(walker.fragments)));
        return new Page(new PatchouliProvider.PageRecord(pageIndex, type, payload, walker.text.isEmpty() ? null : walker.text.toString(),
                walker.items, walker.recipes, true, complete && startNode == 0 ? supported ? Evidence.DecodingStatus.VERIFIED
                : Evidence.DecodingStatus.OPAQUE : Evidence.DecodingStatus.PARTIAL), walker.nextNode, walker.nextOffset, complete);
    }
}
