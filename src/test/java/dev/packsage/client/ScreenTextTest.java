package dev.packsage.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.packsage.chat.ChatContracts;
import dev.packsage.knowledge.Evidence;
import dev.packsage.model.ModelBackend;
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
    void savedV1CitationsKeepTheirExactSourcesAndDestinations(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        var sources = new ArrayList<Evidence.Source>();
        var targets = new ArrayList<Evidence.NavigationTarget>();
        for (String provider : List.of("jei", "ftb_quests", "patchouli", "modonomicon", "guideme",
                "immersiveengineering", "actuallyadditions", "relics", "artifacts", "ponder", "curios")) {
            sources.add(new Evidence.Source(SCOPE, provider, "v1", "saved/record", 1, java.time.Instant.EPOCH,
                    Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of()));
            targets.add(new Evidence.NavigationTarget(SCOPE, provider, "saved/record", "saved/destination"));
        }
        var store = new dev.packsage.chat.ConversationStore(directory, Runnable::run);
        var world = new ChatContracts.WorldIdentity("world", "World");
        var conversation = store.newConversation(world).toCompletableFuture().join();
        String answer = targets.stream().map(target -> "[" + target.providerId() + ":saved/record]")
                .collect(java.util.stream.Collectors.joining(" "))
                + "\n\uE200cite\uE202jei:saved/record\uE202ftb_quests:saved/record\uE201";
        store.appendTurn(world, SCOPE, conversation.id(), new ChatContracts.Turn(new ChatContracts.RequestId("v1"),
                "Old question", answer, List.of(), new ChatContracts.Context("{}", null, SCOPE), sources, targets,
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();
        var saved = new dev.packsage.chat.ConversationStore(directory, Runnable::run)
                .openConversation(world, conversation.id()).toCompletableFuture().join().turns().getFirst();
        var lines = ScreenText.inlineLines(saved.answer(), saved.navigationTargets(), List.of(), saved.sources());
        assertEquals(sources, lines.getFirst().spans().stream().map(ScreenText.InlineSpan::source)
                .filter(java.util.Objects::nonNull).toList());
        assertEquals(targets, lines.getFirst().spans().stream().map(ScreenText.InlineSpan::target)
                .filter(java.util.Objects::nonNull).toList());
        assertEquals(targets.subList(0, 2), lines.get(1).spans().stream().map(ScreenText.InlineSpan::target)
                .filter(java.util.Objects::nonNull).toList());
        assertEquals(sources.subList(0, 2), lines.get(1).spans().stream().map(ScreenText.InlineSpan::source)
                .filter(java.util.Objects::nonNull).toList());
    }

    @Test
    void legacyQuestToolResultsStillRenderStoredSourcesAndLinks() {
        var source = new Evidence.Source(SCOPE, "native", "1", "quest:1000000000000002", 1, java.time.Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        var target = new Evidence.NavigationTarget(SCOPE, "native", source.sourceHandle(), "1000000000000002");
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("legacy-progress"), "Did I finish it?",
                "[Old quest](native:quest:1000000000000002)", List.of(
                new ModelBackend.ToolCall("old-progress", "read_progress", "{\"handles\":[\"native:quest:1000000000000002\"]}"),
                new ModelBackend.ToolResult("old-progress", """
                        {"coverage":"COMPLETE","outcome":"COMPLETE","records":[{
                          "source":{"providerId":"native","providerVersion":"1","sourceHandle":"quest:1000000000000002",
                            "observedAt":"1970-01-01T00:00:00Z","observedGeneration":1,
                            "activity":"ACTIVE","coverage":"COMPLETE","authority":"LOADED","provenance":[],
                            "scope":{"worldSession":"world","resourcesLocale":"en_us","playerTeam":"team",
                              "providerRuntime":"runtime","requestGeneration":1}},
                          "kind":"QUEST","title":"Old quest","payload":{"completed":true}}]}
                        """, false)),
                new ChatContracts.Context("{}", null, SCOPE), List.of(source), List.of(target),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);

        var span = ScreenText.inlineLines(turn.answer(), turn.navigationTargets(), List.of(), turn.sources(),
                ScreenText.sourceLabels(turn)).getFirst().spans().getFirst();
        assertEquals("Old quest", span.text());
        assertEquals(source, span.source());
        assertEquals(target, span.target());
    }

    @Test
    void nativeBrewingAndPotionCitationsUseExactSavedDetailsForReadableLabels() {
        String brewingHandle = "brewing:potion:56:0:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String splashHandle = "brewing:container:3:0:1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String potionHandle = "potion:minecraft:long_strength";
        var brewing = new Evidence.Source(SCOPE, "native", "1.21.1", brewingHandle, 1, java.time.Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
                List.of("client-potion-brewing", "potion-brewing-rule"));
        var splash = new Evidence.Source(SCOPE, "native", "1.21.1", splashHandle, 1, java.time.Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
                List.of("client-potion-brewing", "container-brewing-rule"));
        var potion = new Evidence.Source(SCOPE, "native", "1.21.1", potionHandle, 1, java.time.Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED,
                List.of("client-registry:potion"));
        JsonObject brewingPayload = new JsonObject();
        JsonArray outputs = new JsonArray();
        JsonObject output = new JsonObject();
        output.addProperty("comparisonIdentity", "minecraft:potion");
        output.addProperty("quantity", 1);
        JsonObject potionComponents = new JsonObject();
        potionComponents.addProperty("potion", "minecraft:strength");
        JsonObject outputComponents = new JsonObject();
        outputComponents.add("minecraft:potion_contents", potionComponents);
        output.add("components", outputComponents);
        outputs.add(output);
        brewingPayload.add("outputs", outputs);
        JsonObject splashPayload = brewingPayload.deepCopy();
        splashPayload.getAsJsonArray("outputs").get(0).getAsJsonObject()
                .addProperty("comparisonIdentity", "minecraft:splash_potion");
        JsonObject potionPayload = new JsonObject();
        potionPayload.addProperty("registry", "potion");
        potionPayload.addProperty("id", "minecraft:long_strength");
        JsonArray effects = new JsonArray();
        JsonObject effect = new JsonObject();
        effect.addProperty("id", "minecraft:strength");
        effect.addProperty("durationTicks", 3600);
        effect.addProperty("amplifier", 0);
        effects.add(effect);
        potionPayload.add("effects", effects);
        JsonArray records = new JsonArray();
        for (Evidence.Source source : List.of(brewing, splash, potion)) {
            JsonObject record = new JsonObject();
            JsonObject observed = new JsonObject();
            observed.addProperty("providerId", source.providerId());
            observed.addProperty("sourceHandle", source.sourceHandle());
            observed.addProperty("observedAt", source.observedAt().toString());
            observed.addProperty("observedGeneration", source.observedGeneration());
            observed.add("scope", new Gson().toJsonTree(source.scope()));
            record.add("source", observed);
            record.addProperty("title", source.equals(potion) ? "minecraft:long_strength"
                    : source.equals(splash) ? "minecraft:splash_potion brewing transformation"
                    : "minecraft:potion brewing transformation");
            record.add("payload", source.equals(potion) ? potionPayload : source.equals(splash) ? splashPayload : brewingPayload);
            records.add(record);
        }
        JsonObject result = new JsonObject();
        result.add("records", records);
        var turn = new ChatContracts.Turn(new ChatContracts.RequestId("labels"), "question", "answer",
                List.of(new ModelBackend.ToolResult("saved", result.toString(), false)),
                new ChatContracts.Context("{}", null, SCOPE), List.of(brewing, splash, potion),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
        String grouped = "\uE200cite\uE202native:" + brewingHandle + "\uE201";
        var spans = ScreenText.inlineLines(grouped + " [native:" + splashHandle + "] [native:" + potionHandle + "]", List.of(), List.of(),
                turn.sources(), ScreenText.sourceLabels(turn))
                .getFirst().spans();

        var sourceSpans = spans.stream().filter(span -> span.source() != null).toList();
        assertEquals(List.of("Brewing: Strength", "Brewing: Splash Potion (Strength)", "Potion effects: Long Strength"),
                sourceSpans.stream().map(ScreenText.InlineSpan::text).toList());
        assertEquals(List.of(brewing, splash, potion), sourceSpans.stream().map(ScreenText.InlineSpan::source).toList());
        assertTrue(sourceSpans.stream().allMatch(span -> span.target() == null));
        var target = new Evidence.NavigationTarget(SCOPE, "native", brewingHandle, "validated-target");
        var linkedTurn = new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(), turn.replayItems(),
                turn.context(), turn.sources(), List.of(target), turn.outcome(), 1);
        var explicit = ScreenText.inlineLines("[Custom brew label](native:" + brewingHandle + ")", List.of(target), List.of(),
                linkedTurn.sources(), ScreenText.sourceLabels(linkedTurn))
                .getFirst().spans().getFirst();
        assertEquals("Custom brew label", explicit.text());
        assertEquals(brewing, explicit.source());
        assertEquals(target, explicit.target());

        JsonObject mismatchedResult = result.deepCopy();
        mismatchedResult.getAsJsonArray("records").get(0).getAsJsonObject()
                .getAsJsonObject("source").addProperty("observedGeneration", 2);
        var mismatchedTurn = new ChatContracts.Turn(turn.requestId(), turn.question(), turn.answer(),
                List.of(new ModelBackend.ToolResult("mismatched", mismatchedResult.toString(), false)),
                turn.context(), turn.sources(), turn.navigationTargets(), turn.outcome(), 1);
        var mismatched = ScreenText.inlineLines(grouped, List.of(), List.of(), mismatchedTurn.sources(),
                ScreenText.sourceLabels(mismatchedTurn)).getFirst().spans()
                .stream().filter(span -> span.source() != null).findFirst().orElseThrow();
        assertEquals("Brewing recipe", mismatched.text());
        assertEquals(brewing, mismatched.source());
    }

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
    void journalCitationsRenderTheirPlaceChipAndUnboundMarker() {
        var target = new Evidence.NavigationTarget(SCOPE, "journal", "event:1", "journal-place|minecraft:overworld|10,64,-3");
        for (var citation : List.of("[journal:event:1]", "\uE200cite\uE202journal:event:1\uE201")) {
            var span = ScreenText.inlineLines(citation, List.of(target)).getFirst().spans().getFirst();
            assertEquals("in world", span.text());
            assertEquals(target, span.target());
        }
        var named = ScreenText.inlineLines("[Sand](journal:event:1)", List.of(target)).getFirst().spans().getFirst();
        assertEquals("Sand", named.text());
        assertEquals(target, named.target());
        assertEquals("[note]", ScreenText.inlineLines("[journal:note:n12345678]", List.of()).getFirst().spans().getFirst().text());
        assertEquals("[journal]", ScreenText.inlineLines("[journal:event:2]", List.of()).getFirst().spans().getFirst().text());
        for (var marker : List.of("[command:run|8f0c6b4e]", "[game_state:snapshot]")) {
            assertFalse(ScreenText.inlineLines(marker, List.of()).getFirst().spans().getFirst().text().contains("|"), marker);
            assertFalse(ScreenText.inlineLines(marker, List.of()).getFirst().spans().getFirst().text().contains(":"), marker);
        }
        assertEquals("Show", ScreenText.navigationVerb(target));
        assertEquals("Show in world: minecraft:overworld|10,64,-3", ScreenText.navigationLabel(target));
        assertEquals("Show noted place", ScreenText.navigationLabel(new Evidence.NavigationTarget(SCOPE, "journal", "note:n12345678",
                "journal-note|n12345678")));
    }

    @Test
    void worldTargetsAreLabeledInWorld() {
        var target = new Evidence.NavigationTarget(SCOPE, "world", "block|minecraft:overworld|10,64,-3",
                "world-block|minecraft:overworld|10,64,-3");
        var spans = ScreenText.inlineLines("Chest [world:block|minecraft:overworld|10,64,-3].", List.of(target)).getFirst().spans();
        assertEquals("in world", spans.get(1).text());
        assertEquals(target, spans.get(1).target());
    }

    @Test
    void progressTargetsReadOpenAdvancements() {
        var target = new Evidence.NavigationTarget(SCOPE, "progress", "progress|minecraft/minecraft:story/mine_stone",
                "advancement|minecraft:story/root|minecraft:story/mine_stone");
        assertEquals("Open advancements: mine_stone", ScreenText.navigationLabel(target));
        var bare = new Evidence.NavigationTarget(SCOPE, "progress", "progress|examplemod/examplemod:root",
                "advancement|examplemod:root|examplemod:root");
        assertEquals("Open advancements: root", ScreenText.navigationLabel(bare));
        var spans = ScreenText.inlineLines("Stone [progress:progress|minecraft/minecraft:story/mine_stone].", List.of(target)).getFirst().spans();
        assertEquals("Advancements", spans.get(1).text());
        assertEquals(target, spans.get(1).target());
    }

    @Test
    void registeredLocalControlsAppearAtTheirMarkersWithoutOpaqueIdsOrASeparateCollection() {
        var target = new Evidence.NavigationTarget(SCOPE, "config", "book#entry", "entry#1@lifecycle");
        var spans = ScreenText.inlineLines("Before [config:book#entry] after.", List.of(target)).getFirst().spans();
        assertEquals("Before ", spans.getFirst().text());
        assertEquals("Config", spans.get(1).text());
        assertEquals(target, spans.get(1).target());
        assertEquals(" after.", spans.getLast().text());
        assertTrue(ScreenText.inlineLines("No reference here.", List.of(target)).stream()
                .flatMap(line -> line.spans().stream()).noneMatch(span -> span.target() != null));
        assertTrue(ScreenText.inlineLines("[config:unregistered]", List.of(target)).stream()
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
    void savedWikiAnswerFormatsMarkdownInsideWebCitationRangesWithoutDuplicateActions() {
        var target = new Evidence.NavigationTarget(SCOPE, "web", "https://en.wikipedia.org/wiki/Minecraft?utm_source=openai",
                "https://en.wikipedia.org/wiki/Minecraft?utm_source=openai");
        String answer = "Here\u2019s the [Minecraft Wiki homepage](https://minecraft.wiki/). "
                + "([en.wikipedia.org](https://en.wikipedia.org/wiki/Minecraft?utm_source=openai))";
        var citation = new dev.packsage.model.ModelBackend.UrlCitation(63, 142, "Minecraft", target.sourceHandle());

        var spans = ScreenText.inlineLines(answer, List.of(target), List.of(citation)).getFirst().spans();

        assertEquals("Here\u2019s the Minecraft Wiki homepage. (en.wikipedia.org)",
                spans.stream().map(ScreenText.InlineSpan::text).reduce("", String::concat));
        assertEquals(List.of(target), spans.stream().map(ScreenText.InlineSpan::target).filter(java.util.Objects::nonNull).toList());
        assertEquals("en.wikipedia.org", spans.stream().filter(span -> target.equals(span.target())).findFirst().orElseThrow().text());
        assertEquals(List.of("https://minecraft.wiki/"), spans.stream().map(ScreenText.InlineSpan::externalUrl)
                .filter(java.util.Objects::nonNull).toList());
    }

    @Test
    void ordinaryWebMarkdownKeepsClickableUrlsSeparateFromEvidenceAndLeavesCodeLiteral() {
        var target = new Evidence.NavigationTarget(SCOPE, "web", "https://example.test/source", "https://example.test/source");
        var spans = ScreenText.inlineLines("[Wiki](https://minecraft.wiki/) [Site](http://unknown.test) "
                + "[Other](HTTPS://unknown.test) "
                + "`[Code](https://example.test/source)`", List.of(target)).getFirst().spans();

        assertEquals("Wiki Site Other [Code](https://example.test/source)",
                spans.stream().map(ScreenText.InlineSpan::text).reduce("", String::concat));
        assertTrue(spans.stream().noneMatch(span -> span.target() != null || span.source() != null));
        assertEquals(List.of("https://minecraft.wiki/", "http://unknown.test", "HTTPS://unknown.test"), spans.stream()
                .map(ScreenText.InlineSpan::externalUrl).filter(java.util.Objects::nonNull).toList());
        assertTrue(spans.stream().anyMatch(span -> span.code() && span.externalUrl() == null
                && span.text().equals("[Code](https://example.test/source)")));
    }

    @Test
    void webMarkdownRejectsUnsafeOrMalformedDestinationsAndKeepsTableLinks() {
        for (String url : List.of("javascript:alert(1)", "file:///local", "https://user:password@example.test",
                "https:///missing-host", "https://bad host.test")) {
            assertTrue(ScreenText.inlineLines("[Link](" + url + ")", List.of()).stream().flatMap(line -> line.spans().stream())
                    .noneMatch(span -> span.externalUrl() != null || span.target() != null || span.source() != null), url);
        }
        var table = ScreenText.inlineLines("| Page |\n| --- |\n| [Wiki](https://minecraft.wiki/) |", List.of());
        assertEquals(List.of("https://minecraft.wiki/"), table.stream().flatMap(line -> line.spans().stream())
                .map(ScreenText.InlineSpan::externalUrl).filter(java.util.Objects::nonNull).toList());
    }

    @Test
    void inlineMarkdownClosesStylesAndLeavesCodeReferencesLiteral() {
        var target = new Evidence.NavigationTarget(SCOPE, "config", "book#entry", "entry");
        var spans = ScreenText.inlineLines("**bold** normal `code` [config:book#entry]", List.of(target)).getFirst().spans();
        assertEquals("bold", spans.getFirst().text());
        assertTrue(spans.getFirst().bold());
        assertTrue(spans.stream().anyMatch(span -> span.text().contains("normal") && !span.bold()));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("code") && span.code()));
        assertTrue(ScreenText.inlineLines("`[config:book#entry]`", List.of(target)).stream()
                .flatMap(line -> line.spans().stream()).noneMatch(span -> span.target() != null));
    }

    @Test
    void evolutionLocalLinksAndPrivateCitationReferencesUseOnlyRegisteredTargets() {
        var setting = new Evidence.NavigationTarget(SCOPE, "config", "4A2123FF92275F64", "4A2123FF92275F64");
        var item = new Evidence.NavigationTarget(SCOPE, "native", "fixture:book#entry", "fixture:book#entry");
        String privateCitation = "\uE200cite\uE202config:4A2123FF92275F64\uE202native:fixture:book#entry"
                + "\uE202config:missing\uE201";
        String answer = "[**Punch A Tree** — `config:4A2123FF92275F64`](config:4A2123FF92275F64) "
                + privateCitation + " [search0](https://evil.test) ` [Fake](config:4A2123FF92275F64) ` Claim";
        var web = new Evidence.NavigationTarget(SCOPE, "web", "https://example.test/source", "https://example.test/source");
        var citation = new dev.packsage.model.ModelBackend.UrlCitation(answer.indexOf("Claim"), answer.length(),
                "Example", web.sourceHandle());

        var spans = ScreenText.inlineLines(answer, List.of(setting, item, web), List.of(citation)).getFirst().spans();
        assertEquals(setting, spans.stream().filter(span -> span.text().equals("Config") && span.target() != null)
                .findFirst().orElseThrow().target());
        assertEquals(item, spans.stream().filter(span -> span.text().equals("Source") && span.target() != null)
                .findFirst().orElseThrow().target());
        assertEquals(2, spans.stream().filter(span -> span.target() == setting).count());
        assertTrue(spans.stream().anyMatch(span -> span.target() == setting && span.text().contains("Punch A Tree")));
        assertTrue(spans.stream().anyMatch(span -> span.text().equals("[config]") && span.target() == null));
        assertTrue(spans.stream().anyMatch(span -> span.text().contains("search0") && span.target() == null));
        assertTrue(spans.stream().anyMatch(span -> span.code() && span.text().contains("[Fake](config:")));
        assertTrue(spans.stream().anyMatch(span -> span.target() == web && span.text().equals("Example")));
        assertTrue(spans.stream().filter(span -> span.target() != null)
                .allMatch(span -> span.target() == setting || span.target() == item || span.target() == web));
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
        var lines = ScreenText.inlineLines("| Task | Progress |\n| --- | --- |\n| Gather | 1/1 |", List.of());
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
    void unresolvedModelAliasIsNotShownRaw() {
        for (String closing : List.of("]", ")")) {
            String text = "Done. [ref:e36be868.1.1f" + closing;
            var spans = ScreenText.inlineLines(text, List.of()).getFirst().spans();
            assertEquals("Done.", spans.stream().map(ScreenText.InlineSpan::text).reduce("", String::concat));
            assertTrue(spans.stream().noneMatch(span -> span.text().contains("ref:")));
            assertEquals("Done.", ScreenText.markdownLines(text).getFirst().getString());
            assertEquals("Before after.", ScreenText.inlineLines("Before [ref:e36be868.1.1f" + closing + " after.", List.of())
                    .getFirst().spans().stream().map(ScreenText.InlineSpan::text).reduce("", String::concat));
        }
        String prose = "[see note) [ref:unknown] [ref:ffffffff.9.9 extra) [ref:E36be868.1.1f]";
        assertEquals(prose, ScreenText.inlineLines(prose, List.of()).getFirst().spans().stream()
                .map(ScreenText.InlineSpan::text).reduce("", String::concat));
        assertEquals(prose, ScreenText.markdownLines(prose).getFirst().getString());
    }

    @Test
    void aliasShapedMarkdownLabelKeepsRecordedWorldNavigation() {
        var target = new Evidence.NavigationTarget(SCOPE, "world", "item|player:abc>minecraft/inventory#0@minecraft:torch",
                "world-item|player:abc>minecraft/inventory#0@minecraft:torch");
        var spans = ScreenText.inlineLines("[ref:e36be868.1.1f](world:item|player:abc>minecraft/inventory#0@minecraft:torch)",
                List.of(target)).getFirst().spans();

        assertEquals(1, spans.size());
        assertEquals("ref:e36be868.1.1f", spans.getFirst().text());
        assertEquals(target, spans.getFirst().target());
    }

    @Test
    void citationsFromEveryRegisteredProviderBecomeChips() {
        // Current providers include native/config/world/progress/journal and conversation history.
        for (String provider : List.of("native", "config", "history", "world", "progress", "journal")) {
            var source = new Evidence.Source(SCOPE, provider, "1", "note:abc", 1, java.time.Instant.EPOCH,
                    Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
            for (String marker : List.of("[" + provider + ":note:abc]", "\uE200cite\uE202" + provider + ":note:abc\uE201",
                    "[Recorded note](" + provider + ":note:abc)")) {
                var spans = ScreenText.inlineLines(marker, List.of(), List.of(), List.of(source)).getFirst().spans();
                assertEquals(1, spans.size(), marker);
                assertEquals(source, spans.getFirst().source(), marker);
                assertFalse(spans.getFirst().text().contains(provider + ":note:abc"), marker);
            }
            assertEquals(provider.equals("native") ? "[game]" : provider.equals("journal") ? "[note]" : "[" + provider + "]",
                    ScreenText.markdownLines("[" + provider + ":note:abc]").getFirst().getString());
        }
    }

    @Test
    void citationMarkersBecomeShortDimTags() {
        List<Component> lines = ScreenText.markdownLines("Torch [native:minecraft:torch] and "
                + "[config:cfg/x] then [history:turn/1] but [removed:other/handle].");

        assertEquals("Torch [game] and [config] then [history] but [removed:other/handle].", lines.get(0).getString());
        assertEquals(List.of("[game]", "[config]", "[history]"), dimRuns(lines.get(0)));
    }

    @Test
    void savedProviderGroupsKeepKnownChipsAndNeverDisplayPrivateUseMarkers() {
        var target = new Evidence.NavigationTarget(SCOPE, "world", "item|player:abc>minecraft/armor#3@minecraft:diamond_helmet",
                "world-item|player:abc>minecraft/armor#3@minecraft:diamond_helmet");
        var source = new Evidence.Source(SCOPE, "world", "1", target.sourceHandle(), 1, java.time.Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
        String saved = "Before \uE200cite\uE202ref:ffffffff.9.9\uE202world:" + target.sourceHandle()
                + "\uE202unknown:missing\uE201 after.";
        var spans = ScreenText.inlineLines(saved, List.of(target), List.of(), List.of(source)).getFirst().spans();

        assertEquals(List.of(target), spans.stream().map(ScreenText.InlineSpan::target).filter(java.util.Objects::nonNull).toList());
        assertEquals(List.of(source), spans.stream().map(ScreenText.InlineSpan::source).filter(java.util.Objects::nonNull).toList());
        assertEquals("Before in world after.", spans.stream().map(ScreenText.InlineSpan::text).reduce("", String::concat));
        assertEquals("Before [world] after.", ScreenText.markdownLines(saved).getFirst().getString());
        assertTrue(saved.contains("\uE200cite\uE202"));
        for (String text : List.of("orphan \uE200 \uE201 \uE202", "\uE200cite\uE202ref:ffffffff.9.9",
                "`\uE200cite\uE202unknown:missing\uE201`", "```\n\uE200cite\uE202unknown:missing\uE201\n```")) {
            assertTrue(ScreenText.inlineLines(text, List.of()).stream().flatMap(line -> line.spans().stream())
                    .noneMatch(span -> span.text().chars().anyMatch(value -> value >= 0xE200 && value <= 0xE202)), text);
            assertTrue(ScreenText.markdownLines(text).stream()
                    .noneMatch(line -> line.getString().chars().anyMatch(value -> value >= 0xE200 && value <= 0xE202)), text);
        }
    }

    @Test
    void savedMalformedProviderGroupsPreserveProseBeforeLaterClosingMarkers() {
        for (String[] fixture : List.of(
                new String[]{"A \uE200cite\uE202ref:x then more prose.\uE201", "A citeref:x then more prose."},
                new String[]{"A \uE200cite\uE202ref:x\nthen more prose.\uE201", "A citeref:x\nthen more prose."},
                new String[]{"A \uE200cite\uE202ref:x\tthen more prose.\uE201", "A citeref:x\tthen more prose."},
                new String[]{"A \uE200cite\uE202ref:x[note] more prose.\uE201", "A citeref:x[note] more prose."},
                new String[]{"A \uE200cite\uE202ref:x then more prose. \uE200cite\uE202config:cfg/x\uE201 end.",
                        "A citeref:x then more prose. [config] end."},
                new String[]{"A \uE200cite\uE202ref:x\uE200cite\uE202config:cfg/x\uE201 end.",
                        "A citeref:x[config] end."})) {
            assertEquals(fixture[1], ScreenText.markdownLines(fixture[0]).stream().map(Component::getString)
                    .collect(java.util.stream.Collectors.joining("\n")), fixture[0]);
            assertEquals(fixture[1], ScreenText.inlineLines(fixture[0], List.of()).stream()
                    .map(line -> line.spans().stream().map(ScreenText.InlineSpan::text).reduce("", String::concat))
                    .collect(java.util.stream.Collectors.joining("\n")), fixture[0]);
        }
    }

    @Test
    void groupedCitationsUseShortTagsWithoutInventingNavigationTargets() {
        String grouped = "\uE200cite\uE202config:cfg/first\uE202config:cfg/second\uE202history:turn/1\uE201";
        var spans = ScreenText.inlineLines(grouped + " [unknown:source]", List.of()).getFirst().spans();

        assertEquals("[config][config][history] [unknown:source]",
                spans.stream().map(ScreenText.InlineSpan::text).reduce("", String::concat));
        assertTrue(spans.stream().filter(span -> span.text().startsWith("[") && span.text().endsWith("]"))
                .allMatch(span -> span.target() == null));
        assertEquals(List.of("[config]", "[config]", "[history]"),
                spans.stream().filter(span -> span.text().startsWith("[") && span.text().endsWith("]")
                        && !span.text().equals("[unknown:source]")).map(ScreenText.InlineSpan::text).toList());
    }

    @Test
    void navigableCitationsKeepPlayerNavigationForRawAndGroupedMarkers() {
        var target = new Evidence.NavigationTarget(SCOPE, "config", "cfg/registration", "cfg/registration");
        for (String marker : List.of("[config:cfg/registration]", "\uE200cite\uE202config:cfg/registration\uE201")) {
            var spans = ScreenText.inlineLines(marker, List.of(target)).getFirst().spans();
            assertEquals(1, spans.size());
            assertEquals("Config", spans.getFirst().text());
            assertEquals(target, spans.getFirst().target());
        }
        assertEquals("[config]", ScreenText.inlineLines("[config:cfg/registration]", List.of())
                .getFirst().spans().getFirst().text());
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
        var plain = new Evidence.NavigationTarget(SCOPE, "fixture", "abcd", "abcd");
        assertEquals("Open fixture: abcd", ScreenText.navigationLabel(plain));

        var web = new Evidence.NavigationTarget(SCOPE, "native", "minecraft:torch", "minecraft:torch");
        assertEquals("Open native: minecraft:torch", ScreenText.navigationLabel(web));

        var hashed = new Evidence.NavigationTarget(SCOPE, "fixture", "fixture:book#fixture:book/intro#0",
                "fixture:book#fixture:book/intro#0");
        assertEquals("Open fixture: intro", ScreenText.navigationLabel(hashed));
    }

    @Test
    void worldNavigationLabelSaysShowRatherThanOpen() {
        var block = new Evidence.NavigationTarget(SCOPE, "world", "block|minecraft:overworld|10,64,-3",
                "world-block|minecraft:overworld|10,64,-3");
        assertEquals("Show in world: minecraft:overworld|10,64,-3", ScreenText.navigationLabel(block));
        var mob = new Evidence.NavigationTarget(SCOPE, "world", "entity|3f1c2a4e-0000-4000-8000-000000000001",
                "world-entity|3f1c2a4e-0000-4000-8000-000000000001");
        assertEquals("Show in world: 3f1c2a4e-0000-4000-8000-000000000001", ScreenText.navigationLabel(mob));
    }
    private Evidence.Source recipeSource(String handle, long second) {
        return new Evidence.Source(SCOPE, "native", "1", handle, 1, java.time.Instant.ofEpochSecond(second),
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.LOADED, List.of());
    }

    private JsonObject recipeRecord(Evidence.Source source, String output) {
        var record = com.google.gson.JsonParser.parseString("""
                {"reference":"native:test","payload":{"inputs":[],"outputs":[{"type":"item",
                "comparisonIdentity":"minecraft:OUTPUT","components":{},"quantity":1}],
                "constraints":["recipeType=minecraft:stonecutting"],"decodingStatus":"VERIFIED"}}
                """.replace("OUTPUT", output)).getAsJsonObject();
        record.addProperty("reference", source.providerId() + ":" + source.sourceHandle());
        var observed = new JsonObject();
        observed.addProperty("providerId", source.providerId());
        observed.addProperty("sourceHandle", source.sourceHandle());
        observed.addProperty("observedGeneration", source.observedGeneration());
        observed.add("scope", new Gson().toJsonTree(source.scope()));
        record.add("source", observed);
        record.getAsJsonObject("source").addProperty("observedAt", source.observedAt().toString());
        return record;
    }

    private ChatContracts.Turn recipeTurn(List<Evidence.Source> sources, JsonObject... records) {
        var result = new JsonObject();
        var values = new JsonArray();
        for (var record : records) values.add(record);
        result.add("records", values);
        return new ChatContracts.Turn(new ChatContracts.RequestId("recipes"), "question", "answer",
                List.of(new ModelBackend.ToolResult("recipes", result.toString(), false)),
                new ChatContracts.Context("{}", null, SCOPE), sources,
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1);
    }

    @Test
    void recipeCitationsFollowStoredVerbosityAndPlacement() {
        var source = recipeSource("minecraft:stone", 0);
        var turn = recipeTurn(List.of(source), recipeRecord(source, "stone"));
        var cards = ScreenText.recipeCards(turn);
        var card = cards.get(source);
        assertTrue(card != null);
        for (var verbosity : List.of(dev.packsage.chat.PackSageSettings.Verbosity.NORMAL,
                dev.packsage.chat.PackSageSettings.Verbosity.DETAILED)) {
            var standalone = ScreenText.inlineLines("  [native:minecraft:stone]  ", List.of(), List.of(), turn.sources(),
                    java.util.Map.of(), verbosity, cards).getFirst();
            assertEquals(card, standalone.card());
            assertEquals(source, standalone.spans().stream().filter(span -> span.source() != null).findFirst().orElseThrow().source());
            var inline = ScreenText.inlineLines("Make [native:minecraft:stone].", List.of(), List.of(), turn.sources(),
                    java.util.Map.of(), verbosity, cards).getFirst();
            assertEquals(null, inline.card());
            if (verbosity == dev.packsage.chat.PackSageSettings.Verbosity.DETAILED) {
                assertEquals(null, inline.spans().stream().filter(span -> span.source() != null).findFirst().orElseThrow().resultCard());
            } else {
                assertEquals("Make.", inline.spans().stream().map(ScreenText.InlineSpan::text).collect(java.util.stream.Collectors.joining()));
            }
        }
        for (String text : List.of("[native:minecraft:stone]", "Make [native:minecraft:stone].")) {
            var line = ScreenText.inlineLines(text, List.of(), List.of(), turn.sources(), java.util.Map.of(),
                    dev.packsage.chat.PackSageSettings.Verbosity.CONCISE, cards).getFirst();
            assertEquals(null, line.card());
            assertEquals(card, line.spans().stream().filter(span -> span.source() != null).findFirst().orElseThrow().resultCard());
        }
    }

    @Test
    void sourceCitationsRespectVerbosityAndKeepNavigationAndWebLinks() {
        var source = recipeSource("minecraft:stone", 0);
        var target = new Evidence.NavigationTarget(SCOPE, "native", "minecraft:stone", "minecraft:stone");
        String group = "\uE200cite\uE202native:minecraft:stone\uE201";
        for (var verbosity : List.of(dev.packsage.chat.PackSageSettings.Verbosity.NORMAL,
                dev.packsage.chat.PackSageSettings.Verbosity.CONCISE)) {
            for (String text : List.of("Before [native:minecraft:stone] after.", "Before " + group + " after.")) {
                var spans = ScreenText.inlineLines(text, List.of(), List.of(), List.of(source), java.util.Map.of(), verbosity, java.util.Map.of()).getFirst().spans();
                assertEquals("Before after.", spans.stream().map(ScreenText.InlineSpan::text).collect(java.util.stream.Collectors.joining()));
                assertTrue(spans.stream().noneMatch(span -> span.source() != null));
            }
            var spans = ScreenText.inlineLines("A [native:minecraft:stone], [Stone](native:minecraft:stone).", List.of(), List.of(),
                    List.of(source), java.util.Map.of(), verbosity, java.util.Map.of()).getFirst().spans();
            assertEquals("A, Stone.", spans.stream().map(ScreenText.InlineSpan::text).collect(java.util.stream.Collectors.joining()));
            assertTrue(spans.stream().noneMatch(span -> span.source() != null));
            var navigation = ScreenText.inlineLines("[Stone](native:minecraft:stone)", List.of(target), List.of(),
                    List.of(source), java.util.Map.of(), verbosity, java.util.Map.of()).getFirst().spans().getFirst();
            assertEquals(target, navigation.target());
            var web = ScreenText.inlineLines("[Wiki](https://example.test/page)", List.of(), List.of(),
                    List.of(), java.util.Map.of(), verbosity, java.util.Map.of()).getFirst().spans().getFirst();
            assertEquals("https://example.test/page", web.externalUrl());
        }
        for (var verbosity : java.util.Arrays.asList(null, dev.packsage.chat.PackSageSettings.Verbosity.DETAILED)) {
            var spans = ScreenText.inlineLines("[native:minecraft:stone] [Stone](native:minecraft:stone)", List.of(), List.of(),
                    List.of(source), java.util.Map.of(), verbosity, java.util.Map.of()).getFirst().spans();
            assertEquals(2, spans.stream().filter(span -> span.source() != null).count());
        }
    }

    @Test
    void itemLinksAndLegacyTurnsKeepTheirDisplaySemantics() {
        var source = recipeSource("minecraft:stone", 0);
        var turn = recipeTurn(List.of(source), recipeRecord(source, "stone"));
        var cards = ScreenText.recipeCards(turn);
        for (var verbosity : List.of(dev.packsage.chat.PackSageSettings.Verbosity.NORMAL,
                dev.packsage.chat.PackSageSettings.Verbosity.DETAILED, dev.packsage.chat.PackSageSettings.Verbosity.CONCISE)) {
            var span = ScreenText.inlineLines("[Stone](item:minecraft:stone)", List.of(), List.of(), turn.sources(),
                    java.util.Map.of(), verbosity, cards).getFirst().spans().getFirst();
            assertEquals("Stone", span.text());
            assertEquals("minecraft:stone", span.itemId());
        }
        var lines = ScreenText.inlineLines("[native:minecraft:stone]\n[Stone](item:minecraft:stone)", List.of(), List.of(),
                turn.sources(), java.util.Map.of(), null, cards);
        assertEquals(null, lines.getFirst().card());
        assertEquals(null, lines.getFirst().spans().getFirst().resultCard());
        assertEquals("[Stone](item:minecraft:stone)", lines.get(1).spans().getFirst().text());
        assertEquals(null, lines.get(1).spans().getFirst().itemId());
    }

    @Test
    void groupedAndCanonicalizedAliasCitationsUseSameCard() {
        var source = recipeSource("minecraft:stone", 0);
        var turn = recipeTurn(List.of(source), recipeRecord(source, "stone"));
        var cards = ScreenText.recipeCards(turn);
        // ReferenceAliasBackend expands ref aliases to these canonical forms before storing an answer.
        for (String text : List.of("[native:minecraft:stone]", "[Stone](native:minecraft:stone)",
                "\uE200cite\uE202native:minecraft:stone\uE201")) {
            var line = ScreenText.inlineLines(text, List.of(), List.of(), turn.sources(), java.util.Map.of(),
                    dev.packsage.chat.PackSageSettings.Verbosity.NORMAL, cards).getFirst();
            assertEquals(cards.get(source), line.card());
        }
        var multi = ScreenText.inlineLines("\uE200cite\uE202native:minecraft:stone\uE202native:minecraft:stone\uE201",
                List.of(), List.of(), turn.sources(), java.util.Map.of(), dev.packsage.chat.PackSageSettings.Verbosity.NORMAL, cards).getFirst();
        assertEquals(null, multi.card());
    }

    @Test
    void duplicateRecordsMatchTheCitedObservation() {
        var first = recipeSource("minecraft:stone", 0);
        var second = recipeSource("minecraft:stone", 1);
        var turn = recipeTurn(List.of(first, second), recipeRecord(second, "diamond"), recipeRecord(first, "stone"));
        var cards = ScreenText.recipeCards(turn);
        assertEquals("minecraft:stone", cards.get(first).slots().getLast().stacks().getFirst().identity());
        assertEquals("minecraft:diamond", cards.get(second).slots().getLast().stacks().getFirst().identity());
        var line = ScreenText.inlineLines("[native:minecraft:stone]", List.of(), List.of(), turn.sources(), java.util.Map.of(),
                dev.packsage.chat.PackSageSettings.Verbosity.NORMAL, cards).getFirst();
        assertEquals(cards.get(second), line.card());
    }

    @Test
    void planCitationUsesPlanPayloadAndRejectsMismatchedObservations() {
        var source = recipeSource("plan|minecraft:stone|1|3|0|minecraft:stone", 0);
        var record = recipeRecord(source, "diamond");
        var bad = recipeRecord(source, "dirt");
        bad.getAsJsonObject("source").addProperty("observedGeneration", 2);
        var turn = recipeTurn(List.of(source), record, bad);
        assertEquals("minecraft:diamond", ScreenText.recipeCards(turn).get(source).slots().getLast().stacks().getFirst().identity());
    }

    @Test
    void cardRequiresTheEntireRawLineToBeOneCitation() {
        var source = recipeSource("minecraft:stone", 0);
        var turn = recipeTurn(List.of(source), recipeRecord(source, "stone"));
        var cards = ScreenText.recipeCards(turn);
        for (String text : List.of("# [native:minecraft:stone]", "> [native:minecraft:stone]",
                "**[native:minecraft:stone]**", "[ref:e36be868.1.1f] [native:minecraft:stone]")) {
            assertEquals(null, ScreenText.inlineLines(text, List.of(), List.of(), turn.sources(), java.util.Map.of(),
                    dev.packsage.chat.PackSageSettings.Verbosity.NORMAL, cards).getFirst().card(), text);
        }
    }

    @Test
    void aliasedReplayHandlesBindOnlyWhenTheirSourceIdentityIsKnown() {
        var source = recipeSource("minecraft:stone", 0);
        var record = recipeRecord(source, "stone");
        record.getAsJsonObject("source").addProperty("sourceHandle", "ref:12345678.1.1");
        var turn = recipeTurn(List.of(source), record);
        assertTrue(ScreenText.recipeCards(turn).containsKey(source));
        record.addProperty("reference", "ref:12345678.1.1");
        assertTrue(ScreenText.recipeCards(recipeTurn(List.of(source), record)).isEmpty());
        var alias = recipeSource("ref:12345678.1.1", 0);
        assertTrue(ScreenText.recipeCards(recipeTurn(List.of(alias), record)).containsKey(alias));
    }
}
