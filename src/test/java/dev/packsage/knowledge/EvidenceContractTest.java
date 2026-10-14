package dev.packsage.knowledge;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EvidenceContractTest {
    @Test
    void jsonEnvelopeRoundTripCopiesNestedCollectionsAndRetainsUnknownValues() {
        var mutable = new ArrayList<Evidence.JsonValue>();
        mutable.add(new Evidence.JsonString("input"));
        var payload = new Evidence.JsonObject(Map.of(
                "canonicalRecipeId", new Evidence.JsonNull(),
                "questId", new Evidence.JsonString("9223372036854775808"),
                "quantity", new Evidence.JsonNull(),
                "inputs", new Evidence.JsonArray(mutable),
                "ratio", new Evidence.JsonNumber(new BigDecimal("1.25"))));
        mutable.clear();

        var roundTrip = Evidence.deserializeObject(Evidence.serialize(payload));
        assertEquals(new Evidence.JsonNull(), roundTrip.values().get("canonicalRecipeId"));
        assertEquals("9223372036854775808", ((Evidence.JsonString) roundTrip.values().get("questId")).value());
        assertEquals(new Evidence.JsonNull(), roundTrip.values().get("quantity"));
        assertEquals(1, ((Evidence.JsonArray) roundTrip.values().get("inputs")).values().size());
    }

    @Test
    void scopeGenerationIsPartOfEquality() {
        var first = new Evidence.Scope("world", "resources", "player", "provider", 1);
        var stale = new Evidence.Scope("world", "resources", "player", "provider", 2);

        assertFalse(first.equals(stale));
    }

    @Test
    void recordsCopyTargetsAndKeepSourceScope() {
        var targets = new ArrayList<Evidence.NavigationTarget>();
        var scope = new Evidence.Scope("world", "resources", "player", "provider", 3);
        targets.add(new Evidence.NavigationTarget(scope, "jei", "recipe", "id"));
        var source = new Evidence.Source(scope, "jei", "version", "recipe", 3, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.VIEWER, List.of("mod"));
        var record = new Evidence.Record(source, Evidence.RecordKind.RECIPE, "Recipe",
                new Evidence.JsonObject(Map.of("quantity", new Evidence.JsonNull())), targets);
        targets.clear();

        assertEquals(1, record.targets().size());
        assertEquals(scope, record.source().scope());
    }
}
