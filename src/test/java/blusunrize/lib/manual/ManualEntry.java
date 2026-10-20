package blusunrize.lib.manual;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ManualEntry {
    private List<SpecialElementData> specialData = new ArrayList<>();
    private final Tree.AbstractNode treeNode = new Tree.AbstractNode();
    private final SpecialManualElement baseElement;
    private int elementAccessorCalls;
    private int specialDataCalls;

    public ManualEntry(SpecialManualElement element) {
        baseElement = element;
        if (element != null) specialData.add(new SpecialElementData("", 0, element));
    }

    public List<SpecialElementData> getSpecialData() {
        specialDataCalls++;
        return specialData;
    }

    public Tree.AbstractNode getTreeNode() {
        return treeNode;
    }

    public String getTitle() {
        return "fixture entry";
    }

    public net.minecraft.resources.ResourceLocation getLocation() {
        return net.minecraft.resources.ResourceLocation.parse("immersiveengineering:manual/fixture");
    }

    public void addSpecial(String anchor, int offset) {
        specialData = new ArrayList<>(List.of(new SpecialElementData(anchor, offset, baseElement)));
    }

    public void addLazySpecial(String anchor, int offset) {
        specialData.add(new SpecialElementData(anchor, offset, () -> {
            elementAccessorCalls++;
            return new ManualElementCrafting(new blusunrize.lib.manual.utils.ManualRecipeRef[0][]);
        }));
    }

    public void setSpecials(List<SpecialElementData> values) {
        specialData = values;
    }

    public int elementAccessorCalls() {
        return elementAccessorCalls;
    }

    public int specialDataCalls() {
        return specialDataCalls;
    }

    public static final class SpecialElementData {
        private final String anchor;
        private final int offset;
        private final Supplier<SpecialManualElement> element;

        public SpecialElementData(String anchor, int offset, SpecialManualElement element) {
            this(anchor, offset, () -> element);
        }

        private SpecialElementData(String anchor, int offset, Supplier<SpecialManualElement> element) {
            this.anchor = anchor;
            this.offset = offset;
            this.element = element;
        }

        public SpecialManualElement getElement() {
            return element.get();
        }

        public String getAnchor() {
            return anchor;
        }

        public int getOffset() {
            return offset;
        }
    }
}
