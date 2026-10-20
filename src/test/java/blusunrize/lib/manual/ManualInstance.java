package blusunrize.lib.manual;

import blusunrize.lib.manual.Tree.AbstractNode;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ManualInstance {
    public final Map<ResourceLocation, ManualEntry> contentsByName = new LinkedHashMap<>();
    private boolean initialized = true;
    private int forbiddenCalls;
    private int recipeMatchCalls;

    public ManualEntry addEntry(String id, SpecialManualElement element) {
        var entry = new ManualEntry(element);
        contentsByName.put(ResourceLocation.parse(id), entry);
        return entry;
    }

    public boolean showNodeInList(AbstractNode node) {
        return true;
    }

    public void reload() {
        forbiddenCalls++;
    }

    public void open() {
        forbiddenCalls++;
    }

    public void render() {
        forbiddenCalls++;
    }

    public void recalculateRecipes() {
        recipeMatchCalls++;
    }

    public void matchAllRecipes() {
        recipeMatchCalls++;
    }

    public void reset() {
        contentsByName.clear();
        initialized = true;
        forbiddenCalls = 0;
        recipeMatchCalls = 0;
    }

    public void setInitialized(boolean value) {
        initialized = value;
    }

    public int forbiddenCalls() {
        return forbiddenCalls;
    }

    public int recipeMatchCalls() {
        return recipeMatchCalls;
    }
}
