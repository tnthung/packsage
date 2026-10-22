package dev.packsage.knowledge.storage;

import com.mojang.serialization.JsonOps;
import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.inventory.InventoryReader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Donkey;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.horse.Mule;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.animal.horse.ZombieHorse;
import net.minecraft.world.entity.vehicle.ChestBoat;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.vehicle.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DropperBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.DropperBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.loot.LootTable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.function.LongSupplier;

final class NativeServerStorageReader {
    private NativeServerStorageReader() { }

    static CompletionStage<NativeStorageReader.Observation> read(Minecraft client, StorageReader.Request request, Evidence.Scope scope,
                                                                  LongSupplier monotonicClock, Supplier<Evidence.Scope> currentScope) {
        if (!client.isSameThread()) throw new IllegalStateException("target selection must run on the client thread");
        if (client.player == null || client.level == null) return CompletableFuture.completedFuture(
                unavailable(request, scope, monotonicClock, "client player or level unavailable"));
        Selection selection = null;
        if (request.target() == StorageReader.Target.RIDDEN) {
            var vehicle = client.player.getVehicle();
            if (vehicle != null) selection = new Selection(request.target(), client.player.getUUID(), client.level.dimension(),
                    vehicle.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()), vehicle.position(), null, null, null);
        } else if (client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            var pos = hit.getBlockPos();
            var state = client.level.getBlockState(pos);
            var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            selection = new Selection(request.target(), client.player.getUUID(), client.level.dimension(), null, null,
                    hit.getLocation(), pos, id == null ? null : id.toString(), state.toString());
        } else if (client.hitResult instanceof EntityHitResult hit && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = hit.getEntity();
            var type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            selection = new Selection(request.target(), client.player.getUUID(), client.level.dimension(), entity.getUUID(),
                    type, hit.getLocation(), null, null, null);
        }
        if (selection == null) return CompletableFuture.completedFuture(unavailable(request, scope, monotonicClock,
                request.target() == StorageReader.Target.RIDDEN ? "player is not riding a supported container entity" : "no supported targeted inventory"));
        var server = client.getSingleplayerServer();
        if (server == null) return CompletableFuture.completedFuture(
                unavailable(request, scope, monotonicClock, "private integrated server unavailable"));
        var selected = selection;
        return server.submit(() -> readServer(server, selected, request, scope, monotonicClock, currentScope))
                .handle((observation, failure) -> failure == null ? observation
                        : unavailable(request, scope, monotonicClock, "private integrated read failed"));
    }

    private static NativeStorageReader.Observation readServer(MinecraftServer server, Selection selection,
                                                              StorageReader.Request request, Evidence.Scope scope,
                                                              LongSupplier monotonicClock, Supplier<Evidence.Scope> currentScope) {
        if (!scope.equals(currentScope.get())) return unavailable(request, scope, monotonicClock, "evidence scope changed");
        ServerPlayer player = server.getPlayerList().getPlayer(selection.playerId());
        if (player == null || !player.serverLevel().dimension().equals(selection.dimension())) {
            return unavailable(request, scope, monotonicClock, "server player identity or dimension changed");
        }
        if (selection.target() == StorageReader.Target.RIDDEN) {
            var vehicle = player.getVehicle();
            if (vehicle == null || !vehicle.getUUID().equals(selection.entityId())) {
                return unavailable(request, scope, monotonicClock, "ridden entity changed");
            }
        }
        var level = player.serverLevel();
        if (selection.blockPos() != null) return readBlock(level, selection, player, request, scope, monotonicClock);
        Entity entity = level.getEntity(selection.entityId());
        if (entity == null || entity.isRemoved() || !entity.getUUID().equals(selection.entityId())
                || !Objects.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), selection.entityType())) {
            return unavailable(request, scope, monotonicClock, "target entity changed or is unavailable");
        }
        var entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (entityType == null) return unavailable(request, scope, monotonicClock, "target entity type is unavailable");
        if (entity instanceof AbstractHorse horse && supportedHorse(horse)) return readHorse(horse, player, request, scope, monotonicClock);
        if ((entity.getClass() == ChestBoat.class || entity.getClass() == MinecartChest.class)
                && entity instanceof ContainerEntity container) return readVehicleContainer(container, entity, player, request, scope, monotonicClock);
        return unavailable(request, scope, monotonicClock, "target entity type is unsupported");
    }

    private static NativeStorageReader.Observation readBlock(ServerLevel level, Selection selection, ServerPlayer player,
                                                             StorageReader.Request request, Evidence.Scope scope,
                                                             LongSupplier monotonicClock) {
        BlockPos pos = selection.blockPos();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return unavailable(request, scope, monotonicClock, "target chunk is not loaded");
        BlockState state = chunk.getBlockState(pos);
        var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (blockId == null || !blockId.toString().equals(selection.blockType())
                || !state.toString().equals(selection.blockState())) {
            return unavailable(request, scope, monotonicClock, "target block changed");
        }
        var blockEntity = chunk.getBlockEntity(pos);
        if (blockEntity == null || !supportedBlockEntity(state, blockEntity)) return unavailable(request, scope, monotonicClock,
                "target block inventory is unsupported");
        var halves = new ArrayList<BlockHalf>();
        if (state.getBlock() instanceof ChestBlock chest && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos otherPos = pos.relative(ChestBlock.getConnectedDirection(state));
            var otherChunk = level.getChunkSource().getChunkNow(otherPos.getX() >> 4, otherPos.getZ() >> 4);
            if (otherChunk == null) return unavailable(request, scope, monotonicClock, "both chest halves must already be loaded");
            BlockState otherState = otherChunk.getBlockState(otherPos);
            var opposite = otherState.hasProperty(ChestBlock.TYPE) ? otherState.getValue(ChestBlock.TYPE) : ChestType.SINGLE;
            var otherEntity = otherChunk.getBlockEntity(otherPos);
            if (otherState.getBlock() != state.getBlock() || otherState.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                    || opposite != state.getValue(ChestBlock.TYPE).getOpposite() || !(otherEntity instanceof ChestBlockEntity otherChest)
                    || otherEntity.getClass() != ChestBlockEntity.class || blockEntity.getClass() != ChestBlockEntity.class) {
                return unavailable(request, scope, monotonicClock, "double chest is incomplete or unsupported");
            }
            BlockPos above = pos.above();
            BlockPos otherAbove = otherPos.above();
            if (level.getChunkSource().getChunkNow(above.getX() >> 4, above.getZ() >> 4) == null
                    || level.getChunkSource().getChunkNow(otherAbove.getX() >> 4, otherAbove.getZ() >> 4) == null
                    || ChestBlock.isChestBlockedAt(level, pos) || ChestBlock.isChestBlockedAt(level, otherPos)) {
                return unavailable(request, scope, monotonicClock, "double chest is blocked or not fully loaded");
            }
            var clicked = (ChestBlockEntity) blockEntity;
            var first = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT ? clicked : otherChest;
            var second = state.getValue(ChestBlock.TYPE) == ChestType.RIGHT ? otherChest : clicked;
            halves.add(new BlockHalf(first, state.getValue(ChestBlock.TYPE) == ChestType.RIGHT ? pos : otherPos, "left"));
            halves.add(new BlockHalf(second, state.getValue(ChestBlock.TYPE) == ChestType.RIGHT ? otherPos : pos, "right"));
        } else {
            if (blockEntity instanceof ChestBlockEntity && state.getBlock() instanceof ChestBlock) {
                BlockPos above = pos.above();
                if (level.getChunkSource().getChunkNow(above.getX() >> 4, above.getZ() >> 4) == null
                        || ChestBlock.isChestBlockedAt(level, pos)) {
                    return unavailable(request, scope, monotonicClock, "chest is blocked or adjacent chunk is not loaded");
                }
            }
            halves.add(new BlockHalf((BaseContainerBlockEntity) blockEntity, pos, "container"));
        }
        for (BlockHalf half : halves) {
            var refusal = unreadableReason(half.container());
            if (refusal != null) return unavailable(request, scope, monotonicClock, refusal);
        }
        if (halves.stream().mapToInt(half -> half.container().getContainerSize()).sum() > 256) {
            return unavailable(request, scope, monotonicClock, "container contents exceed the read bound");
        }
        var entries = new ArrayList<Evidence.Record>();
        var positions = new ArrayList<Evidence.JsonValue>();
        var emptySlots = new ArrayList<Evidence.JsonValue>();
        var capturedAt = Instant.now();
        for (BlockHalf half : halves) {
            positions.add(position(half.position(), half.section()));
            emptySlots.addAll(blockEmptySlots(half.container(), half.section()));
            for (int slot = 0; slot < half.container().getContainerSize(); slot++) {
                ItemStack stack = half.container().getItem(slot).copy();
                if (stack.isEmpty()) continue;
                entries.add(storageEntry(stack, scope, capturedAt, "container", slot + (half.section().equals("right") ? 27 : 0), slot,
                        blockId + ":" + level.dimension().location() + ":" + pos.asLong() + ":" + selection.blockState(),
                        half.position(),
                        half.section(), level.registryAccess(), request.filter(), player, level));
            }
        }
        var stateValues = new LinkedHashMap<String, Evidence.JsonValue>();
        stateValues.put("id", new Evidence.JsonString(blockId.toString()));
        stateValues.put("position", position(pos, "target"));
        stateValues.put("positions", new Evidence.JsonArray(positions));
        stateValues.put("ownerIdentity", new Evidence.JsonString(blockId + ":" + level.dimension().location() + ":"
                + pos.asLong() + ":" + selection.blockState()));
        stateValues.put("observation", new Evidence.JsonString("private_integrated_owner"));
        stateValues.put("contentsStatus", new Evidence.JsonString("complete"));
        var coverage = entries.stream().anyMatch(entry -> entry.source().coverage() != Evidence.Coverage.COMPLETE)
                ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        if (coverage == Evidence.Coverage.COMPLETE) stateValues.put("emptySlots", new Evidence.JsonArray(emptySlots));
        return new NativeStorageReader.Observation(entries, scope, capturedAt, monotonicClock.getAsLong(), blockId.toString(),
                stateValues, coverage, List.of("private_integrated_owner", "server-read:block:" + blockId,
                "dimension:" + level.dimension().location(), "position:" + pos, "player:" + player.getUUID()));
    }

    private static NativeStorageReader.Observation readVehicleContainer(ContainerEntity container, Entity entity,
                                                                       ServerPlayer player, StorageReader.Request request,
                                                                       Evidence.Scope scope, LongSupplier monotonicClock) {
        if (container.getLootTable() != null) return unavailable(request, scope, monotonicClock, "pending loot table contents are unavailable");
        var capturedAt = Instant.now();
        var entries = new ArrayList<Evidence.Record>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot).copy();
            if (stack.isEmpty()) continue;
            entries.add(storageEntry(stack, scope, capturedAt, "cargo", slot, slot,
                    entity.getUUID() + ":" + player.serverLevel().dimension().location(), null, "cargo",
                    player.serverLevel().registryAccess(), request.filter(), player, player.serverLevel()));
            if (entries.size() == 256) return unavailable(request, scope, monotonicClock, "vehicle contents exceed the read bound");
        }
        var identity = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        var details = new LinkedHashMap<String, Evidence.JsonValue>();
        details.put("id", new Evidence.JsonString(identity == null ? "unknown:entity" : identity.toString()));
        details.put("ownerIdentity", new Evidence.JsonString(entity.getUUID() + ":" + player.serverLevel().dimension().location()));
        details.put("entityIdentity", new Evidence.JsonString(entity.getUUID().toString()));
        details.put("observation", new Evidence.JsonString("private_integrated_owner"));
        details.put("contentsStatus", new Evidence.JsonString("complete"));
        var coverage = entries.stream().anyMatch(entry -> entry.source().coverage() != Evidence.Coverage.COMPLETE)
                ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        return new NativeStorageReader.Observation(entries, scope, capturedAt, monotonicClock.getAsLong(), "Vehicle cargo", details,
                coverage, List.of("private_integrated_owner", "server-read:vehicle-container",
                "dimension:" + player.serverLevel().dimension().location(), "entity:" + entity.getUUID(), "player:" + player.getUUID()));
    }

    private static NativeStorageReader.Observation readHorse(AbstractHorse horse, ServerPlayer player, StorageReader.Request request,
                                                             Evidence.Scope scope, LongSupplier monotonicClock) {
        if (!horse.isTamed() || horse.isVehicle() && !horse.hasPassenger(player)) return unavailable(request, scope, monotonicClock,
                "horse inventory is not accessible to the current player");
        boolean llama = horse.getClass() == Llama.class || horse.getClass() == TraderLlama.class;
        var capturedAt = Instant.now();
        var entries = new ArrayList<Evidence.Record>();
        ItemStack bodyArmor = horse.getItemBySlot(EquipmentSlot.BODY).copy();
        if (!bodyArmor.isEmpty()) entries.add(storageEntry(bodyArmor, scope, capturedAt, llama ? "body_carpet" : "body_armor", 1, 0,
                horse.getUUID() + ":" + player.serverLevel().dimension().location(), null, llama ? "body_carpet" : "body_armor",
                player.serverLevel().registryAccess(), request.filter(), player, player.serverLevel()));
        var container = horse.getInventory();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot).copy();
            if (llama && slot == 0) {
                if (!stack.isEmpty()) return unavailable(request, scope, monotonicClock, "llama saddle slot is outside its inventory contract");
                continue;
            }
            if (stack.isEmpty()) continue;
            String section = slot == 0 ? "saddle" : "cargo";
            entries.add(storageEntry(stack, scope, capturedAt, section, slot == 0 ? 0 : slot + 1, slot,
                    horse.getUUID() + ":" + player.serverLevel().dimension().location(), null, section,
                    player.serverLevel().registryAccess(), request.filter(), player, player.serverLevel()));
            if (entries.size() == 256) return unavailable(request, scope, monotonicClock, "horse inventory exceeds the read bound");
        }
        var identity = BuiltInRegistries.ENTITY_TYPE.getKey(horse.getType());
        var details = new LinkedHashMap<String, Evidence.JsonValue>();
        details.put("id", new Evidence.JsonString(identity == null ? "unknown:horse" : identity.toString()));
        details.put("ownerIdentity", new Evidence.JsonString(horse.getUUID() + ":" + player.serverLevel().dimension().location()));
        details.put("entityIdentity", new Evidence.JsonString(horse.getUUID().toString()));
        details.put("observation", new Evidence.JsonString("private_integrated_owner"));
        details.put("contentsStatus", new Evidence.JsonString("complete"));
        var coverage = entries.stream().anyMatch(entry -> entry.source().coverage() != Evidence.Coverage.COMPLETE)
                ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE;
        return new NativeStorageReader.Observation(entries, scope, capturedAt, monotonicClock.getAsLong(), "Horse inventory", details,
                coverage, List.of("private_integrated_owner", "server-read:horse",
                "dimension:" + player.serverLevel().dimension().location(), "entity:" + horse.getUUID(), "player:" + player.getUUID()));
    }

    private static Evidence.Record storageEntry(ItemStack stack, Evidence.Scope scope, Instant capturedAt,
                                                String storageType, int slot, int sourceSlot, String ownerIdentity,
                                                BlockPos position, String section, net.minecraft.core.HolderLookup.Provider registries,
                                                InventoryReader.Filter filter, ServerPlayer player, Level level) {
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        var identity = id == null ? "unknown:item" : id.toString();
        var name = stack.getHoverName().getString();
        boolean nameTruncated = name.length() > 256;
        if (name.length() > 256) name = name.substring(0, 256);
        var payload = new LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("name", new Evidence.JsonString(name));
        payload.put("count", new Evidence.JsonNumber(BigDecimal.valueOf(stack.getCount())));
        payload.put("ownerIdentity", new Evidence.JsonString(ownerIdentity));
        payload.put("section", new Evidence.JsonString(section));
        payload.put("storageType", new Evidence.JsonString(storageType));
        payload.put("componentSlot", new Evidence.JsonNumber(BigDecimal.valueOf(sourceSlot)));
        payload.put("menuSlot", new Evidence.JsonNumber(BigDecimal.valueOf(slot)));
        Evidence.JsonObject components;
        try {
            components = net.minecraft.core.component.DataComponentMap.CODEC.encodeStart(
                    registries.createSerializationContext(JsonOps.INSTANCE), stack.getComponents().filter(type ->
                            type != DataComponents.CONTAINER && type != DataComponents.BUNDLE_CONTENTS))
                    .result().filter(com.google.gson.JsonElement::isJsonObject).map(value -> Evidence.deserializeObject(value.toString()))
                    .orElseGet(() -> new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown"))));
        } catch (RuntimeException failure) {
            components = new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown")));
        }
        boolean metadataTruncated = components.values().containsKey("status")
                || Evidence.serialize(components).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 12 * 1024;
        boolean partial = metadataTruncated || nameTruncated && !filter.names().isEmpty();
        payload.put("components", metadataTruncated ? new Evidence.JsonObject(Map.of("status", new Evidence.JsonString("unknown_truncated"))) : components);
        if (dev.packsage.knowledge.nativegame.NativeItemFacts.enrich(payload, stack, registries)) partial = true;
        if (metadataTruncated) payload.put("metadataStatus", new Evidence.JsonString("unknown_truncated"));
        if (nameTruncated) payload.put("nameStatus", new Evidence.JsonString("truncated"));
        if (!filter.tags().isEmpty()) {
            try {
                var tags = stack.getTags().limit(129).map(tag -> tag.location().toString()).toList();
                payload.put("tags", new Evidence.JsonArray(tags.stream().limit(128).map(Evidence.JsonString::new)
                        .map(value -> (Evidence.JsonValue) value).toList()));
                partial |= tags.size() > 128;
            } catch (RuntimeException failure) {
                payload.put("tags", new Evidence.JsonArray(List.of()));
                partial = true;
            }
        }
        if (!filter.tooltip().isEmpty()) {
            try {
                var lines = stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(level), player,
                        net.minecraft.world.item.TooltipFlag.NORMAL).stream().map(Component::getString).toList();
                payload.put("tooltip", new Evidence.JsonArray(lines.stream().limit(32).map(value -> new Evidence.JsonString(
                                value.length() <= 256 ? value : value.substring(0, 256)))
                        .map(value -> (Evidence.JsonValue) value).toList()));
                partial |= lines.size() > 32 || lines.stream().anyMatch(value -> value.length() > 256);
            } catch (RuntimeException failure) {
                payload.put("tooltip", new Evidence.JsonArray(List.of()));
                partial = true;
            }
        }
        if (position != null) payload.put("position", position(position, section));
        var source = new Evidence.Source(scope, "native", "1.21.1", "server-storage-entry:" + scope.worldSession() + ":"
                + ownerIdentity + ":" + section + ":" + sourceSlot + ":" + slot,
                scope.requestGeneration(), capturedAt, Evidence.Activity.ACTIVE, partial ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                Evidence.Authority.SERVER_READ, List.of("private_integrated_owner", "server-read", "serialized-item-components"));
        return new Evidence.Record(source, Evidence.RecordKind.ITEM, identity, new Evidence.JsonObject(payload), List.of());
    }

    private static Evidence.JsonObject position(BlockPos pos, String section) {
        return new Evidence.JsonObject(Map.of("x", new Evidence.JsonNumber(BigDecimal.valueOf(pos.getX())),
                "y", new Evidence.JsonNumber(BigDecimal.valueOf(pos.getY())), "z", new Evidence.JsonNumber(BigDecimal.valueOf(pos.getZ())),
                "section", new Evidence.JsonString(section)));
    }

    static String unreadableReason(BaseContainerBlockEntity container) {
        if (container instanceof RandomizableContainerBlockEntity randomizable && randomizable.getLootTable() != null) {
            return "pending loot table contents are unavailable";
        }
        return null;
    }

    static List<Evidence.JsonValue> blockEmptySlots(BaseContainerBlockEntity container, String section) {
        var emptySlots = new ArrayList<Evidence.JsonValue>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) emptySlots.add(new Evidence.JsonObject(Map.of(
                    "section", new Evidence.JsonString(section),
                    "componentSlot", new Evidence.JsonNumber(BigDecimal.valueOf(slot)))));
        }
        return emptySlots;
    }

    private static boolean supportedHorse(AbstractHorse horse) {
        return horse.getClass() == Horse.class || horse.getClass() == Donkey.class || horse.getClass() == Mule.class
                || horse.getClass() == SkeletonHorse.class || horse.getClass() == ZombieHorse.class
                || horse.getClass() == Llama.class || horse.getClass() == TraderLlama.class;
    }

    private static boolean supportedBlockEntity(BlockState state, net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
        Class<?> type = blockEntity.getClass();
        Class<?> block = state.getBlock().getClass();
        return type == ChestBlockEntity.class && block == ChestBlock.class
                || type == BarrelBlockEntity.class && block == BarrelBlock.class
                || type == ShulkerBoxBlockEntity.class && block == ShulkerBoxBlock.class
                || type == DispenserBlockEntity.class && block == DispenserBlock.class
                || type == DropperBlockEntity.class && block == DropperBlock.class
                || type == HopperBlockEntity.class && block == HopperBlock.class
                || type == FurnaceBlockEntity.class && block == FurnaceBlock.class
                || type == SmokerBlockEntity.class && block == SmokerBlock.class
                || type == BlastFurnaceBlockEntity.class && block == BlastFurnaceBlock.class
                || type == BrewingStandBlockEntity.class && block == BrewingStandBlock.class;
    }

    private static NativeStorageReader.Observation unavailable(StorageReader.Request request, Evidence.Scope scope,
                                                               LongSupplier monotonicClock, String reason) {
        var details = Map.<String, Evidence.JsonValue>of("status", new Evidence.JsonString("unavailable"),
                "detail", new Evidence.JsonString(reason), "contentsStatus", new Evidence.JsonString("unavailable"),
                "completeness", new Evidence.JsonString("unknown"));
        return new NativeStorageReader.Observation(List.of(), scope, Instant.now(), monotonicClock.getAsLong(),
                request.target().name().toLowerCase(java.util.Locale.ROOT), details, Evidence.Coverage.UNAVAILABLE,
                List.of("private_integrated_owner", reason));
    }

    private record Selection(StorageReader.Target target, UUID playerId, ResourceKey<Level> dimension,
                             UUID entityId, ResourceLocation entityType, Vec3 hitPosition,
                             BlockPos blockPos, String blockType, String blockState) {
        private Selection {
            Objects.requireNonNull(target);
            Objects.requireNonNull(playerId);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(hitPosition);
        }
    }

    private record BlockHalf(BaseContainerBlockEntity container, BlockPos position, String section) { }
}
