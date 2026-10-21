package blusunrize.lib.manual;

import blusunrize.lib.manual.Tree.AbstractNode;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ManualInstance {
    public final Map<ResourceLocation, ManualEntry> contentsByName = new LinkedHashMap<>();
    private boolean initialized = true;
    private int forbiddenCalls;
    private int recipeMatchCalls;
    private final Set<AbstractNode> hiddenNodes = new HashSet<>();

    public ManualEntry addEntry(String id, SpecialManualElement element) {
        var entry = new ManualEntry(element);
        contentsByName.put(ResourceLocation.parse(id), entry);
        return entry;
    }

    public void hide(String id) {
        hiddenNodes.add(contentsByName.get(ResourceLocation.parse(id)).getTreeNode());
    }

    public boolean showNodeInList(AbstractNode node) {
        return !hiddenNodes.contains(node);
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
        hiddenNodes.clear();
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

    public void noteRecipeMatchCall() {
        recipeMatchCalls++;
    }
}
