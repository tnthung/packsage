package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Bounded access to current Ponder storyboard registrations and their literal captions. */
public final class PonderGuideReader implements GuideDocumentReader {
    private static final int MAX_REGISTRATIONS = 4096;
    private static final int MAX_METADATA_CHARS = 2048;
    private static final int MAX_CAPTIONS = 512;
    private static final int MAX_TEXT_CHARS = 16_384;
    private final Source source;
    private final String readerVersion;

    interface Source {
        Iterable<Entry> entries();
        SceneText compile(Entry entry);
        boolean isClientThread();
        void openComponent(String component);
    }

    record Entry(String identity, String component, String namespace, String schematic, List<String> tags, String displayText) {
        Entry {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(component);
            Objects.requireNonNull(namespace);
            Objects.requireNonNull(schematic);
            tags = tags.stream().limit(64).toList();
            Objects.requireNonNull(displayText);
            if (displayText.length() > 1024) displayText = displayText.substring(0, 1024);
        }
    }

    record SceneText(String title, String sceneId, String header, List<String> captions) {
        SceneText {
            Objects.requireNonNull(title);
            Objects.requireNonNull(sceneId);
            Objects.requireNonNull(header);
            captions = captions.stream().limit(MAX_CAPTIONS).toList();
        }
    }

    PonderGuideReader(Source source) { this(source, "unknown"); }

    PonderGuideReader(Source source, String version) {
        this.source = Objects.requireNonNull(source);
        this.readerVersion = Objects.requireNonNull(version);
    }

    public static KnowledgeProvider forMinecraft(Supplier<Evidence.Scope> currentScope, Executor clientExecutor) {
        return new GuideDocumentProvider(new PonderGuideReader(new RuntimeSource(), ModList.get().getModContainerById("ponder")
                .map(container -> container.getModInfo().getVersion().toString()).orElse("unknown")), currentScope, clientExecutor, 4);
    }

    @Override public String providerId() { return "ponder"; }

    @Override public String version() { return readerVersion; }

    @Override public String sourceIdentity(Evidence.Scope scope) {
        var hash = scope.resourcesLocale().hashCode();
        var count = 0;
        var entries = source.entries().iterator();
        while (entries.hasNext()) {
            if (count == MAX_REGISTRATIONS) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE,
                    "Ponder registration registry exceeds its identity bound");
            var entry = entries.next();
            hash = 31 * hash + Objects.hash(entry.identity(), entry.component(), entry.namespace(), entry.schematic(), entry.tags(), entry.displayText());
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
            if (afterFound && result.size() == Math.min(limit, 4)) { more = true; break; }
            var entry = entries.next();
            inspected++;
            var position = inspected + ":" + entry.identity();
            if (!afterFound) {
                nextPosition = position;
                if (position.equals(afterPosition)) afterFound = true;
                continue;
            }
            if (!budget.value()) return new CandidatePage(result, nextPosition, true, Evidence.Coverage.PARTIAL,
                    "Ponder registrations reached the source budget");
            if (entry.identity().length() > 1024 || entry.component().length() > 246 || entry.namespace().length() > 246
                    || entry.schematic().length() > 246) {
                coverage = Evidence.Coverage.PARTIAL;
                nextPosition = position;
                continue;
            }
            if (!budget.bytes(Math.min(MAX_METADATA_CHARS, entry.component().length() * 6 + entry.schematic().length() * 6 + 512))) {
                if (result.isEmpty()) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                        "Ponder candidate metadata cannot fit the remaining byte budget");
                return new CandidatePage(result, nextPosition, true, Evidence.Coverage.PARTIAL,
                        "Ponder candidate metadata reached its byte budget");
            }
            if (!budget.callback(providerId(), 4)) return new CandidatePage(result, nextPosition, true,
                    Evidence.Coverage.PARTIAL, "Ponder storyboard callbacks reached the tool-call budget");
            var title = "";
            try {
                var scene = source.compile(entry);
                title = scene.title().length() > 256 ? scene.title().substring(0, 256) : scene.title();
                if (title.length() != scene.title().length()) coverage = Evidence.Coverage.PARTIAL;
            } catch (RuntimeException | LinkageError failure) {
                coverage = Evidence.Coverage.PARTIAL;
            }
            if (!budget.bytes(title.length() * 6)) return new CandidatePage(result, nextPosition, true,
                    Evidence.Coverage.PARTIAL, "Ponder titles reached their byte budget");
            nextPosition = position;
            var provenance = new ArrayList<String>();
            provenance.add("component:" + entry.component());
            provenance.add("namespace:" + entry.namespace());
            provenance.add("schematic:" + entry.schematic());
            provenance.add("navigation:component viewer");
            for (var tag : entry.tags()) {
                if (provenance.size() == 8) { coverage = Evidence.Coverage.PARTIAL; break; }
                if (tag.length() <= 251) provenance.add("tag:" + tag);
                else coverage = Evidence.Coverage.PARTIAL;
            }
            result.add(new Candidate(entry.identity(), title, position,
                    provenance, Evidence.Coverage.PARTIAL));
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
        if (maxChars < 1) throw new IllegalArgumentException("fragment size must be positive");
        var text = new StringBuilder();
        var values = new ArrayList<>(List.of("Component: " + entry.component(), "Namespace: " + entry.namespace(),
                "Schematic: " + entry.schematic(), entry.displayText()));
        values.addAll(entry.tags());
        for (var value : values) {
            if (value.isEmpty()) continue;
            int newline = text.isEmpty() ? 0 : 1;
            var count = Math.min(value.length(), maxChars - text.length() - newline);
            if (count <= 0 || !budget.value()) break;
            if (!budget.bytes((count + newline) * 6)) throw new Toolbox.Failure(Toolbox.Code.EXTRACTION_FAILED,
                    "Ponder candidate text cannot fit the remaining byte budget");
            if (newline != 0) text.append('\n');
            text.append(value, 0, count);
            if (count < value.length()) break;
        }
        return new Fragment(text.toString(), "scene", 0, null, Evidence.Coverage.PARTIAL,
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
        var text = new StringBuilder();
        boolean truncated = false;
        for (int index = -3; index < scene.captions().size(); index++) {
            if (index == -1 && (scene.header().isBlank() || scene.header().equals(scene.title()))) continue;
            if (!budget.value()) break;
            String label = index == -3 ? "Scene ID: " : index == -2 ? "Title: " : index == -1 ? "Header: " : "Caption " + (index + 1) + ": ";
            String value = index == -3 ? scene.sceneId() : index == -2 ? scene.title() : index == -1 ? scene.header() : scene.captions().get(index);
            int newline = text.isEmpty() ? 0 : 1;
            int remaining = Math.min(Math.min(maxChars, MAX_TEXT_CHARS) - text.length(), budget.remainingBytes() / 6) - newline;
            if (remaining <= label.length()) { truncated = true; break; }
            int count = Math.min(value.length(), remaining - label.length());
            if (!budget.bytes((newline + label.length() + count) * 6)) { truncated = true; break; }
            if (newline != 0) text.append('\n');
            text.append(label).append(value, 0, count);
            if (count < value.length()) { truncated = true; break; }
        }
        var fragment = new Fragment(text.toString(), "scene", 0, null, Evidence.Coverage.PARTIAL,
                "Literal caption templates only; shared text, formatting arguments and visual instructions are omitted"
                        + (truncated || budget.exhausted() ? "; caption output reached its read bound" : ""));
        var candidate = new Candidate(found.identity(), scene.title().length() > 256 ? scene.title().substring(0, 256) : scene.title(), found.identity(),
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

    static SceneText literalText(ResourceLocation sceneId, Map<?, ?> fields) {
        var language = Language.getInstance();
        String title = language.getOrDefault(sceneId.getNamespace() + ".ponder." + sceneId.getPath() + ".header",
                fields.get("header") instanceof String value ? value : "");
        if (title.length() > 256) title = title.substring(0, 256);
        var captions = new ArrayList<String>();
        int remaining = MAX_TEXT_CHARS;
        for (int index = 1; index <= MAX_CAPTIONS && remaining > 0; index++) {
            if (!(fields.get("text_" + index) instanceof String raw)) continue;
            String caption = language.getOrDefault(sceneId.getNamespace() + ".ponder." + sceneId.getPath() + ".text_" + index, raw);
            if (caption.length() > remaining) caption = caption.substring(0, remaining);
            captions.add(caption);
            remaining -= caption.length();
        }
        return new SceneText(title, sceneId.toString(), title, captions);
    }

    private static final class RuntimeSource implements Source {
        @Override public Iterable<Entry> entries() {
            try {
                var index = Class.forName("net.createmod.ponder.foundation.PonderIndex");
                var access = index.getMethod("getSceneAccess").invoke(null);
                var entries = (Collection<?>) access.getClass().getMethod("getRegisteredEntries").invoke(access);
                var localization = index.getMethod("getLangAccess").invoke(null);
                var iterator = entries.iterator();
                return () -> new java.util.Iterator<>() {
                    int index;
                    @Override public boolean hasNext() { return iterator.hasNext(); }
                    @Override public Entry next() {
                        var mapping = (Map.Entry<?, ?>) iterator.next();
                        var component = mapping.getKey();
                        var entry = mapping.getValue();
                        try {
                            var tags = ((List<?>) entry.getClass().getMethod("getTags").invoke(entry)).stream().limit(8)
                                    .map(Object::toString).toList();
                            var displayText = new StringBuilder();
                            var item = BuiltInRegistries.ITEM.getOptional((ResourceLocation) component).orElse(null);
                            if (item != null) {
                                String name = Language.getInstance().getOrDefault(item.getDescriptionId());
                                displayText.append(name, 0, Math.min(256, name.length()));
                            }
                            for (var tag : tags) {
                                for (String method : List.of("getTagName", "getTagDescription")) {
                                    if (displayText.length() >= 1023) break;
                                    String value = (String) localization.getClass().getMethod(method, ResourceLocation.class)
                                            .invoke(localization, ResourceLocation.parse(tag));
                                    displayText.append('\n').append(value, 0, Math.min(value.length(), 1023 - displayText.length()));
                                }
                            }
                            return new Entry((index + 1) + ":" + Integer.toHexString(System.identityHashCode(entry)),
                                    component.toString(), (String) entry.getClass().getMethod("getNamespace").invoke(entry),
                                    entry.getClass().getMethod("getSchematicLocation").invoke(entry).toString(),
                                    tags, displayText.toString());
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
                var scene = Class.forName("net.createmod.ponder.foundation.registration.PonderSceneRegistry")
                        .getMethod("compileScene", localization.getClass(),
                        Class.forName("net.createmod.ponder.api.registration.StoryBoardEntry"),
                        Class.forName("net.createmod.ponder.api.level.PonderLevel")).invoke(null, localization, registered, null);
                var sceneId = (ResourceLocation) scene.getClass().getMethod("getId").invoke(scene);
                var raw = ((Map<?, ?>) localization.getClass().getField("specific").get(localization)).get(sceneId);
                return literalText(sceneId, raw instanceof Map<?, ?> fields ? fields : Map.of());
            } catch (Toolbox.Failure failure) {
                throw failure;
            } catch (ReflectiveOperationException failure) {
                throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "Ponder caption compilation is unavailable");
            }
        }

        @Override public boolean isClientThread() { return Minecraft.getInstance().isSameThread(); }

        @Override public void openComponent(String component) {
            try {
                Minecraft.getInstance().setScreen((net.minecraft.client.gui.screens.Screen)
                        Class.forName("net.createmod.ponder.foundation.ui.PonderUI").getMethod("of", ResourceLocation.class)
                                .invoke(null, ResourceLocation.parse(component)));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }

    }
}
