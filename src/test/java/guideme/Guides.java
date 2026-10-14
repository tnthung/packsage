package guideme;

import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;

public final class Guides {
    private static List<Guide> guides = List.of();

    private Guides() { }

    public static Collection<? extends Guide> getAll() { return guides; }

    public static Guide getById(ResourceLocation id) {
        return guides.stream().filter(guide -> guide.getId().equals(id)).findFirst().orElse(null);
    }

    public static void setForTest(List<Guide> value) { guides = List.copyOf(value); }
}
