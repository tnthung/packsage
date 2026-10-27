package dev.packsage.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JeiSupportTest {
    @Test
    void checksSupportedVersions() {
        assertTrue(JeiSupport.supported("19.27.0.340"));
        assertTrue(JeiSupport.supported("19.64.0.454"));
        assertFalse(JeiSupport.supported("19.26.9"));
        assertFalse(JeiSupport.supported("20.0.0"));
        assertFalse(JeiSupport.bookmarksSupported("19.52.9"));
        assertTrue(JeiSupport.bookmarksSupported("19.53.0"));
    }

    @Test
    void absentModHasNoProviders() {
        var support = new JeiSupport(id -> Optional.empty());
        assertTrue(support.installedVersion().isEmpty());
        assertTrue(support.providers(null, Runnable::run, () -> null).isEmpty());
        assertTrue(support.viewer().isEmpty());
        assertEquals(0, support.runtimeGeneration());
    }

    @Test
    void unsupportedModHasNoViewer() {
        assertTrue(new JeiSupport(id -> Optional.of("19.26.9")).viewer().isEmpty());
        assertTrue(new JeiSupport(id -> Optional.of("20.0.0")).viewer().isEmpty());
    }

    @Test
    void bookmarkProviderLoadsOnlyOnBookmarkCapableJei() {
        var current = new JeiSupport(id -> Optional.of("19.64.0.454"));
        assertEquals(java.util.List.of("jei", "jei_bookmarks"), current.providers(null, Runnable::run, () -> null).stream()
                .map(provider -> provider.id()).toList());
        var older = new JeiSupport(id -> Optional.of("19.27.0.340"));
        assertEquals(java.util.List.of("jei"), older.providers(null, Runnable::run, () -> null).stream()
                .map(provider -> provider.id()).toList());
    }
}
