package guideme;

import guideme.compiler.ParsedGuidePage;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class FixtureGuide implements Guide {
    private final ResourceLocation id;
    private final Map<ResourceLocation, ParsedGuidePage> pages;
    private final Map<ResourceLocation, ParsedGuidePage> developmentPages = Map.of();

    public FixtureGuide(ResourceLocation id, Collection<ParsedGuidePage> pages) {
        this.id = id;
        this.pages = new LinkedHashMap<>();
        for (var page : pages) this.pages.put(page.getId(), page);
    }

    @Override public ResourceLocation getId() { return id; }
    @Override public Collection<ParsedGuidePage> getPages() { return pages.values(); }
    @Override public ParsedGuidePage getParsedPage(ResourceLocation pageId) { return pages.get(pageId); }
}
