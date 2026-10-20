package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Reads the active parsed pages exposed by the installed GuideME runtime. */
public final class GuideMeGuideReader implements GuideDocumentReader {
    public static final String ID = "guideme";
    public static final String VERSION = "21.1.19";
    private final String readerVersion;

    public GuideMeGuideReader() { this(VERSION); }

    GuideMeGuideReader(String version) { this.readerVersion = version; }

    public static GuideDocumentProvider forMinecraft(Supplier<Evidence.Scope> scope, Executor clientExecutor, String actualVersion) {
        return new GuideDocumentProvider(new GuideMeGuideReader(actualVersion), scope, clientExecutor);
    }

    @Override public String providerId() { return ID; }

    @Override public String version() { return readerVersion; }

    @Override
    public String sourceIdentity(Evidence.Scope scope) {
        try {
            var guidesType = Class.forName("guideme.Guides", false, getClass().getClassLoader());
            var guides = (Iterable<?>) guidesType.getMethod("getAll").invoke(null);
            var iterator = guides.iterator();
            int hash = 1;
            int count = 0;
            while (iterator.hasNext() && count < 256) {
                var guide = iterator.next();
                var pages = guide.getClass().getDeclaredField("pages");
                pages.setAccessible(true);
                var developmentPages = guide.getClass().getDeclaredField("developmentPages");
                developmentPages.setAccessible(true);
                hash = 31 * hash + System.identityHashCode(guide);
                hash = 31 * hash + System.identityHashCode(pages.get(guide));
                Object developmentPageMap = developmentPages.get(guide);
                hash = 31 * hash + System.identityHashCode(developmentPageMap);
                if (developmentPageMap instanceof Map<?, ?> pageMap) {
                    hash = 31 * hash + pageMap.size();
                    var entries = pageMap.entrySet().iterator();
                    int entryCount = 0;
                    while (entries.hasNext() && entryCount < 32) {
                        var entry = entries.next();
                        hash = 31 * hash + entry.getKey().hashCode();
                        hash = 31 * hash + System.identityHashCode(entry.getValue());
                        entryCount++;
                    }
                    hash = 31 * hash + entryCount;
                }
                count++;
            }
            return scope.worldSession().substring(0, Math.min(128, scope.worldSession().length())) + "|"
                    + scope.resourcesLocale().substring(0, Math.min(64, scope.resourcesLocale().length())) + "|"
                    + scope.providerRuntime().substring(0, Math.min(64, scope.providerRuntime().length())) + "|"
                    + hash + ":" + count + (iterator.hasNext() ? "+" : "");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return scope.worldSession().substring(0, Math.min(128, scope.worldSession().length())) + "|"
                    + scope.resourcesLocale().substring(0, Math.min(64, scope.resourcesLocale().length())) + "|"
                    + scope.providerRuntime().substring(0, Math.min(64, scope.providerRuntime().length())) + "|unavailable";
        }
    }

    @Override
    public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        if (limit < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "candidate limit must be positive");
        try {
            var guidesType = Class.forName("guideme.Guides", false, getClass().getClassLoader());
            var guides = (Iterable<?>) guidesType.getMethod("getAll").invoke(null);
            var candidates = new ArrayList<Candidate>();
            class Collector {
                boolean afterSeen = afterPosition == null;
                boolean partial;
                String next;
                boolean more;
                boolean stopped;

                boolean read(String guideId, Map<?, ?> pages, Map<?, ?> overrides, String layer,
                             boolean laterSourceExists) throws ReflectiveOperationException {
                    var iterator = pages.entrySet().iterator();
                    while (iterator.hasNext()) {
                        if (!budget.value()) {
                            partial = true;
                            stopped = true;
                            more = true;
                            if (!afterSeen) throw new Toolbox.Failure(
                                    Toolbox.Code.EXTRACTION_FAILED, "GuideME page traversal cannot seek past its current cursor within the source-work bound");
                            if (java.util.Objects.equals(next, afterPosition)) throw new Toolbox.Failure(
                                    Toolbox.Code.EXTRACTION_FAILED, "GuideME page traversal exhausted its work bound while seeking past the current cursor");
                            return true;
                        }
                        var entry = (Map.Entry<?, ?>) iterator.next();
                        String pageId = String.valueOf(entry.getKey());
                        String id = guideId + "#" + pageId;
                        String position = id + "|" + layer;
                        if (!afterSeen) {
                            next = position;
                            if (position.equals(afterPosition) || id.equals(afterPosition)) afterSeen = true;
                            continue;
                        }
                        if (layer.equals("base") && overrides.containsKey(entry.getKey())) {
                            next = position;
                            continue;
                        }
                        String language = String.valueOf(entry.getValue().getClass().getMethod("getLanguage").invoke(entry.getValue()));
                        if (id.length() > 1024 || language.length() > 128) {
                            partial = true;
                            stopped = true;
                            return true;
                        }
                        if (!budget.bytes(id.length() * 6 + language.length() * 6 + 192)) {
                            partial = true;
                            stopped = true;
                            more = true;
                            return true;
                        }
                        next = position;
                        candidates.add(new Candidate(id, pageId, position,
                                List.of("guideme:guide:" + guideId, "guideme:language:" + language),
                                Evidence.Coverage.COMPLETE));
                        if (candidates.size() >= limit) {
                            more = iterator.hasNext() || laterSourceExists;
                            stopped = true;
                            return true;
                        }
                    }
                    return false;
                }
            }
            var collector = new Collector();
            var guideIterator = guides.iterator();
            while (guideIterator.hasNext()) {
                if (!budget.value()) {
                    if (!collector.afterSeen) throw new Toolbox.Failure(
                            Toolbox.Code.EXTRACTION_FAILED, "GuideME guide traversal cannot seek past its current cursor within the source-work bound");
                    if (java.util.Objects.equals(collector.next, afterPosition)) throw new Toolbox.Failure(
                            Toolbox.Code.EXTRACTION_FAILED, "GuideME guide traversal exhausted its work bound while seeking past the current cursor");
                    return new CandidatePage(candidates, collector.next, true, Evidence.Coverage.PARTIAL,
                            "GuideME traversal reached the source-work bound");
                }
                var guide = guideIterator.next();
                String guideId = String.valueOf(guide.getClass().getMethod("getId").invoke(guide));
                var pagesField = guide.getClass().getDeclaredField("pages");
                pagesField.setAccessible(true);
                var developmentPagesField = guide.getClass().getDeclaredField("developmentPages");
                developmentPagesField.setAccessible(true);
                Object basePages = pagesField.get(guide);
                Object addedPages = developmentPagesField.get(guide);
                if (!(basePages instanceof Map<?, ?> pageMap) || !(addedPages instanceof Map<?, ?> developmentPageMap)) {
                    collector.partial = true;
                    continue;
                }
                boolean hasLaterGuide = guideIterator.hasNext();
                if (collector.read(guideId, pageMap, developmentPageMap, "base",
                        !developmentPageMap.isEmpty() || hasLaterGuide)) break;
                if (collector.read(guideId, developmentPageMap, Map.of(), "development", hasLaterGuide)) break;
            }
            if (!collector.afterSeen && !collector.partial) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "GuideME page changed or is no longer loaded; restart the lookup");
            return new CandidatePage(candidates, collector.next, collector.more,
                    collector.partial || collector.more ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    collector.partial ? "Some GuideME pages are not currently loaded or the bounded source was incomplete" : null);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new CandidatePage(List.of(), null, false, Evidence.Coverage.UNAVAILABLE,
                    "GuideME is not loaded or its current page registry is unreadable");
        }
    }

    @Override
    public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "fragment limit must be positive");
        int split = id.indexOf('#');
        if (split < 1 || split == id.length() - 1)
            throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "invalid GuideME page identity");
        try {
            String textPosition = fragmentPosition;
            int metadataPosition = 0;
            int astRelationshipPosition = 0;
            int relationshipFlags = 0;
            boolean firstRelationshipRead = fragmentPosition == null;
            boolean textDone = false;
            if (fragmentPosition != null && fragmentPosition.startsWith("g~")) {
                String[] cursor = fragmentPosition.split("~", -1);
                if (cursor.length != 5 || fragmentPosition.length() > 2048)
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
                try {
                    metadataPosition = Integer.parseInt(cursor[1]);
                    astRelationshipPosition = Integer.parseInt(cursor[2]);
                    relationshipFlags = Integer.parseInt(cursor[3]);
                } catch (NumberFormatException failure) {
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
                }
                if (metadataPosition < -1 || astRelationshipPosition < -1 || relationshipFlags < 0 || relationshipFlags > 15)
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
                textDone = cursor[4].equals("-");
                textPosition = textDone ? null : cursor[4];
            } else if (fragmentPosition != null) {
                metadataPosition = -1;
                astRelationshipPosition = -1;
                firstRelationshipRead = false;
            }
            String positionKind = null;
            String pathText = "";
            int offset = 0;
            if (textPosition != null) {
                String[] position = textPosition.split(":", -1);
                if (position.length < 2 || !List.of("s", "a", "l").contains(position[0]))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
                positionKind = position[0];
                pathText = position[1];
                if (positionKind.equals("l")) {
                    if (position.length != 3) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
                    try { offset = Integer.parseInt(position[2]); }
                    catch (NumberFormatException failure) { throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position"); }
                } else if (position.length != 2) {
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
                }
                if (offset < 0 || textPosition.length() > 2048)
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
            }
            var requestedPath = new ArrayList<Integer>();
            if (!pathText.isEmpty()) {
                for (String index : pathText.split("\\.")) {
                    try { requestedPath.add(Integer.parseInt(index)); }
                    catch (NumberFormatException failure) { throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position"); }
                    if (requestedPath.size() > 64 || requestedPath.getLast() < 0)
                        throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
                }
            }
            var guidesType = Class.forName("guideme.Guides", false, getClass().getClassLoader());
            var guide = guidesType.getMethod("getById", ResourceLocation.class).invoke(null,
                    ResourceLocation.parse(id.substring(0, split)));
            if (guide == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "GuideME guide is no longer loaded");
            var page = guide.getClass().getMethod("getParsedPage", ResourceLocation.class).invoke(guide,
                    ResourceLocation.parse(id.substring(split + 1)));
            if (page == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "GuideME page is no longer loaded");
            var pageResourceId = ResourceLocation.parse(id.substring(split + 1));
            Object root = Class.forName("guideme.compiler.ParsedGuidePage", false, getClass().getClassLoader())
                    .getMethod("getAstRoot").invoke(page);
            Class<?> parentType = Class.forName("guideme.libs.mdast.model.MdAstParent", false, getClass().getClassLoader());
            Class<?> literalType = Class.forName("guideme.libs.mdast.model.MdAstLiteral", false, getClass().getClassLoader());
            Class<?> elementType = Class.forName("guideme.libs.mdast.mdx.model.MdxJsxElementFields", false, getClass().getClassLoader());
            int initialMetadataPosition = metadataPosition;
            int initialAstRelationshipPosition = astRelationshipPosition;
            int initialRelationshipFlags = relationshipFlags;
            class Relationships {
                final List<Evidence.JsonValue> items = new ArrayList<>();
                final List<Evidence.JsonValue> recipes = new ArrayList<>();
                int nextMetadata = initialMetadataPosition;
                int nextAst = initialAstRelationshipPosition;
                int flags = initialRelationshipFlags;
                int records;
                int bytes;
                boolean stopped;

                boolean itemsPresent() { return (flags & 1) != 0; }
                boolean recipesPresent() { return (flags & 2) != 0; }
                boolean itemsPartial() { return (flags & 4) != 0 || nextMetadata >= 0; }
                boolean recipesPartial() { return (flags & 8) != 0 || nextAst >= 0; }

                int escapedLength(String value) {
                    int length = 2;
                    for (int index = 0; index < value.length(); index++) {
                        char character = value.charAt(index);
                        length += character == '"' || character == '\\' || character == '\b' || character == '\f'
                                || character == '\n' || character == '\r' || character == '\t' ? 2
                                : character <= 0x1f || character == '<' || character == '>' || character == '&'
                                || character == '=' || character == '\'' || character == '\u2028' || character == '\u2029' ? 6
                                : Character.isHighSurrogate(character) && index + 1 < value.length()
                                && Character.isLowSurrogate(value.charAt(index + 1)) ? 4
                                : character <= 0x7f ? 1 : character <= 0x7ff ? 2 : 3;
                        if (Character.isHighSurrogate(character) && index + 1 < value.length()
                                && Character.isLowSurrogate(value.charAt(index + 1))) index++;
                    }
                    return length;
                }

                boolean charge(String... values) {
                    int size = 144;
                    for (String value : values) size += escapedLength(value);
                    if (bytes + size > 8 * 1024 || !budget.bytes(size)) return false;
                    bytes += size;
                    return true;
                }

                ResourceLocation identity(String value) {
                    String qualified = value.indexOf(':') < 0 ? pageResourceId.getNamespace() + ":" + value : value;
                    return ResourceLocation.tryParse(qualified);
                }

                void readMetadata() throws ReflectiveOperationException {
                    if (nextMetadata < 0) return;
                    if (!budget.value()) return;
                    Object frontmatter = page.getClass().getMethod("getFrontmatter").invoke(page);
                    if (frontmatter == null) { nextMetadata = -1; return; }
                    if (!budget.value()) return;
                    Object additional = frontmatter.getClass().getMethod("additionalProperties").invoke(frontmatter);
                    if (!(additional instanceof Map<?, ?> properties)) { nextMetadata = -1; return; }
                    if (!budget.value()) return;
                    if (!properties.containsKey("item_ids")) { nextMetadata = -1; return; }
                    flags |= 1;
                    if (!budget.value()) return;
                    Object declarations = properties.get("item_ids");
                    if (!(declarations instanceof List<?> values)) {
                        flags |= 4;
                        nextMetadata = -1;
                        return;
                    }
                    for (int index = nextMetadata; index < values.size(); index++) {
                        if (records >= 16) { nextMetadata = index; flags |= 4; return; }
                        if (!budget.value()) { nextMetadata = index; flags |= 4; return; }
                        Object declaration = values.get(index);
                        if (!(declaration instanceof String itemText) || itemText.isBlank() || itemText.length() > 512) {
                            flags |= 4;
                            nextMetadata = index + 1;
                            continue;
                        }
                        ResourceLocation itemId = identity(itemText);
                        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                            flags |= 4;
                            nextMetadata = index + 1;
                            continue;
                        }
                        String sourcePosition = "frontmatter:item_ids:" + index;
                        if (!charge("kind", "associated", "sourcePosition", sourcePosition,
                                "itemId", itemId.toString())) {
                            if (budget.exhausted()) { nextMetadata = index; flags |= 4; return; }
                            flags |= 4;
                            nextMetadata = index + 1;
                            continue;
                        }
                        items.add(new Evidence.JsonObject(Map.of(
                                "kind", new Evidence.JsonString("associated"),
                                "sourcePosition", new Evidence.JsonString(sourcePosition),
                                "itemId", new Evidence.JsonString(itemId.toString()))));
                        records++;
                        nextMetadata = index + 1;
                    }
                    nextMetadata = -1;
                }

                void scan(Object current, int depth, int[] ordinal) throws ReflectiveOperationException {
                    if (stopped) return;
                    int currentOrdinal = ordinal[0]++;
                    if (!budget.value()) {
                        nextAst = currentOrdinal;
                        flags |= 2;
                        stopped = true;
                        return;
                    }
                    if (depth > 64) {
                        flags |= 2 | 8;
                        return;
                    }
                    if (!parentType.isInstance(current) && !literalType.isInstance(current)
                            && !elementType.isInstance(current)) flags |= 2 | 8;
                    List<?> children = List.of();
                    if (parentType.isInstance(current)) {
                        if (!budget.value()) {
                            nextAst = currentOrdinal;
                            flags |= 2;
                            stopped = true;
                            return;
                        }
                        children = (List<?>) parentType.getMethod("children").invoke(current);
                    }
                    if (currentOrdinal < nextAst) {
                        for (Object child : children) {
                            scan(child, depth + 1, ordinal);
                            if (stopped) return;
                        }
                        return;
                    }
                    if (elementType.isInstance(current)) {
                        flags |= 2;
                        if (records >= 16) {
                            nextAst = currentOrdinal;
                            stopped = true;
                            return;
                        }
                        String tag = String.valueOf(elementType.getMethod("name").invoke(current));
                        Object nativeTagsObject = Class.forName("guideme.compiler.tags.RecipeCompiler", false,
                                getClass().getClassLoader()).getConstructor().newInstance();
                        Object nativeTags = nativeTagsObject.getClass().getMethod("getTagNames").invoke(nativeTagsObject);
                        if (!(nativeTags instanceof Set<?> tagSet) || !tagSet.contains(tag)) {
                            flags |= 8;
                        } else {
                            var attributes = (List<?>) elementType.getMethod("attributes").invoke(current);
                            String idValue = null;
                            String fallbackText = null;
                            boolean malformed = false;
                            for (Object attribute : attributes) {
                                if (!budget.value()) {
                                    nextAst = currentOrdinal;
                                    flags |= 2;
                                    stopped = true;
                                    return;
                                }
                                String name = String.valueOf(attribute.getClass().getMethod("name").invoke(attribute));
                                if (!name.equals("id") && !name.equals("fallbackText")) { malformed = true; continue; }
                                if (!attribute.getClass().getMethod("hasStringValue").invoke(attribute).equals(Boolean.TRUE)) {
                                    malformed = true;
                                    continue;
                                }
                                String value = (String) attribute.getClass().getMethod("getStringValue").invoke(attribute);
                                if (value == null || value.length() > 2048) { malformed = true; continue; }
                                if (name.equals("id")) {
                                    if (idValue != null) malformed = true;
                                    else idValue = value;
                                } else {
                                    if (fallbackText != null) malformed = true;
                                    else fallbackText = value;
                                }
                            }
                            String kind = tag.equals("Recipe") ? "recipe" : "output_item";
                            String sourcePosition = "ast:" + currentOrdinal;
                            String targetKey = null;
                            String targetId = null;
                            if (idValue == null || idValue.isBlank()) malformed = true;
                            else if (idValue.length() > 512) malformed = true;
                            else {
                                ResourceLocation target = identity(idValue);
                                if (target == null) malformed = true;
                                else if (tag.equals("Recipe")) {
                                    targetKey = "recipeId";
                                    targetId = target.toString();
                                } else if (BuiltInRegistries.ITEM.containsKey(target)) {
                                    targetKey = "itemId";
                                    targetId = target.toString();
                                }
                                else malformed = true;
                            }
                            if (malformed) flags |= 8;
                            boolean charged = targetKey == null
                                    ? fallbackText == null ? charge("kind", kind, "sourcePosition", sourcePosition, "tag", tag)
                                    : charge("kind", kind, "sourcePosition", sourcePosition, "tag", tag,
                                    "fallbackText", fallbackText)
                                    : fallbackText == null ? charge("kind", kind, "sourcePosition", sourcePosition,
                                    "tag", tag, targetKey, targetId)
                                    : charge("kind", kind, "sourcePosition", sourcePosition, "tag", tag,
                                    "fallbackText", fallbackText, targetKey, targetId);
                            if (!charged) {
                                if (budget.exhausted()) {
                                    nextAst = currentOrdinal;
                                    flags |= 2;
                                    stopped = true;
                                    return;
                                }
                                flags |= 8;
                            } else {
                                var record = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
                                record.put("kind", new Evidence.JsonString(kind));
                                record.put("sourcePosition", new Evidence.JsonString(sourcePosition));
                                record.put("tag", new Evidence.JsonString(tag));
                                if (fallbackText != null) record.put("fallbackText", new Evidence.JsonString(fallbackText));
                                if (targetKey != null) record.put(targetKey, new Evidence.JsonString(targetId));
                                recipes.add(new Evidence.JsonObject(record));
                                records++;
                            }
                        }
                    }
                    for (Object child : children) {
                        scan(child, depth + 1, ordinal);
                        if (stopped) return;
                    }
                }
            }
            var relationships = new Relationships();
            if (relationships.nextMetadata >= 0) relationships.readMetadata();
            if (relationships.nextMetadata < 0 && relationships.nextAst >= 0) {
                if (relationships.records < 16) {
                    int[] ordinal = {0};
                    relationships.scan(root, 0, ordinal);
                    if (!relationships.stopped) {
                        relationships.nextAst = -1;
                        relationships.flags |= 2;
                    }
                } else {
                    relationships.flags |= 2 | 8;
                }
            }
            if (relationships.nextAst >= 0) relationships.flags |= 2;
            if (relationships.itemsPartial()) relationships.flags |= 4;
            if (relationships.recipesPartial()) relationships.flags |= 8;
            var relationshipValues = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
            if (relationships.itemsPresent() && (firstRelationshipRead || !relationships.items.isEmpty() || relationships.itemsPartial())) {
                var group = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
                group.put("coverage", new Evidence.JsonString(relationships.itemsPartial() ? "PARTIAL" : "COMPLETE"));
                group.put("values", new Evidence.JsonArray(relationships.items));
                if (relationships.itemsPartial()) group.put("detail", new Evidence.JsonString("GuideME item declarations were invalid, opaque or remain unread"));
                relationshipValues.put("items", new Evidence.JsonObject(group));
            }
            if (relationships.recipesPresent() && (firstRelationshipRead || !relationships.recipes.isEmpty() || relationships.recipesPartial())) {
                var group = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
                group.put("coverage", new Evidence.JsonString(relationships.recipesPartial() ? "PARTIAL" : "COMPLETE"));
                group.put("values", new Evidence.JsonArray(relationships.recipes));
                if (relationships.recipesPartial()) group.put("detail", new Evidence.JsonString("GuideME recipe tags were unsupported, malformed or remain unread"));
                relationshipValues.put("recipes", new Evidence.JsonObject(group));
            }
            var relationshipObject = new Evidence.JsonObject(relationshipValues);
            class Walker {
                Object found;
                List<Integer> foundPath;
                List<Integer> resumePath;
                int overlap;
                boolean partial;

                void findFirst(Object current, List<Integer> path, int depth) throws ReflectiveOperationException {
                    if (found != null || resumePath != null) return;
                    if (depth > 64) { partial = true; return; }
                    if (!budget.value()) {
                        resumePath = List.copyOf(path);
                        partial = true;
                        return;
                    }
                    if (literalType.isInstance(current)) {
                        found = current;
                        foundPath = List.copyOf(path);
                    } else if (parentType.isInstance(current)) {
                        var values = (List<?>) parentType.getMethod("children").invoke(current);
                        for (int index = 0; index < values.size(); index++) {
                            path.add(index);
                            findFirst(values.get(index), path, depth + 1);
                            path.removeLast();
                            if (found != null || resumePath != null) return;
                        }
                    } else {
                        partial = true;
                    }
                }

                Object locate(Object current, List<Integer> path, List<Object> ancestors) throws ReflectiveOperationException {
                    ancestors.add(current);
                    for (int index : path) {
                        if (!parentType.isInstance(current) || !budget.value()) {
                            resumePath = List.copyOf(path);
                            partial = true;
                            return null;
                        }
                        var values = (List<?>) parentType.getMethod("children").invoke(current);
                        if (index >= values.size()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "GuideME page structure changed; restart the lookup");
                        current = values.get(index);
                        ancestors.add(current);
                    }
                    return current;
                }

                void findNext(List<Integer> path) throws ReflectiveOperationException {
                    var ancestors = new ArrayList<Object>();
                    locate(root, path, ancestors);
                    if (resumePath != null) return;
                    for (int depth = path.size() - 1; depth >= 0; depth--) {
                        var values = (List<?>) parentType.getMethod("children").invoke(ancestors.get(depth));
                        for (int index = path.get(depth) + 1; index < values.size(); index++) {
                            var nextPath = new ArrayList<>(path.subList(0, depth));
                            nextPath.add(index);
                            if (!budget.value()) {
                                resumePath = nextPath;
                                partial = true;
                                return;
                            }
                            findFirst(values.get(index), nextPath, depth + 1);
                            if (found != null || resumePath != null) return;
                        }
                    }
                }

                String encode(String kind, List<Integer> path, int positionOffset) {
                    var encodedPath = new StringBuilder();
                    for (int index : path) {
                        if (!encodedPath.isEmpty()) encodedPath.append('.');
                        encodedPath.append(index);
                    }
                    return kind + ":" + encodedPath + (kind.equals("l") ? ":" + positionOffset : "");
                }
            }
            var walker = new Walker();
            if (!textDone && textPosition == null) walker.findFirst(root, new ArrayList<>(), 0);
            else if (!textDone && positionKind.equals("s")) {
                var ancestors = new ArrayList<Object>();
                Object seed = walker.locate(root, requestedPath, ancestors);
                if (walker.resumePath == null) walker.findFirst(seed, new ArrayList<>(requestedPath), requestedPath.size());
            } else if (!textDone && positionKind.equals("a")) walker.findNext(requestedPath);
            else if (!textDone) {
                var ancestors = new ArrayList<Object>();
                walker.found = walker.locate(root, requestedPath, ancestors);
                walker.foundPath = List.copyOf(requestedPath);
                if (walker.resumePath == null && !literalType.isInstance(walker.found))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "GuideME text node changed; restart the lookup");
            }
            if (walker.found == null) {
                String textNext = walker.resumePath == null ? null : walker.encode("s", walker.resumePath, 0);
                String next = relationships.nextMetadata >= 0 || relationships.nextAst >= 0 || textNext != null
                        ? "g~" + relationships.nextMetadata + "~" + relationships.nextAst + "~" + relationships.flags
                        + "~" + (textNext == null ? "-" : textNext) : null;
                String detail = walker.partial ? "GuideME AST traversal reached its bound or an unsupported node" : null;
                return new Fragment("", id + "|" + (fragmentPosition == null ? "first" : fragmentPosition), 0,
                        next, walker.partial || relationships.nextMetadata >= 0 || relationships.nextAst >= 0
                        ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, detail, relationshipObject);
            }
            String value = String.valueOf(literalType.getMethod("value").invoke(walker.found));
            int startOffset = positionKind != null && positionKind.equals("l") ? offset : 0;
            if (startOffset > value.length()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "GuideME page text changed; restart the lookup");
            int availableChars = budget.remainingBytes() / 6;
            int expectedOverlap = Math.min(GuideDocumentReader.FRAGMENT_OVERLAP_CHARS, startOffset);
            walker.overlap = Math.min(Math.min(expectedOverlap, maxChars - 1),
                    Math.max(0, availableChars - 1));
            if (walker.overlap < expectedOverlap) walker.partial = true;
            int copyStart = startOffset - walker.overlap;
            int copyEnd = startOffset + Math.min(Math.min(value.length() - startOffset, maxChars - walker.overlap),
                    availableChars - walker.overlap);
            if (copyEnd < value.length() && copyEnd > startOffset && Character.isHighSurrogate(value.charAt(copyEnd - 1))) copyEnd--;
            if (copyEnd == startOffset && startOffset < value.length())
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "GuideME text fragment cannot advance within the remaining read budget");
            if (!budget.bytes((copyEnd - copyStart) * 6))
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "GuideME text fragment exceeds the remaining read budget");
            String text = value.substring(copyStart, copyEnd);
            String textNext = copyEnd < value.length() ? walker.encode("l", walker.foundPath, copyEnd)
                    : walker.encode("a", walker.foundPath, 0);
            String nextPosition = "g~" + relationships.nextMetadata + "~" + relationships.nextAst + "~"
                    + relationships.flags + "~" + textNext;
            String detail = walker.overlap < expectedOverlap
                    ? "GuideME fragment overlap was shortened by the output or byte bound"
                    : walker.partial ? "GuideME AST contains unsupported nodes" : null;
            return new Fragment(text, id + "|" + walker.encode("l", walker.foundPath, startOffset),
                    walker.overlap, nextPosition, walker.partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    detail, relationshipObject);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new Fragment("", id, 0, null, Evidence.Coverage.PARTIAL,
                    "current GuideME page text is unavailable");
        }
    }

    @Override
    public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        int split = id.indexOf('#');
        var candidate = new Candidate(id, split < 0 ? id : id.substring(split + 1), id,
                split < 0 ? List.of("guideme") : List.of("guideme:guide:" + id.substring(0, split)),
                Evidence.Coverage.COMPLETE);
        return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
    }
}
