package dev.packsage.journal;

import dev.packsage.world.BlockPlace;

import java.util.List;

/** A player-reported note; its place may be absent. */
public record Note(String id, String text, String playerWords, List<String> tags, BlockPlace place,
                   long createdWall, long createdGameTime, long updatedWall, String originConversation, String originRequest) {
}
