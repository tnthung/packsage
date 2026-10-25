package dev.packsage.world;

import dev.packsage.knowledge.AdapterRegistry;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import dev.packsage.model.PackSageTools;
import dev.packsage.world.minecraft.MinecraftEntities;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInspectionTest {
    private static final Evidence.Scope SCOPE = new Evidence.Scope("world", "resources", "team", "runtime", 1);

    private static Evidence.Result inspect(DenseWorld.World world, Entity entity) throws Exception {
        world.entities.add(entity);
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var server = (net.minecraft.client.server.IntegratedServer) ((sun.misc.Unsafe) field.get(null))
                .allocateInstance(net.minecraft.client.server.IntegratedServer.class);
        var levels = net.minecraft.server.MinecraftServer.class.getDeclaredField("levels");
        levels.setAccessible(true);
        levels.set(server, Map.of(net.minecraft.world.level.Level.OVERWORLD, world));
        var running = net.minecraft.server.MinecraftServer.class.getDeclaredField("running");
        running.setAccessible(true);
        running.set(server, true);
        var registry = new WorldRegistry();
        registry.register(new MinecraftEntities());
        var work = new ServerWork(System::nanoTime);
        var tools = new WorldTools(() -> server, registry, () -> SCOPE, Runnable::run, work,
                () -> CompletableFuture.completedFuture(ClientFocus.NONE), place -> false, id -> null, null);
        var result = tools.handle(new PackSageTools.ReadRequest("inspect_entity", new Evidence.JsonObject(Map.of(
                "id", new Evidence.JsonString(entity.getUUID().toString()))), SCOPE),
                new Toolbox.Context(() -> SCOPE, Map.of(), new AdapterRegistry(), new Toolbox.DocumentBudget(), null)).toCompletableFuture();
        while (!result.isDone()) work.tick();
        return result.get();
    }

    @Test
    void hugeCustomNameIsCappedWithoutDroppingTheEntityFacts() throws Exception {
        var world = DenseWorld.create(0);
        var cow = new Cow(EntityType.COW, world);
        cow.setCustomName(Component.literal("x".repeat(1023) + "\uD83D\uDE00" + "x".repeat(300_000)));

        var result = inspect(world, cow);

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        var record = result.records().getFirst();
        assertTrue(PackSageTools.encodeRecord(record).toString().getBytes(StandardCharsets.UTF_8).length < 65_536);
        assertTrue(record.title().length() < 2048);
        assertEquals(new Evidence.JsonString("x".repeat(1023)), record.payload().values().get("name"));
        assertEquals(new Evidence.JsonBoolean(true), record.payload().values().get("nameTruncated"));
        var section = (Evidence.JsonObject) ((Evidence.JsonObject) record.payload().values().get("sections")).values().get("minecraft");
        var facts = (Evidence.JsonObject) section.values().get("facts");
        assertEquals(new Evidence.JsonString("x".repeat(1023)), facts.values().get("customName"));
        assertEquals(new Evidence.JsonBoolean(true), facts.values().get("nameTruncated"));
        assertTrue(facts.values().containsKey("health"));
        assertTrue(((Evidence.JsonString) section.values().get("note")).value().contains("truncated"));
    }

    @Test
    void cappedOffersPropagatePartialCoverageThroughTheToolResult() throws Exception {
        var world = DenseWorld.create(0);
        var villager = new Villager(EntityType.VILLAGER, world);
        var offers = new MerchantOffers();
        for (int i = 0; i < 65; i++) offers.add(new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 12, 1, 0.05f));
        var field = AbstractVillager.class.getDeclaredField("offers");
        field.setAccessible(true);
        field.set(villager, offers);

        var result = inspect(world, villager);

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Coverage.PARTIAL, result.records().getFirst().source().coverage());
        assertTrue(Evidence.serialize(result.records().getFirst().payload()).contains("bounded"));
    }

    @Test
    void cappedAttributeModifiersPropagatePartialCoverageThroughTheToolResult() throws Exception {
        var world = DenseWorld.create(0);
        var cow = new Cow(EntityType.COW, world);
        for (int i = 0; i < 65; i++) cow.getAttribute(Attributes.MOVEMENT_SPEED).addTransientModifier(new AttributeModifier(
                ResourceLocation.fromNamespaceAndPath("test", "modifier_" + i), 0.01, AttributeModifier.Operation.ADD_VALUE));

        var result = inspect(world, cow);

        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        assertEquals(Evidence.Coverage.PARTIAL, result.records().getFirst().source().coverage());
        assertTrue(Evidence.serialize(result.records().getFirst().payload()).contains("bounded"));
    }

    @Test
    void aComponentHeavyVillagerStillReturnsABoundedPartialRecord() throws Exception {
        var world = DenseWorld.create(0);
        var villager = new Villager(EntityType.VILLAGER, world);
        var offers = new MerchantOffers();
        for (int i = 0; i < 64; i++) {
            var stack = new ItemStack(Items.BREAD);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("x".repeat(3000)));
            offers.add(new MerchantOffer(new ItemCost(Items.EMERALD), stack, 12, 1, 0.05f));
        }
        var field = AbstractVillager.class.getDeclaredField("offers");
        field.setAccessible(true);
        field.set(villager, offers);

        var result = inspect(world, villager);

        assertEquals(1, result.records().size());
        assertEquals(Evidence.Coverage.PARTIAL, result.coverage());
        var payload = Evidence.serialize(result.records().getFirst().payload());
        assertTrue(payload.getBytes(StandardCharsets.UTF_8).length < 64_000, "payload bytes " + payload.length());
        assertTrue(payload.contains("byte budget"), payload);
        assertTrue(payload.contains("health"), "small entity facts survive oversized offers");
    }
}
