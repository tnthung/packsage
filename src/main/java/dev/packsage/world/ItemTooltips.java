package dev.packsage.world;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reads an item's tooltip lines as shown with advanced tooltips off. Each read works on a copy of the stack so a
 * tooltip that changes its stack leaves the world untouched, and a tooltip that throws becomes an {@code unknown}
 * status rather than a failed read.
 */
public final class ItemTooltips {
    public static final int MAX_LINES = 32;
    public static final int MAX_LINE_LENGTH = 256;

    /** The tooltip text; status is {@code complete}, {@code bounded} (lines or characters were cut) or {@code unknown}. */
    public record Tooltip(List<String> lines, String status) {
        public Tooltip {
            lines = List.copyOf(lines);
        }
    }

    private final Function<ItemStack, List<Component>> source;

    ItemTooltips(Function<ItemStack, List<Component>> source) {
        this.source = source;
    }

    public static ItemTooltips of(ServerPlayer viewer) {
        return new ItemTooltips(copy -> copy.getTooltipLines(Item.TooltipContext.of(viewer.level()), viewer, TooltipFlag.NORMAL));
    }

    public Tooltip lines(ItemStack stack) {
        try {
            var components = source.apply(stack.copy());
            var lines = new ArrayList<String>();
            var bounded = components.size() > MAX_LINES;
            for (var component : components.subList(0, Math.min(components.size(), MAX_LINES))) {
                var line = component.getString();
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH);
                    bounded = true;
                }
                lines.add(line);
            }
            return new Tooltip(lines, bounded ? "bounded" : "complete");
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable throwable) {
            return new Tooltip(List.of(), "unknown");
        }
    }
}
