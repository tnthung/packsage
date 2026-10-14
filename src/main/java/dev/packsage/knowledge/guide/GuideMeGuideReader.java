package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Reads the active parsed pages exposed by the installed GuideME runtime. */
public final class GuideMeGuideReader implements GuideDocumentReader {
    public static final String ID = "guideme";
    public static final String VERSION = "21.1.19";

    public static GuideDocumentProvider forMinecraft(Supplier<Evidence.Scope> scope, Executor clientExecutor) {
        return new GuideDocumentProvider(new GuideMeGuideReader(), scope, clientExecutor);
    }

    @Override public String providerId() { return ID; }

    @Override public String version() { return VERSION; }

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
            String positionKind = null;
            String pathText = "";
            int offset = 0;
            if (fragmentPosition != null) {
                String[] position = fragmentPosition.split(":", -1);
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
                if (offset < 0 || fragmentPosition.length() > 2048)
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
            Object root = Class.forName("guideme.compiler.ParsedGuidePage", false, getClass().getClassLoader())
                    .getMethod("getAstRoot").invoke(page);
            Class<?> parentType = Class.forName("guideme.libs.mdast.model.MdAstParent", false, getClass().getClassLoader());
            Class<?> literalType = Class.forName("guideme.libs.mdast.model.MdAstLiteral", false, getClass().getClassLoader());
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
            if (fragmentPosition == null) walker.findFirst(root, new ArrayList<>(), 0);
            else if (positionKind.equals("s")) {
                var ancestors = new ArrayList<Object>();
                Object seed = walker.locate(root, requestedPath, ancestors);
                if (walker.resumePath == null) walker.findFirst(seed, new ArrayList<>(requestedPath), requestedPath.size());
            } else if (positionKind.equals("a")) walker.findNext(requestedPath);
            else {
                var ancestors = new ArrayList<Object>();
                walker.found = walker.locate(root, requestedPath, ancestors);
                walker.foundPath = List.copyOf(requestedPath);
                if (walker.resumePath == null && !literalType.isInstance(walker.found))
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "GuideME text node changed; restart the lookup");
            }
            if (walker.found == null) {
                String next = walker.resumePath == null ? null : walker.encode("s", walker.resumePath, 0);
                return new Fragment("", id + "|" + (fragmentPosition == null ? "first" : fragmentPosition), 0,
                        next, walker.partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                        walker.partial ? "GuideME AST traversal reached its bound or an unsupported node" : null);
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
            String nextPosition = copyEnd < value.length() ? walker.encode("l", walker.foundPath, copyEnd)
                    : walker.encode("a", walker.foundPath, 0);
            String detail = walker.overlap < expectedOverlap
                    ? "GuideME fragment overlap was shortened by the output or byte bound"
                    : walker.partial ? "GuideME AST contains unsupported nodes" : null;
            return new Fragment(text, id + "|" + walker.encode("l", walker.foundPath, startOffset),
                    walker.overlap, nextPosition, walker.partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    detail);
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
