package dev.packsage.knowledge.guide;

import com.klikli_dev.modonomicon.book.entries.BookEntry;
import com.klikli_dev.modonomicon.book.page.BookSpotlightPage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.Ingredient;

/** Modonomicon releases before the native entry-display-state API and Either spotlight API. */
class ModonomiconLegacyGuideReader extends ModonomiconGuideReader {
    ModonomiconLegacyGuideReader(String version) { super(version); }

    ModonomiconLegacyGuideReader(String version, EntryOpener opener) { super(version, opener); }

    @Override protected SpotlightValue spotlightValue(BookSpotlightPage page) {
        try {
            var item = page.getClass().getMethod("getItem").invoke(page);
            return item instanceof Ingredient ingredient ? new SpotlightValue(null, ingredient) : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadableSpotlight) {
            return null;
        }
    }

    @Override protected String entryDisplayState(BookEntry entry, Player player) {
        return legacyEntryDisplayState(entry, player);
    }
}
