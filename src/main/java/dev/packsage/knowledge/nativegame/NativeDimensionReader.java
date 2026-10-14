package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

final class NativeDimensionReader {
    private static final int MAX_CANDIDATES = 512;
    private static final int MAX_SEEK = 2048;

    private NativeDimensionReader() { }

    static CompletionStage<NativeRegistryReader.Page> search(Minecraft client, int offset, int limit,
                                                              String text, List<String> ids, Evidence.Scope scope,
                                                              Supplier<Evidence.Scope> currentScope) {
        if (!client.isSameThread()) throw new IllegalStateException("dimension selection must run on the client thread");
        if (client.player == null || client.level == null || client.getConnection() == null) {
            return CompletableFuture.failedFuture(new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "active client world unavailable"));
        }
        if (currentScope.get() != null && !scope.equals(currentScope.get())) return CompletableFuture.failedFuture(
                new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "evidence scope changed"));
        UUID playerId = client.player.getUUID();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null) return CompletableFuture.completedFuture(clientPage(client.getConnection().levels(), offset, limit,
                text, ids, scope));
        return server.submit(() -> {
            if (currentScope.get() != null && !scope.equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE, "evidence scope changed");
            var profile = server.getSingleplayerProfile();
            var players = server.getPlayerList().getPlayers();
            UUID serverPlayerId = players.size() == 1 ? players.getFirst().getUUID() : null;
            if (profile == null || !server.isSingleplayerOwner(profile) || server.isPublished() || players.size() != 1
                    || !profile.getId().equals(playerId) || !profile.getId().equals(serverPlayerId)) return null;
            return serverPage(server, offset, limit, text, ids, scope);
        }).thenCompose(page -> {
            if (page != null) return CompletableFuture.completedFuture(page);
            return client.submit(() -> {
                if (client.player == null || client.level == null || client.getConnection() == null
                        || currentScope.get() != null && !scope.equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.STALE_SCOPE,
                        "client world or evidence scope changed");
                return clientPage(client.getConnection().levels(), offset, limit, text, ids, scope);
            });
        });
    }

    static CompletionStage<Evidence.Record> read(Minecraft client, String identity, Evidence.Scope scope,
                                                  Supplier<Evidence.Scope> currentScope) {
        ResourceLocation location = ResourceLocation.tryParse(identity);
        if (location == null || !location.toString().equals(identity)) return CompletableFuture.completedFuture(null);
        return search(client, 0, 1, null, List.of(identity), scope, currentScope)
                .thenApply(page -> (currentScope.get() == null || scope.equals(currentScope.get())) && !page.records().isEmpty()
                        ? page.records().getFirst() : null);
    }

    private static NativeRegistryReader.Page serverPage(MinecraftServer server, int offset, int limit,
                                                         String text, List<String> ids, Evidence.Scope scope) {
        if (!ids.isEmpty()) {
            var matches = new ArrayList<Evidence.Record>();
            var seen = new java.util.HashSet<String>();
            for (String identity : ids) {
                if (!seen.add(identity)) continue;
                var location = ResourceLocation.parse(identity);
                var key = ResourceKey.create(Registries.DIMENSION, location);
                if (!server.levelKeys().contains(key)) continue;
                ServerLevel level = server.getLevel(key);
                if (level == null || text != null && !identity.toLowerCase(java.util.Locale.ROOT)
                        .contains(text.toLowerCase(java.util.Locale.ROOT))) continue;
                matches.add(serverRecord(level, identity, scope));
            }
            int start = Math.min(offset, matches.size());
            int end = Math.min(matches.size(), start + limit);
            return page(matches.subList(start, end), end, end == matches.size(), ids.size());
        }
        var iterator = server.levelKeys().iterator();
        int sought = 0;
        while (iterator.hasNext() && sought < offset && sought < MAX_SEEK) {
            iterator.next();
            sought++;
        }
        if (sought < offset) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "dimension continuation exceeds the bounded seek; use an exact dimension ID");
        var records = new ArrayList<Evidence.Record>();
        int scanned = 0;
        while (iterator.hasNext() && scanned < MAX_CANDIDATES) {
            var key = iterator.next();
            scanned++;
            String identity = key.location().toString();
            if (text != null && !identity.toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT))) continue;
            ServerLevel level = server.getLevel(key);
            if (level == null) continue;
            records.add(serverRecord(level, identity, scope));
            if (records.size() == limit) return page(records, offset + scanned, !iterator.hasNext(), scanned);
        }
        return page(records, offset + scanned, !iterator.hasNext(), scanned);
    }

    static NativeRegistryReader.Page clientPage(Set<ResourceKey<Level>> known, int offset, int limit,
                                                 String text, List<String> ids, Evidence.Scope scope) {
        if (!ids.isEmpty()) {
            var matches = new ArrayList<Evidence.Record>();
            var seen = new java.util.HashSet<String>();
            for (String identity : ids) {
                if (!seen.add(identity)) continue;
                var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(identity));
                if (!known.contains(key) || text != null && !identity.toLowerCase(java.util.Locale.ROOT)
                        .contains(text.toLowerCase(java.util.Locale.ROOT))) continue;
                matches.add(dimensionRecord(identity, null, scope, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
                        "client-known-dimension"));
            }
            int start = Math.min(offset, matches.size());
            int end = Math.min(matches.size(), start + limit);
            return page(matches.subList(start, end), end, end == matches.size(), ids.size(), true);
        }
        var iterator = known.iterator();
        int sought = 0;
        while (iterator.hasNext() && sought < offset && sought < MAX_SEEK) {
            iterator.next();
            sought++;
        }
        if (sought < offset) throw new Toolbox.Failure(Toolbox.Code.INVALID_CURSOR,
                "dimension continuation exceeds the bounded seek; use an exact dimension ID");
        var records = new ArrayList<Evidence.Record>();
        int scanned = 0;
        while (iterator.hasNext() && scanned < MAX_CANDIDATES) {
            String identity = iterator.next().location().toString();
            scanned++;
            if (text != null && !identity.toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT))) continue;
            records.add(dimensionRecord(identity, null, scope, Evidence.Coverage.PARTIAL, Evidence.Authority.LOADED,
                    "client-known-dimension"));
            if (records.size() == limit) return page(records, offset + scanned, !iterator.hasNext(), scanned, true);
        }
        return page(records, offset + scanned, !iterator.hasNext(), scanned, true);
    }

    private static NativeRegistryReader.Page page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned) {
        return page(records, nextOffset, exhausted, scanned, false);
    }

    private static NativeRegistryReader.Page page(List<Evidence.Record> records, int nextOffset, boolean exhausted, int scanned,
                                                   boolean forcePartial) {
        return new NativeRegistryReader.Page(records, nextOffset, exhausted,
                forcePartial || !exhausted || records.stream().anyMatch(record -> record.source().coverage() != Evidence.Coverage.COMPLETE), scanned);
    }

    private static Evidence.Record serverRecord(ServerLevel level, String identity, Evidence.Scope scope) {
        String type = level.dimensionTypeRegistration().unwrapKey().map(value -> value.location().toString()).orElse(null);
        return dimensionRecord(identity, type, scope, type == null ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                Evidence.Authority.SERVER_READ, "integrated-server:runtime-dimension");
    }

    static Evidence.Record dimensionRecord(String identity, String type, Evidence.Scope scope,
                                           Evidence.Coverage coverage, Evidence.Authority authority, String provenance) {
        var payload = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("dimension"));
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("identity", new Evidence.JsonString(identity));
        if (type != null) payload.put("dimensionType", new Evidence.JsonString(type));
        var source = new Evidence.Source(scope, "native", "1.21.1", "dimension:" + identity, scope.requestGeneration(),
                Instant.now(), Evidence.Activity.ACTIVE, coverage, authority, List.of(provenance));
        return new Evidence.Record(source, Evidence.RecordKind.OTHER, identity, new Evidence.JsonObject(payload), List.of());
    }
}
