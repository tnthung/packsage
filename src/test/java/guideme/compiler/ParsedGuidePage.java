package guideme.compiler;

import guideme.libs.mdast.model.MdAstRoot;
import net.minecraft.resources.ResourceLocation;

public final class ParsedGuidePage {
    private final ResourceLocation id;
    private final String language;
    private final MdAstRoot astRoot;
    private final Frontmatter frontmatter;

    public ParsedGuidePage(ResourceLocation id, String language, MdAstRoot astRoot) {
        this(id, language, astRoot, new Frontmatter(java.util.Map.of()));
    }

    public ParsedGuidePage(ResourceLocation id, String language, MdAstRoot astRoot, Frontmatter frontmatter) {
        this.id = id;
        this.language = language;
        this.astRoot = astRoot;
        this.frontmatter = frontmatter;
    }

    public ResourceLocation getId() { return id; }
    public String getLanguage() { return language; }
    public MdAstRoot getAstRoot() { return astRoot; }
    public Frontmatter getFrontmatter() { return frontmatter; }
}
