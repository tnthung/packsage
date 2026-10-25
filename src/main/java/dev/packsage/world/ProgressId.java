package dev.packsage.world;

/** A progress entry or tree: {@code adapter/local}, such as {@code minecraft/minecraft:story/mine_stone}; a null adapter means any adapter. */
public record ProgressId(String adapter, String local) {
    public ProgressId {
        if (local == null || local.isBlank() || adapter != null && (adapter.isEmpty() || adapter.indexOf('/') >= 0 || adapter.indexOf(':') >= 0)) {
            throw new IllegalArgumentException("Invalid progress id: " + adapter + "/" + local);
        }
    }

    public String text() {
        return adapter == null ? local : adapter + "/" + local;
    }

    /** Splits at the first {@code /}; a part before it containing {@code :} is a bare local id, so the adapter is null. */
    public static ProgressId parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Progress id is missing");
        }
        var slash = text.indexOf('/');
        if (slash < 0 || text.substring(0, slash).indexOf(':') >= 0) {
            return new ProgressId(null, text);
        }
        if (slash == 0 || slash == text.length() - 1) {
            throw new IllegalArgumentException("Invalid progress id: " + text);
        }
        return new ProgressId(text.substring(0, slash), text.substring(slash + 1));
    }
}
