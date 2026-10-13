package dev.packsage;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModMetadataTest {
    @Test
    void packagedMetadataAcceptsEvolutionLoaderVersionsAndRetainsMinecraftRange() throws Exception {
        var metadata = new String(getClass().getResourceAsStream("/META-INF/neoforge.mods.toml").readAllBytes(), StandardCharsets.UTF_8);
        var dependencies = Pattern.compile("(?s)\\[\\[dependencies\\.packsage]](.*?)(?=\\[\\[|\\z)")
                .matcher(metadata);
        VersionRange neoforgeRange = null;
        VersionRange minecraftRange = null;
        while (dependencies.find()) {
            var block = dependencies.group(1);
            var modId = Pattern.compile("modId=\"([^\"]+)\"").matcher(block);
            var versionRange = Pattern.compile("versionRange=\"([^\"]+)\"").matcher(block);
            if (modId.find() && versionRange.find()) {
                var range = VersionRange.createFromVersionSpec(versionRange.group(1));
                if (modId.group(1).equals("neoforge")) {
                    neoforgeRange = range;
                } else if (modId.group(1).equals("minecraft")) {
                    minecraftRange = range;
                }
            }
        }

        assertTrue(neoforgeRange.containsVersion(new DefaultArtifactVersion("21.1.202")));
        assertTrue(neoforgeRange.containsVersion(new DefaultArtifactVersion("21.1.218")));
        assertTrue(neoforgeRange.containsVersion(new DefaultArtifactVersion("21.1.250")));
        assertTrue(neoforgeRange.containsVersion(new DefaultArtifactVersion("21.1.999")));
        assertFalse(neoforgeRange.containsVersion(new DefaultArtifactVersion("21.1.201")));
        assertFalse(neoforgeRange.containsVersion(new DefaultArtifactVersion("21.2")));
        assertFalse(neoforgeRange.containsVersion(new DefaultArtifactVersion("22")));

        assertTrue(minecraftRange.containsVersion(new DefaultArtifactVersion("1.21.1")));
        assertFalse(minecraftRange.containsVersion(new DefaultArtifactVersion("1.21.2")));
    }
}
