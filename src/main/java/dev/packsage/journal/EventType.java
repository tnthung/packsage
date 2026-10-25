package dev.packsage.journal;

/** The recorded event types, with their stored and model-facing ids and whether consecutive events merge into runs. */
public enum EventType {
    PLACE("place", true),
    BREAK("break", true),
    DEPOSIT("deposit", true),
    WITHDRAW("withdraw", true),
    DEATH("death", false),
    KILL("kill", true),
    DIMENSION("dimension", false),
    ADVANCEMENT("advancement", false),
    PROFESSION("profession", false),
    CURED("cured", false),
    CONVERTED("converted", false),
    TRADE("trade", true),
    RAID_STARTED("raid_start", false),
    RAID_FINISHED("raid_end", false),
    STRUCTURE("structure", false),
    EXPLOSION("explosion", true);

    private final String id;
    private final boolean merges;

    EventType(String id, boolean merges) {
        this.id = id;
        this.merges = merges;
    }

    public String id() {
        return id;
    }

    public boolean merges() {
        return merges;
    }

    public static EventType byId(String id) {
        for (var type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown journal event type " + id);
    }
}
