package dev.packsage.knowledge.relics;

import dev.packsage.knowledge.Evidence;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class RelicsInstanceReader {
    public static final String VERSION_RANGE = "[0.12.0,0.13.0)";
    private static final String RELIC_ITEM = "it.hurts.sskirillss.relics.api.relics.IRelicItem";
    private static final int MAX_ABILITIES = 16;
    private static final int MAX_STATS_PER_ABILITY = 32;
    private static final int MAX_STATS = 128;
    private final boolean present;
    private final boolean compatible;
    private final String version;

    public RelicsInstanceReader(boolean present, boolean compatible, String version) {
        this.present = present;
        this.compatible = compatible;
        this.version = version;
    }

    public Evidence.JsonObject read(LivingEntity player, ItemStack stack) {
        if (!present || stack == null || stack.isEmpty()) return null;
        try {
            Class<?> relicType = Class.forName(RELIC_ITEM, false, RelicsInstanceReader.class.getClassLoader());
            if (!relicType.isInstance(stack.getItem())) return null;
            if (!compatible) return new Evidence.JsonObject(Map.of("coverage", new Evidence.JsonString("UNKNOWN"),
                    "apiVersion", new Evidence.JsonString(version == null ? "unknown" : version)));
            return project(player, stack, stack.getItem());
        } catch (ClassNotFoundException failure) {
            return null;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    Evidence.JsonObject project(LivingEntity player, ItemStack stack, Object relicItem) {
        if (!present || stack == null || stack.isEmpty() || relicItem == null) return null;
        if (!compatible) return new Evidence.JsonObject(Map.of("coverage", new Evidence.JsonString("UNKNOWN"),
                "apiVersion", new Evidence.JsonString(version == null ? "unknown" : version)));
        var result = new LinkedHashMap<String, Evidence.JsonValue>();
        result.put("apiVersion", new Evidence.JsonString(version == null ? "unknown" : version));
        boolean partial = false;
        try {
            Class<?> relicType = Class.forName(RELIC_ITEM, false, RelicsInstanceReader.class.getClassLoader());
            if (!relicType.isInstance(relicItem)) return null;
            var relicData = relicType.getMethod("getRelicData", LivingEntity.class, ItemStack.class)
                    .invoke(relicItem, player, stack.copy());
            if (relicData == null) return new Evidence.JsonObject(Map.of("coverage", new Evidence.JsonString("UNKNOWN"),
                    "apiVersion", new Evidence.JsonString(version == null ? "unknown" : version)));
            Object levelingData = relicData.getClass().getMethod("getLevelingData").invoke(relicData);
            if (levelingData == null) partial = true;
            else {
                for (String[] field : new String[][]{{"level", "getLevel"}, {"experience", "getExperience"},
                        {"rank", "getRank"}, {"points", "getPoints"}}) {
                    try {
                        if (!(levelingData.getClass().getMethod(field[1]).invoke(levelingData) instanceof Number number)
                                || number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                                || number instanceof Float floatValue && !Float.isFinite(floatValue)) partial = true;
                        else result.put(field[0], new Evidence.JsonNumber(new BigDecimal(number.toString())));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        partial = true;
                    }
                }
            }
            var abilities = new LinkedHashMap<String, Evidence.JsonValue>();
            Object abilitiesData = relicData.getClass().getMethod("getAbilitiesData").invoke(relicData);
            boolean abilitiesAvailable = false;
            if (!(abilitiesData.getClass().getMethod("getAbilityIDs").invoke(abilitiesData) instanceof Set<?> ids)) partial = true;
            else {
                abilitiesAvailable = true;
                if (ids.size() > MAX_ABILITIES) partial = true;
                int abilityCount = 0;
                int totalStats = 0;
                for (Object rawId : ids) {
                    if (abilityCount == MAX_ABILITIES) break;
                    abilityCount++;
                    if (!(rawId instanceof String abilityId) || abilityId.isBlank()) {
                        partial = true;
                        continue;
                    }
                    var ability = new LinkedHashMap<String, Evidence.JsonValue>();
                    Object abilityData;
                    try {
                        abilityData = abilitiesData.getClass().getMethod("getAbilityData", String.class).invoke(abilitiesData, abilityId);
                        if (abilityData == null) throw new IllegalStateException("Relics ability data is unavailable");
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        partial = true;
                        continue;
                    }
                    try {
                        if (!(abilityData.getClass().getMethod("getLevel").invoke(abilityData) instanceof Number points)
                                || points instanceof Double doublePoints && !Double.isFinite(doublePoints)
                                || points instanceof Float floatPoints && !Float.isFinite(floatPoints)) partial = true;
                        else ability.put("points", new Evidence.JsonNumber(new BigDecimal(points.toString())));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        partial = true;
                    }
                    var stats = new LinkedHashMap<String, Evidence.JsonValue>();
                    try {
                        Object template = abilityData.getClass().getMethod("getTemplate").invoke(abilityData);
                        if (!(template.getClass().getMethod("getStats").invoke(template) instanceof Map<?, ?> statTemplates)) partial = true;
                        else {
                            boolean statsAvailable = statTemplates.isEmpty();
                            if (statTemplates.size() > MAX_STATS_PER_ABILITY) partial = true;
                            int abilityStats = 0;
                            for (Object rawStatId : statTemplates.keySet()) {
                                if (abilityStats == MAX_STATS_PER_ABILITY || totalStats == MAX_STATS) {
                                    partial = true;
                                    break;
                                }
                                abilityStats++;
                                totalStats++;
                                if (!(rawStatId instanceof String statId) || statId.isBlank()) {
                                    partial = true;
                                    continue;
                                }
                                var stat = new LinkedHashMap<String, Evidence.JsonValue>();
                                Object statData;
                                try {
                                    statData = abilityData.getClass().getMethod("getStatData", String.class).invoke(abilityData, statId);
                                    if (statData == null) throw new IllegalStateException("Relics stat data is unavailable");
                                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                                    partial = true;
                                    continue;
                                }
                                try {
                                    if (!(statData.getClass().getMethod("getValue").invoke(statData) instanceof Number value)
                                            || value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                                            || value instanceof Float floatValue && !Float.isFinite(floatValue)) partial = true;
                                    else stat.put("value", new Evidence.JsonNumber(new BigDecimal(value.toString())));
                                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                                    partial = true;
                                }
                                try {
                                    if (!(statData.getClass().getMethod("getQuality").invoke(statData) instanceof Number quality)
                                            || quality instanceof Double doubleQuality && !Double.isFinite(doubleQuality)
                                            || quality instanceof Float floatQuality && !Float.isFinite(floatQuality)) partial = true;
                                    else stat.put("quality", new Evidence.JsonNumber(new BigDecimal(quality.toString())));
                                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                                    partial = true;
                                }
                                if (!stat.isEmpty()) stats.put(statId, new Evidence.JsonObject(stat));
                                if (!stat.isEmpty()) statsAvailable = true;
                            }
                            if (statsAvailable) ability.put("stats", new Evidence.JsonObject(stats));
                        }
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        partial = true;
                    }
                    abilities.put(abilityId, new Evidence.JsonObject(ability));
                }
            }
            if (abilitiesAvailable) result.put("abilities", new Evidence.JsonObject(abilities));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            partial = true;
        }
        result.put("coverage", new Evidence.JsonString(partial ? "PARTIAL" : "COMPLETE"));
        return new Evidence.JsonObject(result);
    }
}
