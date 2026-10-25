package dev.packsage.world;

/**
 * One player statistic. {@code category} is required; {@code subject} is the block, item or entity id, or the counter
 * id for custom counters, such as {@code minecraft:walk_one_cm}; {@code value} is in {@code unit} and {@code raw} is
 * the game's stored integer.
 */
public record Statistic(String adapter, String category, String subject, String label, double value, StatisticUnit unit, long raw) {
    public Statistic {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("A statistic needs a category: " + adapter + " " + subject);
        }
    }
}
