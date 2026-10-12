package dev.packsage.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackSageSettingsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultsAreBoundedAndPauseSearchAreEnabled() {
        PackSageSettings settings = PackSageSettings.defaults();

        assertTrue(settings.openPaused());
        assertTrue(settings.automaticWebSearch());
        assertEquals(10, settings.historyTurns());
        assertEquals(12_000, settings.historicalTokens());
    }

    @Test
    void validValuesPersistAndInvalidValuesKeepThePreviousBoundedValue() {
        Path path = temporaryDirectory.resolve("settings.properties");
        PackSageSettings settings = PackSageSettings.defaults();
        settings.setOpenPaused(false);
        settings.setAutomaticWebSearch(false);
        settings.setHistoryTurns(25);
        settings.setHistoricalTokens(20_000);
        settings.save(path);

        PackSageSettings loaded = PackSageSettings.load(path);
        assertFalse(loaded.openPaused());
        assertFalse(loaded.automaticWebSearch());
        assertEquals(25, loaded.historyTurns());
        assertEquals(20_000, loaded.historicalTokens());

        assertFalse(loaded.setHistoryTurns(0));
        assertFalse(loaded.setHistoricalTokens(Integer.MAX_VALUE));
        assertEquals(25, loaded.historyTurns());
        assertEquals(20_000, loaded.historicalTokens());
    }

    @Test
    void oneMalformedHistoryLimitDoesNotDiscardTheOtherValidLimit() throws Exception {
        Path path = temporaryDirectory.resolve("partial-settings.properties");
        Files.writeString(path, "historyTurns=31\nhistoricalTokens=not-a-number\n");

        PackSageSettings loaded = PackSageSettings.load(path);

        assertEquals(31, loaded.historyTurns());
        assertEquals(PackSageSettings.DEFAULT_HISTORICAL_TOKENS, loaded.historicalTokens());
    }
}
