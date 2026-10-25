package dev.packsage.journal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.packsage.journal.JournalEntry.EventEntry;

import java.time.Instant;
import java.util.Locale;

/** One-line descriptions of recorded runs; details may have been truncated and need not be valid JSON. */
public final class RunSummary {
    private RunSummary() {
    }

    public static String describe(EventEntry entry, String actorName) {
        var detail = new JsonObject();
        if (entry.detail() != null) {
            try {
                var parsed = JsonParser.parseString(entry.detail());
                if (parsed.isJsonObject()) {
                    detail = parsed.getAsJsonObject();
                }
            } catch (RuntimeException ignored) {
                // A stored detail may end at the character limit. The row still has its plain facts.
            }
        }
        var actor = actorName == null ? entry.actor() : actorName;
        var subject = entry.subject() == null ? "unknown" : entry.subject();
        var count = String.format(Locale.ROOT, "%,d", entry.count());
        var amount = String.format(Locale.ROOT, "%,d", entry.amount());
        var container = detail.has("container") && detail.get("container").isJsonPrimitive()
                ? detail.get("container").getAsString() : "container";
        var name = detail.has("name") && detail.get("name").isJsonPrimitive() ? " \"" + detail.get("name").getAsString() + "\"" : "";
        var text = switch (entry.type()) {
            case PLACE -> actor + " placed " + count + " " + subject;
            case BREAK -> actor + " broke " + count + " " + subject;
            case DEPOSIT -> actor + " put " + amount + " " + subject + " into " + container;
            case WITHDRAW -> actor + " took " + amount + " " + subject + " from " + container;
            case DEATH -> subject + name + " died";
            case KILL -> actor + " killed " + count + " " + subject;
            case DIMENSION -> actor + " travelled to " + subject;
            case ADVANCEMENT -> actor + " earned " + subject;
            case PROFESSION -> subject + name + " changed profession to " + (detail.has("to") && detail.get("to").isJsonPrimitive()
                    ? detail.get("to").getAsString() : "unknown");
            case CURED -> subject + name + " was cured";
            case CONVERTED -> subject + name + " turned into a zombie villager";
            case TRADE -> actor + " traded for " + amount + " " + subject;
            case RAID_STARTED -> "raid started";
            case RAID_FINISHED -> "raid ended";
            case STRUCTURE -> actor + " entered " + subject;
            case EXPLOSION -> count + " explosions of " + subject + " destroyed " + amount + " blocks";
        };
        text += " in " + entry.dimension();
        if (entry.at() != null) {
            var at = entry.at();
            text += (entry.firstWall() == entry.lastWall() ? " at " : " from ") + at.x() + "," + at.y() + "," + at.z();
            var box = entry.box();
            if (box != null && (box.minX() != box.maxX() || box.minY() != box.maxY() || box.minZ() != box.maxZ())) {
                text += " (box " + box.minX() + "," + box.minY() + "," + box.minZ() + " to "
                        + box.maxX() + "," + box.maxY() + "," + box.maxZ() + ")";
            }
        }
        text += entry.firstWall() == entry.lastWall() ? " at " + Instant.ofEpochMilli(entry.firstWall())
                : " between " + Instant.ofEpochMilli(entry.firstWall()) + " and " + Instant.ofEpochMilli(entry.lastWall());
        if (entry.type() == EventType.DEATH && detail.has("message") && detail.get("message").isJsonPrimitive()) {
            text += ": " + detail.get("message").getAsString();
        }
        return text + (entry.countOnly() ? " (positions not recorded)" : "");
    }
}
