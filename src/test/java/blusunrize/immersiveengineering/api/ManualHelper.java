package blusunrize.immersiveengineering.api;

import blusunrize.lib.manual.ManualInstance;

public final class ManualHelper {
    private static final ManualInstance MANUAL = new ManualInstance();

    private ManualHelper() {
    }

    public static ManualInstance getManual() {
        return MANUAL;
    }
}
