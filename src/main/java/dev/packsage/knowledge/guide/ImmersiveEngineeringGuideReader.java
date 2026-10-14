package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Fresh, bounded reads of the loaded Immersive Engineering manual and active localized resources. */
public final class ImmersiveEngineeringGuideReader implements GuideDocumentReader {
    public static final String ID = "immersiveengineering";
    public static final String VERSION = "12.4.2-194";
    private static final String MANUAL_HELPER = "blusunrize.immersiveengineering.api.ManualHelper";
    private static final int MAX_ENTRY_ID_LENGTH = 256;
    private static final int MAX_TITLE_LENGTH = 512;
    private static final int MAX_SEEK_CHARS = 16 * 1024;

    public static GuideDocumentProvider forMinecraft(Supplier<Evidence.Scope> scopeSource, Executor clientExecutor) {
        return new GuideDocumentProvider(new ImmersiveEngineeringGuideReader(), scopeSource, clientExecutor);
    }

    @Override public String providerId() { return ID; }
    @Override public String version() { return VERSION; }

    @Override
    public String sourceIdentity(Evidence.Scope scope) {
        try {
            var manual = manual();
            var entries = entries(manual);
            return VERSION + ":" + scope.resourcesLocale() + ":" + System.identityHashCode(manual) + ":"
                    + System.identityHashCode(entries) + ":" + entries.size();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return VERSION + ":unavailable:" + scope.resourcesLocale();
        }
    }

    @Override
    public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        try {
            var manual = manual();
            var entries = entries(manual);
            if (entries.isEmpty()) return new CandidatePage(List.of(), null, false, Evidence.Coverage.PARTIAL,
                    "Immersive Engineering manual entries have not been loaded");
            var candidates = new ArrayList<Candidate>();
            String lastPosition = afterPosition;
            boolean passedCursor = afterPosition == null;
            boolean partial = false;
            var iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                if (!budget.value()) {
                    partial = true;
                    break;
                }
                var sourceEntry = iterator.next();
                if (!(sourceEntry.getKey() instanceof ResourceLocation location)) {
                    partial = true;
                    continue;
                }
                Object entry = sourceEntry.getValue();
                String id = location.toString();
                if (id.length() > MAX_ENTRY_ID_LENGTH) {
                    partial = true;
                    lastPosition = id;
                    continue;
                }
                lastPosition = id;
                if (!passedCursor) {
                    lastPosition = id;
                    if (id.equals(afterPosition)) passedCursor = true;
                    continue;
                }
                if (!visible(manual, entry)) {
                    lastPosition = id;
                    continue;
                }
                if (!budget.bytes(id.length() * 6 + 1024)) {
                    partial = true;
                    break;
                }
                String title = string(entry, "getTitle");
                if (title == null || title.isBlank()) title = id;
                if (title.length() > MAX_TITLE_LENGTH) {
                    title = title.substring(0, MAX_TITLE_LENGTH);
                    partial = true;
                }
                candidates.add(new Candidate(id, title, id, List.of("Immersive Engineering manual entry " + id),
                        partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE));
                lastPosition = id;
                if (candidates.size() >= Math.max(1, limit)) break;
            }
            if (!passedCursor) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Immersive Engineering manual changed; restart the lookup");
            boolean more = iterator.hasNext();
            if (more && lastPosition == null) more = false;
            return new CandidatePage(candidates, more ? lastPosition : null, more,
                    partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    partial ? "manual traversal or candidate metadata exceeded the read budget" : null);
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new CandidatePage(List.of(), null, false, Evidence.Coverage.PARTIAL,
                    "Immersive Engineering manual entries are not currently available");
        }
    }

    @Override
    public Fragment readFragment(String id, String fragmentPosition, int maxChars, Toolbox.DocumentBudget budget) {
        try {
            if (!budget.value() || !budget.bytes(id.length() * 6 + 512)) throw new Toolbox.Failure(
                    Toolbox.Code.EXTRACTION_FAILED, "Immersive Engineering entry exceeds the remaining read budget");
            var manual = manual();
            var entry = findEntry(manual, id);
            if (entry == null || !visible(manual, entry)) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                    "Immersive Engineering manual entry is no longer visible");
            int offset = 0;
            if (fragmentPosition != null) {
                try {
                    offset = Integer.parseInt(fragmentPosition);
                } catch (NumberFormatException failure) {
                    throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR, "invalid Immersive Engineering text position");
                }
            }
            if (offset < 0 || offset > MAX_SEEK_CHARS) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Immersive Engineering text changed; restart the lookup");
            var resourceManager = Minecraft.getInstance().getResourceManager();
            String locale = Minecraft.getInstance().getLanguageManager().getSelected();
            var resource = resourceManager.getResource(ResourceLocation.fromNamespaceAndPath("immersiveengineering",
                    "manual/" + locale + "/" + path(entry) + ".txt"));
            if (resource.isEmpty()) resource = resourceManager.getResource(ResourceLocation.fromNamespaceAndPath(
                    "immersiveengineering", "manual/en_us/" + path(entry) + ".txt"));
            if (resource.isEmpty()) return new Fragment("", id + "@" + offset, 0, null,
                    Evidence.Coverage.PARTIAL, "localized manual text is missing");
            try (var stream = resource.get().open(); Reader text = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return readText(text, id, offset, maxChars, FRAGMENT_OVERLAP_CHARS, budget);
            }
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError failure) {
            return new Fragment("", id + "@0", 0, null, Evidence.Coverage.PARTIAL,
                    "Immersive Engineering manual text could not be read");
        }
    }

    @Override
    public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        try {
            var manual = manual();
            var entry = findEntry(manual, id);
            if (entry == null || !visible(manual, entry)) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                    "Immersive Engineering manual entry is no longer visible");
            if (!budget.document() || !budget.value() || !budget.bytes(id.length() * 6 + 1024)) {
                throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Immersive Engineering entry exceeds the remaining read budget");
            }
            String title = string(entry, "getTitle");
            if (title == null || title.isBlank()) title = id;
            boolean truncated = title.length() > MAX_TITLE_LENGTH;
            if (truncated) title = title.substring(0, MAX_TITLE_LENGTH);
            var candidate = new Candidate(id, title, id, List.of("Immersive Engineering manual entry " + id),
                    truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE);
            return new ResolvedDocument(candidate, readFragment(id, null, maxChars, budget));
        } catch (Toolbox.Failure failure) {
            throw failure;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Immersive Engineering manual entry is unavailable");
        }
    }

    static Fragment readText(Reader source, String id, int offset, int maxChars, int overlapLimit,
                             Toolbox.DocumentBudget budget) {
        if (offset < 0 || offset > MAX_SEEK_CHARS || maxChars < 1) throw new Toolbox.Failure(
                Toolbox.Code.INVALID_CURSOR, "invalid Immersive Engineering text position");
        try {
            int overlap = Math.min(Math.max(0, overlapLimit), offset);
            int seek = offset - overlap;
            char[] buffer = new char[Math.min(1024, Math.max(1, seek))];
            int skipped = 0;
            while (skipped < seek) {
                if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Immersive Engineering text seek exceeds the read budget");
                int count = source.read(buffer, 0, Math.min(buffer.length, seek - skipped));
                if (count < 0) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                        "Immersive Engineering text changed; restart the lookup");
                if (count == 0) continue;
                skipped += count;
            }
            int maxNewChars = maxChars - overlap;
            if (maxNewChars < 1) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Immersive Engineering text overlap exceeds the fragment budget");
            int boundedNewChars = Math.min(maxNewChars, Math.max(0, budget.remainingBytes() / 6 - overlap));
            if (boundedNewChars == 0 && budget.remainingBytes() < (overlap + 1) * 6) throw new Toolbox.Failure(
                    Toolbox.Code.EXTRACTION_FAILED, "Immersive Engineering text exceeds the remaining read budget");
            if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Immersive Engineering text exceeds the remaining read budget");
            int requested = overlap + boundedNewChars;
            if (!budget.bytes(requested * 6)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Immersive Engineering text exceeds the remaining read budget");
            char[] part = new char[requested];
            int count = 0;
            while (count < requested) {
                int read = source.read(part, count, requested - count);
                if (read < 0) break;
                if (read == 0) continue;
                count += read;
            }
            if (count < overlap) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                    "Immersive Engineering text changed; restart the lookup");
            int newChars = Math.max(0, count - overlap);
            boolean truncated = count == requested && source.read() >= 0;
            String text = new String(part, 0, count);
            if (newChars > 0 && Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                text = text.substring(0, text.length() - 1);
                newChars--;
            }
            int nextOffset = offset + newChars;
            String next = truncated ? Integer.toString(nextOffset) : null;
            return new Fragment(text, id + "@" + offset, overlap, next,
                    truncated ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    truncated ? "bounded localized manual text fragment" : null);
        } catch (IOException failure) {
            throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Immersive Engineering manual text could not be read");
        }
    }

    private static Object manual() throws ReflectiveOperationException {
        var helper = Class.forName(MANUAL_HELPER, false, ImmersiveEngineeringGuideReader.class.getClassLoader());
        return helper.getMethod("getManual").invoke(null);
    }

    private static Map<?, ?> entries(Object manual) throws ReflectiveOperationException {
        return (Map<?, ?>) manual.getClass().getField("contentsByName").get(manual);
    }

    private static Object findEntry(Object manual, String id) throws ReflectiveOperationException {
        var location = ResourceLocation.parse(id);
        return entries(manual).get(location);
    }

    private static boolean visible(Object manual, Object entry) throws ReflectiveOperationException {
        var node = entry.getClass().getMethod("getTreeNode").invoke(entry);
        return node != null && (boolean) manual.getClass().getMethod("showNodeInList", node.getClass().getSuperclass())
                .invoke(manual, node);
    }

    private static String string(Object target, String method) throws ReflectiveOperationException {
        Object value = target.getClass().getMethod(method).invoke(target);
        return value instanceof String text ? text : null;
    }

    private static String path(Object entry) throws ReflectiveOperationException {
        Object location = entry.getClass().getMethod("getLocation").invoke(entry);
        return (String) location.getClass().getMethod("getPath").invoke(location);
    }
}
