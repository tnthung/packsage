package dev.packsage.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Properties;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackSageSettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsAreBoundedAndPauseSearchAreEnabled() {
        PackSageSettings settings = PackSageSettings.defaults();

        assertTrue(settings.openPaused());
        assertTrue(settings.automaticWebSearch());
        assertTrue(settings.hideInactiveAdapters());
        assertEquals(10, settings.historyTurns());
        assertEquals(12_000, settings.historicalTokens());
        assertEquals(PackSageSettings.SendShortcut.ENTER, settings.sendShortcut());
        assertEquals(2, settings.sessionsBeforeCollapse());
    }

    @Test
    void validValuesPersistAndInvalidValuesKeepThePreviousBoundedValue() {
        Path path = temporaryDirectory.resolve("settings.properties");
        PackSageSettings settings = PackSageSettings.defaults();
        settings.setOpenPaused(false);
        settings.setAutomaticWebSearch(false);
        settings.setHideInactiveAdapters(false);
        settings.setHistoryTurns(25);
        settings.setHistoricalTokens(20_000);
        settings.setSendShortcut(PackSageSettings.SendShortcut.CTRL_ENTER);
        settings.setSessionsBeforeCollapse(5);
        settings.save(path).join();

        PackSageSettings loaded = PackSageSettings.load(path);
        assertFalse(loaded.openPaused());
        assertFalse(loaded.automaticWebSearch());
        assertFalse(loaded.hideInactiveAdapters());
        assertEquals(25, loaded.historyTurns());
        assertEquals(20_000, loaded.historicalTokens());
        assertEquals(PackSageSettings.SendShortcut.CTRL_ENTER, loaded.sendShortcut());
        assertEquals(5, loaded.sessionsBeforeCollapse());

        assertFalse(loaded.setHistoryTurns(0));
        assertFalse(loaded.setHistoricalTokens(Integer.MAX_VALUE));
        assertEquals(25, loaded.historyTurns());
        assertEquals(20_000, loaded.historicalTokens());
        assertFalse(loaded.setSessionsBeforeCollapse(0));
        assertFalse(loaded.setSessionsBeforeCollapse(PackSageSettings.MAX_SESSIONS_BEFORE_COLLAPSE + 1));
        assertEquals(5, loaded.sessionsBeforeCollapse());
    }

    @Test
    void savingReplacesTheFileWithoutChangingThePreviousSnapshot() throws Exception {
        Path path = temporaryDirectory.resolve("settings.properties");
        var settings = PackSageSettings.defaults();
        settings.setDeveloperPrompt("old prompt");
        settings.save(path).join();
        byte[] before = Files.readAllBytes(path);
        Path snapshot = Files.createLink(temporaryDirectory.resolve("snapshot.properties"), path);
        settings.setDeveloperPrompt("new prompt");
        settings.save(path).join();
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, Files.readAllBytes(snapshot));
        assertEquals("new prompt", PackSageSettings.load(path).developerPrompt());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(java.util.Set.of(path, snapshot), files.collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void oneMalformedHistoryLimitDoesNotDiscardTheOtherValidLimit() throws Exception {
        Path path = temporaryDirectory.resolve("partial-settings.properties");
        Files.writeString(path, "historyTurns=31\nhistoricalTokens=not-a-number\n");

        PackSageSettings loaded = PackSageSettings.load(path);

        assertEquals(31, loaded.historyTurns());
        assertEquals(PackSageSettings.DEFAULT_HISTORICAL_TOKENS, loaded.historicalTokens());
    }

    @Test
    void modelAndReasoningSettingsSurvivePropertiesRoundTrip() throws Exception {
        Path input = temporaryDirectory.resolve("selected.properties");
        Files.writeString(input, "modelId=gpt-5-mini\nreasoningEffort=high\n");
        Path output = temporaryDirectory.resolve("saved.properties");

        PackSageSettings.load(input).save(output).join();

        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(output)) {
            properties.load(stream);
        }
        assertEquals("gpt-5-mini", properties.getProperty("modelId"));
        assertEquals("high", properties.getProperty("reasoningEffort"));
    }

    @Test
    void malformedNewSettingsKeepDefaults() throws Exception {
        Path path = temporaryDirectory.resolve("malformed-new-settings.properties");
        Files.writeString(path, "sendShortcut=bogus\nsessionsBeforeCollapse=not-a-number\n");

        PackSageSettings loaded = PackSageSettings.load(path);

        assertEquals(PackSageSettings.SendShortcut.ENTER, loaded.sendShortcut());
        assertEquals(PackSageSettings.DEFAULT_SESSIONS_BEFORE_COLLAPSE, loaded.sessionsBeforeCollapse());
    }

    @Test
    void developerPromptRoundTripsUnicodeAndCanBeClearedBySavingDefault() {
        Path path = temporaryDirectory.resolve("developer.properties");
        PackSageSettings settings = PackSageSettings.defaults();
        String prompt = "Line one\n世界 🌍";
        assertTrue(settings.setDeveloperPrompt(prompt));
        settings.save(path).join();
        assertEquals(prompt, PackSageSettings.load(path).developerPrompt());

        PackSageSettings.defaults().withDeveloperPrompt(null).save(path).join();
        assertEquals(null, PackSageSettings.load(path).developerPrompt());
    }

    @Test
    void blankAndOversizedDeveloperPromptsAreRejectedWithoutChangingValue() {
        PackSageSettings settings = PackSageSettings.defaults();
        assertTrue(settings.setDeveloperPrompt("kept"));
        assertFalse(settings.setDeveloperPrompt(" \n"));
        assertFalse(settings.setDeveloperPrompt("x".repeat(PackSageSettings.MAX_DEVELOPER_PROMPT_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> settings.withDeveloperPrompt("x".repeat(PackSageSettings.MAX_DEVELOPER_PROMPT_LENGTH + 1)));
        assertEquals("kept", settings.developerPrompt());
    }

    @Test
    void savingDeveloperPromptPublishesBeforePersistenceAndNormalizesTheDefault() throws Exception {
        Path path = temporaryDirectory.resolve("prompt.properties");
        var settings = PackSageSettings.defaults();
        settings.saveDeveloperPrompt(path, "custom prompt").join();
        assertEquals("custom prompt", settings.developerPrompt());
        assertEquals("custom prompt", PackSageSettings.load(path).developerPrompt());
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> settings.saveDeveloperPrompt(temporaryDirectory, "unsaved prompt").join());
        assertEquals("unsaved prompt", settings.developerPrompt(), "a failed write does not publish a second value later");
        settings.saveDeveloperPrompt(path, dev.packsage.model.GroundingInstructions.defaultInstructions()).join();
        assertEquals(null, settings.developerPrompt());
        assertEquals(null, PackSageSettings.load(path).developerPrompt());
        settings.saveDeveloperPrompt(path, "another prompt").join();
        settings.saveDeveloperPrompt(path, null).join();
        assertEquals(null, PackSageSettings.load(path).developerPrompt());
    }

    @Test
    void settingsSnapshotsQueueWithoutWaitingForTheWriterAndLatestGenerationIsFlushed() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var writer = new SettingsWriter((target, snapshot) -> {
            if (started.getCount() > 0) {
                started.countDown();
                try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new RuntimeException(failure); }
            }
            SettingsWriter.write(target, snapshot);
        });
        var settings = new PackSageSettings(true, true, 10, 12_000, writer);
        var path = temporaryDirectory.resolve("settings.properties");
        var client = java.util.concurrent.Executors.newSingleThreadExecutor();
        CompletableFuture<Void> promptSave;
        try {
            client.submit(() -> settings.save(path)).get(1, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            promptSave = client.submit(() -> settings.saveDeveloperPrompt(path, "newest screen prompt"))
                    .get(1, java.util.concurrent.TimeUnit.SECONDS);
            client.submit(() -> { }).get(1, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            client.shutdownNow();
        }
        promptSave.join();
        assertEquals("newest screen prompt", settings.developerPrompt());
        settings.save(path).join();
        settings.flushAndClose(path).join();
        assertEquals("newest screen prompt", settings.developerPrompt());
        assertEquals("newest screen prompt", PackSageSettings.load(path).developerPrompt());
    }

    @Test
    void outOfOrderSnapshotSubmissionsCannotReplaceNewerDiskData() throws Exception {
        var path = temporaryDirectory.resolve("settings.properties");
        var writer = new SettingsWriter();
        var older = java.util.Map.of("developerPrompt", "old");
        var newer = java.util.Map.of("developerPrompt", "new");
        writer.submit(path, new SettingsWriter.Snapshot(2, newer)).join();
        writer.submit(path, new SettingsWriter.Snapshot(1, older)).join();
        writer.flushAndClose(path, new SettingsWriter.Snapshot(2, newer)).join();
        assertEquals("new", PackSageSettings.load(path).developerPrompt());
    }

    @Test
    void staleDetachedSettingsCannotAcquireANewerGeneration() throws Exception {
        var path = temporaryDirectory.resolve("settings.properties");
        var settings = PackSageSettings.defaults();
        settings.setDeveloperPrompt("current");
        settings.save(path).join();
        var stale = settings.withDeveloperPrompt("stale");
        settings.setDeveloperPrompt("newest");
        settings.save(path).join();

        stale.save(path).join();

        assertEquals("newest", PackSageSettings.load(path).developerPrompt());
        settings.flushAndClose(path).join();
    }

    @Test
    void shutdownFlushWritesLatestSettingsEvenWithoutAnEarlierSave() throws Exception {
        var path = temporaryDirectory.resolve("settings.properties");
        var settings = PackSageSettings.defaults();
        settings.setDeveloperPrompt("shutdown prompt");

        settings.flushAndClose(path).join();

        assertEquals("shutdown prompt", PackSageSettings.load(path).developerPrompt());
    }
}
