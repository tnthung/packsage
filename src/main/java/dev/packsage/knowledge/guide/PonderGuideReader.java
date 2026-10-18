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
            component = component.length() > 256 ? component.substring(0, 256) : component;
            namespace = namespace.length() > 128 ? namespace.substring(0, 128) : namespace;
            schematic = schematic.length() > 256 ? schematic.substring(0, 256) : schematic;
            tags = tags.stream().limit(8).map(tag -> tag.length() > 128 ? tag.substring(0, 128) : tag).toList();
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
        var entries = source.entries().iterator();
        while (entries.hasNext()) {
            if (count == MAX_REGISTRATIONS) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                    "Ponder registration registry exceeds its identity bound");
            var entry = entries.next();
            hash = 31 * hash + Objects.hash(entry.identity(), entry.component(), entry.namespace(), entry.schematic(), entry.tags());
            count++;
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
        var entries = source.entries().iterator();
        while (entries.hasNext()) {
            if (inspected == MAX_REGISTRATIONS) return new CandidatePage(result, nextPosition, true,
                    Evidence.Coverage.PARTIAL, "Ponder registrations reached the traversal bound");
            if (!budget.value()) return new CandidatePage(result, nextPosition, true, Evidence.Coverage.PARTIAL,
                    "Ponder registrations reached the source budget");
            var entry = entries.next();
            inspected++;
            var position = inspected + ":" + entry.identity();
            if (!afterFound) {
                nextPosition = position;
                if (position.equals(afterPosition)) afterFound = true;
                continue;
            }
            if (!budget.bytes(Math.min(MAX_METADATA_CHARS, entry.component().length() * 6 + entry.schematic().length() * 6 + 512))) {
                if (result.isEmpty()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Ponder candidate metadata cannot fit the remaining byte budget");
                return new CandidatePage(result, nextPosition, true, Evidence.Coverage.PARTIAL,
                        "Ponder candidate metadata reached its byte budget");
            }
            if (result.size() == Math.min(limit, 4)) { more = true; break; }
            var scene = source.compile(entry);
            var title = scene.title().length() > 256 ? scene.title().substring(0, 256) : scene.title();
            if (title.length() != scene.title().length()) coverage = Evidence.Coverage.PARTIAL;
            nextPosition = position;
            result.add(new Candidate(entry.identity(), title, position,
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
        Entry entry = null;
        var entries = source.entries().iterator();
        var inspected = 0;
        while (entries.hasNext() && inspected < MAX_REGISTRATIONS) {
            var current = entries.next();
            inspected++;
            if (current.identity().equals(id)) { entry = current; break; }
        }
        if (entry == null) throw new Toolbox.Failure(Toolbox.Code.INVALID_REFERENCE,
                "Ponder storyboard registration is no longer present");
        var text = "Component: " + entry.component() + "\nNamespace: "
                + entry.namespace() + "\nSchematic: " + entry.schematic() + "\nTags: " + String.join(", ", entry.tags());
        if (text.length() > maxChars) text = text.substring(0, maxChars);
        if (!budget.bytes(text.length() * 6)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                "Ponder candidate text cannot fit the remaining byte budget");
        return new Fragment(text, "scene", 0, null, Evidence.Coverage.PARTIAL,
                "Ponder registration metadata; the current localized title is included in candidate metadata");
    }

    @Override public ResolvedDocument resolve(String id, int maxChars, Toolbox.DocumentBudget budget) {
        if (maxChars < 1) throw new IllegalArgumentException("fragment size must be positive");
        var match = source.entries().iterator();
        Entry found = null;
        var inspected = 0;
        while (match.hasNext() && inspected < MAX_REGISTRATIONS) {
            var entry = match.next();
            inspected++;
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
        var candidateTitle = scene.title().length() > 256 ? scene.title().substring(0, 256) : scene.title();
        var candidate = new Candidate(found.identity(), candidateTitle, found.identity(),
                List.of("component:" + found.component(), "namespace:" + found.namespace(),
                        "schematic:" + found.schematic(), "navigation:component viewer"), Evidence.Coverage.PARTIAL);
        return new ResolvedDocument(candidate, fragment);
    }

    @Override public List<Evidence.NavigationTarget> navigationTargets(Evidence.Scope scope, Candidate candidate) {
        Entry found = null;
        var entries = source.entries().iterator();
        var inspected = 0;
        while (entries.hasNext() && inspected++ < MAX_REGISTRATIONS) {
            var entry = entries.next();
            if (entry.identity().equals(candidate.id())) { found = entry; break; }
        }
        if (found == null) return List.of();
        return List.of(new Evidence.NavigationTarget(scope, providerId(), candidate.id(), found.component()));
    }

    @Override public Evidence.NavigationResult open(Evidence.NavigationTarget target, Evidence.Scope scope) {
        if (!scope.equals(target.scope())) return new Evidence.NavigationResult(Evidence.NavigationStatus.STALE,
                "Ponder target is outside the active scope");
        if (!source.isClientThread()) return new Evidence.NavigationResult(Evidence.NavigationStatus.UNSUPPORTED,
                "Ponder navigation requires the client thread");
        Entry found = null;
        var entries = source.entries().iterator();
        var inspected = 0;
        while (entries.hasNext() && inspected++ < MAX_REGISTRATIONS) {
            var entry = entries.next();
            if (entry.identity().equals(target.sourceHandle())) { found = entry; break; }
        }
        if (found == null || !found.component().equals(target.targetId())) return new Evidence.NavigationResult(
                Evidence.NavigationStatus.STALE, "Ponder storyboard registration changed");
        try {
            source.openComponent(found.component());
            return new Evidence.NavigationResult(Evidence.NavigationStatus.OPENED, "Ponder component viewer opened");
        } catch (RuntimeException failure) {
            return new Evidence.NavigationResult(Evidence.NavigationStatus.ERROR, failure.toString());
        }
    }

    private static final class RuntimeSource implements Source {
        @Override public Iterable<Entry> entries() {
            try {
                var index = Class.forName("net.createmod.ponder.foundation.PonderIndex");
                var access = index.getMethod("getSceneAccess").invoke(null);
                var entries = (Collection<?>) access.getClass().getMethod("getRegisteredEntries").invoke(access);
                var iterator = entries.iterator();
                return () -> new java.util.Iterator<>() {
                    int index;
                    @Override public boolean hasNext() { return iterator.hasNext(); }
                    @Override public Entry next() {
                        var mapping = (Map.Entry<?, ?>) iterator.next();
                        var component = mapping.getKey();
                        var entry = mapping.getValue();
                        try {
                            var identity = (index + 1) + ":" + Integer.toHexString(System.identityHashCode(entry));
                            return new Entry(identity, component.toString(), (String) entry.getClass().getMethod("getNamespace").invoke(entry),
                                    entry.getClass().getMethod("getSchematicLocation").invoke(entry).toString(),
                                    ((List<?>) entry.getClass().getMethod("getTags").invoke(entry)).stream().limit(8)
                                            .map(Object::toString).toList());
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
                var index = Class.forName("net.createmod.ponder.foundation.PonderIndex");
                var access = index.getMethod("getSceneAccess").invoke(null);
                var current = (Collection<?>) access.getClass().getMethod("getRegisteredEntries").invoke(access);
                var currentIndex = 0;
                for (var mapping : current) {
                    if (currentIndex++ == MAX_REGISTRATIONS) break;
                    var value = ((Map.Entry<?, ?>) mapping).getValue();
                    if ((currentIndex + ":" + Integer.toHexString(System.identityHashCode(value))).equals(metadata.identity())) {
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
                var sceneId = scene.getClass().getMethod("getId").invoke(scene);
                var specifics = (Map<?, ?>) localization.getClass().getField("specific").get(localization);
                var raw = (Map<?, ?>) specifics.get(sceneId);
                if (raw == null) raw = Map.of();
                var language = Language.getInstance();
                var sceneLocation = (ResourceLocation) sceneId;
                var titleTemplate = raw.get("header") instanceof String value ? value : "";
                var titleKey = sceneLocation.getNamespace() + ".ponder." + sceneLocation.getPath() + ".header";
                var title = language.getOrDefault(titleKey, titleTemplate);
                var ordered = new ArrayList<Map.Entry<String, String>>();
                var checked = 0;
                for (var item : raw.entrySet()) {
                    if (checked++ == MAX_CAPTIONS * 4) break;
                    if (item.getKey() instanceof String key && key.matches("text_[0-9]{1,6}")
                            && item.getValue() instanceof String value) ordered.add(Map.entry(key, value));
                }
                ordered.sort(Comparator.comparingInt(item -> Integer.parseInt(item.getKey().substring(5))));
                var captionTemplates = ordered.stream().limit(MAX_CAPTIONS).map(item -> language.getOrDefault(
                        sceneLocation.getNamespace() + ".ponder." + sceneLocation.getPath() + "." + item.getKey(),
                        item.getValue())).toList();
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

    }
}
