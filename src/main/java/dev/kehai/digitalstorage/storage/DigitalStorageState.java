package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import java.io.IOException;
import java.math.BigInteger;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
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
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;

/**
 * Server-level storage manager. Accounts and volumes are deliberately persisted
 * as independent files rather than a monolithic Minecraft PersistentState.
 */
public final class DigitalStorageState {
    static final int ACCOUNT_SCHEMA_VERSION = 1;
    static final int VOLUME_SCHEMA_VERSION = 1;
    static final int MAX_INCREMENTAL_SNAPSHOT_RESTARTS = 4;
    static final long MAX_DIRTY_VOLUME_AGE_TICKS = 600;
    private static final String SCHEMA_VERSION_KEY = "SchemaVersion";
    private static final Map<MinecraftServer, DigitalStorageState> INSTANCES = new WeakHashMap<>();
    private static boolean lifecycleRegistered;

    private final Path accountsDirectory;
    private final Path volumesDirectory;
    private final Path quarantinedAccountsDirectory;
    private final Path quarantinedVolumesDirectory;
    private final Map<UUID, PlayerStorageAccount> accounts = new HashMap<>();
    private final Map<UUID, VolumeReference> loadedVolumes = new HashMap<>();
    private final ReferenceQueue<StorageVolume> unloadedVolumes = new ReferenceQueue<>();
    private final Map<UUID, StorageVolume> dirtyVolumeInstances = new HashMap<>();
    private final Set<UUID> knownVolumeIds = new HashSet<>();
    private final Set<UUID> dirtyAccounts = new HashSet<>();
    private final Set<UUID> dirtyVolumes = new LinkedHashSet<>();
    private final Set<UUID> deletedAccounts = new HashSet<>();
    private final Set<UUID> deletedVolumes = new HashSet<>();
    private final Map<UUID, DigitalItemStorage.SnapshotCursor> volumeSnapshotCursors = new HashMap<>();
    private final Map<UUID, Integer> volumeSnapshotRestarts = new HashMap<>();
    private final Map<UUID, Long> dirtyVolumeSinceTicks = new HashMap<>();
    private final Map<UUID, Long> accountDiskBytes = new HashMap<>();
    private final Map<UUID, VolumeFileMetrics> volumeFileMetrics = new HashMap<>();
    private final Set<UUID> warnedOversizedVolumes = new HashSet<>();
    private final ExecutorService writer;
    private final AtomicInteger pendingWriteBatches = new AtomicInteger();
    private int quarantinedAccountFiles;
    private int quarantinedVolumeFiles;
    private int ticksSinceFlush;
    private long persistenceTicks;
    private long snapshotRestartCount;
    private long forcedSnapshotCount;
    private boolean flushRequested;
    private volatile String lastNbtEncodeThreadName = "";

    private DigitalStorageState(Path rootDirectory) {
        accountsDirectory = rootDirectory.resolve("accounts");
        volumesDirectory = rootDirectory.resolve("volumes");
        quarantinedAccountsDirectory = rootDirectory.resolve("quarantine").resolve("accounts");
        quarantinedVolumesDirectory = rootDirectory.resolve("quarantine").resolve("volumes");
        try {
            Files.createDirectories(accountsDirectory);
            Files.createDirectories(volumesDirectory);
            Files.createDirectories(quarantinedAccountsDirectory);
            Files.createDirectories(quarantinedVolumesDirectory);
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
    }

    public static synchronized void registerLifecycle() {
        if (lifecycleRegistered) {
            return;
        }
        lifecycleRegistered = true;
        ServerLifecycleEvents.SERVER_STARTING.register(DigitalStorageState::getOrCreate);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DigitalStorageState state;
            synchronized (DigitalStorageState.class) {
                state = INSTANCES.get(server);
            }
            if (state != null) {
                state.tick();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            DigitalStorageState state;
            synchronized (DigitalStorageState.class) {
                state = INSTANCES.get(server);
            }
            if (state != null) {
                state.close();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            synchronized (DigitalStorageState.class) {
                INSTANCES.remove(server);
            }
        });
    }

    public static DigitalStorageState get(ServerWorld world) {
        return getOrCreate(world.getServer());
    }

    private static synchronized DigitalStorageState getOrCreate(MinecraftServer server) {
        return INSTANCES.computeIfAbsent(server, ignored -> new DigitalStorageState(
                server.getSavePath(WorldSavePath.ROOT).resolve("digitalstorage")
        ));
    }

    static DigitalStorageState openForTest(Path rootDirectory) {
        return new DigitalStorageState(rootDirectory);
    }

    public synchronized Optional<StorageVolume> createVolume(UUID ownerId, String name, int maximumVolumes) {
        PlayerStorageAccount existingAccount = accounts.get(ownerId);
        int currentVolumes = existingAccount == null ? 0 : existingAccount.volumeIds().size();
        if (maximumVolumes > 0 && currentVolumes >= maximumVolumes) {
            return Optional.empty();
        }

        UUID id;
        do {
            id = UUID.randomUUID();
        } while (knownVolumeIds.contains(id));

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
        StorageVolume loaded = loadedVolume(id);
        return loaded == null ? loadVolume(id) : Optional.of(loaded);
    }

    public synchronized List<StorageVolume> volumes(UUID ownerId) {
        PlayerStorageAccount account = accounts.get(ownerId);
        if (account == null) {
            return List.of();
        }
        return account.volumeIds().stream()
                .map(this::volume)
                .flatMap(Optional::stream)
                .toList();
    }

    public synchronized boolean ownsVolume(UUID ownerId, UUID volumeId) {
        PlayerStorageAccount account = accounts.get(ownerId);
        return account != null && account.volumeIds().contains(volumeId) && knownVolumeIds.contains(volumeId);
    }

    public synchronized boolean renameVolume(UUID ownerId, UUID volumeId, String name) {
        StorageVolume volume = volume(volumeId).orElse(null);
        return volume != null && volume.ownerId().equals(ownerId) && volume.rename(name);
    }

    public synchronized boolean deleteEmptyVolume(UUID ownerId, UUID volumeId) {
        StorageVolume volume = volume(volumeId).orElse(null);
        if (volume == null
                || !volume.ownerId().equals(ownerId)
                || volume.record().storage().variantCount() != 0) {
            return false;
        }
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
        return accounts.size();
    }

    public synchronized int volumeCount() {
        return knownVolumeIds.size();
    }

    synchronized int loadedVolumeCountForTest() {
        return (int) loadedVolumes.values().stream().filter(reference -> reference.get() != null).count();
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
        flushAndWait();
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
                DigitalItemStorage storage = volume.record().storage();
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
        if (accounts.containsKey(ownerId)) {
            deletedAccounts.remove(ownerId);
            dirtyAccounts.add(ownerId);
        }
    }

    private synchronized void markVolumeDirty(UUID volumeId) {
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
        persistenceTicks++;
        drainUnloadedVolumes(16);
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
        indexVolumeFiles();
        loadAccountFiles();
        repairAccountIndex();
        DigitalStorageMod.LOGGER.info(
                "Indexed Digital Storage files: accounts={}, volumes={}, loaded volumes={}, quarantined accounts={}, quarantined volumes={}",
                accounts.size(),
                knownVolumeIds.size(),
                loadedVolumes.size(),
                quarantinedAccountFiles,
                quarantinedVolumeFiles
        );
    }

    private void indexVolumeFiles() {
        for (Path path : dataFiles(volumesDirectory)) {
            try {
                UUID fileId = uuidFromFile(path);
                long diskBytes = Files.size(path);
                if (!knownVolumeIds.add(fileId)) {
                    throw new IllegalArgumentException("Duplicate volume UUID " + fileId);
                }
                volumeFileMetrics.put(fileId, new VolumeFileMetrics(0, diskBytes, 0, 0, false));
            } catch (IOException | RuntimeException exception) {
                quarantinedVolumeFiles++;
                Path quarantined = quarantineFile(path, quarantinedVolumesDirectory);
                DigitalStorageMod.LOGGER.error(
                        "Quarantined unreadable Digital Storage volume file {} to {}",
                        path,
                        quarantined,
                        exception
                );
            }
        }
    }

    private void loadAccountFiles() {
        for (Path path : dataFiles(accountsDirectory)) {
            try {
                NbtCompound nbt = readFile(path, ACCOUNT_SCHEMA_VERSION, "account");
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
                throw exception;
            } catch (IOException | RuntimeException exception) {
                quarantinedAccountFiles++;
                Path quarantined = quarantineFile(path, quarantinedAccountsDirectory);
                DigitalStorageMod.LOGGER.error(
                        "Quarantined unreadable Digital Storage account file {} to {}",
                        path,
                        quarantined,
                        exception
                );
            }
        }
    }

    private synchronized void repairAccountIndex() {
        for (PlayerStorageAccount account : List.copyOf(accounts.values())) {
            boolean changed = false;
            for (UUID volumeId : account.volumeIds()) {
                if (!knownVolumeIds.contains(volumeId)) {
                    account.removeVolume(volumeId);
                    changed = true;
                }
            }
            if (changed) {
                markAccountDirty(account.ownerId());
            }
        }
    }

    private StorageVolume loadedVolume(UUID id) {
        VolumeReference reference = loadedVolumes.get(id);
        StorageVolume volume = reference == null ? null : reference.get();
        if (reference != null && volume == null) {
            loadedVolumes.remove(id);
        }
        return volume;
    }

    private void rememberLoaded(StorageVolume volume) {
        loadedVolumes.put(volume.id(), new VolumeReference(volume, unloadedVolumes));
    }

    private void drainUnloadedVolumes(int maximum) {
        for (int count = 0; count < maximum; count++) {
            VolumeReference reference = (VolumeReference) unloadedVolumes.poll();
            if (reference == null) {
                return;
            }
            loadedVolumes.remove(reference.id, reference);
        }
    }

    private Optional<StorageVolume> loadVolume(UUID id) {
        if (!knownVolumeIds.contains(id)) {
            return Optional.empty();
        }
        Path path = volumesDirectory.resolve(id + ".dat");
        try {
            NbtCompound nbt = readFile(path, VOLUME_SCHEMA_VERSION, "volume");
            if (!nbt.containsUuid("Id") || !nbt.getUuid("Id").equals(id)) {
                throw new IllegalArgumentException("Volume ID does not match filename");
            }
            StorageVolume volume = StorageVolume.fromNbt(nbt, () -> markVolumeDirty(id));
            rememberLoaded(volume);
            if (dirtyVolumes.contains(id)) {
                dirtyVolumeInstances.put(id, volume);
            }
            DigitalItemStorage storage = volume.record().storage();
            recordVolumeFileMetrics(
                    id,
                    nbt.getSizeInBytes(),
                    Files.size(path),
                    storage.variantCount(),
                    storage.totalItemCount()
            );
            repairLoadedVolumeAccount(volume);
            return Optional.of(volume);
        } catch (FutureSchemaException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            knownVolumeIds.remove(id);
            loadedVolumes.remove(id);
            forgetVolumeFileMetrics(id);
            removeVolumeFromAccounts(id);
            quarantinedVolumeFiles++;
            Path quarantined = quarantineFile(path, quarantinedVolumesDirectory);
            DigitalStorageMod.LOGGER.error(
                    "Quarantined unreadable Digital Storage volume file {} to {}",
                    path,
                    quarantined,
                    exception
            );
            return Optional.empty();
        }
    }

    private void repairLoadedVolumeAccount(StorageVolume volume) {
        for (PlayerStorageAccount account : List.copyOf(accounts.values())) {
            if (!account.ownerId().equals(volume.ownerId()) && account.removeVolume(volume.id())) {
                markAccountDirty(account.ownerId());
            }
        }
        PlayerStorageAccount owner = accounts.computeIfAbsent(volume.ownerId(), PlayerStorageAccount::new);
        if (owner.addVolume(volume.id())) {
            markAccountDirty(owner.ownerId());
        }
    }

    private void removeVolumeFromAccounts(UUID volumeId) {
        for (PlayerStorageAccount account : List.copyOf(accounts.values())) {
            if (account.removeVolume(volumeId)) {
                markAccountDirty(account.ownerId());
            }
        }
    }

    private static NbtCompound readFile(Path path, int supportedSchema, String description) throws IOException {
        NbtCompound nbt = NbtIo.readCompressed(path.toFile());
        int schema = nbt.contains(SCHEMA_VERSION_KEY, NbtElement.INT_TYPE)
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

    private static List<Path> dataFiles(Path directory) {
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".dat"))
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not list Digital Storage directory " + directory, exception);
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

    private static Path quarantineFile(Path source, Path quarantineDirectory) {
        try {
            Files.createDirectories(quarantineDirectory);
            String filename = source.getFileName().toString();
            Path target = quarantineDirectory.resolve(filename + ".broken-" + System.currentTimeMillis());
            int suffix = 1;
            while (Files.exists(target)) {
                target = quarantineDirectory.resolve(filename + ".broken-" + System.currentTimeMillis() + "-" + suffix++);
            }
            return Files.move(source, target);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not quarantine Digital Storage file " + source, exception);
        }
    }

    private void flushAsync(int snapshotBudget, int variantBudget) {
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
        writer.execute(() -> {
            try {
                writeBatch(batch);
            } finally {
                pendingWriteBatches.set(0);
            }
        });
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
                DigitalItemStorage.SnapshotCursor cursor = volumeSnapshotCursors.get(volumeId);
                if (cursor == null) {
                    cursor = volume.record().storage().snapshotCursor();
                    volumeSnapshotCursors.put(volumeId, cursor);
                }
                DigitalItemStorage.CursorProgress progress = cursor.advance(remainingVariantBudget);
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
                    DigitalItemStorage.CursorProgress forcedProgress = cursor.advance(Integer.MAX_VALUE);
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
                    NbtCompound nbt = snapshot.writeNbt();
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
                    NbtCompound nbt = snapshot.writeNbt();
                    nbt.putInt(SCHEMA_VERSION_KEY, VOLUME_SCHEMA_VERSION);
                    return nbt;
                },
                measurement -> recordVolumeFileMetrics(
                        id,
                        measurement.estimatedNbtBytes(),
                        measurement.diskBytes(),
                        snapshot.record().items().size(),
                        snapshotItemCount(snapshot)
                ),
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
    }

    private static void writeOrRetry(
            Path target,
            Supplier<NbtCompound> nbtFactory,
            Consumer<FileMeasurement> onSuccess,
            Runnable retry
    ) {
        try {
            NbtCompound nbt = nbtFactory.get();
            long diskBytes = writeAtomic(target, nbt);
            onSuccess.accept(new FileMeasurement(nbt.getSizeInBytes(), diskBytes));
        } catch (IOException | RuntimeException exception) {
            DigitalStorageMod.LOGGER.error("Could not write Digital Storage file {}; will retry", target, exception);
            retry.run();
        }
    }

    private static void deleteOrRetry(Path target, Runnable onSuccess, Runnable retry) {
        try {
            Files.deleteIfExists(target);
            onSuccess.run();
        } catch (IOException exception) {
            DigitalStorageMod.LOGGER.error("Could not delete Digital Storage file {}; will retry", target, exception);
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
        knownVolumeIds.add(id);
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
                DigitalStorageMod.LOGGER.warn(
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
        for (DigitalItemStorage.StoredEntrySnapshot entry : snapshot.record().items()) {
            total = Math.addExact(total, entry.amount());
        }
        return total;
    }

    private static long writeAtomic(Path target, NbtCompound nbt) throws IOException {
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

    private void flushAndWait() {
        flushRequested = true;
        for (int attempt = 1; attempt <= 4; attempt++) {
            flushAsync(Integer.MAX_VALUE, Integer.MAX_VALUE);
            try {
                Future<?> barrier = writer.submit(() -> { });
                barrier.get(60, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("Timed out flushing Digital Storage files", exception);
            }
            synchronized (this) {
                if (!hasPendingChanges() && pendingWriteBatches.get() == 0) {
                    flushRequested = false;
                    return;
                }
            }
        }
        throw new IllegalStateException("Digital Storage files remained dirty after four flush attempts");
    }

    private void close() {
        flushAndWait();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Digital Storage writer did not stop in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping Digital Storage writer", exception);
        }
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

    private static final class VolumeReference extends WeakReference<StorageVolume> {
        private final UUID id;

        private VolumeReference(StorageVolume volume, ReferenceQueue<StorageVolume> queue) {
            super(volume, queue);
            id = volume.id();
        }
    }

    private static final class FutureSchemaException extends IllegalStateException {
        private FutureSchemaException(String message) {
            super(message);
        }
    }
}
