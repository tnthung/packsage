package dev.packsage.knowledge.guide;

import com.klikli_dev.modonomicon.book.entries.BookEntry;
import net.minecraft.world.entity.player.Player;

/** Modonomicon releases with entry display state but the older Ingredient spotlight getter. */
final class ModonomiconIntermediateGuideReader extends ModonomiconLegacyGuideReader {
    ModonomiconIntermediateGuideReader(String version) { super(version); }

    ModonomiconIntermediateGuideReader(String version, EntryOpener opener) { super(version, opener); }

    @Override protected String entryDisplayState(BookEntry entry, Player player) {
        return entry.getEntryDisplayState(player).name();
    }
}
