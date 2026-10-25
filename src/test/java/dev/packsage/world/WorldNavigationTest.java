package dev.packsage.world;

import dev.packsage.chat.ChatContracts;
import dev.packsage.chat.ConversationStore;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldNavigationTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);
    private static final Evidence.Scope RELOADED = new Evidence.Scope("world", "resources-2", "team", "runtime-2", 7);
    private static final BlockPlace PLACE = new BlockPlace("minecraft:overworld", 10, 64, -3);
    private static final UUID MOB = UUID.fromString("3f1c2a4e-0000-4000-8000-000000000001");
    private static final BlockPlace MOB_PLACE = new BlockPlace("minecraft:overworld", 12, 65, -8);

    /** Records highlights instead of drawing them, standing in for the client. */
    private static final class FakeClient implements WorldNavigation.Client {
        String dimension = "minecraft:overworld";
        boolean tracksMob = true;
        final List<String> drawn = new ArrayList<>();
        final List<Long> until = new ArrayList<>();

        @Override
        public String dimension() {
            return dimension;
        }

        @Override
        public boolean tracks(UUID id) {
            return tracksMob && MOB.equals(id);
        }

        @Override
        public void highlightBlock(BlockPlace place, long untilMillis) {
            drawn.add("block " + place.text());
            until.add(untilMillis);
        }

        @Override
        public void highlightEntity(UUID id, long untilMillis) {
            drawn.add("entity " + id);
            until.add(untilMillis);
        }
    }

    private static WorldTools tools(Evidence.Scope scope, Function<BlockPlace, Boolean> blockExists, Function<UUID, BlockPlace> entityPlace,
                                    FakeClient client) {
        return new WorldTools(() -> null, new WorldRegistry(), () -> scope, Runnable::run, new ServerWork(System::nanoTime), () -> java.util.concurrent.CompletableFuture.completedFuture(ClientFocus.NONE), blockExists, entityPlace, client);
    }

    /** The living mob stands at MOB_PLACE; any other id is gone. */
    private static BlockPlace mob(UUID id) {
        return MOB.equals(id) ? MOB_PLACE : null;
    }

    private static Evidence.NavigationTarget block(Evidence.Scope scope) {
        return new Evidence.NavigationTarget(scope, WorldTools.ID, "block|" + PLACE.text(), WorldNavigation.blockId(PLACE));
    }

    private static Evidence.NavigationTarget entity(Evidence.Scope scope) {
        return new Evidence.NavigationTarget(scope, WorldTools.ID, "entity|" + MOB, WorldNavigation.entityId(MOB));
    }

    @Test
    void navigationIdsRoundTrip() {
        assertEquals("world-block|minecraft:overworld|10,64,-3", WorldNavigation.blockId(PLACE));
        assertEquals("world-entity|3f1c2a4e-0000-4000-8000-000000000001", WorldNavigation.entityId(MOB));
        assertEquals(new WorldNavigation.Destination.Block(PLACE), WorldNavigation.parse(WorldNavigation.blockId(PLACE)));
        assertEquals(new WorldNavigation.Destination.Entity(MOB), WorldNavigation.parse(WorldNavigation.entityId(MOB)));
        for (var malformed : List.of("", "world-block|", "world-block|minecraft:overworld|1,2", "world-entity|not-a-uuid",
                "world-entity|1-1-1-1-1", "world-chest|minecraft:overworld|1,2,3", "block|minecraft:overworld|1,2,3")) {
            assertThrows(IllegalArgumentException.class, () -> WorldNavigation.parse(malformed), malformed);
        }
    }

    @Test
    void openingABrokenBlockOrDeadMobIsUnavailableAndDrawsNothing() {
        var client = new FakeClient();
        var gone = tools(SCOPE, place -> false, id -> null, client);
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, gone.open(block(SCOPE), SCOPE).status());
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, gone.open(entity(SCOPE), SCOPE).status());
        var failing = tools(SCOPE, place -> {
            throw new IllegalStateException("world highlights need an integrated singleplayer server");
        }, id -> {
            throw new IllegalStateException("world highlights need an integrated singleplayer server");
        }, client);
        var failed = failing.open(block(SCOPE), SCOPE);
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, failed.status());
        assertEquals("world highlights need an integrated singleplayer server", failed.detail());
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, failing.open(entity(SCOPE), SCOPE).status());
        assertEquals(List.of(), client.drawn);

        var before = System.currentTimeMillis();
        var present = tools(SCOPE, PLACE::equals, WorldNavigationTest::mob, client);
        assertEquals(Evidence.NavigationStatus.OPENED, present.open(block(SCOPE), SCOPE).status());
        assertEquals(Evidence.NavigationStatus.OPENED, present.open(entity(SCOPE), SCOPE).status());
        assertEquals(List.of("block " + PLACE.text(), "entity " + MOB), client.drawn);
        for (long until : client.until) assertTrue(until >= before + 30_000 && until <= System.currentTimeMillis() + 30_000, "until " + until);

        client.drawn.clear();
        client.dimension = "minecraft:the_nether";
        var elsewhere = tools(SCOPE, place -> {
            throw new AssertionError("another dimension is not read");
        }, WorldNavigationTest::mob, client).open(block(SCOPE), SCOPE);
        assertEquals(Evidence.NavigationStatus.OPENED, elsewhere.status());
        assertEquals("10, 64, -3 in minecraft:overworld", elsewhere.detail());
        assertEquals(List.of(), client.drawn);
    }

    @Test
    void aLivingMobTheClientCannotShowOpensWithItsPlaceAndDrawsNothing() {
        var client = new FakeClient();
        var tools = tools(SCOPE, PLACE::equals, WorldNavigationTest::mob, client);
        client.dimension = "minecraft:the_nether";
        var elsewhere = tools.open(entity(SCOPE), SCOPE);
        assertEquals(Evidence.NavigationStatus.OPENED, elsewhere.status());
        assertEquals("12, 65, -8 in minecraft:overworld", elsewhere.detail());

        client.dimension = "minecraft:overworld";
        client.tracksMob = false;
        var untracked = tools.open(entity(SCOPE), SCOPE);
        assertEquals(Evidence.NavigationStatus.OPENED, untracked.status());
        assertEquals("12, 65, -8 in minecraft:overworld", untracked.detail());
        assertEquals(List.of(), client.drawn);

        client.tracksMob = true;
        var shown = tools.open(entity(SCOPE), SCOPE);
        assertEquals(Evidence.NavigationStatus.OPENED, shown.status());
        assertNull(shown.detail());
        assertEquals(List.of("entity " + MOB), client.drawn);

        var malformed = new Evidence.NavigationTarget(SCOPE, WorldTools.ID, "block|x", "world-block|x");
        assertEquals(Evidence.NavigationStatus.ERROR, tools.open(malformed, SCOPE).status());
        assertEquals(List.of("entity " + MOB), client.drawn);
    }

    @Test
    void targetsSavedInTheConversationStoreOpenAfterARestart(@TempDir Path directory) throws Exception {
        var world = new ChatContracts.WorldIdentity("world", "World");
        var store = new ConversationStore(directory, Runnable::run);
        var conversation = store.newConversation(world).toCompletableFuture().join();
        var sources = List.of(block(SCOPE), entity(SCOPE)).stream().map(target -> new Evidence.Source(SCOPE, WorldTools.ID, WorldTools.VERSION,
                target.sourceHandle(), 1, Instant.EPOCH, Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.SERVER_READ,
                List.of())).toList();
        store.appendTurn(world, SCOPE, conversation.id(), new ChatContracts.Turn(new ChatContracts.RequestId("saved"), "Where is my chest?",
                "Here.", List.of(), new ChatContracts.Context("context", null, SCOPE), sources, List.of(block(SCOPE), entity(SCOPE)),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();

        // A new store, provider and scope stand in for a Minecraft restart; only the serialized turn survives.
        var saved = new ConversationStore(directory, Runnable::run).openLatest(world).toCompletableFuture().join()
                .turns().getFirst().navigationTargets();
        assertEquals(List.of(block(SCOPE), entity(SCOPE)), saved);
        var client = new FakeClient();
        var knowledge = new KnowledgeService(List.of(tools(RELOADED, PLACE::equals, WorldNavigationTest::mob, client)), () -> RELOADED);
        for (var target : saved) {
            var refreshed = knowledge.refreshNavigation(target, RELOADED).toCompletableFuture().get();
            assertEquals(new Evidence.NavigationTarget(RELOADED, WorldTools.ID, target.sourceHandle(), target.targetId()), refreshed);
            assertEquals(Evidence.NavigationStatus.OPENED, knowledge.open(refreshed, RELOADED).status());
        }
        assertEquals(List.of("block " + PLACE.text(), "entity " + MOB), client.drawn);
    }

    @Test
    void savedTargetsResolveAfterAReloadWithANewScope() throws Exception {
        var client = new FakeClient();
        var tools = tools(RELOADED, PLACE::equals, WorldNavigationTest::mob, client);
        var knowledge = new KnowledgeService(List.of(tools), () -> RELOADED);
        for (var saved : List.of(block(SCOPE), entity(SCOPE))) {
            var refreshed = knowledge.refreshNavigation(saved, RELOADED).toCompletableFuture().get();
            assertEquals(new Evidence.NavigationTarget(RELOADED, WorldTools.ID, saved.sourceHandle(), saved.targetId()), refreshed);
            assertEquals(Evidence.NavigationStatus.OPENED, knowledge.open(refreshed, RELOADED).status());
        }
        assertEquals(List.of("block " + PLACE.text(), "entity " + MOB), client.drawn);

        var malformed = new Evidence.NavigationTarget(SCOPE, WorldTools.ID, "block|x", "world-block|x");
        assertNull(tools.refreshNavigation(malformed, RELOADED).toCompletableFuture().get());
        var foreign = new Evidence.NavigationTarget(SCOPE, "config", "block|" + PLACE.text(), WorldNavigation.blockId(PLACE));
        assertNull(tools.refreshNavigation(foreign, RELOADED).toCompletableFuture().get());
        assertNull(tools.refreshNavigation(block(SCOPE), SCOPE).toCompletableFuture().get());
    }

    @Test
    void placeIdsRoundTrip() {
        assertEquals("world-place|minecraft:overworld|10,64,-3", WorldNavigation.placeId(PLACE));
        assertEquals(new WorldNavigation.Destination.Place(PLACE), WorldNavigation.parse(WorldNavigation.placeId(PLACE)));
        for (var malformed : List.of("world-place|", "world-place|minecraft:overworld|1,2", "world-place|minecraft:overworld|1,2,x")) {
            assertThrows(IllegalArgumentException.class, () -> WorldNavigation.parse(malformed), malformed);
        }
    }

    @Test
    void placesOpenWithoutAnExistenceCheckInTheSameDimension() {
        var client = new FakeClient();
        var tools = tools(SCOPE, place -> false, id -> null, client);
        var before = System.currentTimeMillis();
        var opened = tools.open(place(SCOPE), SCOPE);
        assertEquals(Evidence.NavigationStatus.OPENED, opened.status());
        assertNull(opened.detail());
        assertEquals(List.of("place " + PLACE.text()), client.drawn);
        long until = client.until.getFirst();
        assertTrue(until >= before + 30_000 && until <= System.currentTimeMillis() + 30_000, "until " + until);

        client.drawn.clear();
        client.dimension = null;
        assertEquals(Evidence.NavigationStatus.UNAVAILABLE, tools.open(place(SCOPE), SCOPE).status());
        assertEquals(List.of(), client.drawn);
    }

    @Test
    void placesInAnotherDimensionOpenWithCoordinates() {
        var client = new FakeClient();
        client.dimension = "minecraft:the_nether";
        var opened = tools(SCOPE, place -> {
            throw new AssertionError("a place is never read");
        }, id -> null, client).open(place(SCOPE), SCOPE);
        assertEquals(Evidence.NavigationStatus.OPENED, opened.status());
        assertEquals("10, 64, -3 in minecraft:overworld", opened.detail());
        assertEquals(List.of(), client.drawn);
    }

    @Test
    void savedPlaceTargetsRefreshIntoANewScope(@TempDir Path directory) throws Exception {
        var world = new ChatContracts.WorldIdentity("world", "World");
        var store = new ConversationStore(directory, Runnable::run);
        var conversation = store.newConversation(world).toCompletableFuture().join();
        var source = new Evidence.Source(SCOPE, WorldTools.ID, WorldTools.VERSION, place(SCOPE).sourceHandle(), 1, Instant.EPOCH,
                Evidence.Activity.ACTIVE, Evidence.Coverage.COMPLETE, Evidence.Authority.SERVER_READ, List.of());
        store.appendTurn(world, SCOPE, conversation.id(), new ChatContracts.Turn(new ChatContracts.RequestId("saved"), "Where is a desert?",
                "There.", List.of(), new ChatContracts.Context("context", null, SCOPE), List.of(source), List.of(place(SCOPE)),
                new ChatContracts.Outcome(ChatContracts.OutcomeStatus.COMPLETED, null), 1)).toCompletableFuture().join();

        // A new store, provider and scope stand in for a Minecraft restart; only the serialized turn survives.
        var saved = new ConversationStore(directory, Runnable::run).openLatest(world).toCompletableFuture().join()
                .turns().getFirst().navigationTargets();
        assertEquals(List.of(place(SCOPE)), saved);
        var client = new FakeClient();
        var tools = tools(RELOADED, place -> false, id -> null, client);
        var knowledge = new KnowledgeService(List.of(tools), () -> RELOADED);
        var refreshed = knowledge.refreshNavigation(saved.getFirst(), RELOADED).toCompletableFuture().get();
        assertEquals(new Evidence.NavigationTarget(RELOADED, WorldTools.ID, saved.getFirst().sourceHandle(), saved.getFirst().targetId()), refreshed);
        assertEquals(Evidence.NavigationStatus.OPENED, knowledge.open(refreshed, RELOADED).status());
        assertEquals(List.of("place " + PLACE.text()), client.drawn);

        var malformed = new Evidence.NavigationTarget(SCOPE, WorldTools.ID, "locate|x", "world-place|x");
        assertNull(tools.refreshNavigation(malformed, RELOADED).toCompletableFuture().get());
    }
}
