package dev.packsage.client;

import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenTextTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);

    private static List<String> boldRuns(Component component) {
        List<String> runs = new ArrayList<>();
        component.visit((style, text) -> {
            if (style.isBold() && !text.isEmpty()) runs.add(text);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return runs;
    }

    @Test
    void boldMarkersAreRemovedAndTextIsBold() {
        Component line = ScreenText.markdownLines("It makes **1 torch per craft**.").get(0);

        assertEquals("It makes 1 torch per craft.", line.getString());
        assertEquals(List.of("1 torch per craft"), boldRuns(line));
    }

    @Test
    void inlineCodeLosesBackticksAndGetsColour() {
        Component line = ScreenText.markdownLines("Use `minecraft:torch` now").get(0);

        assertEquals("Use minecraft:torch now", line.getString());
        List<String> coloured = new ArrayList<>();
        line.visit((style, text) -> {
            if (style.getColor() != null && !text.isEmpty()) coloured.add(text);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        assertEquals(List.of("minecraft:torch"), coloured);
    }

    @Test
    void listItemsBecomeBulletsAndHeadingsBecomeBold() {
        List<Component> lines = ScreenText.markdownLines("# Title\n- **1 coal OR 1 charcoal**\n* two\n  - nested");

        assertEquals(List.of("Title", "• 1 coal OR 1 charcoal", "• two", "  • nested"),
                lines.stream().map(Component::getString).toList());
        assertEquals(List.of("Title"), boldRuns(lines.get(0)));
        assertEquals(List.of("1 coal OR 1 charcoal"), boldRuns(lines.get(1)));
    }

    @Test
    void unbalancedOrUnknownMarkersStayVisible() {
        assertEquals("a **b and `c", ScreenText.markdownLines("a **b and `c").get(0).getString());
        assertEquals("2 * 3 ** x", ScreenText.markdownLines("2 * 3 ** x").get(0).getString());
        assertEquals("****", ScreenText.markdownLines("****").get(0).getString());
        assertEquals("#nospace", ScreenText.markdownLines("#nospace").get(0).getString());
        assertEquals(2, ScreenText.markdownLines("a\n\nb").size() - 1);
    }

    @Test
    void crlfLinesAreTreatedLikeLf() {
        List<Component> lines = ScreenText.markdownLines("# Title\r\n- item\r\nplain");

        assertEquals(List.of("Title", "• item", "plain"), lines.stream().map(Component::getString).toList());
    }

    private static List<String> dimRuns(Component component) {
        List<String> runs = new ArrayList<>();
        component.visit((style, text) -> {
            if (style.getColor() != null && style.getColor().getValue() == ScreenText.DIM_COLOR && !text.isEmpty()) runs.add(text);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return runs;
    }

    @Test
    void citationMarkersBecomeShortDimTags() {
        List<Component> lines = ScreenText.markdownLines("Torch [native:minecraft:torch] and "
                + "[jei:jei/1/1/minecraft:crafting/recipe-packsage:fixture_lantern-123] then [ftb_quests:1000000000000002] "
                + "[patchouli:fixture:book#fixture:book/intro#0] [config:cfg/x] [jei:other/handle].");

        assertEquals("Torch [game] and [recipe] then [quest] [book] [config] [jei].", lines.get(0).getString());
        assertEquals(List.of("[game]", "[recipe]", "[quest]", "[book]", "[config]", "[jei]"), dimRuns(lines.get(0)));
    }

    @Test
    void bracketedTextThatIsNotAPackSageProviderMarkerIsPreservedVerbatim() {
        String text = "Use [minecraft:torch] or [todo:fix] or [other:abc] or [Native:x]";

        assertEquals(text, ScreenText.markdownLines(text).get(0).getString());
        assertEquals(List.of(), dimRuns(ScreenText.markdownLines(text).get(0)));
    }

    @Test
    void citationMarkerIsOmittedWhenANavigationTargetCoversIt() {
        var target = new Evidence.NavigationTarget(SCOPE, "native", "minecraft:torch", "minecraft:torch");

        List<Component> lines = ScreenText.markdownLines("Makes 4 [native:minecraft:torch]. See [native:minecraft:coal].", List.of(target));

        assertEquals("Makes 4. See [game].", lines.get(0).getString());
    }

    @Test
    void nonMarkerBracketsAndLinksStayVisible() {
        assertEquals("a [note: see this] b [x](https://e.test) [https://e.test] [1]",
                ScreenText.markdownLines("a [note: see this] b [x](https://e.test) [https://e.test] [1]").get(0).getString());
    }

    @Test
    void tableRowsRenderWithoutRawPipesAndSeparatorRowsAreDropped() {
        List<Component> lines = ScreenText.markdownLines("Intro\n| Item | Count |\n|---|:---:|\n| **Torch** | 4 |\nAfter");

        assertEquals(List.of("Intro", "Item   Count", "Torch   4", "After"), lines.stream().map(Component::getString).toList());
        assertEquals("Item   Count", String.join("", boldRuns(lines.get(1))));
        assertEquals(List.of("Torch"), boldRuns(lines.get(2)));
    }

    @Test
    void pipesOutsideTablesAreLeftAlone() {
        assertEquals("a | b", ScreenText.markdownLines("a | b").get(0).getString());
        assertEquals("x -|- y", ScreenText.markdownLines("x -|- y").get(0).getString());
    }

    @Test
    void codeFenceLinesAreNotDrawnAndBodyIsPlainColouredText() {
        List<Component> lines = ScreenText.markdownLines("Before\n```json\n{\"a\": **1**}\n\n`x`\n```\nAfter **b**");

        assertEquals(List.of("Before", "{\"a\": **1**}", "", "`x`", "After b"), lines.stream().map(Component::getString).toList());
        List<String> coloured = new ArrayList<>();
        lines.get(1).visit((style, text) -> {
            if (style.getColor() != null && style.getColor().getValue() == ScreenText.CODE_COLOR && !text.isEmpty()) coloured.add(text);
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        assertEquals(List.of("{\"a\": **1**}"), coloured);
    }

    @Test
    void unclosedFenceKeepsTheRestAsCode() {
        List<Component> lines = ScreenText.markdownLines("```\nline one\nline two");

        assertEquals(List.of("line one", "line two"), lines.stream().map(Component::getString).toList());
    }

    @Test
    void contextSummaryIsCompact() {
        String json = "{\"requestId\":\"r\",\"dimension\":\"minecraft:overworld\","
                + "\"position\":\"BlockPos{x=39, y=63, z=24}\",\"biome\":\"minecraft:taiga\",\"time\":\"4016\"}";
        ChatContracts.Screenshot shot = new ChatContracts.Screenshot("asset", "image/png", 1, java.time.Instant.EPOCH);

        assertEquals("Context: overworld · x39 y63 z24 · taiga · screenshot attached",
                ScreenText.contextSummary(new ChatContracts.Context(json, shot, SCOPE)));
        assertEquals("Context: overworld · x39 y63 z24 · taiga · no screenshot",
                ScreenText.contextSummary(new ChatContracts.Context(json, null, SCOPE)));
    }

    @Test
    void contextSummaryDegradesGracefully() {
        assertEquals("Context: taiga · no screenshot", ScreenText.contextSummary(
                new ChatContracts.Context("{\"biome\":\"minecraft:taiga\"}", null, SCOPE)));
        assertEquals("Context attached", ScreenText.contextSummary(new ChatContracts.Context("not json", null, SCOPE)));
        assertEquals("Context attached", ScreenText.contextSummary(new ChatContracts.Context("{}", null, SCOPE)));
        assertEquals("Context: screenshot attached", ScreenText.contextSummary(new ChatContracts.Context("{}",
                new ChatContracts.Screenshot("a", "image/png", 1, java.time.Instant.EPOCH), SCOPE)));
    }

    @Test
    void outcomeIsShownOnlyWhenNotCompleted() {
        assertEquals(null, ScreenText.outcomeLine(new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null)));
        assertEquals("Outcome: FAILED (boom)",
                ScreenText.outcomeLine(new ChatContracts.Outcome(ChatContracts.OutcomeStatus.FAILED, "boom")));
        assertEquals("Outcome: CANCELLED",
                ScreenText.outcomeLine(new ChatContracts.Outcome(ChatContracts.OutcomeStatus.CANCELLED, null)));
    }

    @Test
    void navigationLabelIsReadableAndHasNoHashSuffix() {
        var jei = new Evidence.NavigationTarget(SCOPE, "jei", "jei/4/9/minecraft:crafting/recipe-minecraft:torch-331",
                "jei/4/9/minecraft:crafting/recipe-minecraft:torch-331");
        assertEquals("Open recipe: Torch", ScreenText.navigationLabel(jei));

        var slashed = new Evidence.NavigationTarget(SCOPE, "jei", "jei/4/9/minecraft:crafting/recipe-create:crafting/foo_bar-3",
                "jei/4/9/minecraft:crafting/recipe-create:crafting/foo_bar-3");
        assertEquals("Open recipe: Foo Bar", ScreenText.navigationLabel(slashed));

        var noCanonical = new Evidence.NavigationTarget(SCOPE, "jei",
                "jei/4/9/minecraft:crafting/net.example.Recipe-1a2b3c-7", "jei/4/9/minecraft:crafting/net.example.Recipe-1a2b3c-7");
        assertEquals("Open recipe", ScreenText.navigationLabel(noCanonical));

        var category = new Evidence.NavigationTarget(SCOPE, "jei", "jei/4/9/minecraft:crafting/category",
                "jei/4/9/minecraft:crafting/category");
        assertEquals("Open recipe", ScreenText.navigationLabel(category));

        var ftb =new Evidence.NavigationTarget(SCOPE, "ftb", "abcd", "abcd");
        assertEquals("Open ftb: abcd", ScreenText.navigationLabel(ftb));

        var web = new Evidence.NavigationTarget(SCOPE, "native", "minecraft:torch", "minecraft:torch");
        assertEquals("Open native: minecraft:torch", ScreenText.navigationLabel(web));

        var patchouli = new Evidence.NavigationTarget(SCOPE, "patchouli", "fixture:book#fixture:book/intro#0",
                "fixture:book#fixture:book/intro#0");
        assertTrue(ScreenText.navigationLabel(patchouli).startsWith("Open patchouli: "));
    }
}
