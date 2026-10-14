package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
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
            var identity = new StringBuilder(scope.worldSession()).append('|').append(scope.resourcesLocale())
                    .append('|').append(scope.providerRuntime());
            var iterator = guides.iterator();
            int count = 0;
            while (iterator.hasNext() && count < 2048) {
                var guide = iterator.next();
                var pages = guide.getClass().getDeclaredField("pages");
                pages.setAccessible(true);
                identity.append('|').append(System.identityHashCode(guide)).append(':')
                        .append(System.identityHashCode(pages.get(guide)));
                count++;
            }
            return identity.append("|count:").append(count).append(iterator.hasNext() ? "+" : "").toString();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return scope.worldSession() + "|" + scope.resourcesLocale() + "|" + scope.providerRuntime() + "|unavailable";
        }
    }

    @Override
    public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        if (limit < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "candidate limit must be positive");
        try {
            var guidesType = Class.forName("guideme.Guides", false, getClass().getClassLoader());
            var guides = (Iterable<?>) guidesType.getMethod("getAll").invoke(null);
            var candidates = new ArrayList<Candidate>();
            boolean afterSeen = afterPosition == null;
            boolean partial = false;
            String next = null;
            var guideIterator = guides.iterator();
            while (guideIterator.hasNext()) {
                if (!budget.value()) return new CandidatePage(candidates, next, next != null, Evidence.Coverage.PARTIAL,
                        "GuideME traversal reached the source-work bound");
                var guide = guideIterator.next();
                String guideId = String.valueOf(guide.getClass().getMethod("getId").invoke(guide));
                Object loadedPages;
                try {
                    loadedPages = guide.getClass().getMethod("getPages").invoke(guide);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    partial = true;
                    continue;
                }
                if (!(loadedPages instanceof Iterable<?> pages)) { partial = true; continue; }
                var pageIterator = pages.iterator();
                while (pageIterator.hasNext()) {
                    if (!budget.value()) return new CandidatePage(candidates, next, next != null, Evidence.Coverage.PARTIAL,
                            "GuideME traversal reached the source-work bound");
                    var page = pageIterator.next();
                    String pageId = String.valueOf(page.getClass().getMethod("getId").invoke(page));
                    String id = guideId + "#" + pageId;
                    next = id;
                    if (!afterSeen) {
                        if (id.equals(afterPosition)) afterSeen = true;
                        continue;
                    }
                    String language = String.valueOf(page.getClass().getMethod("getLanguage").invoke(page));
                    if (!budget.bytes(Math.min(1024, id.length() * 6 + language.length() * 6 + 192)))
                        return new CandidatePage(candidates, id, true, Evidence.Coverage.PARTIAL,
                                "GuideME metadata reached the output bound");
                    candidates.add(new Candidate(id, pageId, id,
                            List.of("guideme:guide:" + guideId, "guideme:language:" + language),
                            Evidence.Coverage.COMPLETE));
                    if (candidates.size() >= limit) {
                        boolean more = pageIterator.hasNext() || guideIterator.hasNext();
                        return new CandidatePage(candidates, id, more || partial,
                                more || partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                                partial ? "Some GuideME pages are not currently loaded" : null);
                    }
                }
            }
            if (!afterSeen) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "GuideME page changed or is no longer loaded; restart the lookup");
            return new CandidatePage(candidates, next, false,
                    partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    partial ? "Some GuideME pages are not currently loaded" : null);
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
        int startNode = 0;
        int startOffset = 0;
        if (fragmentPosition != null) {
            String[] position = fragmentPosition.split(":", -1);
            try {
                if (position.length != 2) throw new NumberFormatException();
                startNode = Integer.parseInt(position[0]);
                startOffset = Integer.parseInt(position[1]);
                if (startNode < 0 || startOffset < 0) throw new NumberFormatException();
            } catch (NumberFormatException failure) {
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid GuideME fragment position");
            }
        }
        try {
            var guidesType = Class.forName("guideme.Guides", false, getClass().getClassLoader());
            var guide = guidesType.getMethod("getById", ResourceLocation.class).invoke(null,
                    ResourceLocation.parse(id.substring(0, split)));
            if (guide == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "GuideME guide is no longer loaded");
            var page = guide.getClass().getMethod("getParsedPage", ResourceLocation.class).invoke(guide,
                    ResourceLocation.parse(id.substring(split + 1)));
            if (page == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "GuideME page is no longer loaded");
            Object root = page.getClass().getMethod("getAstRoot").invoke(page);
            class Walker {
                int node;
                int nextNode = -1;
                int nextOffset;
                int copied;
                int remaining = maxChars;
                int overlap;
                boolean partial;
                final StringBuilder text = new StringBuilder();

                void read(Object current, int depth) throws ReflectiveOperationException {
                    if (nextNode >= 0) return;
                    if (depth > 64) { partial = true; return; }
                    int currentNode = node++;
                    if (!budget.value()) {
                        if (currentNode <= startNode) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                                "GuideME extraction budget cannot advance to the requested text position");
                        nextNode = currentNode;
                        partial = true;
                        return;
                    }
                    Class<?> type = current.getClass();
                    if (type.getName().equals("guideme.libs.mdast.model.MdAstParent")
                            || type.getSuperclass() != null && isParent(type.getSuperclass())) {
                        Object children = type.getMethod("children").invoke(current);
                        if (!(children instanceof Iterable<?> values)) { partial = true; return; }
                        for (var child : values) {
                            read(child, depth + 1);
                            if (nextNode >= 0) return;
                        }
                    } else if (isLiteral(type)) {
                        String value = String.valueOf(type.getMethod("value").invoke(current));
                        if (currentNode < startNode) return;
                        int offset = currentNode == startNode ? startOffset : 0;
                        if (offset > value.length()) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                                "GuideME page text changed; restart the lookup");
                        if (offset < value.length()) {
                            int copiedEnd = offset + Math.min(Math.min(value.length() - offset, remaining),
                                    Math.max(0, budget.remainingBytes() / 6));
                            int copyStart = Math.max(0, offset - (currentNode == startNode ? GuideDocumentReader.FRAGMENT_OVERLAP_CHARS : 0));
                            overlap = offset - copyStart;
                            if (copiedEnd == offset && offset < value.length()) {
                                nextNode = currentNode;
                                nextOffset = offset;
                                partial = true;
                                return;
                            }
                            if (!budget.bytes((copiedEnd - copyStart) * 6)) {
                                nextNode = currentNode;
                                nextOffset = offset;
                                partial = true;
                                return;
                            }
                            text.append(value, copyStart, copiedEnd);
                            copied = copiedEnd - offset;
                            remaining -= copied;
                            if (copiedEnd < value.length()) {
                                nextNode = currentNode;
                                nextOffset = copiedEnd;
                                partial = true;
                                return;
                            }
                        }
                        if (remaining == 0) { nextNode = node; nextOffset = 0; partial = true; }
                    } else {
                        partial = true;
                        if (!(current instanceof Iterable<?>)) return;
                        for (var child : (Iterable<?>) current) {
                            read(child, depth + 1);
                            if (nextNode >= 0) return;
                        }
                    }
                }

                boolean isParent(Class<?> type) {
                    return type.getName().equals("guideme.libs.mdast.model.MdAstParent");
                }

                boolean isLiteral(Class<?> type) {
                    return type.getName().equals("guideme.libs.mdast.model.MdAstLiteral")
                            || type.getSuperclass() != null && isLiteral(type.getSuperclass());
                }
            }
            var walker = new Walker();
            walker.read(root, 0);
            if (walker.node <= startNode && walker.nextNode < 0)
                throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "GuideME fragment position is beyond the current page");
            String nextPosition = walker.nextNode < 0 ? null : walker.nextNode + ":" + walker.nextOffset;
            return new Fragment(walker.text.toString(), id + "|" + startNode + ":" + startOffset,
                    walker.overlap, nextPosition, walker.partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    walker.partial ? "GuideME AST text was bounded or contains unsupported nodes" : null);
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
