package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Reads the installed Actually Additions Patchouli booklet without retaining its page bodies. */
public final class ActuallyAdditionsGuideReader implements GuideDocumentReader {
    public static final String ID = "actuallyadditions";
    public static final String VERSION = "1.3.26";
    private static final ResourceLocation BOOK_ID = ResourceLocation.parse("actuallyadditions:booklet");
    private final Supplier<Object> bookSource;

    public ActuallyAdditionsGuideReader() { this(ActuallyAdditionsGuideReader::liveBook); }

    ActuallyAdditionsGuideReader(Supplier<Object> bookSource) { this.bookSource = Objects.requireNonNull(bookSource); }

    public static GuideDocumentProvider forMinecraft(Supplier<Evidence.Scope> scope, Executor clientExecutor) {
        return new GuideDocumentProvider(new ActuallyAdditionsGuideReader(), scope, clientExecutor);
    }

    @Override public String providerId() { return ID; }

    @Override public String version() { return VERSION; }

    @Override
    public String sourceIdentity(Evidence.Scope scope) {
        var book = bookSource.get();
        return scope.worldSession() + "|" + scope.resourcesLocale() + "|" + scope.providerRuntime()
                + "|" + (book == null ? "unloaded" : System.identityHashCode(book));
    }

    @Override
    public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        if (limit < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "candidate limit must be positive");
        var book = bookSource.get();
        if (book == null) return new CandidatePage(List.of(), null, false, Evidence.Coverage.UNAVAILABLE,
                "Actually Additions booklet or Patchouli is not loaded");
        Object contents;
        try {
            contents = book.getClass().getMethod("getContents").invoke(book);
            if (contents == null || (boolean) contents.getClass().getMethod("isErrored").invoke(contents))
                return new CandidatePage(List.of(), null, false, Evidence.Coverage.PARTIAL, "booklet contents are not ready");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new CandidatePage(List.of(), null, false, Evidence.Coverage.PARTIAL, "booklet contents are currently unreadable");
        }
        try {
            var entries = (Map<?, ?>) contents.getClass().getField("entries").get(contents);
            var values = entries.values();
            var indexedEntries = (List<?>) values.getClass().getMethod("asList").invoke(values);
            var candidates = new ArrayList<Candidate>();
            int start = 0;
            String next = null;
            if (afterPosition != null) {
                int separator = afterPosition.indexOf('|');
                try {
                    if (separator < 1) throw new NumberFormatException();
                    int previous = Integer.parseInt(afterPosition.substring(0, separator));
                    if (previous < 0 || previous >= indexedEntries.size()
                            || !afterPosition.substring(separator + 1).equals(indexedEntries.get(previous).getClass()
                            .getMethod("getId").invoke(indexedEntries.get(previous)).toString()))
                        throw new NumberFormatException();
                    if (!budget.value()) return new CandidatePage(candidates, null, false, Evidence.Coverage.PARTIAL,
                            "booklet cursor seek reached the source-work bound");
                    start = previous + 1;
                } catch (NumberFormatException failure) {
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Actually Additions booklet entries changed; restart the lookup");
                }
            }
            for (int index = start; index < indexedEntries.size(); index++) {
                if (!budget.value()) return new CandidatePage(candidates, next, next != null, Evidence.Coverage.PARTIAL,
                        "booklet traversal reached the source-work bound");
                var entry = indexedEntries.get(index);
                var id = entry.getClass().getMethod("getId").invoke(entry).toString();
                next = index + "|" + id;
                if ((boolean) entry.getClass().getMethod("shouldHide").invoke(entry)
                        || (boolean) entry.getClass().getMethod("isLocked").invoke(entry)) continue;
                if (!budget.bytes(id.length() * 6 + 128))
                    return new CandidatePage(candidates, next, false, Evidence.Coverage.PARTIAL,
                            "booklet metadata reached the output bound");
                var helper = Class.forName("dev.packsage.knowledge.patchouli.PatchouliLiveReader");
                var titleMethod = java.util.Arrays.stream(helper.getMethods()).filter(method -> method.getName().equals("title")
                        && method.getParameterCount() == 3).findFirst().orElseThrow();
                var title = (String) titleMethod.invoke(null, book, entry, budget);
                candidates.add(new Candidate(id, title, next, List.of("patchouli:book:" + BOOK_ID), Evidence.Coverage.COMPLETE));
                if (candidates.size() == limit)
                    return new CandidatePage(candidates, next, index + 1 < indexedEntries.size(), index + 1 < indexedEntries.size()
                            ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, null);
            }
            return new CandidatePage(candidates, next, false, Evidence.Coverage.COMPLETE, null);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new CandidatePage(List.of(), null, false, Evidence.Coverage.PARTIAL,
                    "Actually Additions booklet entries are currently unreadable");
        }
    }

    @Override
    public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "fragment limit must be positive");
        var book = bookSource.get();
        if (book == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Actually Additions booklet is not loaded");
        try {
            var contents = book.getClass().getMethod("getContents").invoke(book);
            if (contents == null || (boolean) contents.getClass().getMethod("isErrored").invoke(contents))
                throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Actually Additions booklet is not ready");
            var entryId = ResourceLocation.parse(id);
            var entry = (Map<?, ?>) contents.getClass().getField("entries").get(contents);
            if (!budget.value() || !budget.bytes(id.length() * 6 + 128))
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "booklet entry exceeded the read budget");
            var current = entry.get(entryId);
            if (current == null || (boolean) current.getClass().getMethod("shouldHide").invoke(current)
                    || (boolean) current.getClass().getMethod("isLocked").invoke(current)) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                    "Actually Additions booklet entry is no longer visible");
            int[] position = fragmentPosition == null ? new int[]{0, 0, 0, 0} : parsePosition(fragmentPosition);
            int overlap = Math.min(511, Math.min(position[2], maxChars - 1));
            int newChars = maxChars - overlap;
            var reader = Class.forName("dev.packsage.knowledge.patchouli.PatchouliLiveReader");
            var positionType = Class.forName("dev.packsage.knowledge.patchouli.PatchouliLiveReader$Position");
            var sourcePosition = positionType.getConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(position[0], position[1], position[2], position[3]);
            Method read = java.util.Arrays.stream(reader.getMethods()).filter(method -> method.getName().equals("entry")
                    && method.getParameterCount() == 8).findFirst().orElseThrow();
            var result = read.invoke(null, book, current, sourcePosition, overlap, newChars, false, 1, budget);
            var record = result.getClass().getMethod("record").invoke(result);
            var pages = (List<?>) record.getClass().getMethod("pages").invoke(record);
            var text = new StringBuilder();
            for (var page : pages) {
                if (page == null) continue;
                var pageText = (String) page.getClass().getMethod("text").invoke(page);
                if (pageText != null) text.append(pageText);
            }
            var following = result.getClass().getMethod("next").invoke(result);
            var next = following == null ? null : position(following);
            var recordCoverage = (Evidence.Coverage) record.getClass().getMethod("coverage").invoke(record);
            var detail = (String) record.getClass().getMethod("failure").invoke(record);
            if (text.isEmpty() && recordCoverage == Evidence.Coverage.COMPLETE) recordCoverage = Evidence.Coverage.PARTIAL;
            if (text.isEmpty() && detail == null) detail = "booklet page has no bounded text content";
            return new Fragment(text.toString(), position[0] + "," + position[1] + "," + position[2] + "," + position[3],
                    overlap, next, recordCoverage, detail);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "Actually Additions booklet page could not be read");
        }
    }

    @Override
    public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "fragment limit must be positive");
        var book = bookSource.get();
        if (book == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Actually Additions booklet is not loaded");
        Candidate candidate;
        try {
            var contents = book.getClass().getMethod("getContents").invoke(book);
            if (contents == null || (boolean) contents.getClass().getMethod("isErrored").invoke(contents))
                throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Actually Additions booklet is not ready");
            if (!budget.value() || !budget.bytes(id.length() * 6 + 128))
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "booklet entry exceeded the read budget");
            var current = ((Map<?, ?>) contents.getClass().getField("entries").get(contents)).get(ResourceLocation.parse(id));
            if (current == null || (boolean) current.getClass().getMethod("shouldHide").invoke(current)
                    || (boolean) current.getClass().getMethod("isLocked").invoke(current)) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                    "Actually Additions booklet entry is no longer available");
            var entryId = current.getClass().getMethod("getId").invoke(current).toString();
            var helper = Class.forName("dev.packsage.knowledge.patchouli.PatchouliLiveReader");
            var titleMethod = java.util.Arrays.stream(helper.getMethods()).filter(method -> method.getName().equals("title")
                    && method.getParameterCount() == 3).findFirst().orElseThrow();
            var title = (String) titleMethod.invoke(null, book, current, budget);
            candidate = new Candidate(entryId, title, entryId,
                    List.of("patchouli:book:" + BOOK_ID), Evidence.Coverage.COMPLETE);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "Actually Additions booklet entry is no longer available");
        }
        return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
    }

    private static Object liveBook() {
        try {
            var registry = Class.forName("vazkii.patchouli.common.book.BookRegistry");
            var instance = registry.getField("INSTANCE").get(null);
            return ((Map<?, ?>) registry.getField("books").get(instance)).get(BOOK_ID);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return null;
        }
    }

    private static String position(Object value) throws ReflectiveOperationException {
        return value.getClass().getMethod("page").invoke(value) + ","
                + value.getClass().getMethod("node").invoke(value) + ","
                + value.getClass().getMethod("textOffset").invoke(value) + ","
                + value.getClass().getMethod("mapping").invoke(value);
    }

    private static int[] parsePosition(String value) {
        var parts = value.split(",", -1);
        if (parts.length != 4) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid booklet fragment position");
        try {
            int page = Integer.parseInt(parts[0]);
            int node = Integer.parseInt(parts[1]);
            int offset = Integer.parseInt(parts[2]);
            int mapping = Integer.parseInt(parts[3]);
            if (page < 0 || node < 0 || offset < 0 || mapping < 0) throw new NumberFormatException();
            return new int[]{page, node, offset, mapping};
        } catch (NumberFormatException failure) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid booklet fragment position");
        }
    }
}
