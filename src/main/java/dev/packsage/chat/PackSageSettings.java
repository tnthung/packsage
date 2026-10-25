package dev.packsage.chat;

import dev.packsage.model.ModelBackend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public final class PackSageSettings {
    public enum SendShortcut { ENTER, CTRL_ENTER }

    public static final int DEFAULT_HISTORY_TURNS = 10;
    public static final int DEFAULT_HISTORICAL_TOKENS = 12_000;
    public static final int DEFAULT_SESSIONS_BEFORE_COLLAPSE = 2;
    public static final int MAX_HISTORY_TURNS = 100;
    public static final int MAX_HISTORICAL_TOKENS = 1_000_000;
    public static final int MAX_SESSIONS_BEFORE_COLLAPSE = 1_000;
    public static final int MAX_DEVELOPER_PROMPT_LENGTH = 65_536;

    private boolean openPaused;
    private boolean automaticWebSearch;
    private boolean hideInactiveAdapters = true;
    private Boolean debugBridge;
    private Integer debugBridgePort;
    private int historyTurns;
    private int historicalTokens;
    private SendShortcut sendShortcut;
    private int sessionsBeforeCollapse;
    private String modelId;
    private ModelBackend.ReasoningEffort reasoningEffort;
    private volatile String developerPrompt;
    private volatile long saveGeneration;
    private final Object saveLock = new Object();

    public PackSageSettings(boolean openPaused, boolean automaticWebSearch, int historyTurns, int historicalTokens) {
        this.openPaused = openPaused;
        this.automaticWebSearch = automaticWebSearch;
        if (historyTurns < 1 || historyTurns > MAX_HISTORY_TURNS) throw new IllegalArgumentException("historyTurns is out of bounds");
        if (historicalTokens < 1 || historicalTokens > MAX_HISTORICAL_TOKENS) throw new IllegalArgumentException("historicalTokens is out of bounds");
        this.historyTurns = historyTurns;
        this.historicalTokens = historicalTokens;
        sendShortcut = SendShortcut.ENTER;
        sessionsBeforeCollapse = DEFAULT_SESSIONS_BEFORE_COLLAPSE;
        modelId = null;
        reasoningEffort = ModelBackend.ReasoningEffort.DEFAULT;
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
            if (properties.containsKey("debugBridge")) settings.debugBridge = Boolean.parseBoolean(properties.getProperty("debugBridge"));
            try {
                int port = Integer.parseInt(properties.getProperty("debugBridgePort", ""));
                if (port >= 1 && port <= 65535) settings.debugBridgePort = port;
            } catch (NumberFormatException ignored) { }
            settings.openPaused = Boolean.parseBoolean(properties.getProperty("openPaused", Boolean.toString(settings.openPaused)));
            settings.automaticWebSearch = Boolean.parseBoolean(properties.getProperty("automaticWebSearch", Boolean.toString(settings.automaticWebSearch)));
            settings.hideInactiveAdapters = Boolean.parseBoolean(properties.getProperty("hideInactiveAdapters", Boolean.toString(settings.hideInactiveAdapters)));
            try {
                settings.sendShortcut = SendShortcut.valueOf(properties.getProperty("sendShortcut", "enter").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) { }
            try {
                int turns = Integer.parseInt(properties.getProperty("historyTurns", Integer.toString(settings.historyTurns)));
                if (turns >= 1 && turns <= MAX_HISTORY_TURNS) settings.historyTurns = turns;
            } catch (NumberFormatException ignored) { }
            String configuredModelId = properties.getProperty("modelId", "");
            settings.modelId = configuredModelId.isBlank() ? null : configuredModelId;
            String configuredPrompt = properties.getProperty("developerPrompt");
            if (configuredPrompt != null && !configuredPrompt.isBlank() && configuredPrompt.length() <= MAX_DEVELOPER_PROMPT_LENGTH) {
                settings.developerPrompt = configuredPrompt;
            }
            try {
                settings.reasoningEffort = ModelBackend.ReasoningEffort.valueOf(properties.getProperty("reasoningEffort", "DEFAULT").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) { }
            try {
                int tokens = Integer.parseInt(properties.getProperty("historicalTokens", Integer.toString(settings.historicalTokens)));
                if (tokens >= 1 && tokens <= MAX_HISTORICAL_TOKENS) settings.historicalTokens = tokens;
            } catch (NumberFormatException ignored) { }
            try {
                int sessions = Integer.parseInt(properties.getProperty("sessionsBeforeCollapse", Integer.toString(settings.sessionsBeforeCollapse)));
                if (sessions >= 1 && sessions <= MAX_SESSIONS_BEFORE_COLLAPSE) settings.sessionsBeforeCollapse = sessions;
            } catch (NumberFormatException ignored) { }
        } catch (IOException | IllegalArgumentException ignored) {
            return settings;
        }
        return settings;
    }

    public void save(Path path) {
        saveGeneration++;
        synchronized (saveLock) {
            Properties properties = properties();
            try {
                if (path.getParent() != null) Files.createDirectories(path.getParent());
                var temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "packsage-", ".properties.tmp");
                try {
                    try (OutputStream output = Files.newOutputStream(temporary)) {
                        properties.store(output, "PackSage settings");
                    }
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException exception) {
                throw new IllegalStateException("could not save PackSage settings", exception);
            }
        }
    }

    /** Worker-side restore: screen saves share this lock, and client-side changes invalidate captured generations. */
    public boolean saveIfUnchanged(PackSageSettings snapshot, Runnable write) {
        synchronized (saveLock) {
            if (saveGeneration != snapshot.saveGeneration) return false;
            write.run();
            return true;
        }
    }

    /** Compares a client-thread snapshot, including saves that did not change a setting's value. */
    public boolean sameSnapshot(PackSageSettings snapshot) {
        return saveGeneration == snapshot.saveGeneration && properties().equals(snapshot.properties());
    }

    private Properties properties() {
        Properties properties = new Properties();
        if (debugBridge != null) properties.setProperty("debugBridge", Boolean.toString(debugBridge));
        if (debugBridgePort != null) properties.setProperty("debugBridgePort", Integer.toString(debugBridgePort));
        properties.setProperty("openPaused", Boolean.toString(openPaused));
        properties.setProperty("automaticWebSearch", Boolean.toString(automaticWebSearch));
        properties.setProperty("hideInactiveAdapters", Boolean.toString(hideInactiveAdapters));
        properties.setProperty("sendShortcut", sendShortcut.name().toLowerCase(java.util.Locale.ROOT));
        properties.setProperty("historyTurns", Integer.toString(historyTurns));
        properties.setProperty("historicalTokens", Integer.toString(historicalTokens));
        properties.setProperty("sessionsBeforeCollapse", Integer.toString(sessionsBeforeCollapse));
        if (modelId != null) properties.setProperty("modelId", modelId);
        if (developerPrompt != null) properties.setProperty("developerPrompt", developerPrompt);
        properties.setProperty("reasoningEffort", reasoningEffort.name().toLowerCase(java.util.Locale.ROOT));
        return properties;
    }

    public boolean openPaused() { return openPaused; }
    public boolean debugBridge() { return Boolean.TRUE.equals(debugBridge); }
    public int debugBridgePort() { return debugBridgePort == null ? 25599 : debugBridgePort; }
    public boolean automaticWebSearch() { return automaticWebSearch; }
    public boolean hideInactiveAdapters() { return hideInactiveAdapters; }
    public int historyTurns() { return historyTurns; }
    public int historicalTokens() { return historicalTokens; }
    public SendShortcut sendShortcut() { return sendShortcut; }
    public int sessionsBeforeCollapse() { return sessionsBeforeCollapse; }
    public String modelId() { return modelId; }
    public ModelBackend.ReasoningEffort reasoningEffort() { return reasoningEffort; }
    public String developerPrompt() { return developerPrompt; }

    public void setOpenPaused(boolean value) { openPaused = value; saveGeneration++; }
    public void setAutomaticWebSearch(boolean value) { automaticWebSearch = value; saveGeneration++; }
    public void setHideInactiveAdapters(boolean value) { hideInactiveAdapters = value; saveGeneration++; }
    public void setSendShortcut(SendShortcut value) { sendShortcut = value == null ? SendShortcut.ENTER : value; saveGeneration++; }
    public void setModelId(String value) { modelId = value == null || value.isBlank() ? null : value; saveGeneration++; }
    public void setReasoningEffort(ModelBackend.ReasoningEffort value) { reasoningEffort = value == null ? ModelBackend.ReasoningEffort.DEFAULT : value; saveGeneration++; }

    public boolean setDeveloperPrompt(String value) {
        if (value != null && (value.isBlank() || value.length() > MAX_DEVELOPER_PROMPT_LENGTH)) return false;
        developerPrompt = value;
        saveGeneration++;
        return true;
    }

    public void saveDeveloperPrompt(Path path, String value) {
        saveGeneration++;
        synchronized (saveLock) {
            PackSageSettings candidate = withDeveloperPrompt(dev.packsage.model.GroundingInstructions.defaultInstructions().equals(value) ? null : value);
            candidate.save(path);
            setDeveloperPrompt(candidate.developerPrompt());
        }
    }

    public PackSageSettings withDeveloperPrompt(String value) {
        PackSageSettings copy = new PackSageSettings(openPaused, automaticWebSearch, historyTurns, historicalTokens);
        copy.sendShortcut = sendShortcut;
        copy.sessionsBeforeCollapse = sessionsBeforeCollapse;
        copy.modelId = modelId;
        copy.reasoningEffort = reasoningEffort;
        copy.hideInactiveAdapters = hideInactiveAdapters;
        copy.debugBridge = debugBridge;
        copy.debugBridgePort = debugBridgePort;
        if (!copy.setDeveloperPrompt(value)) throw new IllegalArgumentException("developerPrompt is out of bounds");
        copy.saveGeneration = saveGeneration;
        return copy;
    }

    public boolean setHistoryTurns(int value) {
        if (value < 1 || value > MAX_HISTORY_TURNS) return false;
        historyTurns = value;
        saveGeneration++;
        return true;
    }

    public boolean setHistoricalTokens(int value) {
        if (value < 1 || value > MAX_HISTORICAL_TOKENS) return false;
        historicalTokens = value;
        saveGeneration++;
        return true;
    }

    public boolean setSessionsBeforeCollapse(int value) {
        if (value < 1 || value > MAX_SESSIONS_BEFORE_COLLAPSE) return false;
        sessionsBeforeCollapse = value;
        saveGeneration++;
        return true;
    }

    public ChatContracts.HistoryLimits historyLimits() {
        return new ChatContracts.HistoryLimits(historyTurns, historicalTokens);
    }
}
