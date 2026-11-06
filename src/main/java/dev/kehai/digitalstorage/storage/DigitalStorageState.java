package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Server-level storage manager. Accounts and volumes are deliberately persisted
 * as independent files rather than a monolithic Minecraft PersistentState.
 */
public final class DigitalStorageState {
    static final int ACCOUNT_SCHEMA_VERSION = 1;
    static final int VOLUME_SCHEMA_VERSION = 2;
    static final int MAX_INCREMENTAL_SNAPSHOT_RESTARTS = 4;
    static final long MAX_DIRTY_VOLUME_AGE_TICKS = 600;
    private static final String SCHEMA_VERSION_KEY = "SchemaVersion";
    private static final Map<MinecraftServer, DigitalStorageState> INSTANCES = new WeakHashMap<>();

    private final Path accountsDirectory;
    private final Path volumesDirectory;
    private final Path deletedVolumeMarkersDirectory;
    private final Path quarantinedAccountsDirectory;
    private final Path quarantinedVolumesDirectory;
    private final Path quarantinedDeletedVolumesDirectory;
    private final Map<UUID, PlayerStorageAccount> accounts = new HashMap<>();
    private final Map<UUID, StorageVolume> loadedVolumes = new HashMap<>();
    private final Map<UUID, StorageVolume> dirtyVolumeInstances = new HashMap<>();
    private final Set<UUID> knownVolumeIds = new HashSet<>();
    private final Map<UUID, String> unreadableVolumes = new HashMap<>();
    private final Set<UUID> futureSchemaAccounts = new HashSet<>();
    private final Set<UUID> unreadableAccounts = new HashSet<>();
    private final Set<UUID> missingVolumeIds = new HashSet<>();
    private final Set<UUID> unavailableVolumeIds = new HashSet<>();
    private final Set<UUID> deletionMarkers = new HashSet<>();
    private final Set<UUID> dirtyAccounts = new HashSet<>();
    private final Set<UUID> dirtyVolumes = new LinkedHashSet<>();
    private final Set<UUID> deletedAccounts = new HashSet<>();
    private final Set<UUID> deletedVolumes = new HashSet<>();
    private final Map<UUID, VolumeLedger.SnapshotCursor> volumeSnapshotCursors = new HashMap<>();
    private final Map<UUID, Integer> volumeSnapshotRestarts = new HashMap<>();
    private final Map<UUID, Long> dirtyVolumeSinceTicks = new HashMap<>();
    private final Map<UUID, Long> accountDiskBytes = new HashMap<>();
    private final Map<UUID, VolumeFileMetrics> volumeFileMetrics = new HashMap<>();
    private final Set<UUID> warnedOversizedVolumes = new HashSet<>();
    private ExecutorService writer;
    private final StorageDirectoryCoordinator coordinator;
    private final Path rootDirectory;
    private long generation;
    private volatile boolean ready;
    private boolean initializing;
    private final AtomicInteger pendingWriteBatches = new AtomicInteger();
    private int quarantinedAccountFiles;
    private int quarantinedVolumeFiles;
    private int ticksSinceFlush;
    private long persistenceTicks;
    private long snapshotRestartCount;
    private long forcedSnapshotCount;
    private boolean flushRequested;
    private volatile boolean closing;
    private volatile boolean closed;
    private volatile boolean finalFlush;
    private boolean markerInspectionFailed;
    private boolean accountScanFailed;
    private boolean accountsFullyLoaded = true;
    private boolean volumeScanFailed;
    private long persistenceFailureCount;
    private String lastPersistenceFailure = "";
    private long flushTimeoutSeconds = 30;
    private volatile String lastNbtEncodeThreadName = "";
    private volatile java.util.function.Consumer<UUID> beforeVolumeWriteCallbackForTest;

    private DigitalStorageState(Path rootDirectory) {
        this(rootDirectory, 5);
    }

    private DigitalStorageState(Path rootDirectory, long lockTimeoutSeconds) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        coordinator = StorageDirectoryCoordinator.forRoot(this.rootDirectory);
        accountsDirectory = rootDirectory.resolve("accounts");
        volumesDirectory = rootDirectory.resolve("volumes");
        deletedVolumeMarkersDirectory = rootDirectory.resolve("deleted-volumes");
        quarantinedAccountsDirectory = rootDirectory.resolve("quarantine").resolve("accounts");
        quarantinedVolumesDirectory = rootDirectory.resolve("quarantine").resolve("volumes");
        quarantinedDeletedVolumesDirectory = rootDirectory.resolve("quarantine").resolve("deleted-volumes");
        boolean locked = false;
        try {
            locked = coordinator.writeLock.tryLock(lockTimeoutSeconds, TimeUnit.SECONDS);
            if (locked) initializeLocked();
            else DigitalStorage.LOGGER.error("Digital Storage directory {} is busy; starting read-only with no loaded data until the previous writer releases it", this.rootDirectory);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            DigitalStorage.LOGGER.error("Interrupted waiting for Digital Storage directory {}; starting read-only", this.rootDirectory, interrupted);
        } finally {
            if (locked) coordinator.writeLock.unlock();
        }
    }

    private void initializeLocked() {
        initializing = true;
        generation = ++coordinator.generation;
        try {
            Files.createDirectories(accountsDirectory);
            Files.createDirectories(volumesDirectory);
            Files.createDirectories(deletedVolumeMarkersDirectory);
            Files.createDirectories(quarantinedAccountsDirectory);
            Files.createDirectories(quarantinedVolumesDirectory);
            Files.createDirectories(quarantinedDeletedVolumesDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create Digital Storage data directories", exception);
        }
        writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "DigitalStorage-Writer");
            thread.setDaemon(true);
            return thread;
        });
        quarantinedAccountFiles = regularFileCount(quarantinedAccountsDirectory);
        quarantinedVolumeFiles = regularFileCount(quarantinedVolumesDirectory);
        loadAllFiles();
        ready = true;
        initializing = false;
    }

    public static void onServerStarting(MinecraftServer server) {
        getOrCreate(server);
    }

    public static void onServerTick(MinecraftServer server) {
        DigitalStorageState state;
        synchronized (DigitalStorageState.class) {
            state = INSTANCES.get(server);
        }
        if (state != null) {
            state.tick();
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        DigitalStorageState state;
        synchronized (DigitalStorageState.class) {
            state = INSTANCES.get(server);
        }
        if (state != null) {
            state.close();
        }
    }

    public static synchronized void onServerStopped(MinecraftServer server) {
        INSTANCES.remove(server);
    }

    public static DigitalStorageState get(ServerLevel world) {
        return getOrCreate(world.getServer());
    }

    private static synchronized DigitalStorageState getOrCreate(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, ignored -> new DigitalStorageState(
                server.getWorldPath(LevelResource.ROOT).resolve("digitalstorage")
        ));
    }

    static DigitalStorageState openForTest(Path rootDirectory) {
        return new DigitalStorageState(rootDirectory, 1);
    }

    synchronized boolean readyForTest() { return ready; }
    void tickForTest() { tick(); }
    int pendingBatchesForTest() { return pendingWriteBatches.get(); }
    void flushTimeoutForTest(long seconds) { flushTimeoutSeconds = seconds; }

    public synchronized Optional<StorageVolume> createVolume(UUID ownerId, String name, int maximumVolumes) {
        if (!ready || closing || closed || generation != coordinator.generation) return Optional.empty();
        if (accountScanFailed || futureSchemaAccounts.contains(ownerId) || unreadableAccounts.contains(ownerId)) return Optional.empty();
        PlayerStorageAccount existingAccount = accounts.get(ownerId);
        int currentVolumes = existingAccount == null ? 0 : existingAccount.volumeIds().size();
        if (maximumVolumes > 0 && currentVolumes >= maximumVolumes) {
            return Optional.empty();
        }

        UUID id;
        do {
            id = UUID.randomUUID();
            boolean marked = hasDeletionMarker(id);
            if (markerInspectionFailed) return Optional.empty();
            if (!knownVolumeIds.contains(id) && !marked) break;
        } while (true);

        UUID volumeId = id;
        PlayerStorageAccount account = accounts.computeIfAbsent(ownerId, PlayerStorageAccount::new);
        StorageVolume volume = StorageVolume.create(
                volumeId,
                ownerId,
                name,
                () -> markVolumeDirty(volumeId)
        );
        knownVolumeIds.add(volumeId);
        rememberLoaded(volume);
        dirtyVolumeInstances.put(volumeId, volume);
        account.addVolume(volumeId);
        markAccountDirty(ownerId);
        markVolumeDirty(volumeId);
        return Optional.of(volume);
    }

    public synchronized Optional<StorageVolume> volume(UUID id) {
        if (!ready || closing || closed || generation != coordinator.generation) return Optional.empty();
        StorageVolume loaded = loadedVolume(id);
        return loaded == null ? loadVolume(id) : Optional.of(loaded);
    }

    public enum VolumeStatus { PRESENT, MISSING, TEMPORARILY_UNAVAILABLE, UNREADABLE, CONFIRMED_DELETED }
    public record VolumeLookup(VolumeStatus status, StorageVolume volume) { }

    public synchronized VolumeLookup lookupVolume(UUID id) {
        if (!ready || closing || closed || generation != coordinator.generation) {
            return new VolumeLookup(VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
        }
        StorageVolume cached = loadedVolume(id);
        if (cached != null) return new VolumeLookup(VolumeStatus.PRESENT, cached);
        if (!coordinator.writeLock.tryLock()) return new VolumeLookup(VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
        try {
            if (generation != coordinator.generation) return new VolumeLookup(VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
            return lookupVolumeLocked(id);
        } finally { coordinator.writeLock.unlock(); }
    }

    private VolumeLookup lookupVolumeLocked(UUID id) {
        StorageVolume loaded = loadedVolume(id);
        if (loaded != null) return new VolumeLookup(VolumeStatus.PRESENT, loaded);
        boolean marked = hasDeletionMarker(id);
        if (markerInspectionFailed) return new VolumeLookup(VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
        boolean accountsComplete = accountsFullyLoaded && !accountScanFailed && futureSchemaAccounts.isEmpty() && unreadableAccounts.isEmpty();
        if (marked) return new VolumeLookup(accountsComplete ? VolumeStatus.CONFIRMED_DELETED : VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
        if (unreadableVolumes.containsKey(id)) return new VolumeLookup(VolumeStatus.UNREADABLE, null);
        if (!knownVolumeIds.contains(id)) {
            if (accounts.values().stream().anyMatch(account -> account.volumeIds().contains(id))) {
                return new VolumeLookup(VolumeStatus.MISSING, null);
            }
            if (!accountsComplete || volumeScanFailed) return new VolumeLookup(VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
            try {
                Files.readAttributes(volumesDirectory.resolve(id + ".dat"), java.nio.file.attribute.BasicFileAttributes.class);
                return new VolumeLookup(VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
            } catch (java.nio.file.NoSuchFileException absent) {
                return new VolumeLookup(VolumeStatus.CONFIRMED_DELETED, null);
            } catch (IOException | SecurityException failure) {
                return new VolumeLookup(VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
            }
        }
        StorageVolume volume = loadVolume(id).orElse(null);
        if (volume != null) return new VolumeLookup(VolumeStatus.PRESENT, volume);
        if (unreadableVolumes.containsKey(id)) return new VolumeLookup(VolumeStatus.UNREADABLE, null);
        if (unavailableVolumeIds.contains(id) || markerInspectionFailed) return new VolumeLookup(VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
        return new VolumeLookup(VolumeStatus.MISSING, null);
    }

    public synchronized List<StorageVolume> volumes(UUID ownerId) {
        return volumes(ownerId, Integer.MAX_VALUE);
    }

    public synchronized int volumeCount(UUID ownerId) {
        if (!ready || closed || generation != coordinator.generation) return 0;
        PlayerStorageAccount account = accounts.get(ownerId);
        return account == null ? 0 : account.volumeIds().size();
    }

    public synchronized List<UUID> volumeIds(UUID ownerId) {
        if (!ready || closed || generation != coordinator.generation) return List.of();
        PlayerStorageAccount account = accounts.get(ownerId);
        return account == null ? List.of() : account.volumeIds();
    }

    public synchronized List<StorageVolume> volumes(UUID ownerId, int maximum) {
        if (!ready || closed || generation != coordinator.generation) return List.of();
        PlayerStorageAccount account = accounts.get(ownerId);
        if (account == null) {
            return List.of();
        }
        return account.volumeIds().stream()
                .map(this::volume)
                .flatMap(Optional::stream)
                .limit(maximum)
                .toList();
    }

    public synchronized boolean ownsVolume(UUID ownerId, UUID volumeId) {
        if (!ready || closed || generation != coordinator.generation) return false;
        PlayerStorageAccount account = accounts.get(ownerId);
        return account != null && account.volumeIds().contains(volumeId) && knownVolumeIds.contains(volumeId);
    }

    public synchronized boolean renameVolume(UUID ownerId, UUID volumeId, String name) {
        StorageVolume volume = volume(volumeId).orElse(null);
        return volume != null && volume.ownerId().equals(ownerId) && volume.rename(name);
    }

    public synchronized boolean deleteEmptyVolume(UUID ownerId, UUID volumeId) {
        if (!ready || closing || closed || generation != coordinator.generation) return false;
        if (!accountsFullyLoaded || accountScanFailed || !futureSchemaAccounts.isEmpty() || !unreadableAccounts.isEmpty()) return false;
        if (!dev.kehai.digitalstorage.security.DigitalStorageMountTracker.mounts(volumeId).isEmpty()) return false;
        StorageVolume volume = loadedVolume(volumeId);
        if (volume == null
                || !volume.ownerId().equals(ownerId)
                || volume.record().storage().variantCount() != 0) {
            return false;
        }
        if (!persistVolumeDeletion(volumeId)) return false;
        deletionMarkers.add(volumeId);
        knownVolumeIds.remove(volumeId);
        loadedVolumes.remove(volumeId);
        dirtyVolumeInstances.remove(volumeId);
        dirtyVolumes.remove(volumeId);
        clearVolumeSnapshotProgress(volumeId);
        deletedVolumes.add(volumeId);

        PlayerStorageAccount account = accounts.get(ownerId);
        if (account != null) {
            account.removeVolume(volumeId);
            if (account.volumeIds().isEmpty()) {
                accounts.remove(ownerId);
                dirtyAccounts.remove(ownerId);
                deletedAccounts.add(ownerId);
            } else {
                markAccountDirty(ownerId);
            }
        }
        return true;
    }

    public synchronized int accountCount() {
        if (!ready || closed || generation != coordinator.generation) return 0;
        return accounts.size();
    }

    public synchronized int volumeCount() {
        if (!ready || closed || generation != coordinator.generation) return 0;
        return knownVolumeIds.size();
    }

    synchronized int loadedVolumeCountForTest() {
        return loadedVolumes.size();
    }

    public synchronized int quarantinedRecordCount() {
        return quarantinedAccountFiles + quarantinedVolumeFiles;
    }

    public synchronized int dirtyAccountCount() {
        return dirtyAccounts.size();
    }

    public synchronized int dirtyVolumeCount() {
        return dirtyVolumes.size();
    }

    public int pendingWriteBatches() {
        return pendingWriteBatches.get();
    }

    public synchronized long snapshotRestartCount() {
        return snapshotRestartCount;
    }

    public synchronized long forcedSnapshotCount() {
        return forcedSnapshotCount;
    }

    public synchronized long oldestDirtyVolumeAgeTicks() {
        return dirtyVolumeSinceTicks.values().stream()
                .mapToLong(startTick -> Math.max(0, persistenceTicks - startTick))
                .max()
                .orElse(0);
    }

    public void flushNow() {
        if (!flushAndWait()) throw new IllegalStateException("Digital Storage flush failed");
    }

    synchronized long persistenceFailureCount() { return persistenceFailureCount; }
    synchronized String lastPersistenceFailure() { return lastPersistenceFailure; }
    private synchronized void recordPersistenceFailure(String description, Exception failure) {
        persistenceFailureCount++;
        lastPersistenceFailure = description + ": " + failure;
    }

    String lastNbtEncodeThreadName() {
        return lastNbtEncodeThreadName;
    }

    public synchronized int variantCount() {
        return Math.toIntExact(contentStats(true).variantCount());
    }

    public synchronized BigInteger totalItemCount() {
        return contentStats(true).totalItemCount();
    }

    public synchronized ContentStats contentStats(boolean inspectColdVolumes) {
        long variants = 0;
        BigInteger items = BigInteger.ZERO;
        int inspectedVolumes = 0;
        int uninspectedVolumes = 0;
        for (UUID volumeId : List.copyOf(knownVolumeIds)) {
            StorageVolume volume = loadedVolume(volumeId);
            if (volume == null && inspectColdVolumes) {
                volume = loadVolume(volumeId).orElse(null);
            }
            if (volume != null) {
                VolumeLedger storage = volume.record().storage();
                variants = Math.addExact(variants, storage.variantCount());
                items = items.add(BigInteger.valueOf(storage.totalItemCount()));
                inspectedVolumes++;
                continue;
            }

            VolumeFileMetrics metrics = volumeFileMetrics.get(volumeId);
            if (metrics != null && metrics.contentKnown()) {
                variants = Math.addExact(variants, metrics.variantCount());
                items = items.add(BigInteger.valueOf(metrics.totalItemCount()));
                inspectedVolumes++;
            } else {
                uninspectedVolumes++;
            }
        }
        return new ContentStats(variants, items, inspectedVolumes, uninspectedVolumes);
    }

    public synchronized StorageSizeStats storageSizeStats() {
        long accountsOnDisk = accountDiskBytes.values().stream().mapToLong(Long::longValue).sum();
        long volumesOnDisk = volumeFileMetrics.values().stream()
                .mapToLong(VolumeFileMetrics::diskBytes)
                .sum();
        long totalVolumeNbt = volumeFileMetrics.values().stream()
                .mapToLong(VolumeFileMetrics::estimatedNbtBytes)
                .sum();
        long largestVolumeNbt = volumeFileMetrics.values().stream()
                .mapToLong(VolumeFileMetrics::estimatedNbtBytes)
                .max()
                .orElse(0);
        long averageVolumeNbt = volumeFileMetrics.isEmpty() ? 0 : totalVolumeNbt / volumeFileMetrics.size();
        int warningThreshold = DigitalStorageConfig.get().volumeNbtWarningBytes;
        long oversizedVolumes = warningThreshold == 0 ? 0 : volumeFileMetrics.values().stream()
                .filter(metrics -> metrics.estimatedNbtBytes() > warningThreshold)
                .count();
        return new StorageSizeStats(
                accountsOnDisk + volumesOnDisk,
                accountsOnDisk,
                volumesOnDisk,
                totalVolumeNbt,
                largestVolumeNbt,
                averageVolumeNbt,
                Math.toIntExact(oversizedVolumes)
        );
    }

    void flushAndWaitForTest() {
        flushAndWait();
    }

    void closeForTest() {
        close();
    }

    void beforeVolumeWriteCallbackForTest(java.util.function.Consumer<UUID> callback) {
        beforeVolumeWriteCallbackForTest = callback;
    }

    void flushAsyncForTest() {
        flushAsync(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    synchronized Set<UUID> drainVolumeSnapshotsForTest(int snapshotBudget, int variantBudget) {
        return Set.copyOf(drainWriteBatch(snapshotBudget, variantBudget).volumeWrites().keySet());
    }

    synchronized void advancePersistenceTicksForTest(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Persistence ticks cannot move backwards");
        }
        persistenceTicks += ticks;
    }

    private synchronized void markAccountDirty(UUID ownerId) {
        if ((!ready && !initializing) || (closed && !finalFlush)) return;
        if (accounts.containsKey(ownerId)) {
            deletedAccounts.remove(ownerId);
            dirtyAccounts.add(ownerId);
        }
    }

    private synchronized void markVolumeDirty(UUID volumeId) {
        if ((!ready && !initializing) || (closed && !finalFlush)) return;
        if (knownVolumeIds.contains(volumeId)) {
            StorageVolume volume = loadedVolume(volumeId);
            if (volume != null) {
                dirtyVolumeInstances.put(volumeId, volume);
            }
            deletedVolumes.remove(volumeId);
            if (dirtyVolumes.add(volumeId)) {
                dirtyVolumeSinceTicks.put(volumeId, persistenceTicks);
            }
        }
    }

    private synchronized void tick() {
        if (closed) return;
        if (!ready) {
            if (!coordinator.writeLock.tryLock()) return;
            try {
                initializeLocked();
                DigitalStorage.LOGGER.info("Digital Storage directory {} is ready after previous writer released it", rootDirectory);
            } finally { coordinator.writeLock.unlock(); }
            return;
        }
        persistenceTicks++;
        DigitalStorageConfig config = DigitalStorageConfig.get();
        int interval = config.persistenceFlushIntervalTicks;
        ticksSinceFlush++;
        if (ticksSinceFlush >= interval) {
            ticksSinceFlush = 0;
            flushRequested = true;
        }
        if (flushRequested) {
            flushAsync(config.persistenceSnapshotsPerTick, config.persistenceVariantsPerTick);
        }
    }

    private void loadAllFiles() {
        loadDeletedVolumeMarkers();
        indexVolumeFiles();
        loadAccountFiles();
        applyDeletionMarkers();
        repairAccountIndex();
        DigitalStorage.LOGGER.info(
                "Indexed Digital Storage files: accounts={}, volumes={}, loaded volumes={}, quarantined accounts={}, quarantined volumes={}",
                accounts.size(),
                knownVolumeIds.size(),
                loadedVolumes.size(),
                quarantinedAccountFiles,
                quarantinedVolumeFiles
        );
    }

    // Commit the marker before acknowledging deletion; never remove it while stale
    // account or volume snapshots could still be recovered after a process crash.
    private boolean persistVolumeDeletion(UUID id) {
        if (!coordinator.writeLock.tryLock()) return false;
        Path marker = deletedVolumeMarkersDirectory.resolve(id + ".dat");
        Path temporary = null;
        try {
            if (closed || !ready || generation != coordinator.generation) return false;
            temporary = Files.createTempFile(deletedVolumeMarkersDirectory, id.toString(), ".tmp");
            try (var file = java.nio.channels.FileChannel.open(temporary,
                    java.nio.file.StandardOpenOption.WRITE)) {
                file.force(true);
            }
            try {
                Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, marker);
            }
            return true;
        } catch (IOException exception) {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            DigitalStorage.LOGGER.error("Could not persist Digital Storage deletion for {}", id, exception);
            return false;
        } finally {
            coordinator.writeLock.unlock();
        }
    }

    private void loadDeletedVolumeMarkers() {
        for (Path path : dataFiles(deletedVolumeMarkersDirectory)) {
            try {
                deletionMarkers.add(uuidFromFile(path));
                // A valid marker is retained on disk as the source of truth.
            } catch (IllegalArgumentException invalid) {
                DigitalStorage.LOGGER.error("Invalid Digital Storage deletion marker {}; file left untouched", path, invalid);
            }
        }
    }

    private void applyDeletionMarkers() {
        if (!accountsFullyLoaded || accountScanFailed || !futureSchemaAccounts.isEmpty() || !unreadableAccounts.isEmpty()) {
            if (!deletionMarkers.isEmpty()) DigitalStorage.LOGGER.warn("Deferring volume deletions while account files are unavailable");
            return;
        }
        for (UUID id : deletionMarkers) {
            for (PlayerStorageAccount account : List.copyOf(accounts.values())) {
                if (account.removeVolume(id)) markAccountDirty(account.ownerId());
            }
            deletedVolumes.add(id);
        }
    }

    private void clearCompletedDeletionMarkers() {
        synchronized (this) {
            if (!accountsFullyLoaded || accountScanFailed || !futureSchemaAccounts.isEmpty() || !unreadableAccounts.isEmpty() || hasPendingChanges()) return;
            for (UUID id : List.copyOf(deletionMarkers)) {
                if (accounts.values().stream().anyMatch(account -> account.volumeIds().contains(id))) continue;
                try {
                    try {
                        Files.readAttributes(volumesDirectory.resolve(id + ".dat"), java.nio.file.attribute.BasicFileAttributes.class);
                        continue;
                    } catch (java.nio.file.NoSuchFileException absent) {
                        // Only confirmed absence permits marker retirement.
                    }
                    Files.deleteIfExists(deletedVolumeMarkersDirectory.resolve(id + ".dat"));
                    deletionMarkers.remove(id);
                } catch (IOException failure) {
                    recordPersistenceFailure("delete marker " + id, failure);
                    DigitalStorage.LOGGER.warn("Could not retire Digital Storage deletion marker {}", id, failure);
                }
            }
        }
    }

    private boolean hasDeletionMarker(UUID id) {
        try {
            Files.readAttributes(deletedVolumeMarkersDirectory.resolve(id + ".dat"), java.nio.file.attribute.BasicFileAttributes.class);
            markerInspectionFailed = false;
            return true;
        } catch (java.nio.file.NoSuchFileException missing) {
            markerInspectionFailed = false;
            return false;
        } catch (IOException | SecurityException failure) {
            markerInspectionFailed = true;
            DigitalStorage.LOGGER.warn("Could not inspect Digital Storage deletion marker for {}; treating it as present", id, failure);
            return true;
        }
    }

    private void indexVolumeFiles() {
        for (Path path : dataFiles(volumesDirectory)) {
            try {
                UUID fileId = uuidFromFile(path);
                if (hasDeletionMarker(fileId)) {
                    continue;
                }
                long diskBytes = Files.size(path);
                if (!knownVolumeIds.add(fileId)) {
                    throw new IllegalArgumentException("Duplicate volume UUID " + fileId);
                }
                volumeFileMetrics.put(fileId, new VolumeFileMetrics(0, diskBytes, 0, 0, false));
            } catch (IOException | RuntimeException exception) {
                volumeScanFailed = true;
                DigitalStorage.LOGGER.error("Could not index Digital Storage volume {}; file left untouched", path, exception);
            }
        }
    }

    private void loadAccountFiles() {
        for (Path path : dataFiles(accountsDirectory)) {
            try {
                CompoundTag nbt = readFile(path, ACCOUNT_SCHEMA_VERSION, "account");
                UUID fileId = uuidFromFile(path);
                long diskBytes = Files.size(path);
                PlayerStorageAccount account = PlayerStorageAccount.fromNbt(nbt);
                if (!account.ownerId().equals(fileId)) {
                    throw new IllegalArgumentException("Account owner does not match filename");
                }
                if (accounts.putIfAbsent(account.ownerId(), account) != null) {
                    throw new IllegalArgumentException("Duplicate account owner " + account.ownerId());
                }
                accountDiskBytes.put(fileId, diskBytes);
            } catch (FutureSchemaException exception) {
                accountsFullyLoaded = false;
                try {
                    futureSchemaAccounts.add(uuidFromFile(path));
                } catch (IllegalArgumentException invalidName) {
                    DigitalStorage.LOGGER.error("Digital Storage future-schema account {} has an invalid filename; file left untouched", path, invalidName);
                    continue;
                }
                DigitalStorage.LOGGER.error("Digital Storage account {} uses a newer schema; file left untouched and account skipped this session", path, exception);
            } catch (IOException | RuntimeException exception) {
                accountsFullyLoaded = false;
                try { unreadableAccounts.add(uuidFromFile(path)); }
                catch (IllegalArgumentException invalidName) { /* No owner can be inferred from the name. */ }
                DigitalStorage.LOGGER.error("Could not read Digital Storage account {}; file left untouched", path, exception);
            }
        }
    }

    private synchronized void repairAccountIndex() {
        for (PlayerStorageAccount account : List.copyOf(accounts.values())) {
            for (UUID volumeId : account.volumeIds()) {
                if (hasDeletionMarker(volumeId)) continue;
                if (!knownVolumeIds.contains(volumeId)) {
                    knownVolumeIds.add(volumeId);
                    if (missingVolumeIds.add(volumeId)) DigitalStorage.LOGGER.warn("Account {} references missing volume {}; reference preserved", account.ownerId(), volumeId);
                }
            }
        }
        for (UUID id : List.copyOf(knownVolumeIds)) {
            if (missingVolumeIds.contains(id)) continue;
            Path path = volumesDirectory.resolve(id + ".dat");
            try {
                CompoundTag nbt = readFile(path, VOLUME_SCHEMA_VERSION, "volume");
                if (nbt.hasUUID("Id") && id.equals(nbt.getUUID("Id")) && nbt.hasUUID("Owner")) {
                    repairVolumeOwner(id, nbt.getUUID("Owner"));
                }
            } catch (IOException | RuntimeException failure) {
                DigitalStorage.LOGGER.warn("Could not inspect owner of volume {}; file and account references preserved", id);
            }
        }
    }

    private StorageVolume loadedVolume(UUID id) {
        return loadedVolumes.get(id);
    }

    private void rememberLoaded(StorageVolume volume) {
        volume.record().storage().setActivePredicate(() -> ready && !closed && generation == coordinator.generation
                && loadedVolumes.get(volume.id()) == volume);
        loadedVolumes.put(volume.id(), volume);
    }

    private Optional<StorageVolume> loadVolume(UUID id) {
        if (hasDeletionMarker(id) || unreadableVolumes.containsKey(id) || !knownVolumeIds.contains(id)) {
            return Optional.empty();
        }
        Path path = volumesDirectory.resolve(id + ".dat");
        try {
            CompoundTag nbt = readFile(path, VOLUME_SCHEMA_VERSION, "volume");
            if (!nbt.hasUUID("Id") || !nbt.getUUID("Id").equals(id)) {
                throw new IllegalArgumentException("Volume ID does not match filename");
            }
            StorageVolume volume = StorageVolume.fromNbt(nbt, () -> markVolumeDirty(id));
            missingVolumeIds.remove(id);
            unavailableVolumeIds.remove(id);
            rememberLoaded(volume);
            if (dirtyVolumes.contains(id)) {
                dirtyVolumeInstances.put(id, volume);
            }
            VolumeLedger storage = volume.record().storage();
            recordVolumeFileMetrics(
                    id,
                    nbt.sizeInBytes(),
                    Files.size(path),
                    storage.variantCount(),
                    storage.totalItemCount()
            );
            repairLoadedVolumeAccount(volume);
            return Optional.of(volume);
        } catch (FutureSchemaException exception) {
            markUnreadable(id, exception.getMessage());
            return Optional.empty();
        } catch (InvalidStorageDataException exception) {
            markUnreadable(id, exception.getMessage());
            return Optional.empty();
        } catch (java.nio.file.NoSuchFileException exception) {
            if (missingVolumeIds.add(id)) DigitalStorage.LOGGER.warn("Digital Storage volume file {} is missing; reference preserved", path);
            return Optional.empty();
        } catch (java.io.EOFException | java.util.zip.ZipException exception) {
            markUnreadable(id, exception.toString());
            return Optional.empty();
        } catch (IOException | SecurityException exception) {
            if (!(exception instanceof SecurityException) && Files.notExists(path)) {
                if (missingVolumeIds.add(id)) DigitalStorage.LOGGER.warn("Digital Storage volume file {} is missing; reference preserved", path);
                return Optional.empty();
            }
            if (unavailableVolumeIds.add(id)) DigitalStorage.LOGGER.warn("Digital Storage volume {} is temporarily unavailable; file left untouched", id, exception);
            return Optional.empty();
        } catch (RuntimeException exception) {
            markUnreadable(id, exception.toString());
            return Optional.empty();
        }
    }

    private void markUnreadable(UUID id, String reason) {
        if (unreadableVolumes.putIfAbsent(id, reason) == null) {
            DigitalStorage.LOGGER.error("Digital Storage volume {} is unreadable: {}; file {} was not modified", id, reason, volumesDirectory.resolve(id + ".dat"));
        }
    }

    private void repairLoadedVolumeAccount(StorageVolume volume) {
        repairVolumeOwner(volume.id(), volume.ownerId());
    }

    private void repairVolumeOwner(UUID volumeId, UUID ownerId) {
        if (accountScanFailed || futureSchemaAccounts.contains(ownerId) || unreadableAccounts.contains(ownerId)) return;
        for (PlayerStorageAccount account : List.copyOf(accounts.values())) {
            if (!account.ownerId().equals(ownerId) && account.removeVolume(volumeId)) {
                DigitalStorage.LOGGER.warn("Volume {} is indexed by {} but belongs to {}; repairing index", volumeId, account.ownerId(), ownerId);
                markAccountDirty(account.ownerId());
            }
        }
        PlayerStorageAccount owner = accounts.computeIfAbsent(ownerId, PlayerStorageAccount::new);
        if (owner.addVolume(volumeId)) {
            markAccountDirty(owner.ownerId());
        }
    }

    private static CompoundTag readFile(Path path, int supportedSchema, String description) throws IOException {
        CompoundTag nbt = NbtIo.readCompressed(path.toFile());
        int schema = nbt.contains(SCHEMA_VERSION_KEY, Tag.TAG_INT)
                ? nbt.getInt(SCHEMA_VERSION_KEY)
                : 0;
        if (schema > supportedSchema) {
            throw new FutureSchemaException(
                    "Digital Storage " + description + " schema " + schema
                            + " is newer than supported schema " + supportedSchema
            );
        }
        return nbt;
    }

    private List<Path> dataFiles(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .sorted()
                    .toList();
        } catch (IOException | SecurityException exception) {
            if (directory.equals(accountsDirectory)) { accountScanFailed = true; accountsFullyLoaded = false; }
            if (directory.equals(volumesDirectory)) volumeScanFailed = true;
            DigitalStorage.LOGGER.error("Could not list Digital Storage directory {}; files left untouched", directory, exception);
            return List.of();
        }
    }

    private static int regularFileCount(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            return Math.toIntExact(paths.filter(Files::isRegularFile).count());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect Digital Storage directory " + directory, exception);
        }
    }

    private static UUID uuidFromFile(Path path) {
        String filename = path.getFileName().toString();
        return UUID.fromString(filename.substring(0, filename.length() - 4));
    }

    private void flushAsync(int snapshotBudget, int variantBudget) {
        if (!ready || (closed && !finalFlush) || generation != coordinator.generation) return;
        if (!pendingWriteBatches.compareAndSet(0, 1)) {
            return;
        }
        WriteBatch batch;
        try {
            batch = drainWriteBatch(snapshotBudget, variantBudget);
        } catch (RuntimeException exception) {
            pendingWriteBatches.set(0);
            throw exception;
        }
        if (batch.isEmpty()) {
            pendingWriteBatches.set(0);
            synchronized (this) {
                flushRequested = hasPendingChanges();
            }
            return;
        }
        try {
        writer.execute(() -> {
            coordinator.writeLock.lock();
            try {
                if ((closed && !finalFlush) || generation != coordinator.generation) {
                    recordPersistenceFailure("stale writer batch", new IllegalStateException("closed or generation changed"));
                    DigitalStorage.LOGGER.error("Discarded late Digital Storage write batch for {}", rootDirectory);
                    return;
                }
                writeBatch(batch);
            } finally {
                coordinator.writeLock.unlock();
                pendingWriteBatches.set(0);
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException failure) {
            synchronized (this) {
                batch.accountWrites().keySet().forEach(this::markAccountDirty);
                batch.volumeWrites().keySet().forEach(this::markVolumeDirty);
                batch.accountDeletes().forEach(this::retryAccountDelete);
                batch.volumeDeletes().forEach(this::retryVolumeDelete);
                pendingWriteBatches.set(0);
            }
            recordPersistenceFailure("writer rejected batch", failure);
            DigitalStorage.LOGGER.error("Digital Storage writer rejected a write batch", failure);
        }
    }

    private synchronized WriteBatch drainWriteBatch(int snapshotBudget, int variantBudget) {
        Map<UUID, PlayerStorageAccount.Snapshot> accountWrites = new HashMap<>();
        for (UUID ownerId : peek(dirtyAccounts, snapshotBudget)) {
            PlayerStorageAccount account = accounts.get(ownerId);
            if (account != null) {
                accountWrites.put(ownerId, account.snapshot());
            }
            dirtyAccounts.remove(ownerId);
        }

        Map<UUID, StorageVolume.Snapshot> volumeWrites = new HashMap<>();
        int remainingVariantBudget = variantBudget;
        for (UUID volumeId : peek(dirtyVolumes, snapshotBudget)) {
            StorageVolume volume = dirtyVolumeInstances.get(volumeId);
            if (volume == null) {
                volume = loadedVolume(volumeId);
            }
            if (volume != null) {
                VolumeLedger.SnapshotCursor cursor = volumeSnapshotCursors.get(volumeId);
                if (cursor == null) {
                    cursor = volume.record().storage().snapshotCursor();
                    volumeSnapshotCursors.put(volumeId, cursor);
                }
                VolumeLedger.CursorProgress progress = cursor.advance(remainingVariantBudget);
                int examinedEntries = progress.examinedEntries();
                boolean forceSnapshot = false;
                if (progress.restarted()) {
                    snapshotRestartCount++;
                    int restarts = volumeSnapshotRestarts.merge(volumeId, 1, Integer::sum);
                    forceSnapshot = restarts >= MAX_INCREMENTAL_SNAPSHOT_RESTARTS;
                }
                long dirtySinceTick = dirtyVolumeSinceTicks.getOrDefault(volumeId, persistenceTicks);
                if (!progress.complete()
                        && (forceSnapshot || persistenceTicks - dirtySinceTick >= MAX_DIRTY_VOLUME_AGE_TICKS)) {
                    VolumeLedger.CursorProgress forcedProgress = cursor.advance(Integer.MAX_VALUE);
                    examinedEntries += forcedProgress.examinedEntries();
                    progress = forcedProgress;
                    forcedSnapshotCount++;
                }
                remainingVariantBudget -= examinedEntries;
                if (progress.complete()) {
                    volumeWrites.put(volumeId, volume.snapshot(progress.snapshots()));
                    dirtyVolumes.remove(volumeId);
                    clearVolumeSnapshotProgress(volumeId);
                } else {
                    dirtyVolumes.remove(volumeId);
                    dirtyVolumes.add(volumeId);
                }
            } else {
                dirtyVolumes.remove(volumeId);
                clearVolumeSnapshotProgress(volumeId);
            }
            if (remainingVariantBudget <= 0) {
                break;
            }
        }

        Set<UUID> accountDeletes = Set.copyOf(take(deletedAccounts, snapshotBudget));
        Set<UUID> volumeDeletes = Set.copyOf(take(deletedVolumes, snapshotBudget));
        return new WriteBatch(accountWrites, volumeWrites, accountDeletes, volumeDeletes);
    }

    private void clearVolumeSnapshotProgress(UUID volumeId) {
        volumeSnapshotCursors.remove(volumeId);
        volumeSnapshotRestarts.remove(volumeId);
        dirtyVolumeSinceTicks.remove(volumeId);
    }

    private boolean hasPendingChanges() {
        return !dirtyAccounts.isEmpty()
                || !dirtyVolumes.isEmpty()
                || !deletedAccounts.isEmpty()
                || !deletedVolumes.isEmpty();
    }

    private static <T> List<T> take(Set<T> source, int maximum) {
        List<T> selected = peek(source, maximum);
        source.removeAll(selected);
        return selected;
    }

    private static <T> List<T> peek(Set<T> source, int maximum) {
        List<T> selected = new ArrayList<>(Math.min(source.size(), maximum));
        java.util.Iterator<T> iterator = source.iterator();
        while (iterator.hasNext() && selected.size() < maximum) {
            selected.add(iterator.next());
        }
        return selected;
    }

    private void writeBatch(WriteBatch batch) {
        batch.accountWrites().forEach((id, snapshot) -> writeOrRetry(
                accountsDirectory.resolve(id + ".dat"),
                () -> {
                    lastNbtEncodeThreadName = Thread.currentThread().getName();
                    CompoundTag nbt = snapshot.writeNbt();
                    nbt.putInt(SCHEMA_VERSION_KEY, ACCOUNT_SCHEMA_VERSION);
                    return nbt;
                },
                measurement -> recordAccountFileMetrics(id, measurement.diskBytes()),
                () -> markAccountDirty(id)
        ));
        batch.volumeWrites().forEach((id, snapshot) -> writeOrRetry(
                volumesDirectory.resolve(id + ".dat"),
                () -> {
                    lastNbtEncodeThreadName = Thread.currentThread().getName();
                    CompoundTag nbt = snapshot.writeNbt();
                    nbt.putInt(SCHEMA_VERSION_KEY, VOLUME_SCHEMA_VERSION);
                    return nbt;
                },
                measurement -> {
                    var hook = beforeVolumeWriteCallbackForTest;
                    if (hook != null) hook.accept(id);
                    recordVolumeFileMetrics(id, measurement.estimatedNbtBytes(), measurement.diskBytes(),
                            snapshot.record().items().size(), snapshotItemCount(snapshot));
                },
                () -> markVolumeDirty(id)
        ));
        batch.accountDeletes().forEach(id -> deleteOrRetry(
                accountsDirectory.resolve(id + ".dat"),
                () -> forgetAccountFileMetrics(id),
                () -> retryAccountDelete(id)
        ));
        batch.volumeDeletes().forEach(id -> deleteOrRetry(
                volumesDirectory.resolve(id + ".dat"),
                () -> forgetVolumeFileMetrics(id),
                () -> retryVolumeDelete(id)
        ));
        if (!closed && generation == coordinator.generation) clearCompletedDeletionMarkers();
    }

    private void writeOrRetry(
            Path target,
            Supplier<CompoundTag> nbtFactory,
            Consumer<FileMeasurement> onSuccess,
            Runnable retry
    ) {
        try {
            CompoundTag nbt = nbtFactory.get();
            long diskBytes = writeAtomic(target, nbt);
            onSuccess.accept(new FileMeasurement(nbt.sizeInBytes(), diskBytes));
        } catch (IOException | RuntimeException exception) {
            recordPersistenceFailure("write " + target, exception);
            DigitalStorage.LOGGER.error("Could not write Digital Storage file {}; will retry", target, exception);
            retry.run();
        }
    }

    private void deleteOrRetry(Path target, Runnable onSuccess, Runnable retry) {
        try {
            Files.deleteIfExists(target);
            onSuccess.run();
        } catch (IOException exception) {
            recordPersistenceFailure("delete " + target, exception);
            DigitalStorage.LOGGER.error("Could not delete Digital Storage file {}; will retry", target, exception);
            retry.run();
        }
    }

    private synchronized void recordAccountFileMetrics(UUID id, long diskBytes) {
        accountDiskBytes.put(id, diskBytes);
    }

    private synchronized void recordVolumeFileMetrics(
            UUID id,
            long estimatedNbtBytes,
            long diskBytes,
            int variantCount,
            long totalItemCount
    ) {
        if (!knownVolumeIds.contains(id)) return;
        volumeFileMetrics.put(id, new VolumeFileMetrics(
                estimatedNbtBytes,
                diskBytes,
                variantCount,
                totalItemCount,
                true
        ));
        if (!dirtyVolumes.contains(id)) {
            dirtyVolumeInstances.remove(id);
        }
        int warningThreshold = DigitalStorageConfig.get().volumeNbtWarningBytes;
        if (warningThreshold > 0 && estimatedNbtBytes > warningThreshold) {
            if (warnedOversizedVolumes.add(id)) {
                DigitalStorage.LOGGER.warn(
                        "Storage volume {} estimated NBT size is {} bytes, above the soft warning threshold of {} bytes",
                        id,
                        estimatedNbtBytes,
                        warningThreshold
                );
            }
        } else {
            warnedOversizedVolumes.remove(id);
        }
    }

    private synchronized void forgetAccountFileMetrics(UUID id) {
        accountDiskBytes.remove(id);
    }

    private synchronized void forgetVolumeFileMetrics(UUID id) {
        volumeFileMetrics.remove(id);
        warnedOversizedVolumes.remove(id);
    }

    private synchronized void retryAccountDelete(UUID id) {
        if (!accounts.containsKey(id)) {
            deletedAccounts.add(id);
        }
    }

    private synchronized void retryVolumeDelete(UUID id) {
        if (!knownVolumeIds.contains(id)) {
            deletedVolumes.add(id);
        }
    }

    private static long snapshotItemCount(StorageVolume.Snapshot snapshot) {
        long total = 0;
        for (VolumeLedger.StoredEntrySnapshot entry : snapshot.record().items()) {
            total = Math.addExact(total, entry.amount());
        }
        return total;
    }

    private static long writeAtomic(Path target, CompoundTag nbt) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        NbtIo.writeCompressed(nbt, temporary.toFile());
        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return Files.size(target);
    }

    private boolean flushAndWait() {
        if (!ready || writer == null || (closed && !finalFlush) || generation != coordinator.generation) return false;
        synchronized (this) {
            // Final flush starts from live storage, including after a failed tick.
            // Keep dirty records and writer ordering; discard only partial scans.
            volumeSnapshotCursors.clear();
            volumeSnapshotRestarts.clear();
        }
        flushRequested = true;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(flushTimeoutSeconds);
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                flushAsync(Integer.MAX_VALUE, Integer.MAX_VALUE);
                Future<?> barrier = writer.submit(() -> { });
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException("flush deadline exceeded");
                barrier.get(remaining, TimeUnit.NANOSECONDS);
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                recordPersistenceFailure("flush", exception);
                DigitalStorage.LOGGER.error("Could not flush Digital Storage files", exception);
                return false;
            }
            synchronized (this) {
                if (!hasPendingChanges() && pendingWriteBatches.get() == 0) {
                    flushRequested = false;
                    return true;
                }
            }
        }
        recordPersistenceFailure("flush", new IllegalStateException("files remained dirty after four attempts"));
        DigitalStorage.LOGGER.error("Digital Storage files remained dirty after four flush attempts");
        return false;
    }

    private void close() {
        synchronized (this) {
            if (closing || closed) return;
            closing = true;
            finalFlush = ready;
            closed = true;
        }
        if (ready) flushAndWait();
        finalFlush = false;
        if (writer != null) writer.shutdown();
    }

    private record WriteBatch(
            Map<UUID, PlayerStorageAccount.Snapshot> accountWrites,
            Map<UUID, StorageVolume.Snapshot> volumeWrites,
            Set<UUID> accountDeletes,
            Set<UUID> volumeDeletes
    ) {
        boolean isEmpty() {
            return accountWrites.isEmpty()
                    && volumeWrites.isEmpty()
                    && accountDeletes.isEmpty()
                    && volumeDeletes.isEmpty();
        }
    }

    public record StorageSizeStats(
            long liveDiskBytes,
            long accountDiskBytes,
            long volumeDiskBytes,
            long totalEstimatedVolumeNbtBytes,
            long largestEstimatedVolumeNbtBytes,
            long averageEstimatedVolumeNbtBytes,
            int oversizedVolumeCount
    ) {
    }

    public record ContentStats(
            long variantCount,
            BigInteger totalItemCount,
            int inspectedVolumeCount,
            int uninspectedVolumeCount
    ) {
    }

    private record FileMeasurement(long estimatedNbtBytes, long diskBytes) {
    }

    private record VolumeFileMetrics(
            long estimatedNbtBytes,
            long diskBytes,
            int variantCount,
            long totalItemCount,
            boolean contentKnown
    ) {
    }

    private static final class FutureSchemaException extends IllegalStateException {
        private FutureSchemaException(String message) {
            super(message);
        }
    }
}
