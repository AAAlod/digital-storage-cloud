package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.optimization.InventoryTransferExecutor;
import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.NetworkServices.StartResult;
import dev.kehai.digitalstorage.optimization.TopologyToken;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.StorageVolume;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Per-server jobs retain source handles only while active; every batch rechecks its live binding/topology. */
public final class ForgeMigrationManager {
    private static final Map<MinecraftServer, Jobs> SERVERS = new IdentityHashMap<>();
    private ForgeMigrationManager() { }
    public static void starting(MinecraftServer server) { SERVERS.putIfAbsent(server, new Jobs()); }
    public static void stopped(MinecraftServer server) { SERVERS.remove(server); }
    public static void stopping(MinecraftServer server) {
        var jobs = SERVERS.get(server);
        if (jobs != null) jobs.stopAll(server.getTickCount(), "server stopping");
    }
    public static StartResult start(ServerPlayer player, ForgeAccessorBlockEntity accessor, NetworkAnalysis.Report report,
                                    ForgeInventoryTransferExecutor.Origin origin) {
        StorageVolume volume = accessor.getVolume();
        if (volume == null || !volume.ownerId().equals(player.getUUID())) return StartResult.NOT_OWNER;
        if (!(accessor.getLevel() instanceof ServerLevel world) || player.serverLevel() != world || !origin.known()
                || !origin.dimension().equals(world.dimension().location().toString())
                || origin.accessorPosition() != accessor.getBlockPos().asLong()) return StartResult.NO_NETWORK;
        var server = world.getServer();
        var jobs = SERVERS.get(server);
        if (jobs == null) return StartResult.NO_NETWORK;
        var reference = new WeakReference<>(accessor);
        var dimension = world.dimension();
        var position = accessor.getBlockPos().immutable();
        Supplier<DigitalStorageRecord> binding = () -> {
            var currentWorld = server.getLevel(dimension);
            var current = reference.get();
            if (currentWorld == null || current == null || current.isRemoved() || !currentWorld.hasChunkAt(position)
                    || currentWorld.getBlockEntity(position) != current) return null;
            var currentVolume = current.getVolume();
            return currentVolume == null || !currentVolume.id().equals(volume.id())
                    || !currentVolume.ownerId().equals(player.getUUID()) ? null : currentVolume.record();
        };
        return jobs.start(player.getUUID(), volume.id(), report, ForgeTransferSessions.get(server), origin, binding, server.getTickCount());
    }
    public static boolean cancel(ServerPlayer player, UUID volumeId) {
        var jobs = SERVERS.get(player.getServer());
        return jobs != null && jobs.cancel(player.getUUID(), volumeId, player.getServer().getTickCount(), "cancelled");
    }
    public static void logout(ServerPlayer player) {
        var jobs = SERVERS.get(player.getServer());
        if (jobs != null) jobs.cancelOwner(player.getUUID(), player.getServer().getTickCount());
    }
    public static MigrationTask.Status status(UUID volumeId) {
        for (var jobs : SERVERS.values()) if (jobs.statuses.containsKey(volumeId)) return jobs.statuses.get(volumeId);
        return MigrationTask.Status.idle();
    }
    public static void tick(MinecraftServer server) {
        var jobs = SERVERS.get(server);
        if (jobs != null) jobs.tick(server.getTickCount(), DigitalStorageConfig.get().migrationViewsScannedPerTick);
    }

    static final class Jobs {
        private final Map<UUID, Job> active = new HashMap<>();
        private final Map<UUID, MigrationTask.Status> statuses = new HashMap<>();
        private boolean accepting = true;
        StartResult start(UUID owner, UUID volume, NetworkAnalysis.Report report, ForgeTransferSessions.Session session,
                          ForgeInventoryTransferExecutor.Origin origin, Supplier<DigitalStorageRecord> binding, long tick) {
            if (!accepting) return StartResult.NO_NETWORK;
            var record = binding.get();
            if (record == null || record.storage().volumeId().filter(volume::equals).isEmpty()) return StartResult.NETWORK_CHANGED;
            if (!report.available() || !origin.known()) return StartResult.NO_NETWORK;
            if (report.targetEndpointCount() > 1) return StartResult.DUPLICATE_TARGET_ENDPOINTS;
            if (report.candidates().isEmpty()) return StartResult.NOTHING_TO_MOVE;
            if (!TopologyToken.isCurrent(report.topology())) return StartResult.NETWORK_CHANGED;
            if (active.containsKey(volume)) return StartResult.ALREADY_RUNNING;
            if (!session.available()) return StartResult.RECOVERY_REQUIRED;
            var job = new Job(owner, volume, binding, record);
            var executor = session.executor(owner, volume, origin);
            InventoryTransferExecutor guarded = (source, target, key, maximum) -> {
                if (active.get(volume) != job || binding.get() != record || !TopologyToken.isCurrent(report.topology())) {
                    return new InventoryTransferExecutor.MoveResult(0, 0, "accessor or network changed");
                }
                return executor.move(source, target, key, maximum);
            };
            try {
                job.task = new MigrationTask(report.candidates().stream().map(NetworkAnalysis.Candidate::variant).toList(),
                        report.estimatedFreedViews(), report.topology(), report.sourceEndpoints(), guarded);
            } catch (RuntimeException failure) { return StartResult.NETWORK_CHANGED; }
            active.put(volume, job);
            statuses.put(volume, job.task.status(tick, MigrationTask.State.RUNNING, ""));
            return StartResult.STARTED;
        }
        boolean cancel(UUID owner, UUID volume, long tick, String detail) {
            var job = active.get(volume);
            if (job == null || !job.owner.equals(owner)) return false;
            active.remove(volume);
            job.terminal = MigrationTask.State.CANCELLED;
            job.detail = detail;
            statuses.put(volume, job.task.status(tick, job.terminal, detail));
            return true;
        }
        void cancelOwner(UUID owner, long tick) {
            for (var job : List.copyOf(active.values())) if (job.owner.equals(owner)) cancel(owner, job.volume, tick, "player disconnected");
        }
        void stopAll(long tick, String detail) {
            accepting = false;
            for (var job : active.values()) {
                job.terminal = MigrationTask.State.STOPPED;
                job.detail = detail;
                statuses.put(job.volume, job.task.status(tick, job.terminal, detail));
            }
            active.clear();
        }
        MigrationTask.Status status(UUID volume) { return statuses.getOrDefault(volume, MigrationTask.Status.idle()); }
        void tick(long tick, int budget) {
            for (var job : List.copyOf(active.values())) {
                if (active.get(job.volume) != job) continue;
                MigrationTask.State result;
                String detail;
                try {
                    var record = job.binding.get();
                    result = record != job.record ? MigrationTask.State.STOPPED : job.task.tick(record, budget);
                    detail = record != job.record ? "accessor unavailable" : job.task.stopDetail();
                } catch (RuntimeException failure) {
                    result = MigrationTask.State.STOPPED;
                    detail = "source inspection failed: " + failure.getClass().getSimpleName();
                    dev.kehai.digitalstorage.DigitalStorage.LOGGER.warn("Forge migration stopped for {}", job.volume, failure);
                }
                // A source callback may cancel/logout during extraction. Preserve
                // cancellation but include any items settled before it returned.
                if (job.terminal != null) { result = job.terminal; detail = job.detail; }
                if (result != MigrationTask.State.RUNNING) active.remove(job.volume, job);
                if (active.get(job.volume) == job || !active.containsKey(job.volume)) {
                    statuses.put(job.volume, job.task.status(tick, result, detail));
                }
            }
            statuses.entrySet().removeIf(entry -> !entry.getValue().active() && tick - entry.getValue().updatedTick() > 1200);
        }
    }
    private static final class Job {
        private final UUID owner;
        private final UUID volume;
        private final Supplier<DigitalStorageRecord> binding;
        private final DigitalStorageRecord record;
        private MigrationTask task;
        private MigrationTask.State terminal;
        private String detail;
        private Job(UUID owner, UUID volume, Supplier<DigitalStorageRecord> binding, DigitalStorageRecord record) {
            this.owner = owner; this.volume = volume; this.binding = binding; this.record = record;
        }
    }
}
