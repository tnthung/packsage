package guideme.compiler;

import guideme.libs.mdast.model.MdAstRoot;
import net.minecraft.resources.ResourceLocation;

public final class ParsedGuidePage {
    private final ResourceLocation id;
    private final String language;
    private final MdAstRoot astRoot;

    public ParsedGuidePage(ResourceLocation id, String language, MdAstRoot astRoot) {
        this.id = id;
        this.language = language;
        this.astRoot = astRoot;
    }

    public ResourceLocation getId() { return id; }
    public String getLanguage() { return language; }
    public MdAstRoot getAstRoot() { return astRoot; }
}
