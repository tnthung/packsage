package dev.packsage.world.minecraft;

import dev.packsage.knowledge.Evidence;
import dev.packsage.world.BlockPlace;
import dev.packsage.world.EntityInspector;
import dev.packsage.world.Section;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Vanilla mob facts, including the villager state the game uses to decide iron-golem spawning. Reads never mutate. */
public final class MinecraftEntities implements EntityInspector {
    /** Villager keeps today's restock count private with no accessor; null when the field cannot be read. */
    private static final Field RESTOCKS_TODAY = restocksField();
    static final int MAX_MODIFIERS = 64;

    @Override
    public String adapterId() {
        return MinecraftInventories.ADAPTER;
    }

    @Override
    public Section inspectEntity(Entity entity) {
        var values = new LinkedHashMap<String, Evidence.JsonValue>();
        var notes = new ArrayList<String>();
        if (entity instanceof LivingEntity living) {
            values.put("health", decimal(living.getHealth()));
            values.put("maxHealth", decimal(living.getMaxHealth()));
            var effects = new ArrayList<Evidence.JsonValue>();
            for (var effect : living.getActiveEffects()) {
                effects.add(new Evidence.JsonObject(Map.of(
                        "id", new Evidence.JsonString(effect.getEffect().getRegisteredName()),
                        "amplifier", number(effect.getAmplifier()),
                        "durationTicks", number(effect.getDuration()))));
            }
            values.put("effects", new Evidence.JsonArray(effects));
            var attributes = attributes(living.getAttributes());
            values.put("attributes", attributes);
            if (attributes.values().stream().map(Evidence.JsonObject.class::cast)
                    .anyMatch(attribute -> attribute.values().containsKey("modifiersStatus"))) {
                notes.add("Attribute modifiers are bounded to " + MAX_MODIFIERS + " per attribute");
            }
            if (entity instanceof AbstractHorse horse) {
                values.put("mount", mount(horse.getAttributeValue(Attributes.JUMP_STRENGTH),
                        horse.getAttributeValue(Attributes.MOVEMENT_SPEED)));
            }
        }
        var customName = entity.getCustomName();
        if (customName != null) values.put("customName", new Evidence.JsonString(customName.getString()));
        if (entity instanceof AgeableMob ageable) values.put("age", number(ageable.getAge()));
        if (entity instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null) {
            values.put("tamedOwner", new Evidence.JsonString(ownable.getOwnerUUID().toString()));
        }
        if (entity instanceof Leashable leashable) values.put("leashed", new Evidence.JsonBoolean(leashable.isLeashed()));
        if (!entity.getPassengers().isEmpty()) {
            var passengers = new ArrayList<Evidence.JsonValue>();
            for (var passenger : entity.getPassengers()) {
                passengers.add(new Evidence.JsonObject(Map.of(
                        "type", new Evidence.JsonString(BuiltInRegistries.ENTITY_TYPE.getKey(passenger.getType()).toString()),
                        "uuid", new Evidence.JsonString(passenger.getStringUUID()))));
            }
            values.put("passengers", new Evidence.JsonArray(passengers));
        }
        if (entity instanceof Villager villager) villager(villager, values);
        if (entity instanceof AbstractVillager merchant) {
            var server = merchant.level().getServer();
            if (!MinecraftTrades.facts(merchant, server == null ? null : MinecraftInventories.host(server), values)) {
                notes.add("Trade offers are bounded to " + MinecraftTrades.MAX_OFFERS);
            }
        }
        if (entity instanceof IronGolem golem) values.put("playerCreated", new Evidence.JsonBoolean(golem.isPlayerCreated()));
        return values.isEmpty() ? null : new Section(new Evidence.JsonObject(values),
                notes.isEmpty() ? Evidence.Coverage.COMPLETE : Evidence.Coverage.PARTIAL,
                notes.isEmpty() ? null : String.join("; ", notes));
    }

    private static void villager(Villager villager, Map<String, Evidence.JsonValue> values) {
        var data = villager.getVillagerData();
        values.put("profession", new Evidence.JsonString(BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()).toString()));
        values.put("level", number(data.getLevel()));
        values.put("xp", number(villager.getVillagerXp()));
        var restocks = restocksToday(villager);
        if (restocks != null) values.put("restocksToday", number(restocks));
        var brain = villager.getBrain();
        memory(brain, MemoryModuleType.HOME).ifPresent(pos -> values.put("home", place(pos)));
        memory(brain, MemoryModuleType.JOB_SITE).ifPresent(pos -> values.put("jobSite", place(pos)));
        memory(brain, MemoryModuleType.MEETING_POINT).ifPresent(pos -> values.put("meetingPoint", place(pos)));
        values.put("timeUnits", new Evidence.JsonString("lastSlept, lastWorkedAtPoi and golemDetectedUntil are game times in ticks"
                + " (20 per second); compare them with the record's gameTime"));
        memory(brain, MemoryModuleType.LAST_SLEPT).ifPresent(time -> values.put("lastSlept", number(time)));
        memory(brain, MemoryModuleType.LAST_WORKED_AT_POI).ifPresent(time -> values.put("lastWorkedAtPoi", number(time)));
        // The memory is a flag that expires; report the game time it expires at (true when it never expires).
        if (memory(brain, MemoryModuleType.GOLEM_DETECTED_RECENTLY).isPresent()) {
            var timeToLive = brain.getTimeUntilExpiry(MemoryModuleType.GOLEM_DETECTED_RECENTLY);
            values.put("golemDetectedUntil", timeToLive == Long.MAX_VALUE ? new Evidence.JsonBoolean(true)
                    : number(villager.level().getGameTime() + timeToLive));
        }
        values.put("wantsToSpawnGolem", new Evidence.JsonBoolean(villager.wantsToSpawnGolem(villager.level().getGameTime())));
        var gossip = new LinkedHashMap<String, Long>();
        for (var entries : villager.getGossips().getGossipEntries().values()) {
            for (var entry : entries.object2IntEntrySet()) {
                gossip.merge(entry.getKey().id, (long) entry.getIntValue(), Long::sum);
            }
        }
        var totals = new LinkedHashMap<String, Evidence.JsonValue>();
        for (var type : GossipType.values()) {
            var total = gossip.get(type.id);
            if (total != null) totals.put(type.id, number(total));
        }
        values.put("gossip", new Evidence.JsonObject(totals));
    }

    /** Every attribute the mob has, with base, current value and modifiers, sorted by id. */
    static Evidence.JsonArray attributes(AttributeMap map) {
        var entries = new ArrayList<Evidence.JsonValue>();
        BuiltInRegistries.ATTRIBUTE.holders()
                .filter(map::hasAttribute)
                .sorted(Comparator.comparing(holder -> holder.key().location().toString()))
                .forEach(holder -> {
                    var instance = map.getInstance(holder);
                    var modifiers = new ArrayList<Evidence.JsonValue>();
                    instance.getModifiers().stream().sorted(Comparator.comparing(modifier -> modifier.id().toString()))
                            .limit(MAX_MODIFIERS).forEach(modifier -> modifiers.add(new Evidence.JsonObject(Map.of(
                                    "id", new Evidence.JsonString(modifier.id().toString()),
                                    "amount", decimal(modifier.amount()),
                                    "operation", new Evidence.JsonString(modifier.operation().getSerializedName())))));
                    var fields = new LinkedHashMap<String, Evidence.JsonValue>();
                    fields.put("id", new Evidence.JsonString(holder.key().location().toString()));
                    fields.put("base", decimal(instance.getBaseValue()));
                    fields.put("value", decimal(instance.getValue()));
                    fields.put("modifiers", new Evidence.JsonArray(modifiers));
                    if (instance.getModifiers().size() > MAX_MODIFIERS) fields.put("modifiersStatus", new Evidence.JsonString("bounded"));
                    entries.add(new Evidence.JsonObject(fields));
                });
        return new Evidence.JsonArray(entries);
    }

    /** Jump height and flat-ground speed in blocks for horse-like mobs, from their attributes. */
    static Evidence.JsonObject mount(double jumpStrength, double movementSpeed) {
        double height = -0.1817584952 * Math.pow(jumpStrength, 3) + 3.689713992 * Math.pow(jumpStrength, 2)
                + 2.128599134 * jumpStrength - 0.343930367;
        var fields = new LinkedHashMap<String, Evidence.JsonValue>();
        fields.put("jumpHeightBlocks", new Evidence.JsonNumber(BigDecimal.valueOf(height).setScale(2, RoundingMode.HALF_UP)));
        fields.put("speedBlocksPerSecond", new Evidence.JsonNumber(BigDecimal.valueOf(movementSpeed * 42.16).setScale(2, RoundingMode.HALF_UP)));
        fields.put("note", new Evidence.JsonString("approximate conversions from the jump_strength and movement_speed attributes"));
        return new Evidence.JsonObject(fields);
    }

    /** Empty when the memory is absent or not registered for this brain. */
    private static <U> Optional<U> memory(Brain<?> brain, MemoryModuleType<U> type) {
        var memory = brain.getMemoryInternal(type);
        return memory == null ? Optional.empty() : memory;
    }

    private static Evidence.JsonString place(GlobalPos pos) {
        return new Evidence.JsonString(new BlockPlace(pos.dimension().location().toString(),
                pos.pos().getX(), pos.pos().getY(), pos.pos().getZ()).text());
    }

    private static Integer restocksToday(Villager villager) {
        if (RESTOCKS_TODAY == null) return null;
        try {
            return RESTOCKS_TODAY.getInt(villager);
        } catch (IllegalAccessException exception) {
            return null;
        }
    }

    private static Field restocksField() {
        try {
            var field = Villager.class.getDeclaredField("numberOfRestocksToday");
            return field.getType() == int.class && field.trySetAccessible() ? field : null;
        } catch (NoSuchFieldException | SecurityException exception) {
            return null;
        }
    }

    private static Evidence.JsonNumber number(long value) {
        return new Evidence.JsonNumber(BigDecimal.valueOf(value));
    }

    private static Evidence.JsonValue decimal(double value) {
        return Double.isFinite(value) ? new Evidence.JsonNumber(BigDecimal.valueOf(value)) : new Evidence.JsonNull();
    }

    private static Evidence.JsonValue decimal(float value) {
        return Float.isFinite(value) ? new Evidence.JsonNumber(new BigDecimal(Float.toString(value))) : new Evidence.JsonNull();
    }
}
