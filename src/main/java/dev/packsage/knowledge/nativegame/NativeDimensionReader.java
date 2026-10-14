package dev.packsage.knowledge.nativegame;

import dev.packsage.knowledge.Evidence;
import dev.packsage.knowledge.Toolbox;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

final class NativeDimensionReader {
    private NativeDimensionReader() { }

    static CompletionStage<NativeRegistryReader.Page> search(Minecraft client, int offset, int limit,
                                                              String text, List<String> ids, Evidence.Scope scope,
                                                              Supplier<Evidence.Scope> currentScope) {
        if (!client.isSameThread()) throw new IllegalStateException("dimension selection must run on the client thread");
        var known = client.getConnection() == null ? Set.<ResourceKey<Level>>of()
                : Set.copyOf(client.getConnection().levels());
        var playerId = client.player == null ? null : client.player.getUUID();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || playerId == null) return CompletableFuture.completedFuture(
                clientPage(known, offset, limit, text, ids, scope));
        return server.submit(() -> {
            if (!scope.equals(currentScope.get())) throw new Toolbox.Failure(Toolbox.Code.UNAVAILABLE, "evidence scope changed");
            var profile = server.getSingleplayerProfile();
            var players = server.getPlayerList().getPlayers();
            UUID serverPlayerId = players.size() == 1 ? players.getFirst().getUUID() : null;
            if (profile == null || !server.isSingleplayerOwner(profile) || server.isPublished()
                    || players.size() != 1 || !profile.getId().equals(playerId) || !profile.getId().equals(serverPlayerId)) {
                return clientPage(known, offset, limit, text, ids, scope);
            }
            var keys = server.levelKeys().stream().sorted(Comparator.comparing(key -> key.location().toString())).toList();
            return serverPage(server, keys, offset, limit, text, ids, scope);
        });
    }

    static CompletionStage<Evidence.Record> read(Minecraft client, String identity, Evidence.Scope scope,
                                                  Supplier<Evidence.Scope> currentScope) {
        if (!client.isSameThread()) throw new IllegalStateException("dimension read must run on the client thread");
        var location = net.minecraft.resources.ResourceLocation.tryParse(identity);
        if (location == null || !location.toString().equals(identity) || client.getConnection() == null) {
            return CompletableFuture.completedFuture(null);
        }
        var key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location);
        var known = client.getConnection().levels().contains(key);
        var playerId = client.player == null ? null : client.player.getUUID();
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || playerId == null) return CompletableFuture.completedFuture(known
                ? record(identity, null, scope, Evidence.Coverage.PARTIAL, "client-known-dimension") : null);
        return server.submit(() -> {
            if (!scope.equals(currentScope.get())) return null;
            var profile = server.getSingleplayerProfile();
            var players = server.getPlayerList().getPlayers();
            UUID serverPlayerId = players.size() == 1 ? players.getFirst().getUUID() : null;
            if (profile != null && server.isSingleplayerOwner(profile) && !server.isPublished() && players.size() == 1
                    && profile.getId().equals(playerId) && profile.getId().equals(serverPlayerId)) {
                if (!server.levelKeys().contains(key)) return null;
                var level = server.getLevel(key);
                String type = level == null ? null : level.dimensionTypeRegistration().unwrapKey()
                        .map(value -> value.location().toString()).orElse(null);
                return record(identity, type, scope, type == null ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                        "integrated-server:runtime-dimension");
            }
            return known ? record(identity, null, scope, Evidence.Coverage.PARTIAL, "client-known-dimension") : null;
        });
    }

    private static NativeRegistryReader.Page serverPage(MinecraftServer server, List<ResourceKey<Level>> keys,
                                                         int offset, int limit, String text, List<String> ids,
                                                         Evidence.Scope scope) {
        var records = new ArrayList<Evidence.Record>();
        int index = 0;
        int scanned = 0;
        for (var key : keys) {
            if (!ids.isEmpty() && !ids.contains(key.location().toString())) continue;
            ServerLevel level = server.getLevel(key);
            if (level == null) continue;
            String identity = key.location().toString();
            if (text != null && !identity.toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT))) continue;
            if (index++ < offset) continue;
            if (records.size() == limit) return new NativeRegistryReader.Page(records, offset + records.size(), false, false, scanned);
            var type = level.dimensionTypeRegistration().unwrapKey().map(value -> value.location().toString()).orElse(null);
            records.add(record(identity, type, scope, type == null ? Evidence.Coverage.PARTIAL : Evidence.Coverage.COMPLETE,
                    "integrated-server:runtime-dimension"));
            scanned++;
        }
        return new NativeRegistryReader.Page(records, offset + records.size(), true,
                records.stream().anyMatch(value -> value.source().coverage() != Evidence.Coverage.COMPLETE), scanned);
    }

    static NativeRegistryReader.Page clientPage(Set<ResourceKey<Level>> known, int offset, int limit,
                                                 String text, List<String> ids, Evidence.Scope scope) {
        var matches = known.stream().map(key -> key.location().toString()).filter(identity -> ids.isEmpty() || ids.contains(identity))
                .filter(identity -> text == null || identity.toLowerCase(java.util.Locale.ROOT).contains(text.toLowerCase(java.util.Locale.ROOT)))
                .sorted().toList();
        int start = Math.min(offset, matches.size());
        int end = Math.min(matches.size(), start + limit);
        var records = matches.subList(start, end).stream().map(identity -> record(identity, null, scope,
                Evidence.Coverage.PARTIAL, "client-known-dimension")).toList();
        return new NativeRegistryReader.Page(records, end, end == matches.size(), true, end - start);
    }

    private static Evidence.Record record(String identity, String type, Evidence.Scope scope,
                                          Evidence.Coverage coverage, String provenance) {
        var payload = new java.util.LinkedHashMap<String, Evidence.JsonValue>();
        payload.put("registry", new Evidence.JsonString("dimension"));
        payload.put("id", new Evidence.JsonString(identity));
        payload.put("identity", new Evidence.JsonString(identity));
        if (type != null) payload.put("dimensionType", new Evidence.JsonString(type));
        var source = new Evidence.Source(scope, "native", "1.21.1", "dimension:" + identity, scope.requestGeneration(),
                Instant.now(), Evidence.Activity.ACTIVE, coverage, Evidence.Authority.LOADED, List.of(provenance));
        return new Evidence.Record(source, Evidence.RecordKind.OTHER, identity, new Evidence.JsonObject(payload), List.of());
    }
}
