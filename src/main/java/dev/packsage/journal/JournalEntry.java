package dev.packsage.journal;

import dev.packsage.knowledge.Evidence;
import dev.packsage.world.BlockPlace;

import java.util.List;
import java.util.Locale;

/** Events and player reports share a stable creation-time ordering. */
public sealed interface JournalEntry {
    long sortWall();

    String ref();

    record EventEntry(long id, EventType type, String actor, String subject, String dimension, long count, long amount,
                      long firstGameTime, long lastGameTime, long firstWall, long lastWall, BlockPlace at, Box box,
                      String detail, boolean countOnly, List<BlockPlace> inArea, int inAreaCount) implements JournalEntry {
        public EventEntry {
            inArea = List.copyOf(inArea);
        }

        @Override
        public long sortWall() {
            return firstWall;
        }

        @Override
        public String ref() {
            return "e" + String.format(Locale.ROOT, "%012d", id);
        }
    }

    record NoteEntry(Note note) implements JournalEntry {
        public Evidence.Authority authority() {
            return Evidence.Authority.PLAYER_REPORTED;
        }

        public String trust() {
            return "player-said";
        }

        @Override
        public long sortWall() {
            return note.createdWall();
        }

        @Override
        public String ref() {
            return "n" + note.id();
        }
    }
}
