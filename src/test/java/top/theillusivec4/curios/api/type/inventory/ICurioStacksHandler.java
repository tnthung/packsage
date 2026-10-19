package top.theillusivec4.curios.api.type.inventory;

public interface ICurioStacksHandler {
    IDynamicStackHandler getStacks();

    IDynamicStackHandler getCosmeticStacks();

    String getIdentifier();

    int getSlots();
}
