package guideme;

import guideme.compiler.ParsedGuidePage;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;

public interface Guide {
    ResourceLocation getId();
    Collection<ParsedGuidePage> getPages();
    ParsedGuidePage getParsedPage(ResourceLocation id);
}
