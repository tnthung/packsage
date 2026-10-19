package dev.packsage.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Properties;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        settings.save(path);

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

        PackSageSettings.load(input).save(output);

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
        settings.save(path);
        assertEquals(prompt, PackSageSettings.load(path).developerPrompt());

        PackSageSettings.defaults().withDeveloperPrompt(null).save(path);
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
}
