package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** Public storage facade over the server's single SavedData document. */
public final class DigitalStorageState {
    private static final Map<MinecraftServer, DigitalStorageState> INSTANCES = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile DigitalStorageData data;
    private final DimensionDataStorage storage;
    private final Path root;
    private final SnapshotCoordinator coordinator;
    private volatile boolean degraded;

    private DigitalStorageState(DigitalStorageData data, DimensionDataStorage storage) {
        this(data, storage, null, null, false);
    }

    private DigitalStorageState(DigitalStorageData data, DimensionDataStorage storage, Path root,
                                SnapshotCoordinator coordinator, boolean degraded) {
        this.data = data;
        this.storage = storage;
        this.root = root;
        this.coordinator = coordinator;
        this.degraded = degraded;
    }

    private static DigitalStorageState openGuarded(DimensionDataStorage storage, Path root, long timeoutMillis) {
        var coordinator = SnapshotCoordinator.forRoot(root);
        try {
            if (!coordinator.tryLock(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.error("Digital Storage {} is temporarily read-only while a previous snapshot installation finishes", root);
                return new DigitalStorageState(DigitalStorageData.readOnly(), storage, root, coordinator, true);
            }
            try {
                long generation = coordinator.nextGeneration();
                Files.createDirectories(root.resolve("data"));
                var data = DigitalStorageData.open(storage, root);
                data.enableSnapshotWriter(root.resolve("data").resolve(DigitalStorageData.ID + ".dat"), coordinator, generation);
                return new DigitalStorageState(data, storage, root, coordinator, false);
            } finally { coordinator.unlock(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DigitalStorageState(DigitalStorageData.readOnly(), storage, root, coordinator, true);
        } catch (java.io.IOException | RuntimeException e) {
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.error("Digital Storage {} could not be opened; retaining read-only state", root, e);
            return new DigitalStorageState(DigitalStorageData.readOnly(), storage, root, coordinator, false);
        }
    }

    synchronized void recoverIfDegraded() {
        if (!degraded) return;
        try {
            if (!coordinator.tryLock(0, java.util.concurrent.TimeUnit.MILLISECONDS)) return;
            try {
                long generation = coordinator.nextGeneration();
                Files.createDirectories(root.resolve("data"));
                var reloaded = DigitalStorageData.open(storage, root);
                reloaded.enableSnapshotWriter(root.resolve("data").resolve(DigitalStorageData.ID + ".dat"), coordinator, generation);
                data = reloaded;
                degraded = false;
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Digital Storage {} reloaded after the prior installation finished; unsaved prior changes remain subject to the autosave window", root);
            } finally { coordinator.unlock(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (java.io.IOException | RuntimeException e) {
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.error("Digital Storage {} could not reload after the prior installation; retaining read-only state", root, e);
            degraded = false;
        }
    }

    // Fabric SERVER_STARTING runs before worlds exist; initialize on a world event or first tick.
    public static void onServerStarting(MinecraftServer server) { }
    public static void onServerTick(MinecraftServer server) {
        if (server.overworld() != null) getOrCreate(server).recoverIfDegraded();
    }
    public static void onServerStopping(MinecraftServer server) {
        var state = INSTANCES.get(server);
        if (state != null && !state.data.readOnly) {
            try {
                state.data.shutdownSnapshot(5000);
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Digital Storage final snapshot installed: {}", state.data.lastSuccessfulSnapshot());
            } catch (RuntimeException e) {
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.error("Digital Storage shutdown snapshot failed; dirty={}, last successful snapshot={}", state.data.isDirty(), state.data.lastSuccessfulSnapshot(), e);
            }
        }
    }
    public static void onServerStopped(MinecraftServer server) {
        var state = INSTANCES.remove(server);
        if (state != null) {
            state.data.closeSnapshotWriter();
            try {
                if (!state.data.awaitSnapshotWriterStop(5000)) {
                    dev.kehai.digitalstorage.DigitalStorage.LOGGER.error("Digital Storage snapshot worker is still in flight after shutdown timeout; dirty={}, last successful snapshot={}; JVM exit is allowed and the current generation is not confirmed saved", state.data.isDirty(), state.data.lastSuccessfulSnapshot());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.error("Interrupted while waiting for Digital Storage snapshot worker; last successful snapshot={}", state.data.lastSuccessfulSnapshot(), e);
            }
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Digital Storage stopped: dirty={}, last successful snapshot={}", state.data.isDirty(), state.data.lastSuccessfulSnapshot());
            state.data.readOnly = true;
        }
    }
    public static DigitalStorageState get(ServerLevel world) {
        var server = world.getServer();
        var existing = INSTANCES.get(server);
        if (existing != null) return existing;
        var overworld = server.overworld();
        if (overworld == null && world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) overworld = world;
        if (overworld == null) return new DigitalStorageState(DigitalStorageData.readOnly(), null);
        var storage = overworld.getDataStorage();
        return INSTANCES.computeIfAbsent(server, key -> openGuarded(storage, key.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), 5000));
    }
    private static DigitalStorageState getOrCreate(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, key -> {
            var storage = key.overworld().getDataStorage();
            return openGuarded(storage, key.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT), 5000);
        });
    }

    /** Diagnostics must not initialize storage or trigger migration. */
    public static DigitalStorageState peek(ServerLevel world) {
        return INSTANCES.getOrDefault(world.getServer(), new DigitalStorageState(DigitalStorageData.readOnly(), null));
    }

    static DigitalStorageState openForTest(Path root) {
        var storage = new DimensionDataStorage(root.resolve("data").toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
        return openGuarded(storage, root, 1000);
    }

    public synchronized Optional<StorageVolume> createVolume(UUID owner, String name, int maximum) {
        return data.createVolume(owner, name, maximum);
    }
    public synchronized Optional<StorageVolume> volume(UUID id) {
        return data.readOnly ? Optional.empty() : Optional.ofNullable(data.volumes.get(id));
    }
    public enum VolumeStatus { PRESENT, MISSING, TEMPORARILY_UNAVAILABLE, UNREADABLE, CONFIRMED_DELETED }
    public record VolumeLookup(VolumeStatus status, StorageVolume volume) { }
    public synchronized VolumeLookup lookupVolume(UUID id) { return data.lookup(id); }
    public synchronized List<StorageVolume> volumes(UUID owner) { return volumes(owner, Integer.MAX_VALUE); }
    public synchronized List<StorageVolume> volumes(UUID owner, int maximum) {
        return volumeIds(owner).stream().map(data.volumes::get).filter(java.util.Objects::nonNull).limit(maximum).toList();
    }
    public synchronized int volumeCount(UUID owner) { return volumeIds(owner).size(); }
    public synchronized List<UUID> volumeIds(UUID owner) {
        if (data.readOnly) return List.of();
        var account = data.accounts.get(owner);
        return account == null ? List.of() : account.volumeIds();
    }
    public synchronized boolean ownsVolume(UUID owner, UUID id) {
        return !data.readOnly && volumeIds(owner).contains(id);
    }
    public synchronized boolean renameVolume(UUID owner, UUID id, String name) {
        var volume = volume(id).orElse(null);
        return volume != null && volume.ownerId().equals(owner) && volume.rename(name);
    }
    public synchronized boolean deleteEmptyVolume(UUID owner, UUID id) { return data.deleteEmptyVolume(owner, id); }
    public synchronized int accountCount() { return data.readOnly ? 0 : data.accounts.size(); }
    public synchronized int volumeCount() { return data.readOnly ? 0 : allIds().size(); }
    public synchronized int unreadableCount() { return data.legacyUnreadable.size() + data.legacyUnreadableAccounts.size(); }
    public boolean savedDataDirty() { return data.isDirty(); }
    public long totalVariantNbtBytes() { return data.globalBudget.committedBytes(); }
    public long maxTotalVariantNbtBytes() { return data.globalBudget.maxBytes(); }
    public boolean totalVariantNbtOverBudget() { return totalVariantNbtBytes() > maxTotalVariantNbtBytes(); }
    public long snapshotGeneration() { return data.snapshotGeneration(); }
    public String snapshotWorkerStatus() { return degraded ? "degraded" : data.snapshotWorkerStatus(); }
    public String snapshotLastFailure() { return data.snapshotLastFailure(); }
    public String savedDataMode() {
        if (degraded) return "degraded-read-only";
        if (data.futureSchema) return "future-schema";
        if (data.corrupt) return "corrupt";
        return data.readOnly ? "read-only" : "read-write";
    }
    public String legacyBackupStatus() { return data.backupStatus; }

    public synchronized void flushNow() {
        if (data.readOnly || storage == null) throw new IllegalStateException("Digital Storage SavedData is read-only or unavailable");
        data.flushSnapshotAndWait(5000);
    }
    void closeForTest() {
        if (!data.readOnly) {
            flushNow();
            data.closeSnapshotWriter(); data.readOnly = true;
        }
    }
    int loadedVolumeCountForTest() { return data.volumes.size(); }

    private Set<UUID> allIds() {
        Set<UUID> ids = new HashSet<>(data.volumes.keySet());
        ids.addAll(data.legacyUnreadable.keySet());
        data.accounts.values().forEach(account -> ids.addAll(account.volumeIds()));
        return ids;
    }
    public synchronized int variantCount() { return Math.toIntExact(contentStats(false).variantCount()); }
    public synchronized BigInteger totalItemCount() { return contentStats(false).totalItemCount(); }
    public synchronized ContentStats contentStats(boolean inspectColdVolumes) {
        long variants = 0;
        BigInteger items = BigInteger.ZERO;
        if (!data.readOnly) for (var volume : data.volumes.values()) {
            variants = Math.addExact(variants, volume.record().storage().variantCount());
            items = items.add(BigInteger.valueOf(volume.record().storage().totalItemCount()));
        }
        int inspected = data.readOnly ? 0 : data.volumes.size();
        return new ContentStats(variants, items, inspected, data.readOnly ? 0 : Math.max(0, allIds().size() - inspected));
    }
    public synchronized StorageSizeStats storageSizeStats() {
        long total = 0, largest = 0, oversized = 0;
        int threshold = DigitalStorageConfig.get().volumeNbtWarningBytes;
        if (!data.readOnly) for (var volume : data.volumes.values()) {
            long size = volume.snapshot().writeNbt().sizeInBytes();
            total = Math.addExact(total, size);
            largest = Math.max(largest, size);
            if (threshold > 0 && size > threshold) oversized++;
        }
        int count = data.readOnly ? 0 : data.volumes.size();
        return new StorageSizeStats(data.fileBytes, total, largest, count == 0 ? 0 : total / count, Math.toIntExact(oversized));
    }
    public record StorageSizeStats(long liveDiskBytes, long totalEstimatedVolumeNbtBytes,
                                   long largestEstimatedVolumeNbtBytes, long averageEstimatedVolumeNbtBytes,
                                   int oversizedVolumeCount) { }
    public record ContentStats(long variantCount, BigInteger totalItemCount, int inspectedVolumeCount, int uninspectedVolumeCount) { }
}
