package dev.packsage.knowledge.guide;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmersiveEngineeringGuideReaderTest {
    @Test
    void initializedManualExposesDistinctRecipeReferenceFormsWithEntryProvenance() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var name = new blusunrize.lib.manual.utils.ManualRecipeRef(
                net.minecraft.resources.ResourceLocation.parse("immersiveengineering:hammering"));
        var result = new blusunrize.lib.manual.utils.ManualRecipeRef(
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT));
        var waterPotion = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION);
        waterPotion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.WATER));
        var awkwardPotion = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION);
        awkwardPotion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.AWKWARD));
        var layout = new blusunrize.lib.manual.utils.ManualRecipeRef(new blusunrize.lib.manual.PositionedItemStack[] {
                new blusunrize.lib.manual.PositionedItemStack(java.util.List.of(waterPotion, awkwardPotion), 23, 41, "2")
        });
        var entry = manual.addEntry("immersiveengineering:manual/hammer", new blusunrize.lib.manual.ManualElementCrafting(
                new blusunrize.lib.manual.utils.ManualRecipeRef[][] {{name, result, layout}}));
        entry.addSpecial("hammer-anchor", 17);

        var fragment = new ImmersiveEngineeringGuideReader().readFragment("immersiveengineering:manual/hammer", null,
                64, new Toolbox.DocumentBudget());

        var serialized = Evidence.serialize(fragment.relationships());
        assertTrue(serialized.contains("immersiveengineering:hammering"));
        assertTrue(serialized.contains("minecraft:iron_ingot"));
        assertTrue(serialized.contains("immersiveengineering:manual/hammer"));
        assertTrue(serialized.contains("hammer-anchor"));
        assertTrue(serialized.contains("sourcePosition") && serialized.contains("17"));
        assertTrue(serialized.contains("23") && serialized.contains("41") && serialized.contains("2"));
        assertTrue(serialized.contains("minecraft:water") && serialized.contains("minecraft:awkward"));
        assertTrue(!serialized.contains("\"role\":\"input\"") && !serialized.contains("\"role\":\"output\""));
        assertTrue(name.isRecipeName() && result.isResult() && layout.isLayout());
        assertEquals(0, manual.forbiddenCalls());
    }

    @Test
    void layoutAlternativeContinuationPreservesEveryBoundedCellAlternative() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var items = java.util.List.of(net.minecraft.world.item.Items.IRON_INGOT,
                net.minecraft.world.item.Items.GOLD_INGOT, net.minecraft.world.item.Items.DIAMOND,
                net.minecraft.world.item.Items.EMERALD, net.minecraft.world.item.Items.COPPER_INGOT,
                net.minecraft.world.item.Items.REDSTONE, net.minecraft.world.item.Items.LAPIS_LAZULI,
                net.minecraft.world.item.Items.COAL, net.minecraft.world.item.Items.CHARCOAL,
                net.minecraft.world.item.Items.QUARTZ, net.minecraft.world.item.Items.AMETHYST_SHARD,
                net.minecraft.world.item.Items.PRISMARINE_SHARD, net.minecraft.world.item.Items.FLINT,
                net.minecraft.world.item.Items.STICK, net.minecraft.world.item.Items.OAK_PLANKS,
                net.minecraft.world.item.Items.GLASS, net.minecraft.world.item.Items.BUCKET,
                net.minecraft.world.item.Items.WATER_BUCKET, net.minecraft.world.item.Items.POTION,
                net.minecraft.world.item.Items.NETHER_STAR);
        var alternatives = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        for (var item : items) alternatives.add(new net.minecraft.world.item.ItemStack(item));
        var layout = new blusunrize.lib.manual.utils.ManualRecipeRef(new blusunrize.lib.manual.PositionedItemStack[] {
                new blusunrize.lib.manual.PositionedItemStack(alternatives, 7, 11, "3")
        });
        var entry = manual.addEntry("immersiveengineering:manual/layout-bounded",
                new blusunrize.lib.manual.ManualElementCrafting(
                        new blusunrize.lib.manual.utils.ManualRecipeRef[][] {{layout}}));
        entry.addSpecial("layout-anchor", 2);

        String cursor = null;
        String allFragments = "";
        var cursors = new java.util.HashSet<String>();
        int fragments = 0;
        do {
            var fragment = new ImmersiveEngineeringGuideReader().readFragment(
                    "immersiveengineering:manual/layout-bounded", cursor, 64, new Toolbox.DocumentBudget());
            allFragments += Evidence.serialize(fragment.relationships());
            cursor = fragment.nextPosition();
            fragments++;
            assertTrue(fragments <= 8, "layout alternatives must remain bounded and make progress");
            if (cursor != null) assertTrue(cursors.add(cursor), "layout cursor repeated");
        } while (cursor != null);

        assertTrue(allFragments.contains("layout-anchor"));
        assertTrue(allFragments.contains("\"x\":7") && allFragments.contains("\"y\":11")
                && allFragments.contains("\"amount\":\"3\""));
        for (var item : items) assertTrue(allFragments.contains(net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(item).toString()));
        assertEquals(0, manual.forbiddenCalls());
    }

    @Test
    void namedPotionLayoutAlternativesContinueWithinByteBoundAndRetainComponents() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var alternatives = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
        for (int index = 0; index < 20; index++) {
            var potion = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION);
            potion.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                    new net.minecraft.world.item.alchemy.PotionContents(index % 2 == 0
                            ? net.minecraft.world.item.alchemy.Potions.WATER
                            : net.minecraft.world.item.alchemy.Potions.AWKWARD));
            alternatives.add(potion);
        }
        var layout = new blusunrize.lib.manual.utils.ManualRecipeRef(new blusunrize.lib.manual.PositionedItemStack[] {
                new blusunrize.lib.manual.PositionedItemStack(alternatives, 13, 17, "1")
        });
        var entry = manual.addEntry("immersiveengineering:manual/potion-layout",
                new blusunrize.lib.manual.ManualElementCrafting(
                        new blusunrize.lib.manual.utils.ManualRecipeRef[][] {{layout}}));
        entry.addSpecial("potion-layout-anchor", 0);

        String cursor = null;
        String allFragments = "";
        var cursors = new java.util.HashSet<String>();
        int fragments = 0;
        do {
            var fragment = new ImmersiveEngineeringGuideReader().readFragment(
                    "immersiveengineering:manual/potion-layout", cursor, 64, new Toolbox.DocumentBudget());
            String serialized = Evidence.serialize(fragment.relationships());
            assertTrue(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8 * 1024);
            allFragments += serialized;
            cursor = fragment.nextPosition();
            fragments++;
            assertTrue(fragments <= 8, "named potion alternatives must advance within the byte bound");
            if (cursor != null) assertTrue(cursors.add(cursor), "named potion layout cursor repeated");
        } while (cursor != null);

        assertEquals(20, allFragments.split("\"itemId\":\"minecraft:potion\"", -1).length - 1);
        assertEquals(20, allFragments.split("minecraft:potion_contents", -1).length - 1);
        assertTrue(allFragments.contains("potion-layout-anchor"));
        assertTrue(allFragments.contains("\"x\":13") && allFragments.contains("\"y\":17")
                && allFragments.contains("\"amount\":\"1\""));
    }

    @Test
    void oversizedLayoutAmountIsPartialAndCannotRepeatItsCursor() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var layout = new blusunrize.lib.manual.utils.ManualRecipeRef(new blusunrize.lib.manual.PositionedItemStack[] {
                new blusunrize.lib.manual.PositionedItemStack(java.util.List.of(
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT)), 0, 0, "x".repeat(9000))
        });
        var entry = manual.addEntry("immersiveengineering:manual/oversized-layout",
                new blusunrize.lib.manual.ManualElementCrafting(
                        new blusunrize.lib.manual.utils.ManualRecipeRef[][] {{layout}}));
        entry.addSpecial("oversized-layout-anchor", 0);

        var first = new ImmersiveEngineeringGuideReader().readFragment(
                "immersiveengineering:manual/oversized-layout", null, 64, new Toolbox.DocumentBudget());
        assertTrue(Evidence.serialize(first.relationships()).contains("PARTIAL"));
        var cursor = first.nextPosition();
        if (cursor != null) {
            var next = new ImmersiveEngineeringGuideReader().readFragment(
                    "immersiveengineering:manual/oversized-layout", cursor, 64, new Toolbox.DocumentBudget());
            assertNull(next.nextPosition(), "an indivisible oversized amount must be skipped as partial, not retried forever");
        }
    }

    @Test
    void oversizedLayoutCellDoesNotHideFollowingValidCells() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var layout = new blusunrize.lib.manual.utils.ManualRecipeRef(new blusunrize.lib.manual.PositionedItemStack[] {
                new blusunrize.lib.manual.PositionedItemStack(java.util.List.of(
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT)), 0, 0, "x".repeat(9000)),
                new blusunrize.lib.manual.PositionedItemStack(java.util.List.of(
                        new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT)), 3, 5, "2")
        });
        var entry = manual.addEntry("immersiveengineering:manual/layout-oversized-cell",
                new blusunrize.lib.manual.ManualElementCrafting(
                        new blusunrize.lib.manual.utils.ManualRecipeRef[][] {{layout}}));
        entry.addSpecial("oversized-cell-anchor", 0);

        var fragment = new ImmersiveEngineeringGuideReader().readFragment(
                "immersiveengineering:manual/layout-oversized-cell", null, 64, new Toolbox.DocumentBudget());
        var serialized = Evidence.serialize(fragment.relationships());

        assertTrue(serialized.contains("PARTIAL"));
        assertTrue(serialized.contains("minecraft:gold_ingot"));
        assertTrue(serialized.contains("\"x\":3") && serialized.contains("\"y\":5")
                && serialized.contains("\"amount\":\"2\""));
    }

    @Test
    void escapedMaximumAnchorKeepsAllRowsWithinPerFragmentByteLimit() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var rows = new blusunrize.lib.manual.utils.ManualRecipeRef[16][];
        for (int row = 0; row < rows.length; row++) {
            rows[row] = new blusunrize.lib.manual.utils.ManualRecipeRef[] {
                    new blusunrize.lib.manual.utils.ManualRecipeRef(net.minecraft.resources.ResourceLocation.parse(
                            "immersiveengineering:quoted_anchor_recipe_" + row))
            };
        }
        var entry = manual.addEntry("immersiveengineering:manual/quoted-anchor",
                new blusunrize.lib.manual.ManualElementCrafting(rows));
        entry.addSpecial("\"".repeat(256), 0);

        String cursor = null;
        String allFragments = "";
        var cursors = new java.util.HashSet<String>();
        int fragments = 0;
        do {
            var fragment = new ImmersiveEngineeringGuideReader().readFragment(
                    "immersiveengineering:manual/quoted-anchor", cursor, 64, new Toolbox.DocumentBudget());
            String serialized = Evidence.serialize(fragment.relationships());
            assertTrue(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8 * 1024);
            allFragments += serialized;
            cursor = fragment.nextPosition();
            fragments++;
            assertTrue(fragments <= 16, "escaped provenance must advance by source row");
            if (cursor != null) assertTrue(cursors.add(cursor), "escaped-anchor cursor repeated");
        } while (cursor != null);
        for (int row = 0; row < rows.length; row++) {
            assertTrue(allFragments.contains("quoted_anchor_recipe_" + row));
        }
    }

    @Test
    void freshBudgetResumesAfterAnEmptyRecipeRowAndOpaqueSpecialBoundary() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var emptyRowEntry = manual.addEntry("immersiveengineering:manual/empty-row-boundary",
                new blusunrize.lib.manual.ManualElementCrafting(new blusunrize.lib.manual.utils.ManualRecipeRef[][] {
                        {}, {new blusunrize.lib.manual.utils.ManualRecipeRef(
                                net.minecraft.resources.ResourceLocation.parse("immersiveengineering:after_empty_row"))}
                }));
        emptyRowEntry.addSpecial("empty-row", 0);
        var exhaustedAtEmptyRow = new Toolbox.DocumentBudget();
        for (int value = 0; value < 2044; value++) assertTrue(exhaustedAtEmptyRow.value());
        var emptyRowFirst = new ImmersiveEngineeringGuideReader().readFragment(
                "immersiveengineering:manual/empty-row-boundary", null, 64, exhaustedAtEmptyRow);
        assertTrue(emptyRowFirst.nextPosition() != null);
        var emptyRowNext = new ImmersiveEngineeringGuideReader().readFragment(
                "immersiveengineering:manual/empty-row-boundary", emptyRowFirst.nextPosition(), 64,
                new Toolbox.DocumentBudget());
        assertTrue(Evidence.serialize(emptyRowNext.relationships()).contains("immersiveengineering:after_empty_row"));
        assertNull(emptyRowNext.nextPosition());

        manual.reset();
        var opaqueEntry = manual.addEntry("immersiveengineering:manual/opaque-boundary", null);
        opaqueEntry.setSpecials(java.util.List.of(
                new blusunrize.lib.manual.ManualEntry.SpecialElementData("opaque-before", 0,
                        new blusunrize.lib.manual.SpecialManualElement()),
                new blusunrize.lib.manual.ManualEntry.SpecialElementData("opaque-at-boundary", 1,
                        new blusunrize.lib.manual.SpecialManualElement()),
                new blusunrize.lib.manual.ManualEntry.SpecialElementData("valid-after", 2,
                        new blusunrize.lib.manual.ManualElementCrafting(new blusunrize.lib.manual.utils.ManualRecipeRef[][] {{
                                new blusunrize.lib.manual.utils.ManualRecipeRef(
                                        net.minecraft.resources.ResourceLocation.parse("immersiveengineering:after_opaque"))
                        }}))));
        var exhaustedAtOpaque = new Toolbox.DocumentBudget();
        for (int value = 0; value < 2044; value++) assertTrue(exhaustedAtOpaque.value());
        var opaqueFirst = new ImmersiveEngineeringGuideReader().readFragment(
                "immersiveengineering:manual/opaque-boundary", null, 64, exhaustedAtOpaque);
        assertTrue(opaqueFirst.nextPosition() != null);
        var opaqueNext = new ImmersiveEngineeringGuideReader().readFragment(
                "immersiveengineering:manual/opaque-boundary", opaqueFirst.nextPosition(), 64,
                new Toolbox.DocumentBudget());
        assertTrue(Evidence.serialize(opaqueNext.relationships()).contains("immersiveengineering:after_opaque"));
        assertNull(opaqueNext.nextPosition());
    }

    @Test
    void uninitializedContinuationAfterCompletedTextIsTerminalPartial() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        manual.setInitialized(false);
        manual.addEntry("immersiveengineering:manual/uninitialized-completed-text", null);

        var fragment = new ImmersiveEngineeringGuideReader().readFragment(
                "immersiveengineering:manual/uninitialized-completed-text", "ie1|0.0.0.0.0|1|done", 64,
                new Toolbox.DocumentBudget());

        assertEquals(Evidence.Coverage.PARTIAL, fragment.coverage());
        assertTrue(Evidence.serialize(fragment.relationships()).contains("UNAVAILABLE"));
        assertNull(fragment.nextPosition());
    }

    @Test
    void initializedManualProjectsBlueprintBottlingAndMixerSelectorsWithoutRecipeMatching() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var water = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION);
        water.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.WATER));
        var awkward = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION);
        awkward.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.AWKWARD));
        var refs = new blusunrize.lib.manual.utils.ManualRecipeRef[] {
                new blusunrize.lib.manual.utils.ManualRecipeRef(net.minecraft.resources.ResourceLocation.parse("immersiveengineering:blueprint")),
                new blusunrize.lib.manual.utils.ManualRecipeRef(water),
                new blusunrize.lib.manual.utils.ManualRecipeRef(awkward)
        };
        var blueprint = manual.addEntry("immersiveengineering:manual/blueprint",
                new blusunrize.immersiveengineering.client.manual.ManualElementBlueprint(refs));
        blueprint.addSpecial("blueprint-anchor", 0);
        var mixer = manual.addEntry("immersiveengineering:manual/mixer",
                new blusunrize.immersiveengineering.client.manual.ManualElementMixer(new net.minecraft.world.level.material.Fluid[] {
                        net.minecraft.world.level.material.Fluids.WATER
                }));
        mixer.addSpecial("mixer-anchor", -8);
        var bottling = manual.addEntry("immersiveengineering:manual/bottling",
                new blusunrize.immersiveengineering.client.manual.ManualElementBottling(refs));
        bottling.addSpecial("bottling-anchor", 4);
        var fragment = new ImmersiveEngineeringGuideReader().readFragment("immersiveengineering:manual/blueprint", null,
                64, new Toolbox.DocumentBudget());
        var serialized = Evidence.serialize(fragment.relationships());
        var bottlingFragment = new ImmersiveEngineeringGuideReader().readFragment("immersiveengineering:manual/bottling", null,
                64, new Toolbox.DocumentBudget());
        var mixerFragment = new ImmersiveEngineeringGuideReader().readFragment("immersiveengineering:manual/mixer", null,
                64, new Toolbox.DocumentBudget());

        assertTrue(serialized.contains("blueprint-anchor"));
        assertTrue(serialized.contains("minecraft:water"));
        assertTrue(serialized.contains("minecraft:awkward"));
        assertTrue(Evidence.serialize(bottlingFragment.relationships()).contains("bottling-anchor"));
        assertTrue(Evidence.serialize(mixerFragment.relationships()).contains("minecraft:water"));
        assertTrue(Evidence.serialize(mixerFragment.relationships()).contains("mixer-anchor"));
        assertEquals(0, manual.recipeMatchCalls());
        assertEquals(0, manual.forbiddenCalls());
    }

    @Test
    void uninitializedManualAndLazyElementAccessorsAreNeverUsed() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        manual.setInitialized(false);
        var entry = manual.addEntry("immersiveengineering:manual/lazy", null);
        entry.addLazySpecial("lazy-anchor", 0);

        var fragment = new ImmersiveEngineeringGuideReader().readFragment("immersiveengineering:manual/lazy", null,
                32, new Toolbox.DocumentBudget());

        assertTrue(Evidence.serialize(fragment.relationships()).contains("recipes"));
        assertTrue(Evidence.serialize(fragment.relationships()).contains("UNAVAILABLE")
                || Evidence.serialize(fragment.relationships()).contains("PARTIAL"));
        assertEquals(0, entry.specialDataCalls());
        assertEquals(0, entry.elementAccessorCalls());
        assertEquals(0, manual.forbiddenCalls());
    }

    @Test
    void continuationUsesCurrentManualDeclarationsAndDoesNotRetainEarlierElementState() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var rows = new blusunrize.lib.manual.utils.ManualRecipeRef[24][];
        for (int row = 0; row < rows.length; row++) {
            rows[row] = new blusunrize.lib.manual.utils.ManualRecipeRef[] {
                    new blusunrize.lib.manual.utils.ManualRecipeRef(net.minecraft.resources.ResourceLocation.parse(
                            "immersiveengineering:old_recipe_" + row))
            };
        }
        var entry = manual.addEntry("immersiveengineering:manual/reloaded",
                new blusunrize.lib.manual.ManualElementCrafting(rows));
        entry.addSpecial("anchor", 0);
        var reader = new ImmersiveEngineeringGuideReader();
        var first = reader.readFragment("immersiveengineering:manual/reloaded", null, 32, new Toolbox.DocumentBudget());
        assertTrue(first.nextPosition() != null);
        var changedRows = new blusunrize.lib.manual.utils.ManualRecipeRef[24][];
        for (int row = 0; row < changedRows.length; row++) {
            changedRows[row] = new blusunrize.lib.manual.utils.ManualRecipeRef[] {
                    new blusunrize.lib.manual.utils.ManualRecipeRef(net.minecraft.resources.ResourceLocation.parse(
                            "immersiveengineering:new_recipe_" + row))
            };
        }
        entry.setSpecials(java.util.List.of(new blusunrize.lib.manual.ManualEntry.SpecialElementData("new-anchor", 5,
                new blusunrize.lib.manual.ManualElementCrafting(changedRows))));

        var next = reader.readFragment("immersiveengineering:manual/reloaded", first.nextPosition(), 32,
                new Toolbox.DocumentBudget());
        var fresh = reader.readFragment("immersiveengineering:manual/reloaded", null, 32, new Toolbox.DocumentBudget());

        assertTrue(Evidence.serialize(next.relationships()).contains("immersiveengineering:new_recipe_"));
        assertTrue(Evidence.serialize(next.relationships()).contains("new-anchor"));
        assertTrue(!Evidence.serialize(next.relationships()).contains("immersiveengineering:old_recipe_"));
        assertTrue(Evidence.serialize(fresh.relationships()).contains("immersiveengineering:new_recipe_0"));
        assertTrue(Evidence.serialize(fresh.relationships()).contains("new-anchor"));
        assertTrue(!Evidence.serialize(fresh.relationships()).contains("immersiveengineering:old_recipe_"));
    }

    @Test
    void boundedRowsAdvanceAndUnknownNativeElementsStayOpaque() {
        var manual = blusunrize.immersiveengineering.api.ManualHelper.getManual();
        manual.reset();
        var rows = new blusunrize.lib.manual.utils.ManualRecipeRef[24][];
        for (int row = 0; row < rows.length; row++) {
            rows[row] = new blusunrize.lib.manual.utils.ManualRecipeRef[] {
                    new blusunrize.lib.manual.utils.ManualRecipeRef(net.minecraft.resources.ResourceLocation.parse(
                            "immersiveengineering:recipe_" + row))
            };
        }
        var entry = manual.addEntry("immersiveengineering:manual/bounded", new blusunrize.lib.manual.ManualElementCrafting(rows));
        entry.addSpecial("rows", 0);

        var first = new ImmersiveEngineeringGuideReader().readFragment("immersiveengineering:manual/bounded", null,
                64, new Toolbox.DocumentBudget());

        assertTrue(first.nextPosition() != null);
        assertTrue(Evidence.serialize(first.relationships()).contains("PARTIAL"));
        assertTrue(Evidence.serialize(first.relationships()).contains("recipe_"));
        var cursors = new java.util.HashSet<String>();
        String cursor = first.nextPosition();
        String allRows = Evidence.serialize(first.relationships());
        int fragments = 1;
        while (cursor != null && fragments < 8) {
            assertTrue(cursors.add(cursor), "relationship/text cursor repeated");
            var fragment = new ImmersiveEngineeringGuideReader().readFragment("immersiveengineering:manual/bounded", cursor,
                    64, new Toolbox.DocumentBudget());
            allRows += Evidence.serialize(fragment.relationships());
            cursor = fragment.nextPosition();
            fragments++;
        }
        assertNull(cursor, "bounded declaration traversal must reach a terminal cursor");
        for (int row = 0; row < rows.length; row++) assertTrue(allRows.contains("recipe_" + row));

        manual.reset();
        var opaque = manual.addEntry("immersiveengineering:manual/custom", new blusunrize.lib.manual.SpecialManualElement());
        opaque.addSpecial("custom", 3);
        var unknown = new ImmersiveEngineeringGuideReader().readFragment("immersiveengineering:manual/custom", null,
                64, new Toolbox.DocumentBudget());
        var serialized = Evidence.serialize(unknown.relationships());
        assertTrue(serialized.contains("OPAQUE") || serialized.contains("PARTIAL"));
        assertTrue(serialized.contains("detail"));
    }

    @Test
    void textContinuationIncludesOnlyTheBoundedSearchOverlap() {
        var text = "0123456789";
        var first = ImmersiveEngineeringGuideReader.readText(new StringReader(text), "immersiveengineering:manual/test",
                0, 6, 2, new Toolbox.DocumentBudget());
        var next = ImmersiveEngineeringGuideReader.readText(new StringReader(text), "immersiveengineering:manual/test",
                6, 6, 2, new Toolbox.DocumentBudget());

        assertEquals("012345", first.text());
        assertEquals(0, first.overlapChars());
        assertEquals("6", first.nextPosition());
        assertEquals("456789", next.text());
        assertEquals(2, next.overlapChars());
        assertNull(next.nextPosition());
        assertEquals(Evidence.Coverage.COMPLETE, next.coverage());
    }

    @Test
    void textContinuationStopsBeforeCopyingPastTheSharedByteBudget() {
        var budget = new Toolbox.DocumentBudget();
        budget.bytes(96 * 1024 - 12);

        var fragment = ImmersiveEngineeringGuideReader.readText(new StringReader("abcdef"), "manual/test", 0,
                6, 2, budget);

        assertEquals("ab", fragment.text());
        assertEquals("2", fragment.nextPosition());
        assertEquals(Evidence.Coverage.PARTIAL, fragment.coverage());
    }

    @Test
    void insufficientTextBudgetMarksExhaustionForCursorResumption() {
        var budget = new Toolbox.DocumentBudget();
        budget.bytes(96 * 1024 - 4);

        var failure = assertThrows(Toolbox.Failure.class, () -> ImmersiveEngineeringGuideReader.readText(
                new StringReader("text"), "manual/test", 0, 1, 0, budget));

        assertEquals(Toolbox.Code.EXTRACTION_FAILED, failure.code());
        assertTrue(budget.exhausted());
    }

    @Test
    void continuationShortensOverlapWhenTheFragmentLimitRequiresIt() {
        var fragment = ImmersiveEngineeringGuideReader.readText(new StringReader("0123456789"), "manual/test", 5,
                2, 2, new Toolbox.DocumentBudget());

        assertEquals("45", fragment.text());
        assertEquals(1, fragment.overlapChars());
        assertEquals("6", fragment.nextPosition());
        assertEquals(Evidence.Coverage.PARTIAL, fragment.coverage());
    }

    @Test
    void seekBoundReturnsTerminalPartialEvidenceWhenTextContinuesPastIt() {
        var text = "a".repeat(16 * 1024 + 1);
        var fragment = ImmersiveEngineeringGuideReader.readText(new StringReader(text), "manual/test",
                16 * 1024 - 2, 4, 1, new Toolbox.DocumentBudget());

        assertEquals(3, fragment.text().length());
        assertEquals(1, fragment.overlapChars());
        assertNull(fragment.nextPosition());
        assertEquals(Evidence.Coverage.PARTIAL, fragment.coverage());
        assertEquals("manual text exceeded the bounded seek limit", fragment.detail());
    }

    @Test
    void unsupportedManualFormattingIsReportedAsPartial() {
        var fragment = ImmersiveEngineeringGuideReader.readText(new StringReader("<br>line"), "manual/test",
                0, 16, 2, new Toolbox.DocumentBudget());

        assertEquals("<br>line", fragment.text());
        assertEquals(Evidence.Coverage.PARTIAL, fragment.coverage());
        assertEquals("manual formatting tokens are preserved without expansion", fragment.detail());
    }
}
