package dev.packsage.knowledge.curios;

import dev.packsage.knowledge.Evidence;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuriosInventoryAdapterTest {
    @BeforeAll
    static void bootstrapMinecraft() { Bootstrap.bootStrap(); }

    @AfterEach
    void resetCuriosApi() { CuriosApi.reset(); }

    @Test
    void captureAndLocateUseCurrentFunctionalStacksAndReturnCopies() {
        var ringStack = new ItemStack(Items.GOLD_INGOT, 3);
        var necklaceStack = new ItemStack(Items.DIAMOND, 2);
        var ringHandler = new ICurioStacksHandler() {
            @Override public IDynamicStackHandler getStacks() {
                return new IDynamicStackHandler() {
                    @Override public int getSlots() { return 1; }
                    @Override public ItemStack getStackInSlot(int index) { return ringStack; }
                };
            }
            @Override public IDynamicStackHandler getCosmeticStacks() { throw new AssertionError("cosmetic stacks were read"); }
            @Override public String getIdentifier() { return "ring"; }
            @Override public int getSlots() { return 1; }
        };
        var necklaceHandler = new ICurioStacksHandler() {
            @Override public IDynamicStackHandler getStacks() {
                return new IDynamicStackHandler() {
                    @Override public int getSlots() { return 1; }
                    @Override public ItemStack getStackInSlot(int index) { return necklaceStack; }
                };
            }
            @Override public IDynamicStackHandler getCosmeticStacks() { throw new AssertionError("cosmetic stacks were read"); }
            @Override public String getIdentifier() { return "necklace"; }
            @Override public int getSlots() { return 1; }
        };
        var handlers = new LinkedHashMap<String, ICurioStacksHandler>();
        handlers.put("ring", ringHandler);
        handlers.put("necklace", necklaceHandler);
        CuriosApi.setInventory(Optional.of(() -> handlers));
        var adapter = new CuriosInventoryAdapter(true, true, "9.5.1+1.21.1");

        var captured = adapter.capture(null);
        ringStack.setCount(7);
        captured.slots().getFirst().stack().setCount(1);

        assertEquals(Evidence.Coverage.COMPLETE, captured.coverage());
        assertEquals(List.of("ring:0", "necklace:0"), captured.slots().stream()
                .map(slot -> slot.type() + ":" + slot.index()).toList());
        assertEquals(3, captured.slots().getFirst().stack().getCount());
        assertEquals(7, adapter.locate(null, "ring", 0).getCount());
        adapter.locate(null, "ring", 0).setCount(1);
        assertEquals(7, ringStack.getCount());
        assertNull(adapter.locate(null, "ring", 1));
        assertNull(adapter.locate(null, "charm", 0));
    }

    @Test
    void unsupportedVersionAndUnavailableCapabilityKeepCoverageUnknown() {
        CuriosApi.setInventory(Optional.empty());
        var unsupported = new CuriosInventoryAdapter(true, false, "10.0.0+1.21.1");

        assertEquals(Evidence.Coverage.PARTIAL, unsupported.capture(null).coverage());
        assertFalse(unsupported.enabled());
        assertEquals(0, CuriosApi.calls());

        var supported = new CuriosInventoryAdapter(true, true, "9.5.1+1.21.1");
        assertEquals(Evidence.Coverage.PARTIAL, supported.capture(null).coverage());
        assertFalse(supported.available());
        assertEquals(1, CuriosApi.calls());
    }

    @Test
    void readFailureCanRecoverOnTheNextFreshCapture() {
        var stack = new ItemStack(Items.EMERALD);
        var fail = new AtomicBoolean(true);
        var handler = new ICurioStacksHandler() {
            @Override public IDynamicStackHandler getStacks() {
                return new IDynamicStackHandler() {
                    @Override public int getSlots() { return 1; }
                    @Override public ItemStack getStackInSlot(int index) {
                        if (fail.getAndSet(false)) throw new IllegalStateException("temporary read failure");
                        return stack;
                    }
                };
            }
            @Override public IDynamicStackHandler getCosmeticStacks() { throw new AssertionError("cosmetic stacks were read"); }
            @Override public String getIdentifier() { return "ring"; }
            @Override public int getSlots() { return 1; }
        };
        CuriosApi.setInventory(Optional.of(() -> Map.of("ring", handler)));
        var adapter = new CuriosInventoryAdapter(true, true, "9.5.1+1.21.1");

        assertEquals(Evidence.Coverage.PARTIAL, adapter.capture(null).coverage());
        assertFalse(adapter.available());
        assertEquals(Evidence.Coverage.COMPLETE, adapter.capture(null).coverage());
        assertTrue(adapter.available());
        assertEquals(Items.EMERALD, adapter.locate(null, "ring", 0).getItem());
    }

    @Test
    void functionalHandlerTraversalStopsAtTheSlotBound() {
        var reads = new AtomicInteger();
        var handler = new ICurioStacksHandler() {
            @Override public IDynamicStackHandler getStacks() {
                return new IDynamicStackHandler() {
                    @Override public int getSlots() { return 10_000; }
                    @Override public ItemStack getStackInSlot(int index) { reads.incrementAndGet(); return ItemStack.EMPTY; }
                };
            }
            @Override public IDynamicStackHandler getCosmeticStacks() { throw new AssertionError("cosmetic stacks were read"); }
            @Override public String getIdentifier() { return "ring"; }
            @Override public int getSlots() { return 10_000; }
        };
        CuriosApi.setInventory(Optional.of(() -> Map.of("ring", handler)));

        var result = new CuriosInventoryAdapter(true, true, "9.5.1+1.21.1").capture(null);

        assertEquals(256, reads.get());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
    }
}
