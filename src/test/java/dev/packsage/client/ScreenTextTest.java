package dev.packsage.client;

import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenTextTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "en_us", "team", "runtime", 1);

    @Test
    void nativeMarkdownCitationsKeepTheirDescriptionsAndRecordedSources() {
        List<Evidence.Source> sources = List.of(
                new Evidence.Source(SCOPE, "native", "1", "inventory:observation:hotbar:0", 1,
                        java.time.Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                        Evidence.Authority.LOADED, List.of("player-inventory", "hotbar:0")),
                new Evidence.Source(SCOPE, "native", "1", "minecraft:oak_planks", 1,
                        java.time.Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL,
                        Evidence.Authority.LOADED, List.of("client-recipe-manager")),
                new Evidence.Source(SCOPE, "native", "1", "minecraft:stick", 1,
                        java.time.Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL,
                        Evidence.Authority.LOADED, List.of("client-recipe-manager")));

        var spans = ScreenText.inlineLines("[Coal and wood in your inventory](native:inventory:observation:hotbar:0)"
                + " · [Planks recipe](native:minecraft:oak_planks) · [Sticks recipe](native:minecraft:stick)",
                List.of(), List.of(), sources).getFirst().spans();

        assertEquals(List.of("Coal and wood in your inventory", "Planks recipe", "Sticks recipe"),
                spans.stream().filter(span -> span.source() != null).map(ScreenText.InlineSpan::text).toList());
        assertEquals(sources, spans.stream().map(ScreenText.InlineSpan::source).filter(java.util.Objects::nonNull).toList());
        assertFalse(spans.stream().anyMatch(span -> span.text().equals("[game]")));
    }

    @Test
    void recordedSourcesBindGroupedAndBareCitationsWithoutCreatingUnvalidatedNavigation() {
        var inventory = new Evidence.Source(SCOPE, "native", "native", "inventory:observation:hotbar:0", 1,
                java.time.Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE,
                Evidence.Authority.LOADED, List.of("player-inventory"));
        var recipe = new Evidence.Source(SCOPE, "native", "native", "minecraft:stick", 1,
                java.time.Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL,
                Evidence.Authority.LOADED, List.of("client-recipe-manager"));
        String grouped = "\uE200cite\uE202native:inventory:observation:hotbar:0\uE202native:minecraft:stick\uE201";
        var spans = ScreenText.inlineLines(grouped + " [native:minecraft:stick] [native:missing]",
                List.of(), List.of(), List.of(inventory, recipe)).getFirst().spans();
        assertEquals(List.of("Inventory", "Stick", "Stick"), spans.stream().filter(span -> span.source() != null)
                .map(ScreenText.InlineSpan::text).toList());
        assertEquals(List.of(inventory, recipe, recipe), spans.stream().map(ScreenText.InlineSpan::source)
                .filter(java.util.Objects::nonNull).toList());
        assertTrue(spans.stream().noneMatch(span -> span.text().equals("[game]") || span.target() != null));
        var target = new Evidence.NavigationTarget(SCOPE, "native", "minecraft:stick", "exact-target");
        var navigable = ScreenText.inlineLines("[Sticks recipe](native:minecraft:stick)", List.of(target), List.of(),
                List.of(recipe)).getFirst().spans().getFirst();
        assertEquals(target, navigable.target());
        assertEquals(recipe, navigable.source());
        var unboundLink = ScreenText.inlineLines("[Useful recipe](native:minecraft:missing)", List.of(), List.of(),
                List.of(recipe)).getFirst().spans().getFirst();
        assertEquals("Useful recipe", unboundLink.text());
        assertEquals(null, unboundLink.target());
        assertEquals(null, unboundLink.source());
    }

    @Test
    void registeredLocalControlsAppearAtTheirMarkersWithoutOpaqueIdsOrASeparateCollection() {
        var target = new Evidence.NavigationTarget(SCOPE, "patchouli", "book#entry", "entry#1@lifecycle");
        var spans = ScreenText.inlineLines("Before [patchouli:book#entry] after.", List.of(target)).getFirst().spans();
        assertEquals("Before ", spans.getFirst().text());
        assertEquals("Guide", spans.get(1).text());
        assertEquals(target, spans.get(1).target());
        assertEquals(" after.", spans.getLast().text());
        assertTrue(ScreenText.inlineLines("No reference here.", List.of(target)).stream()
                .flatMap(line -> line.spans().stream()).noneMatch(span -> span.target() != null));
        assertTrue(ScreenText.inlineLines("[patchouli:unregistered]", List.of(target)).stream()
                .flatMap(line -> line.spans().stream()).noneMatch(span -> span.target() != null));
    }

    @Test
    void webReferencesKeepClaimTextAndInsertOnlyRegisteredSourcesInline() {
        var target = new Evidence.NavigationTarget(SCOPE, "web", "https://example.test/source", "https://example.test/source");
        var citation = new dev.packsage.model.ModelBackend.UrlCitation(0, 5, "Example", target.sourceHandle());
        var spans = ScreenText.inlineLines("Claim next.", List.of(target), List.of(citation)).getFirst().spans();
        assertEquals("Claim", spans.getFirst().text());
        assertEquals(null, spans.getFirst().target());
        assertEquals(target, spans.get(1).target());
        assertEquals("Example", spans.get(1).text());
        assertEquals(" next.", spans.getLast().text());
        assertTrue(ScreenText.inlineLines("[Unknown](https://unknown.test)", List.of(target)).stream()
                .flatMap(line -> line.spans().stream()).noneMatch(span -> span.target() != null));
    }

    @Test
    void inlineMarkdownClosesStylesAndLeavesCodeReferencesLiteral() {
        var target = new Evidence.NavigationTarget(SCOPE, "patchouli", "book#entry", "entry");
        var spans = ScreenText.inlineLines("**bold** normal `code` [patchouli:book#entry]", List.of(target)).getFirst().spans();
        assertEquals("bold", spans.getFirst().text());
        assertTrue(spans.getFirst().bold());
        assertTrue(spans.stream().anyMatch(span -> span.text().contains("normal") && !span.bold()));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("code") && span.code()));
        assertTrue(ScreenText.inlineLines("`[patchouli:book#entry]`", List.of(target)).stream()
                .flatMap(line -> line.spans().stream()).noneMatch(span -> span.target() != null));
    }

    @Test
    void evolutionLocalLinksAndPrivateCitationReferencesUseOnlyRegisteredTargets() {
        var quest = new Evidence.NavigationTarget(SCOPE, "ftb_quests", "4A2123FF92275F64", "4A2123FF92275F64");
        var guide = new Evidence.NavigationTarget(SCOPE, "patchouli", "fixture:book#entry", "fixture:book#entry");
        String privateCitation = "\uE200cite\uE202ftb_quests:4A2123FF92275F64\uE202patchouli:fixture:book#entry"
                + "\uE202ftb_quests:missing\uE201";
        String answer = "[**Punch A Tree** — `ftb_quests:4A2123FF92275F64`](ftb_quests:4A2123FF92275F64) "
                + privateCitation + " [search0](https://evil.test) ` [Fake](ftb_quests:4A2123FF92275F64) ` Claim";
        var web = new Evidence.NavigationTarget(SCOPE, "web", "https://example.test/source", "https://example.test/source");
        var citation = new dev.packsage.model.ModelBackend.UrlCitation(answer.indexOf("Claim"), answer.length(),
                "Example", web.sourceHandle());

        var spans = ScreenText.inlineLines(answer, List.of(quest, guide, web), List.of(citation)).getFirst().spans();
        assertEquals(quest, spans.stream().filter(span -> span.text().equals("Quest") && span.target() != null)
                .findFirst().orElseThrow().target());
        assertEquals(guide, spans.stream().filter(span -> span.text().equals("Guide") && span.target() != null)
                .findFirst().orElseThrow().target());
        assertEquals(2, spans.stream().filter(span -> span.target() == quest).count());
        assertTrue(spans.stream().anyMatch(span -> span.target() == quest && span.text().contains("Punch A Tree")));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("[quest]") && span.target() == null));
        assertTrue(spans.stream().anyMatch(span -> span.text().contains("search0") && span.target() == null));
        assertTrue(spans.stream().anyMatch(span -> span.code() && span.text().contains("[Fake](ftb_quests:")));
        assertTrue(spans.stream().anyMatch(span -> span.target() == web && span.text().equals("Example")));
        assertTrue(spans.stream().filter(span -> span.target() != null)
                .allMatch(span -> span.target() == quest || span.target() == guide || span.target() == web));
    }

    @Test
    void webReferenceOffsetsSurviveWindowsNewlinesAndHeadingPrefixes() {
        var target = new Evidence.NavigationTarget(SCOPE, "web", "https://example.test/source", "https://example.test/source");
        String answer = "First\r\n##   Claim";
        var citation = new dev.packsage.model.ModelBackend.UrlCitation(answer.indexOf("Claim"), answer.length(), "Example", target.sourceHandle());
        var lines = ScreenText.inlineLines(answer, List.of(target), List.of(citation));
        assertEquals("Claim", lines.get(1).spans().getFirst().text());
        assertEquals(target, lines.get(1).spans().getLast().target());
    }

    @Test
    void blockquotesCarryMetadataAndKeepCitationOffsetsInOriginalText() {
        var target = new Evidence.NavigationTarget(SCOPE, "web", "https://example.test/source", "https://example.test/source");
        String answer = "> Claim source";
        var citation = new dev.packsage.model.ModelBackend.UrlCitation(answer.indexOf("Claim"), answer.indexOf(" source"), "Example", target.sourceHandle());
        var line = ScreenText.inlineLines(answer, List.of(target), List.of(citation)).getFirst();

        assertTrue(line.quote());
        assertEquals("Claim", line.spans().getFirst().text());
        assertEquals("Example", line.spans().get(1).text());
        assertEquals(target, line.spans().get(1).target());
        assertEquals(" source", line.spans().getLast().text());
    }

    @Test
    void configMarkersResolveOnlyExactRegisteredSources() {
        var source = new Evidence.Source(SCOPE, "config", "fixture", "cfg/test", 1, java.time.Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        var line = ScreenText.inlineLines("A [config:cfg/test] B [config:cfg/other] C ` [config:cfg/test] `",
                List.of(), List.of(), List.of(source)).getFirst();

        assertEquals("Config", line.spans().get(1).text());
        assertEquals(source, line.spans().get(1).source());
        assertEquals("[config]", line.spans().get(3).text());
        assertEquals(null, line.spans().get(3).source());
        assertTrue(line.spans().stream().filter(ScreenText.InlineSpan::code).allMatch(span -> span.source() == null));
        var latest = new Evidence.Source(SCOPE, "config", "fixture", source.sourceHandle(), 1, java.time.Instant.EPOCH.plusSeconds(1),
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        assertEquals(latest, ScreenText.inlineLines("[config:cfg/test]", List.of(), List.of(), List.of(source, latest))
                .getFirst().spans().getFirst().source());
    }

    @Test
    void inlineTablesHideSeparatorRowsAndKeepHeaderEmphasis() {
        var lines = ScreenText.inlineLines("| Quest | Progress |\n| --- | --- |\n| Gather | 1/1 |", List.of());
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().spans().stream().allMatch(ScreenText.InlineSpan::bold));
        assertTrue(lines.getLast().spans().stream().map(ScreenText.InlineSpan::text).reduce("", String::concat).contains("1/1"));
        assertTrue(lines.stream().flatMap(line -> line.spans().stream()).noneMatch(span -> span.text().contains("|")));
    }

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
    void groupedGuideCitationsUseShortTagsWithoutInventingNavigationTargets() {
        String grouped = "\uE200cite\uE202artifacts:artifacts:umbrella#glider\uE202relics:relics:leafy_mantle#camouflage"
                + "\uE202modonomicon:fixture:book#entry\uE202guideme:fixture:guide#page"
                + "\uE202immersiveengineering:manual/fluid_pipes\uE202actuallyadditions:actuallyadditions:crystals\uE201";
        var spans = ScreenText.inlineLines(grouped + " [unknown:source]", List.of()).getFirst().spans();

        assertEquals("[guide][guide][guide][guide][manual][guide] [unknown:source]",
                spans.stream().map(ScreenText.InlineSpan::text).reduce("", String::concat));
        assertTrue(spans.stream().filter(span -> span.text().startsWith("[") && span.text().endsWith("]"))
                .allMatch(span -> span.target() == null));
        assertEquals(List.of("[guide]", "[guide]", "[guide]", "[guide]", "[manual]", "[guide]"),
                spans.stream().filter(span -> span.text().startsWith("[") && span.text().endsWith("]")
                        && !span.text().equals("[unknown:source]")).map(ScreenText.InlineSpan::text).toList());
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

        assertEquals("Context: overworld · 39, 63, 24 · taiga · screenshot attached",
                ScreenText.contextSummary(new ChatContracts.Context(json, shot, SCOPE)));
        assertEquals("Context: overworld · 39, 63, 24 · taiga · no screenshot",
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
    void gameplayContextTooltipShowsReadableCapturedFieldsWithoutRawPayloads() {
        String json = "{\"position\":\"BlockPos{x=38, y=63, z=19}\","
                + "\"dimension\":\"minecraft:overworld\",\"biome\":\"minecraft:taiga\","
                + "\"held\":\"item:minecraft:stone;count=64;name=Stone;components={custom_name=Stone}\","
                + "\"targeted\":\"block:minecraft:grass_block@MutableBlockPos{x=38, y=60, z=19}:Block{minecraft:grass_block}[snowy=false]\","
                + "\"focusKind\":\"block\"}";
        var screenshot = new ChatContracts.Screenshot("captured.png", "image/png", 123, java.time.Instant.EPOCH);

        List<ScreenText.TooltipField> fields = ScreenText.gameplayContextFields(new ChatContracts.Context(json, screenshot, SCOPE));

        assertEquals(List.of(new ScreenText.TooltipField("Coordinates", "38, 63, 19"),
                new ScreenText.TooltipField("Dimension", "Overworld"), new ScreenText.TooltipField("Biome", "Taiga"),
                new ScreenText.TooltipField("Held item", "Stone ×64"), new ScreenText.TooltipField("Looking at", "Grass Block"),
                new ScreenText.TooltipField("Target at", "38, 60, 19")), fields);
        String display = fields.toString();
        assertFalse(display.contains("components") || display.contains("snowy") || display.contains("minecraft:"));
        assertFalse(display.contains("captured.png"));
    }

    @Test
    void gameplayContextTooltipHidesEntityUuidAndDistinguishesMissAndUnavailable() {
        String entity = "{\"position\":\"BlockPos{x=1, y=2, z=3}\",\"dimension\":\"minecraft:the_nether\","
                + "\"biome\":\"minecraft:soul_sand_valley\",\"held\":\"unavailable\","
                + "\"targeted\":\"entity:minecraft:zombie@123e4567-e89b-12d3-a456-426614174000\",\"focusKind\":\"entity\"}";
        List<ScreenText.TooltipField> entityFields = ScreenText.gameplayContextFields(new ChatContracts.Context(entity, null, SCOPE));
        assertEquals(new ScreenText.TooltipField("Dimension", "Nether"), entityFields.get(1));
        assertEquals(new ScreenText.TooltipField("Held item", "None"), entityFields.get(3));
        assertEquals(new ScreenText.TooltipField("Looking at", "Zombie"), entityFields.get(4));
        assertFalse(entityFields.toString().contains("123e4567"));

        List<ScreenText.TooltipField> missed = ScreenText.gameplayContextFields(new ChatContracts.Context(
                "{\"focusKind\":\"miss\",\"held\":\"unavailable\"}", null, SCOPE));
        assertEquals(new ScreenText.TooltipField("Looking at", "None"), missed.get(4));
        List<ScreenText.TooltipField> unavailable = ScreenText.gameplayContextFields(new ChatContracts.Context(
                "{\"focusKind\":\"unavailable\"}", null, SCOPE));
        assertEquals(new ScreenText.TooltipField("Looking at", "Unknown"), unavailable.get(4));
        assertEquals(new ScreenText.TooltipField("Held item", "None"), unavailable.get(3));
    }

    @Test
    void gameplayContextTooltipDegradesMalformedDataToUnknown() {
        List<ScreenText.TooltipField> fields = ScreenText.gameplayContextFields(new ChatContracts.Context("not json", null, SCOPE));

        assertEquals(List.of(new ScreenText.TooltipField("Coordinates", "Unknown"),
                new ScreenText.TooltipField("Dimension", "Unknown"), new ScreenText.TooltipField("Biome", "Unknown"),
                new ScreenText.TooltipField("Held item", "Unknown"), new ScreenText.TooltipField("Looking at", "Unknown")), fields);
    }

    @Test
    void historyTooltipExplainsEstimateAndConfiguredVersusModelClampedLimits() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            var turn = new ChatContracts.Turn(new ChatContracts.RequestId("history"), "question", "answer", List.of(),
                    new ChatContracts.Context("{}", null, SCOPE), List.of(),
                    new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 0);
            var preview = new ChatContracts.HistoryPreview(new ChatContracts.HistorySelection(List.of(turn, turn, turn), 228),
                    12_000, 12_000, 272_000, 10);
            assertEquals(List.of(new ScreenText.TooltipField("Usage", "~228 / 12,000 (2%)"),
                    new ScreenText.TooltipField("Included", "6 messages (3 / 10 turns)"),
                    new ScreenText.TooltipField("Your limit", "12,000 tokens"),
                    new ScreenText.TooltipField("Model limit", "272,000 tokens")), ScreenText.historyContextFields(preview));

            var clamped = new ChatContracts.HistoryPreview(new ChatContracts.HistorySelection(List.of(), 2_720),
                    300_000, 272_000, 272_000, 10);
            assertEquals(List.of(new ScreenText.TooltipField("Usage", "~2,720 / 272,000 (1%)"),
                    new ScreenText.TooltipField("Included", "0 messages (0 / 10 turns)"),
                    new ScreenText.TooltipField("Your limit", "300,000 tokens"),
                    new ScreenText.TooltipField("Model limit", "272,000 tokens")), ScreenText.historyContextFields(clamped));

            var unknownModel = new ChatContracts.HistoryPreview(new ChatContracts.HistorySelection(List.of(), 0),
                    12_000, 12_000, null, 10);
            assertEquals("Unknown", ScreenText.historyContextFields(unknownModel).getLast().value());
        } finally {
            Locale.setDefault(previous);
        }
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
