package dev.packsage.journal;

import dev.packsage.PackSageLog;
import dev.packsage.world.BlockPlace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Read-only extraction on the integrated server; all disk work belongs to Journal's background threads. */
public final class JournalHooks {
    private record Interaction(ContainerSessions.Holder holder, long gameTime) {
    }

    static final class Session {
        MinecraftServer server;
        final ContainerSessions containers = new ContainerSessions();
        final PortalTracker portals = new PortalTracker();
        final PortalTracker sources = new PortalTracker();
        final VillagerWatch villagers = new VillagerWatch();
        final VillagerPoll villagerPoll = new VillagerPoll();
        final RaidWatch raids = new RaidWatch();
        final StructureWatch structures = new StructureWatch();
        final LinkedHashMap<UUID, Interaction> interactions = new LinkedHashMap<>(16, 0.75f, true);
        boolean polling = true;
        boolean raidPolling = true;
        int ticks;
        long openingNanos;
        long reportedEvictions;
        long reportedOversized;
        long lastOverflowLogNanos = System.nanoTime();
        long reportedOverflow;

        void updateStats(JournalStats stats) {
            var evictions = containers.evictions() + portals.evictions() + sources.evictions() + villagers.evictions()
                    + raids.evictions();
            stats.trackerEvictions.add(evictions - reportedEvictions);
            reportedEvictions = evictions;
            stats.oversizedSnapshots.add(containers.oversizedSnapshots() - reportedOversized);
            reportedOversized = containers.oversizedSnapshots();
        }
    }

    private final Journal journal;
    private final Supplier<MinecraftServer> integrated;
    private Session session = new Session();

    public JournalHooks(Journal journal, Supplier<MinecraftServer> integrated) {
        this.journal = journal;
        this.integrated = integrated;
    }

    Session newSession() {
        session = new Session();
        return session;
    }

    private void start(MinecraftServer server) {
        if (session.server == server || !server.isRunning() || server.isStopped()) return;
        newSession();
        session.server = server;
        var overworld = server.overworld();
        long opening = System.nanoTime();
        journal.open(server.getWorldPath(LevelResource.ROOT).resolve("packsage").resolve("journal.db"),
                overworld::getGameTime);
        session.openingNanos += System.nanoTime() - opening;
    }

    /** The whole handler, including server lookup and data extraction, is inside this failure boundary. */
    private void hook(Object source, Consumer<MinecraftServer> action) {
        long started = 0;
        boolean serverThread = false;
        try {
            // Minecraft clears its server reference before ServerStoppingEvent and ServerStoppedEvent.
            var server = integrated.get();
            if (server == null) server = session.server;
            if (server == null || !server.isSameThread()) return;
            serverThread = true;
            started = System.nanoTime();
            action.accept(server);
        } catch (RuntimeException e) {
            journal.stats().hookFailures.increment();
        } finally {
            if (serverThread) {
                session.updateStats(journal.stats());
                long elapsed = System.nanoTime() - started;
                long opening = Math.min(session.openingNanos, elapsed);
                session.openingNanos = 0;
                // Opening and closing with the world are not gameplay work, so they stay out of the per-tick figure.
                if (source instanceof ServerStartedEvent || source instanceof ServerStoppingEvent || source instanceof ServerStoppedEvent
                        || "world start".equals(source)) {
                    journal.stats().worldStartStopNanos(elapsed);
                } else {
                    if (opening > 0) journal.stats().worldStartStopNanos(opening);
                    journal.stats().hookNanos(source instanceof String text ? text : source.getClass().getSimpleName(), elapsed - opening);
                }
            }
        }
    }

    public void register(IEventBus bus) {
        bus.addListener(EventPriority.NORMAL, false, ServerStartedEvent.class, event -> hook(event, server -> {
            if (server == null || event.getServer() != server) return;
            start(server);
        }));
        bus.addListener(EventPriority.NORMAL, false, ServerStoppingEvent.class, event -> hook(event, server -> {
            if (server == null || event.getServer() != server) return;
            session.polling = false;
            for (var player : server.getPlayerList().getPlayers()) {
                // A modded menu can fail independently; still close the other players' snapshots.
                try {
                    closeContainer(player, player.containerMenu);
                } catch (RuntimeException e) {
                    journal.stats().hookFailures.increment();
                }
            }
        }));
        bus.addListener(EventPriority.NORMAL, false, ServerStoppedEvent.class, event -> hook(event, server -> {
            if (server == null || event.getServer() != server) return;
            session.polling = false;
            PackSageLog.LOGGER.info("PackSage {}", journal.stats().summary());
            journal.close();
            session.server = null;
        }));
        bus.addListener(EventPriority.LOWEST, false, BlockEvent.BreakEvent.class, event -> hook(event, server -> {
            if (server == null || !(event.getLevel() instanceof ServerLevel level) || level.isClientSide()
                    || level.getServer() != server || !(event.getPlayer() instanceof ServerPlayer player)) return;
            var pos = event.getPos();
            journal.record(JournalEvents.broken(new JournalEvents.Stamp(server.overworld().getGameTime(), System.currentTimeMillis(),
                    level.dimension().location().toString()), "player:" + player.getUUID(), player.getScoreboardName(),
                    BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString(), pos.getX(), pos.getY(), pos.getZ()));
        }));
        bus.addListener(EventPriority.LOWEST, false, BlockEvent.EntityPlaceEvent.class, event -> hook(event, server -> {
            if (server == null || !(event.getLevel() instanceof ServerLevel level) || level.isClientSide()
                    || level.getServer() != server || !(event.getEntity() instanceof ServerPlayer player)) return;
            var pos = event.getPos();
            journal.record(JournalEvents.place(new JournalEvents.Stamp(server.overworld().getGameTime(), System.currentTimeMillis(),
                    level.dimension().location().toString()), "player:" + player.getUUID(), player.getScoreboardName(),
                    BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString(), pos.getX(), pos.getY(), pos.getZ()));
        }));
        bus.addListener(EventPriority.LOWEST, false, ExplosionEvent.Detonate.class, event -> hook(event, server -> {
            if (server == null || !(event.getLevel() instanceof ServerLevel level) || level.isClientSide() || level.getServer() != server) return;
            var explosion = event.getExplosion();
            var source = explosion.getIndirectSourceEntity();
            var direct = explosion.getDirectSourceEntity();
            var center = explosion.center();
            JournalEvents.explosion(new JournalEvents.Stamp(server.overworld().getGameTime(), System.currentTimeMillis(),
                            level.dimension().location().toString()), source instanceof ServerPlayer player ? "player:" + player.getUUID()
                            : source == null ? "environment" : "entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(source.getType()),
                    direct == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).toString(),
                    center.x, center.y, center.z, explosionBlocks(event.getAffectedBlocks(), pos -> {
                        var state = level.getBlockState(pos);
                        return state.isAir() ? null : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    }, journal.stats())).forEach(journal::record);
        }));
        bus.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.RightClickBlock.class, event -> hook(event, server -> {
            if (server == null || !(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()
                    || player.getServer() != server) return;
            var pos = event.getPos();
            rememberInteraction(player.getUUID(), new ContainerSessions.Holder(
                    BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).getBlock()).toString(),
                    player.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), null), server.overworld().getGameTime());
        }));
        bus.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.EntityInteract.class, event -> hook(event, server -> {
            if (server == null || !(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()
                    || player.getServer() != server) return;
            var entity = event.getTarget();
            var pos = entity.blockPosition();
            rememberInteraction(player.getUUID(), new ContainerSessions.Holder(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), entity.getStringUUID()),
                    server.overworld().getGameTime());
        }));
        bus.addListener(EventPriority.NORMAL, false, PlayerContainerEvent.Open.class, event -> hook(event, server -> {
            if (server == null || !(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()
                    || player.getServer() != server || !session.polling) return;
            openContainer(player, event.getContainer(), server.overworld().getGameTime());
        }));
        bus.addListener(EventPriority.NORMAL, false, PlayerContainerEvent.Close.class, event -> hook(event, server -> {
            if (server == null || !(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()
                    || player.getServer() != server) return;
            closeContainer(player, event.getContainer());
        }));
        bus.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class, event -> hook(event, server -> {
            var victim = event.getEntity();
            if (server == null || !(victim.level() instanceof ServerLevel level) || level.isClientSide() || level.getServer() != server) return;
            var cause = event.getSource();
            var killer = cause.getEntity();
            var tamed = victim instanceof TamableAnimal animal && animal.isTame()
                    || victim instanceof AbstractHorse horse && horse.isTamed();
            var recordDeath = JournalEvents.recordsDeath(victim instanceof ServerPlayer, victim instanceof Villager, tamed, victim.hasCustomName());
            if (!recordDeath && !(killer instanceof ServerPlayer)) return;
            var direct = cause.getDirectEntity();
            var pos = victim.blockPosition();
            var facts = new JournalEvents.DeathFacts(BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString(),
                    victim.getStringUUID(), victim.hasCustomName() ? victim.getCustomName().getString() : null,
                    victim instanceof ServerPlayer, victim instanceof Villager,
                    tamed,
                    victim instanceof Villager villager ? BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()).toString() : null,
                    cause.typeHolder().unwrapKey().map(key -> key.location().toString()).orElse("unknown"),
                    killer instanceof ServerPlayer player ? "player:" + player.getUUID()
                            : killer == null ? null : "entity:" + BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType()),
                    direct == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(direct.getType()).toString(),
                    recordDeath ? cause.getLocalizedDeathMessage(victim).getString() : null, pos.getX(), pos.getY(), pos.getZ());
            var stamp = new JournalEvents.Stamp(server.overworld().getGameTime(), System.currentTimeMillis(), level.dimension().location().toString());
            JournalEvents.death(stamp, facts).ifPresent(journal::record);
            JournalEvents.kill(stamp, facts).ifPresent(journal::record);
        }));
        bus.addListener(EventPriority.LOWEST, false, EntityTravelToDimensionEvent.class, event -> hook(event, server -> {
            if (server == null || !(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()
                    || player.getServer() != server) return;
            var pos = player.blockPosition();
            var dimension = player.level().dimension().location().toString();
            session.portals.arrived("player:" + player.getUUID(), dimension, server.overworld().getGameTime());
            session.sources.leaving("player:" + player.getUUID(), dimension,
                    new BlockPlace(dimension, pos.getX(), pos.getY(), pos.getZ()), server.overworld().getGameTime());
            if (player.portalProcess != null) {
                var portalPos = player.portalProcess.getEntryPosition();
                if (pos.distSqr(portalPos) <= 9 && player.level().hasChunkAt(portalPos)) {
                    var state = player.level().getBlockState(portalPos);
                    if (state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY)) {
                        session.portals.leaving("player:" + player.getUUID(), dimension,
                                new BlockPlace(dimension, portalPos.getX(), portalPos.getY(), portalPos.getZ()), server.overworld().getGameTime());
                    }
                }
            }
        }));
        bus.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerChangedDimensionEvent.class, event -> hook(event, server -> {
            if (server == null || !(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()
                    || player.getServer() != server) return;
            var time = server.overworld().getGameTime();
            var source = event.getFrom().location().toString();
            var destination = event.getTo().location().toString();
            var last = session.sources.arrived("player:" + player.getUUID(), source, time);
            var portal = session.portals.arrived("player:" + player.getUUID(), source, time);
            if (last.isEmpty()) {
                journal.stats().hookFailures.increment();
                return;
            }
            var pos = player.blockPosition();
            journal.record(JournalEvents.dimension(new JournalEvents.Stamp(time, System.currentTimeMillis(), source),
                    "player:" + player.getUUID(), destination, portal.orElse(null),
                    new BlockPlace(destination, pos.getX(), pos.getY(), pos.getZ()), last.get()));
        }));
        bus.addListener(EventPriority.NORMAL, false, AdvancementEvent.AdvancementEarnEvent.class, event -> hook(event, server -> {
            if (server == null || !(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()
                    || player.getServer() != server) return;
            var display = event.getAdvancement().value().display();
            if (display.isEmpty()) return;
            var pos = player.blockPosition();
            journal.record(JournalEvents.advancement(new JournalEvents.Stamp(server.overworld().getGameTime(), System.currentTimeMillis(),
                    player.level().dimension().location().toString()), "player:" + player.getUUID(), event.getAdvancement().id().toString(),
                    display.get().getTitle().getString(), pos.getX(), pos.getY(), pos.getZ()));
        }));
        bus.addListener(EventPriority.NORMAL, false, LivingConversionEvent.Post.class, event -> hook(event, server -> {
            var before = event.getEntity();
            if (server == null || !(before.level() instanceof ServerLevel level) || level.isClientSide() || level.getServer() != server) return;
            var after = event.getOutcome();
            var pos = after.blockPosition();
            var stamp = new JournalEvents.Stamp(server.overworld().getGameTime(), System.currentTimeMillis(), level.dimension().location().toString());
            if (before instanceof ZombieVillager && after instanceof Villager) {
                journal.record(JournalEvents.cured(stamp, after.getStringUUID(), pos.getX(), pos.getY(), pos.getZ()));
            } else if (before instanceof Villager && after instanceof ZombieVillager) {
                journal.record(JournalEvents.converted(stamp, before.getStringUUID(), pos.getX(), pos.getY(), pos.getZ()));
            }
        }));
        bus.addListener(EventPriority.NORMAL, false, TradeWithVillagerEvent.class, event -> hook(event, server -> {
            if (server == null || !(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide()
                    || player.getServer() != server) return;
            var villager = event.getAbstractVillager();
            var offer = event.getMerchantOffer();
            var pos = villager.blockPosition();
            journal.record(JournalEvents.trade(new JournalEvents.Stamp(server.overworld().getGameTime(), System.currentTimeMillis(),
                    player.level().dimension().location().toString()), "player:" + player.getUUID(), villager.getStringUUID(),
                    villager instanceof Villager normal ? BuiltInRegistries.VILLAGER_PROFESSION.getKey(normal.getVillagerData().getProfession()).toString() : null,
                    BuiltInRegistries.ITEM.getKey(offer.getResult().getItem()).toString(), offer.getResult().getCount(),
                    BuiltInRegistries.ITEM.getKey(offer.getCostA().getItem()).toString(),
                    offer.getCostB().isEmpty() ? null : BuiltInRegistries.ITEM.getKey(offer.getCostB().getItem()).toString(),
                    pos.getX(), pos.getY(), pos.getZ()));
        }));
        bus.addListener(EventPriority.NORMAL, false, ServerTickEvent.Post.class, event -> {
            hook(event, server -> {
                if (server == null || event.getServer() != server) return;
                // Registration can miss ServerStartedEvent before Minecraft publishes isReady.
                start(server);
                if (!session.polling || !journal.recording()) return;
                session.ticks++;
                poll(server);
                var now = System.nanoTime();
                var overflowed = journal.stats().overflowed.sum();
                if (now - session.lastOverflowLogNanos >= TimeUnit.MINUTES.toNanos(1) && overflowed > session.reportedOverflow) {
                    PackSageLog.LOGGER.info("PackSage {}", journal.stats().summary());
                    session.lastOverflowLogNanos = now;
                    session.reportedOverflow = overflowed;
                }
            });
            journal.stats().endTick();
        });
        try {
            var server = integrated.get();
            if (server != null) server.execute(() -> hook("world start", current -> {
                // Queued tasks also run while paused; no further server tick is required for a ready world.
                if (current == server && server.isReady()) start(server);
            }));
        } catch (RuntimeException e) {
            journal.stats().hookFailures.increment();
        }
    }

    static Map<String, long[]> explosionBlocks(List<BlockPos> positions, Function<BlockPos, String> blockId, JournalStats stats) {
        var count = 0;
        var grouped = new HashMap<String, ArrayList<Long>>();
        // Unread positions may be air; only count confirmed non-air losses.
        if (positions.size() > 8_192) stats.unreadExplosionPositions.add(positions.size() - 8_192);
        for (int i = 0; i < Math.min(positions.size(), 8_192); i++) {
            var pos = positions.get(i);
            var id = blockId.apply(pos);
            if (id != null) {
                if (count < 4_096) {
                    grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(Positions.pack(pos.getX(), pos.getY(), pos.getZ()));
                    count++;
                } else {
                    stats.droppedExplosionPositions.increment();
                }
            }
        }
        var result = new HashMap<String, long[]>();
        grouped.forEach((id, packed) -> result.put(id, packed.stream().mapToLong(Long::longValue).toArray()));
        return result;
    }

    private void rememberInteraction(UUID player, ContainerSessions.Holder holder, long gameTime) {
        session.interactions.put(player, new Interaction(holder, gameTime));
        if (session.interactions.size() > 64) {
            session.interactions.pollFirstEntry();
        }
    }

    private void openContainer(ServerPlayer player, AbstractContainerMenu menu, long gameTime) {
        ContainerSessions.Holder holder = null;
        for (var slot : menu.slots) {
            if (slot.container instanceof Inventory || slot.container instanceof CraftingContainer
                    || slot.container instanceof ResultContainer || slot.container instanceof MerchantContainer) continue;
            if (slot.container instanceof BlockEntity block) {
                var pos = block.getBlockPos();
                holder = new ContainerSessions.Holder(BuiltInRegistries.BLOCK.getKey(block.getBlockState().getBlock()).toString(),
                        player.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), null);
                break;
            }
            if (holder == null && slot.container instanceof Entity entity) {
                var pos = entity.blockPosition();
                holder = new ContainerSessions.Holder(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                        entity.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), entity.getStringUUID());
            }
        }
        if (holder == null && menu instanceof HorseInventoryMenu mount) {
            // A mount's screen shows its own container, not the mob, and opening it with the inventory key while riding
            // has no interaction to fall back on; the menu names its mount.
            var horse = mount.horse;
            var pos = horse.blockPosition();
            holder = new ContainerSessions.Holder(BuiltInRegistries.ENTITY_TYPE.getKey(horse.getType()).toString(),
                    horse.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ(), horse.getStringUUID());
        }
        if (holder == null) {
            var interaction = session.interactions.get(player.getUUID());
            if (interaction != null && gameTime >= interaction.gameTime() && gameTime - interaction.gameTime() <= 2) {
                holder = interaction.holder();
            }
        }
        if (holder != null) {
            session.containers.open("player:" + player.getUUID(), menu.containerId, holder, contents(menu));
        }
    }

    private void closeContainer(ServerPlayer player, AbstractContainerMenu menu) {
        if (!session.containers.has("player:" + player.getUUID())) return;
        session.containers.close("player:" + player.getUUID(), player.getScoreboardName(), menu.containerId, contents(menu),
                new JournalEvents.Stamp(player.getServer().overworld().getGameTime(), System.currentTimeMillis(),
                        player.level().dimension().location().toString())).forEach(journal::record);
    }

    /** Copies only stored slots; deduplicate aliases of the same backing container slot. */
    private Map<String, Long> contents(AbstractContainerMenu menu) {
        var contents = new HashMap<String, Long>();
        var seen = new java.util.IdentityHashMap<net.minecraft.world.Container, HashSet<Integer>>();
        for (var slot : menu.slots) {
            if (slot.container instanceof Inventory || slot.container instanceof CraftingContainer
                    || slot.container instanceof ResultContainer || slot.container instanceof MerchantContainer
                    || !seen.computeIfAbsent(slot.container, ignored -> new HashSet<>()).add(slot.getContainerSlot())) continue;
            var stack = slot.getItem();
            if (!stack.isEmpty()) {
                contents.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), (long) stack.getCount(), Math::addExact);
                if (contents.size() > ContainerSessions.MAX_ITEMS) break;
            }
        }
        return contents;
    }

    private void poll(MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            var stamp = new JournalEvents.Stamp(server.overworld().getGameTime(), System.currentTimeMillis(), level.dimension().location().toString());
            if (session.raidPolling && session.ticks % 20 == 0) {
                // Raids has no enumeration API in 1.21.1; the access transformer exposes this map for reads only.
                try {
                    var present = new HashSet<>(level.getRaids().raidMap.keySet());
                    for (var id : present) {
                        var raid = level.getRaids().get(id);
                        if (raid == null) continue;
                        var center = raid.getCenter();
                        session.raids.observe(id, new RaidWatch.RaidState(raid.isStarted(), raid.isVictory() ? "victory"
                                        : raid.isLoss() ? "loss" : raid.isStopped() ? "stopped" : null, raid.getRaidOmenLevel(),
                                raid.getGroupsSpawned(), center.getX(), center.getY(), center.getZ()), stamp).forEach(journal::record);
                    }
                    session.raids.missing(present, stamp).forEach(journal::record);
                } catch (LinkageError e) {
                    journal.stats().hookFailures.increment();
                    session.raidPolling = false;
                }
            }
            if (session.ticks % 40 == 5) {
                for (var player : level.players()) {
                    var pos = player.blockPosition();
                    if (!level.hasChunkAt(pos)) continue;
                    var manager = level.structureManager();
                    for (var entry : manager.getAllStructuresAt(pos).entrySet()) {
                        // StructureManager may fetch referenced chunks; skip references that are not already loaded.
                        if (entry.getValue().longStream().anyMatch(chunk -> !level.getChunkSource().hasChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk)))) continue;
                        var start = manager.getStructureWithPieceAt(pos, entry.getKey());
                        if (!start.isValid()) continue;
                        var id = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey(entry.getKey());
                        if (id == null) continue;
                        session.structures.inside("player:" + player.getUUID(), id.toString(), start.getChunkPos().x, start.getChunkPos().z,
                                pos.getX(), pos.getY(), pos.getZ(), stamp).forEach(journal::record);
                    }
                }
            }
            session.villagerPoll.step(level, villager -> {
                var pos = villager.blockPosition();
                session.villagers.observe(villager.getStringUUID(), BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession()).toString(),
                        pos.getX(), pos.getY(), pos.getZ(), stamp).forEach(journal::record);
            });
        }
    }
}
