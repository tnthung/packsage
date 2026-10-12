package dev.packsage.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class PackSageSettings {
    public static final int DEFAULT_HISTORY_TURNS = 10;
    public static final int DEFAULT_HISTORICAL_TOKENS = 12_000;
    public static final int MAX_HISTORY_TURNS = 100;
    public static final int MAX_HISTORICAL_TOKENS = 1_000_000;

    private boolean openPaused;
    private boolean automaticWebSearch;
    private int historyTurns;
    private int historicalTokens;

    public PackSageSettings(boolean openPaused, boolean automaticWebSearch, int historyTurns, int historicalTokens) {
        this.openPaused = openPaused;
        this.automaticWebSearch = automaticWebSearch;
        if (historyTurns < 1 || historyTurns > MAX_HISTORY_TURNS) throw new IllegalArgumentException("historyTurns is out of bounds");
        if (historicalTokens < 1 || historicalTokens > MAX_HISTORICAL_TOKENS) throw new IllegalArgumentException("historicalTokens is out of bounds");
        this.historyTurns = historyTurns;
        this.historicalTokens = historicalTokens;
    }

    public static PackSageSettings defaults() {
        return new PackSageSettings(true, true, DEFAULT_HISTORY_TURNS, DEFAULT_HISTORICAL_TOKENS);
    }

    public static PackSageSettings load(Path path) {
        PackSageSettings settings = defaults();
        if (!Files.isRegularFile(path)) return settings;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            settings.openPaused = Boolean.parseBoolean(properties.getProperty("openPaused", Boolean.toString(settings.openPaused)));
            settings.automaticWebSearch = Boolean.parseBoolean(properties.getProperty("automaticWebSearch", Boolean.toString(settings.automaticWebSearch)));
            try {
                int turns = Integer.parseInt(properties.getProperty("historyTurns", Integer.toString(settings.historyTurns)));
                if (turns >= 1 && turns <= MAX_HISTORY_TURNS) settings.historyTurns = turns;
            } catch (NumberFormatException ignored) { }
            try {
                int tokens = Integer.parseInt(properties.getProperty("historicalTokens", Integer.toString(settings.historicalTokens)));
                if (tokens >= 1 && tokens <= MAX_HISTORICAL_TOKENS) settings.historicalTokens = tokens;
            } catch (NumberFormatException ignored) { }
        } catch (IOException | IllegalArgumentException ignored) {
            return settings;
        }
        return settings;
    }

    public void save(Path path) {
        Properties properties = new Properties();
        properties.setProperty("openPaused", Boolean.toString(openPaused));
        properties.setProperty("automaticWebSearch", Boolean.toString(automaticWebSearch));
        properties.setProperty("historyTurns", Integer.toString(historyTurns));
        properties.setProperty("historicalTokens", Integer.toString(historicalTokens));
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(path)) {
                properties.store(output, "PackSage settings");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("could not save PackSage settings", exception);
        }
    }

    public boolean openPaused() { return openPaused; }
    public boolean automaticWebSearch() { return automaticWebSearch; }
    public int historyTurns() { return historyTurns; }
    public int historicalTokens() { return historicalTokens; }

    public void setOpenPaused(boolean value) { openPaused = value; }
    public void setAutomaticWebSearch(boolean value) { automaticWebSearch = value; }

    public boolean setHistoryTurns(int value) {
        if (value < 1 || value > MAX_HISTORY_TURNS) return false;
        historyTurns = value;
        return true;
    }

    public boolean setHistoricalTokens(int value) {
        if (value < 1 || value > MAX_HISTORICAL_TOKENS) return false;
        historicalTokens = value;
        return true;
    }

    public ChatContracts.HistoryLimits historyLimits() {
        return new ChatContracts.HistoryLimits(historyTurns, historicalTokens);
    }
}
