package dev.packsage.journal;

import com.google.gson.JsonObject;
import dev.packsage.world.BlockPlace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Recording rules over plain facts. Hooks supply ids and positions, never live world objects. */
public final class JournalEvents {
    public record Stamp(long gameTime, long wallMillis, String dimension) {
    }

    public record DeathFacts(String victimType, String victimId, String customName, boolean player, boolean villager,
                             boolean tamed, String profession, String damageType, String killerRef, String directType,
                             String message, int x, int y, int z) {
        private String detail() {
            var detail = new JsonObject();
            detail.addProperty("victimId", victimId);
            if (customName != null) {
                detail.addProperty("name", customName);
            }
            if (profession != null) {
                detail.addProperty("profession", profession);
            }
            detail.addProperty("damageType", damageType);
            if (killerRef != null && !killerRef.equals("environment")) {
                detail.addProperty("killer", killerRef);
            }
            if (directType != null) {
                detail.addProperty("direct", directType);
            }
            detail.addProperty("message", message);
            return detail.toString();
        }
    }

    private JournalEvents() {
    }

    public static JournalEvent place(Stamp stamp, String playerRef, String playerName, String block, int x, int y, int z) {
        var detail = new JsonObject();
        if (playerName != null) {
            detail.addProperty("actorName", playerName);
        }
        return new JournalEvent(EventType.PLACE, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), playerRef,
                block, null, 1, new long[]{Positions.pack(x, y, z)}, detail.toString());
    }

    public static JournalEvent broken(Stamp stamp, String playerRef, String playerName, String block, int x, int y, int z) {
        var detail = new JsonObject();
        if (playerName != null) {
            detail.addProperty("actorName", playerName);
        }
        return new JournalEvent(EventType.BREAK, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), playerRef,
                block, null, 1, new long[]{Positions.pack(x, y, z)}, detail.toString());
    }

    /** The hook must cap the whole explosion at 4,096 positions before grouping by block id. */
    public static List<JournalEvent> explosion(Stamp stamp, String actorRef, String sourceType, double cx, double cy, double cz,
                                                Map<String, long[]> brokenByBlock) {
        long amount = 0;
        for (var positions : brokenByBlock.values()) {
            amount += positions.length;
            if (amount > Run.MAX_POSITIONS) {
                throw new IllegalArgumentException("an explosion exceeds 4,096 positions");
            }
        }
        var events = new ArrayList<JournalEvent>();
        events.add(new JournalEvent(EventType.EXPLOSION, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), actorRef,
                sourceType == null ? "unknown" : sourceType, null, amount,
                new long[]{Positions.pack(Math.toIntExact(Math.round(cx)), Math.toIntExact(Math.round(cy)), Math.toIntExact(Math.round(cz)))}, null));
        for (var block : brokenByBlock.keySet().stream().sorted().toList()) {
            var positions = brokenByBlock.get(block);
            if (positions.length > 0) {
                events.add(new JournalEvent(EventType.BREAK, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), "explosion",
                        block, null, positions.length, positions, null));
            }
        }
        return List.copyOf(events);
    }

    public static JournalEvent transfer(Stamp stamp, String playerRef, String playerName, String item, long delta,
                                        String containerKind, int x, int y, int z, String containerEntity) {
        if (delta == 0 || delta == Long.MIN_VALUE) {
            throw new IllegalArgumentException("a transfer needs a nonzero representable amount");
        }
        var detail = new JsonObject();
        detail.addProperty("container", containerKind);
        if (containerEntity != null) {
            detail.addProperty("entity", containerEntity);
        }
        if (playerName != null) {
            detail.addProperty("actorName", playerName);
        }
        return new JournalEvent(delta > 0 ? EventType.DEPOSIT : EventType.WITHDRAW, stamp.gameTime(), stamp.wallMillis(),
                stamp.dimension(), playerRef, item, null, Math.abs(delta), new long[]{Positions.pack(x, y, z)}, detail.toString());
    }

    public static Optional<JournalEvent> death(Stamp stamp, DeathFacts facts) {
        if (!facts.player() && !facts.villager() && !facts.tamed() && facts.customName() == null) {
            return Optional.empty();
        }
        return Optional.of(new JournalEvent(EventType.DEATH, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(),
                facts.killerRef() == null ? "environment" : facts.killerRef(), facts.victimType(), null, 1,
                new long[]{Positions.pack(facts.x(), facts.y(), facts.z())}, facts.detail()));
    }

    public static Optional<JournalEvent> kill(Stamp stamp, DeathFacts facts) {
        if (facts.killerRef() == null || !facts.killerRef().startsWith("player:")) {
            return Optional.empty();
        }
        return Optional.of(new JournalEvent(EventType.KILL, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(),
                facts.killerRef(), facts.victimType(), null, 1,
                new long[]{Positions.pack(facts.x(), facts.y(), facts.z())}, facts.detail()));
    }

    /** With no known portal, use the overload that supplies the player's last source-side position. */
    public static JournalEvent dimension(Stamp from, String playerRef, String toDimension, BlockPlace portal, BlockPlace arrival) {
        return dimension(from, playerRef, toDimension, portal, arrival, null);
    }

    public static JournalEvent dimension(Stamp from, String playerRef, String toDimension, BlockPlace portal, BlockPlace arrival,
                                         BlockPlace lastPosition) {
        var at = portal == null ? lastPosition : portal;
        if (at == null || !from.dimension().equals(at.dimension()) || !toDimension.equals(arrival.dimension())) {
            throw new IllegalArgumentException("dimension travel needs a source position and a destination arrival");
        }
        var detail = new JsonObject();
        if (portal != null) {
            detail.addProperty("portal", portal.text());
        }
        detail.addProperty("arrival", arrival.text());
        return new JournalEvent(EventType.DIMENSION, from.gameTime(), from.wallMillis(), from.dimension(), playerRef,
                toDimension, null, 1, new long[]{Positions.pack(at.x(), at.y(), at.z())}, detail.toString());
    }

    public static JournalEvent advancement(Stamp stamp, String playerRef, String id, String title, int x, int y, int z) {
        var detail = new JsonObject();
        detail.addProperty("title", title);
        return new JournalEvent(EventType.ADVANCEMENT, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), playerRef,
                id, null, 1, new long[]{Positions.pack(x, y, z)}, detail.toString());
    }

    public static JournalEvent profession(Stamp stamp, String villagerId, String from, String to, int x, int y, int z) {
        var detail = new JsonObject();
        detail.addProperty("from", from);
        detail.addProperty("to", to);
        return new JournalEvent(EventType.PROFESSION, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), "environment",
                "minecraft:villager", villagerId, 1, new long[]{Positions.pack(x, y, z)}, detail.toString());
    }

    public static JournalEvent cured(Stamp stamp, String villagerId, int x, int y, int z) {
        return new JournalEvent(EventType.CURED, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), "environment",
                "minecraft:villager", villagerId, 1, new long[]{Positions.pack(x, y, z)}, null);
    }

    public static JournalEvent converted(Stamp stamp, String villagerId, int x, int y, int z) {
        return new JournalEvent(EventType.CONVERTED, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), "environment",
                "minecraft:villager", villagerId, 1, new long[]{Positions.pack(x, y, z)}, null);
    }

    public static JournalEvent trade(Stamp stamp, String playerRef, String villagerId, String profession, String result, long count,
                                     String costA, String costB, int x, int y, int z) {
        var detail = new JsonObject();
        detail.addProperty("profession", profession);
        detail.addProperty("costA", costA);
        if (costB != null) {
            detail.addProperty("costB", costB);
        }
        return new JournalEvent(EventType.TRADE, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), playerRef,
                result, villagerId, count, new long[]{Positions.pack(x, y, z)}, detail.toString());
    }

    public static JournalEvent raidStarted(Stamp stamp, int raidId, int omen, int waves, int x, int y, int z) {
        var detail = new JsonObject();
        detail.addProperty("raidId", raidId);
        detail.addProperty("omen", omen);
        detail.addProperty("waves", waves);
        return new JournalEvent(EventType.RAID_STARTED, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), "environment",
                "raid", Integer.toString(raidId), 1, new long[]{Positions.pack(x, y, z)}, detail.toString());
    }

    public static JournalEvent raidFinished(Stamp stamp, int raidId, String outcome, int waves, int x, int y, int z) {
        var detail = new JsonObject();
        detail.addProperty("raidId", raidId);
        detail.addProperty("outcome", outcome);
        detail.addProperty("waves", waves);
        return new JournalEvent(EventType.RAID_FINISHED, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), "environment",
                "raid", Integer.toString(raidId), 1, new long[]{Positions.pack(x, y, z)}, detail.toString());
    }

    public static JournalEvent structure(Stamp stamp, String playerRef, String structure, int chunkX, int chunkZ, int x, int y, int z) {
        return new JournalEvent(EventType.STRUCTURE, stamp.gameTime(), stamp.wallMillis(), stamp.dimension(), playerRef,
                structure, chunkX + "," + chunkZ, 1, new long[]{Positions.pack(x, y, z)}, null);
    }
}
