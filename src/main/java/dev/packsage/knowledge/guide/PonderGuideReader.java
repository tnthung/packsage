package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Bounded access to current Ponder storyboard registrations and their literal captions. */
public final class PonderGuideReader implements GuideDocumentReader {
    private static final String VERSION = "1.0.81+mc1.21.1";
    private static final int MAX_REGISTRATIONS = 4096;
    private static final int MAX_METADATA_CHARS = 2048;
    private static final int MAX_CAPTIONS = 512;
    private static final int MAX_TEXT_CHARS = 16_384;
    private final Source source;

    interface Source {
        Iterable<Entry> entries();
        SceneText compile(Entry entry);
        boolean isClientThread();
        void openComponent(String component);
    }

    record Entry(String identity, String component, String namespace, String schematic, List<String> tags) {
        Entry {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(component);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(schematic);
            tags = List.copyOf(tags);
        }
    }

    record SceneText(String title, String sceneId, String header, List<String> captions) {
        SceneText {
            Objects.requireNonNull(title);
            Objects.requireNonNull(sceneId);
            Objects.requireNonNull(header);
            captions = List.copyOf(captions);
        }
    }

    public PonderGuideReader() { this(new RuntimeSource()); }

    PonderGuideReader(Source source) { this.source = Objects.requireNonNull(source); }

    public static KnowledgeProvider forMinecraft(Supplier<Evidence.Scope> currentScope, Executor clientExecutor) {
        return new GuideDocumentProvider(new PonderGuideReader(), currentScope, clientExecutor, 4);
    }

    @Override public String providerId() { return "ponder"; }

    @Override public String version() { return VERSION; }

    @Override public String sourceIdentity(Evidence.Scope scope) {
        var hash = scope.resourcesLocale().hashCode();
        var count = 0;
        for (var entry : source.entries()) {
            if (count++ == MAX_REGISTRATIONS) return "bounded:" + count + ':' + hash;
            hash = 31 * hash + Objects.hash(entry.identity(), entry.component(), entry.namespace(), entry.schematic(), entry.tags());
        }
        return Integer.toHexString(hash) + ':' + count;
    }

    @Override public CandidatePage candidates(String afterPosition, int limit, Toolbox.DocumentBudget budget) {
        if (limit < 1) throw new IllegalArgumentException("candidate limit must be positive");
        var result = new ArrayList<Candidate>();
        var afterFound = afterPosition == null;
        var inspected = 0;
        var more = false;
        var coverage = Evidence.Coverage.COMPLETE;
        String nextPosition = afterPosition;
        for (var entry : source.entries()) {
            if (inspected++ >= MAX_REGISTRATIONS) return new CandidatePage(result, nextPosition, true,
                    Evidence.Coverage.PARTIAL, "Ponder registrations reached the traversal bound");
            var position = inspected + ":" + entry.identity();
            if (!afterFound) {
                nextPosition = position;
                if (position.equals(afterPosition)) afterFound = true;
                continue;
            }
            if (!budget.value()) return new CandidatePage(result, nextPosition, true, Evidence.Coverage.PARTIAL,
                    "Ponder registrations reached the source budget");
            if (!budget.bytes(Math.min(MAX_METADATA_CHARS, entry.component().length() * 6 + entry.schematic().length() * 6 + 512))) {
                if (result.isEmpty()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Ponder candidate metadata cannot fit the remaining byte budget");
                return new CandidatePage(result, nextPosition, true, Evidence.Coverage.PARTIAL,
                        "Ponder candidate metadata reached its byte budget");
            }
            if (result.size() == Math.min(limit, 4)) { more = true; break; }
            nextPosition = position;
            result.add(new Candidate(entry.identity(), "", position,
                    List.of("component:" + entry.component(), "namespace:" + entry.namespace(),
                            "schematic:" + entry.schematic(), "tags:" + String.join(",", entry.tags()),
                            "navigation:component viewer"), Evidence.Coverage.PARTIAL));
        }
        if (!afterFound) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "Ponder continuation registration is no longer present");
        return new CandidatePage(result, nextPosition, more, coverage, null);
    }

    @Override public Fragment readFragment(String id, String fragmentPosition, int maxChars,
                                           Toolbox.DocumentBudget budget) {
        if (fragmentPosition != null) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "Ponder captions are read as one bounded fragment");
        var entry = find(id);
        if (entry == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                "Ponder storyboard registration is no longer present");
        if (!budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Ponder storyboard reached its extraction budget");
        var scene = source.compile(entry);
        var text = "Title: " + scene.title() + "\nComponent: " + entry.component() + "\nNamespace: "
                + entry.namespace() + "\nSchematic: " + entry.schematic() + "\nTags: " + String.join(", ", entry.tags());
        if (text.length() > maxChars) text = text.substring(0, maxChars);
        if (!budget.bytes(text.length() * 6)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Ponder candidate text cannot fit the remaining byte budget");
        return new Fragment(text, "scene", 0, null, Evidence.Coverage.PARTIAL,
                "Ponder literal title and registration metadata; caption body is available only through explicit evidence reads");
    }

    @Override public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1) throw new IllegalArgumentException("fragment size must be positive");
        var match = source.entries().iterator();
        Entry found = null;
        var inspected = 0;
        while (match.hasNext() && inspected++ < MAX_REGISTRATIONS) {
            var entry = match.next();
            if (entry.identity().equals(id)) { found = entry; break; }
        }
        if (found == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                "Ponder storyboard registration is no longer present");
        if (!budget.document() || !budget.value()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Ponder storyboard reached its extraction budget");
        var scene = source.compile(found);
        var captions = new ArrayList<String>();
        captions.add("Scene ID: " + scene.sceneId());
        captions.add("Title: " + scene.title());
        if (!scene.header().isBlank()) captions.add("Header: " + scene.header());
        for (var index = 0; index < scene.captions().size() && index < MAX_CAPTIONS; index++) {
            captions.add("Caption " + (index + 1) + ": " + scene.captions().get(index));
        }
        var text = new StringBuilder();
        for (var caption : captions) {
            if (!budget.value()) break;
            var remaining = Math.min(Math.min(maxChars - text.length(), MAX_TEXT_CHARS - text.length()), budget.remainingBytes() / 6);
            if (remaining <= 0) break;
            if (!text.isEmpty()) text.append('\n');
            var count = Math.min(caption.length(), Math.max(0, remaining - (text.isEmpty() ? 0 : 1)));
            if (count <= 0) break;
            text.append(caption, 0, count);
            if (!budget.bytes(count * 6)) break;
            if (count < caption.length()) break;
        }
        var fragment = new Fragment(text.toString(), "scene", 0, null, Evidence.Coverage.PARTIAL,
                "Literal caption templates only; shared text, formatting arguments and visual instructions are omitted");
        var candidate = new Candidate(found.identity(), bounded(scene.title(), 256), found.identity(),
                List.of("component:" + found.component(), "namespace:" + found.namespace(),
                        "schematic:" + found.schematic(), "navigation:component viewer"), Evidence.Coverage.PARTIAL);
        return new ResolvedDocument(candidate, fragment);
    }

    List<Evidence.NavigationTarget> navigationTargets(Evidence.Scope scope, Candidate candidate) {
        var found = find(candidate.id());
        if (found == null) return List.of();
        return List.of(new Evidence.NavigationTarget(scope, providerId(), candidate.id(), found.component()));
    }

    Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!scope.equals(target.scope())) return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                "Ponder target is outside the active scope");
        if (!source.isClientThread()) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED,
                "Ponder navigation requires the client thread");
        var found = find(target.sourceHandle());
        if (found == null || !found.component().equals(target.targetId())) return new Evidence.NavigationResult(
                Evidence.NavigationStatus.STALE, "Ponder storyboard registration changed");
        try {
            source.openComponent(found.component());
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "Ponder component viewer opened");
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.toString());
        }
    }

    private Entry find(String identity) {
        var inspected = 0;
        for (var entry : source.entries()) {
            if (inspected++ >= MAX_REGISTRATIONS) return null;
            if (entry.identity().equals(identity)) return entry;
        }
        return null;
    }

    private static String bounded(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }

    private static final class RuntimeSource implements Source {
        private Object registry() throws ReflectiveOperationException {
            var index = Class.forName("net.createmod.ponder.foundation.PonderIndex");
            return index.getMethod("getSceneAccess").invoke(null);
        }

        @Override public Iterable<Entry> entries() {
            try {
                var access = registry();
                var entries = (Collection<?>) access.getClass().getMethod("getRegisteredEntries").invoke(access);
                var iterator = entries.iterator();
                return () -> new java.util.Iterator<>() {
                    int index;
                    @Override public boolean hasNext() { return index < MAX_REGISTRATIONS && iterator.hasNext(); }
                    @Override public Entry next() {
                        var mapping = (Map.Entry<?, ?>) iterator.next();
                        var component = mapping.getKey();
                        var entry = mapping.getValue();
                        try {
                            var identity = Integer.toHexString(System.identityHashCode(entry));
                            return new Entry(identity, component.toString(), (String) invoke(entry, "getNamespace"),
                                    invoke(entry, "getSchematicLocation").toString(),
                                    ((List<?>) invoke(entry, "getTags")).stream().limit(64).map(Object::toString).toList());
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException(failure);
                        } finally { index++; }
                    }
                };
            } catch (ReflectiveOperationException failure) {
                throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Ponder registry is unavailable");
            }
        }

        @Override public SceneText compile(Entry metadata) {
            try {
                Object registered = null;
                var access = registry();
                var current = (Collection<?>) access.getClass().getMethod("getRegisteredEntries").invoke(access);
                for (var mapping : current) {
                    var value = ((Map.Entry<?, ?>) mapping).getValue();
                    if (Integer.toHexString(System.identityHashCode(value)).equals(metadata.identity())) {
                        registered = value;
                        break;
                    }
                }
                if (registered == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                        "Ponder storyboard registration changed");
                var localization = Class.forName("net.createmod.ponder.foundation.registration.PonderLocalization")
                        .getConstructor().newInstance();
                var registryClass = Class.forName("net.createmod.ponder.foundation.registration.PonderSceneRegistry");
                var compile = registryClass.getMethod("compileScene", localization.getClass(),
                        Class.forName("net.createmod.ponder.api.registration.StoryBoardEntry"),
                        Class.forName("net.createmod.ponder.api.level.PonderLevel"));
                var scene = compile.invoke(null, localization, registered, null);
                var sceneId = invoke(scene, "getId");
                var specifics = (Map<?, ?>) localization.getClass().getField("specific").get(localization);
                var raw = (Map<?, ?>) specifics.get(sceneId);
                if (raw == null) raw = Map.of();
                var language = Language.getInstance();
                var sceneLocation = (ResourceLocation) sceneId;
                var titleTemplate = raw.get("header") instanceof String value ? value : "";
                var title = localized(language, sceneLocation, "header", titleTemplate);
                var ordered = new ArrayList<Map.Entry<String, String>>();
                var checked = 0;
                for (var item : raw.entrySet()) {
                    if (checked++ == MAX_CAPTIONS * 4) break;
                    if (item.getKey() instanceof String key && key.matches("text_[0-9]{1,6}")
                            && item.getValue() instanceof String value) ordered.add(Map.entry(key, value));
                }
                ordered.sort(Comparator.comparingInt(item -> Integer.parseInt(item.getKey().substring(5))));
                var captionTemplates = ordered.stream().limit(MAX_CAPTIONS).map(item -> localized(language,
                        sceneLocation, item.getKey(), item.getValue())).toList();
                return new SceneText(title, sceneId.toString(), title, captionTemplates);
            } catch (Toolbox.Failure failure) {
                throw failure;
            } catch (ReflectiveOperationException failure) {
                throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Ponder caption compilation is unavailable");
            }
        }

        @Override public boolean isClientThread() { return Minecraft.getInstance().isSameThread(); }

        @Override public void openComponent(String component) {
            try {
                var ui = Class.forName("net.createmod.ponder.foundation.ui.PonderUI");
                var screen = ui.getMethod("of", ResourceLocation.class).invoke(null, ResourceLocation.parse(component));
                Minecraft.getInstance().setScreen((net.minecraft.client.gui.screens.Screen) screen);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

        private static Object invoke(Object target, String method) throws ReflectiveOperationException {
            return target.getClass().getMethod(method).invoke(target);
        }

        private static String localized(Language language, ResourceLocation id, String key, String raw) {
            var langKey = id.getNamespace() + ".ponder." + id.getPath() + "." + key;
            return language.getOrDefault(langKey, raw);
        }
    }
}
