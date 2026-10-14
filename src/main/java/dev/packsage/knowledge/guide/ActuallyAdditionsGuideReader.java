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

    public static GuideDocumentProvider forMinecraft(Supplier<Evidence.Scope> scope, Executor clientExecutor) {
        return new GuideDocumentProvider(new ActuallyAdditionsGuideReader(), scope, clientExecutor);
    }

    @Override public String providerId() { return ID; }

    @Override public String version() { return VERSION; }

    @Override
    public String sourceIdentity(Evidence.Scope scope) {
        var book = book();
        return scope.worldSession() + "|" + scope.resourcesLocale() + "|" + scope.providerRuntime()
                + "|" + (book == null ? "unloaded" : System.identityHashCode(book));
    }

    @Override
    public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        if (limit < 1) throw new Toolbox.Failure(Toolbox.Code.INVALID_ARGUMENTS, "candidate limit must be positive");
        var contents = contents();
        if (contents == null) return new CandidatePage(List.of(), null, false, Evidence.Coverage.UNAVAILABLE,
                "Actually Additions booklet or Patchouli is not loaded");
        try {
            var entries = (Map<?, ?>) contents.getClass().getField("entries").get(contents);
            var candidates = new ArrayList<Candidate>();
            boolean afterSeen = afterPosition == null;
            String next = null;
            var iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                if (!budget.value()) return new CandidatePage(candidates, next, next != null, Evidence.Coverage.PARTIAL,
                        "booklet traversal reached the source-work bound");
                var entry = iterator.next();
                var id = entryId(entry);
                if (!afterSeen) {
                    if (id.equals(afterPosition)) afterSeen = true;
                    next = id;
                    continue;
                }
                if (!visible(entry)) { next = id; continue; }
                if (!budget.bytes(Math.min(512, id.length() * 6 + 128)))
                    return new CandidatePage(candidates, next, false, Evidence.Coverage.PARTIAL,
                            "booklet metadata reached the output bound");
                candidates.add(new Candidate(id, entryTitle(entry), id, List.of("patchouli:book:" + BOOK_ID), Evidence.Coverage.COMPLETE));
                next = id;
                if (candidates.size() == limit)
                    return new CandidatePage(candidates, next, iterator.hasNext(), iterator.hasNext()
                            ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE, null);
            }
            if (!afterSeen) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "Actually Additions booklet entry changed; restart the lookup");
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
        var book = book();
        var contents = contents(book);
        if (contents == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Actually Additions booklet is not loaded");
        try {
            var entryId = ResourceLocation.parse(id);
            var entry = (Map<?, ?>) contents.getClass().getField("entries").get(contents);
            if (!budget.value() || !budget.bytes(Math.min(512, id.length() * 6 + 128)))
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "booklet entry exceeded the read budget");
            var current = entry.get(entryId);
            if (current == null || !visible(current)) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                    "Actually Additions booklet entry is no longer visible");
            int[] position = fragmentPosition == null ? new int[]{0, 0, 0} : parsePosition(fragmentPosition);
            int overlap = Math.min(511, Math.min(position[2], maxChars - 1));
            int newChars = maxChars - overlap;
            var reader = Class.forName("dev.packsage.knowledge.patchouli.PatchouliLiveReader");
            var positionType = Class.forName("dev.packsage.knowledge.patchouli.PatchouliLiveReader$Position");
            var sourcePosition = positionType.getConstructor(int.class, int.class, int.class, int.class)
                    .newInstance(position[0], position[1], position[2], 0);
            Method read = java.util.Arrays.stream(reader.getMethods()).filter(method -> method.getName().equals("entry")
                    && method.getParameterCount() == 6).findFirst().orElseThrow();
            var result = read.invoke(null, book, current, sourcePosition, overlap, newChars, budget);
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
            if (text.length() > maxChars) text.setLength(maxChars);
            return new Fragment(text.toString(), position[0] + "," + position[1] + "," + position[2], overlap, next,
                    next == null ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                    text.isEmpty() ? "booklet page has no bounded text content" : null);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED, "Actually Additions booklet page could not be read");
        }
    }

    @Override
    public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        var contents = contents();
        if (contents == null) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Actually Additions booklet is not loaded");
        Candidate candidate;
        try {
            var current = ((Map<?, ?>) contents.getClass().getField("entries").get(contents)).get(ResourceLocation.parse(id));
            if (current == null || !visible(current)) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                    "Actually Additions booklet entry is no longer available");
            var entryId = entryId(current);
            candidate = new Candidate(entryId, entryTitle(current), entryId,
                    List.of("patchouli:book:" + BOOK_ID), Evidence.Coverage.COMPLETE);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE, "Actually Additions booklet entry is no longer available");
        }
        return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
    }

    private static Object book() {
        try {
            var registry = Class.forName("vazkii.patchouli.common.book.BookRegistry");
            var instance = registry.getField("INSTANCE").get(null);
            return ((Map<?, ?>) registry.getField("books").get(instance)).get(BOOK_ID);
        } catch (ReflectiveOperationException | LinkageError failure) {
            return null;
        }
    }

    private static Object contents() { return contents(book()); }

    private static Object contents(Object book) {
        if (book == null) return null;
        try {
            var contents = book.getClass().getMethod("getContents").invoke(book);
            return contents == null || (boolean) contents.getClass().getMethod("isErrored").invoke(contents) ? null : contents;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static boolean visible(Object entry) throws ReflectiveOperationException {
        return !(boolean) entry.getClass().getMethod("shouldHide").invoke(entry)
                && !(boolean) entry.getClass().getMethod("isLocked").invoke(entry);
    }

    private static String entryId(Object entry) throws ReflectiveOperationException {
        return entry.getClass().getMethod("getId").invoke(entry).toString();
    }

    private static String entryTitle(Object entry) throws ReflectiveOperationException {
        var component = entry.getClass().getMethod("getName").invoke(entry);
        return Objects.toString(component.getClass().getMethod("getString").invoke(component));
    }

    private static String position(Object value) throws ReflectiveOperationException {
        return value.getClass().getMethod("page").invoke(value) + ","
                + value.getClass().getMethod("node").invoke(value) + ","
                + value.getClass().getMethod("textOffset").invoke(value);
    }

    private static int[] parsePosition(String value) {
        var parts = value.split(",", -1);
        if (parts.length != 3) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid booklet fragment position");
        try {
            int page = Integer.parseInt(parts[0]);
            int node = Integer.parseInt(parts[1]);
            int offset = Integer.parseInt(parts[2]);
            if (page < 0 || node < 0 || offset < 0) throw new NumberFormatException();
            return new int[]{page, node, offset};
        } catch (NumberFormatException failure) {
            throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid booklet fragment position");
        }
    }
}
