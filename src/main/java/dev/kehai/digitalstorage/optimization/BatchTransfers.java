package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-thread batch ownership; closing a menu never cancels a running batch. */
public final class BatchTransfers {
    public interface Backend { BatchTransfer.Route open(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor); }
    private static Backend backend;
    private static final Map<MinecraftServer, Map<UUID, Job>> SERVERS = new IdentityHashMap<>();
    private record Job(UUID owner, BatchTransfer task, long[] lastTick) { }
    private BatchTransfers() { }
    public static void install(Backend value) { backend = java.util.Objects.requireNonNull(value); }
    public static BatchTransfer.Route open(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        var volume = accessor == null ? null : accessor.getVolume();
        if (volume == null || !volume.ownerId().equals(player.getUUID())) throw new IllegalStateException("not_owner");
        var route = backend.open(player, accessor);
        if (route == null || route.ports().isEmpty()) throw new IllegalStateException("no_network");
        return route;
    }
    public static BatchTransfer get(MinecraftServer server, UUID volume) {
        var job = SERVERS.getOrDefault(server, Map.of()).get(volume); return job == null ? null : job.task();
    }
    public static boolean start(ServerPlayer player, UUID volume, BatchTransfer task) {
        var jobs = SERVERS.computeIfAbsent(player.getServer(), ignored -> new HashMap<>());
        if (active(player.getServer(), volume) || NetworkServices.get().status(volume).active()) return false;
        jobs.put(volume, new Job(player.getUUID(), task, new long[]{player.getServer().getTickCount()})); return true;
    }
    public static boolean active(MinecraftServer server, UUID volume) { var task = get(server, volume); return task != null && task.active(); }
    public static void cancel(ServerPlayer player, UUID volume) {
        var job = SERVERS.getOrDefault(player.getServer(), Map.of()).get(volume);
        if (job != null && job.owner().equals(player.getUUID())) job.task().cancel();
    }
    public static void tick(MinecraftServer server) {
        var jobs = SERVERS.get(server); if (jobs == null) return;
        for (var job : List.copyOf(jobs.values())) {
            if (!job.task().active()) continue;
            if (server.getPlayerList().getPlayer(job.owner()) == null) job.task().stop("player_disconnected");
            else try { job.task().tick(dev.kehai.digitalstorage.config.DigitalStorageConfig.get().migrationViewsScannedPerTick); }
            catch (RuntimeException failure) {
                job.task().stop("transfer_failed");
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.error("Batch transfer stopped for {}", job.owner(), failure);
            }
            job.lastTick()[0] = server.getTickCount();
        }
        jobs.entrySet().removeIf(e -> !e.getValue().task().active() && server.getTickCount() - e.getValue().lastTick()[0] > 1200);
    }
    public static void stopped(MinecraftServer server) { SERVERS.remove(server); }
}
