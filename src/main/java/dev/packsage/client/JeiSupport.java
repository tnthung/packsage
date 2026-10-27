package dev.packsage.client;

import dev.packsage.PackSageLog;
import dev.packsage.client.recipe.ItemViewer;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeProvider;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

public final class JeiSupport {
    private static final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();
    private final Function<String, Optional<String>> modVersion;

    public JeiSupport() {
        this(id -> {
            var mods = ModList.get();
            return mods == null ? Optional.empty() : mods.getModContainerById(id)
                    .map(container -> container.getModInfo().getVersion().toString());
        });
    }

    JeiSupport(Function<String, Optional<String>> modVersion) {
        this.modVersion = modVersion;
    }

    public static boolean supported(String version) {
        return versionMatches("[19.27,20)", version);
    }

    public static boolean bookmarksSupported(String version) {
        return versionMatches("[19.53,20)", version);
    }

    private static boolean versionMatches(String range, String version) {
        if (version == null || !version.matches("[0-9]+(?:[.-][0-9]+)*")) return false;
        try {
            return VersionRange.createFromVersionSpec(range).containsVersion(new DefaultArtifactVersion(version));
        } catch (org.apache.maven.artifact.versioning.InvalidVersionSpecificationException failure) {
            return false;
        }
    }

    public Optional<String> installedVersion() {
        return modVersion.apply("jei");
    }

    public List<KnowledgeProvider> providers(Minecraft client, Executor clientExecutor, Supplier<Evidence.Scope> scope) {
        var version = installedVersion();
        if (version.isEmpty() || !supported(version.get())) return List.of();
        var providers = new ArrayList<KnowledgeProvider>();
        for (String name : List.of("dev.packsage.jei.JeiProvider", "dev.packsage.jei.bookmark.JeiBookmarkProvider")) {
            if (name.endsWith("JeiBookmarkProvider") && !bookmarksSupported(version.get())) continue;
            try {
                providers.add((KnowledgeProvider) Class.forName(name).getConstructor(Minecraft.class, Executor.class, Supplier.class)
                        .newInstance(client, clientExecutor, scope));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                if (reportedFailures.add(name)) PackSageLog.LOGGER.warn("Could not load JEI integration {}", name, failure);
            }
        }
        return List.copyOf(providers);
    }

    public Optional<ItemViewer> viewer() {
        if (installedVersion().filter(JeiSupport::supported).isEmpty()) return Optional.empty();
        String name = "dev.packsage.jei.JeiItemViewer";
        try {
            return Optional.of((ItemViewer) Class.forName(name).getConstructor().newInstance());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (reportedFailures.add(name)) PackSageLog.LOGGER.warn("Could not load JEI integration {}", name, failure);
            return Optional.empty();
        }
    }

    public long runtimeGeneration() {
        if (installedVersion().filter(JeiSupport::supported).isEmpty()) return 0;
        String name = "dev.packsage.jei.JeiRuntimeHolder";
        try {
            return (Long) Class.forName(name).getMethod("generation").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (reportedFailures.add(name)) PackSageLog.LOGGER.warn("Could not load JEI integration {}", name, failure);
            return 0;
        }
    }
}
