package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.DigitalStorage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

/** One SavedData document; the host schedules saves and this class safely replaces its file. */
public final class DigitalStorageData extends SavedData {
    public static final String ID = "digitalstorage";
    public static final int SCHEMA_VERSION = 1;
    private static final java.util.concurrent.atomic.AtomicLong BACKUP_CLOCK = new java.util.concurrent.atomic.AtomicLong();
    final Map<UUID, PlayerStorageAccount> accounts = new LinkedHashMap<>();
    final Map<UUID, StorageVolume> volumes = new LinkedHashMap<>();
    final Map<UUID, String> legacyUnreadable = new LinkedHashMap<>();
    final Map<UUID, String> legacyUnreadableAccounts = new LinkedHashMap<>();
    final GlobalBudget globalBudget = new GlobalBudget(0, () -> Math.max(1_048_576,
            Math.min(dev.kehai.digitalstorage.config.DigitalStorageConfig.HARD_MAX_TOTAL_VARIANT_NBT_BYTES,
                    dev.kehai.digitalstorage.config.DigitalStorageConfig.get().maxTotalVariantNbtBytes)));
    final TransactionRegistry transactionRegistry = new TransactionRegistry();
    String legacyBackup = "";
    String backupStatus = "none";
    boolean readOnly;
    boolean futureSchema;
    boolean corrupt;
    volatile long fileBytes;
    boolean accountsIncomplete;
    private boolean migrationSaveRetry;
    private TempWriter writerForTest;
    private final java.util.concurrent.atomic.AtomicLong changeGeneration = new java.util.concurrent.atomic.AtomicLong();
    private DetachedSnapshotWriter snapshotWriter;
    private volatile boolean shuttingDown;
    private boolean preserveFailedTemporary;
    private boolean retainFallbackBackup;
    private volatile long lastSuccessfulGeneration = -1, lastSuccessfulTime;
    private volatile String shutdownFailure = "none";
    long generationForTest() { return changeGeneration.get(); }
    long snapshotGeneration() { return changeGeneration.get(); }
    String snapshotWorkerStatus() { return snapshotWriter == null ? "unavailable" : snapshotWriter.busy() ? "writing" : "idle"; }
    String snapshotLastFailure() { return !shutdownFailure.equals("none") ? shutdownFailure : snapshotWriter == null || snapshotWriter.failure.isEmpty() ? "none" : snapshotWriter.failure; }

    /** Detached snapshot prototype; caller must run this at the server-thread safe point. */
    CompoundTag captureSnapshotForTest(long timeoutMillis) throws InterruptedException {
        SnapshotCapture snapshot = captureSnapshot(timeoutMillis);
        return snapshot == null ? null : snapshot.data();
    }

    private record SnapshotCapture(CompoundTag data, long generation) { }

    private SnapshotCapture captureSnapshot(long timeoutMillis) throws InterruptedException {
        try (var gate = transactionRegistry.tryCapture(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            if (gate == null) return null;
            CompoundTag snapshot = save(new CompoundTag());
            return new SnapshotCapture(snapshot, changeGeneration.get());
        }
    }

    void enableSnapshotWriter(Path file, SnapshotCoordinator coordinator, long lifecycle) {
        if (!readOnly && snapshotWriter == null) snapshotWriter = new DetachedSnapshotWriter(file, coordinator, lifecycle);
    }
    void enableSnapshotWriterForTest(Path file, SnapshotCoordinator coordinator, long lifecycle, FileOperations operations) {
        if (!readOnly && snapshotWriter == null) snapshotWriter = new DetachedSnapshotWriter(file, coordinator, lifecycle, operations);
    }

    void flushSnapshotAndWait(long timeoutMillis) {
        var writer = snapshotWriter;
        if (readOnly || writer == null) throw new IllegalStateException("Digital Storage snapshot writer unavailable");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            if (!writer.await(remainingMillis(deadline)) && writer.busy()) throw new IllegalStateException("Prior snapshot timed out");
            if (!isDirty()) return;
            SnapshotCapture snapshot = captureSnapshot(remainingMillis(deadline));
            if (snapshot == null || !writer.submit(snapshot.data(), snapshot.generation())) throw new IllegalStateException("Snapshot capture unavailable");
            if (!writer.await(remainingMillis(deadline)) || isDirty()) throw new IllegalStateException("Current snapshot failed or timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for snapshot", e);
        }
    }

    private static long remainingMillis(long deadline) {
        return Math.max(0, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
    }

    void closeSnapshotWriter() { if (snapshotWriter != null) snapshotWriter.close(); }

    void shutdownSnapshot(long timeoutMillis) {
        shuttingDown = true;
        transactionRegistry.beginShutdown();
        var writer = snapshotWriter;
        if (writer == null || readOnly) throw new IllegalStateException("Snapshot writer unavailable");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        writer.stopAccepting();
        try {
            // Wait for active transactions and block new ones before accepting a final capture.
            try (var gate = transactionRegistry.tryCapture(remainingMillis(deadline), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (gate == null) throw new IllegalStateException("Active transactions did not finish before shutdown timeout");
                if (!writer.await(remainingMillis(deadline)) && writer.busy()) throw new IllegalStateException("Prior snapshot installation timed out");
                var snapshot = new SnapshotCapture(save(new CompoundTag()), changeGeneration.get());
                if (!writer.submitFinal(snapshot.data(), snapshot.generation())) throw new IllegalStateException("Final snapshot submission rejected");
                if (!writer.await(remainingMillis(deadline)) || isDirty() || lastSuccessfulGeneration != snapshot.generation())
                    throw new IllegalStateException("Final snapshot failed or generation did not match");
            }
        } catch (InterruptedException e) {
            synchronized (this) { super.setDirty(true); }
            shutdownFailure = "Interrupted during shutdown snapshot";
            Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted during shutdown snapshot", e);
        } catch (RuntimeException e) {
            synchronized (this) { super.setDirty(true); }
            shutdownFailure = e.toString();
            throw e;
        } finally { writer.close(); }
    }

    boolean awaitSnapshotWriterStop(long timeoutMillis) throws InterruptedException {
        return snapshotWriter == null || snapshotWriter.awaitTermination(timeoutMillis);
    }
    String lastSuccessfulSnapshot() {
        return lastSuccessfulGeneration < 0 ? "unknown" : lastSuccessfulGeneration + " at " + java.time.Instant.ofEpochMilli(lastSuccessfulTime);
    }
    long lastSuccessfulGenerationForTest() { return lastSuccessfulGeneration; }
    long lastSuccessfulTimeForTest() { return lastSuccessfulTime; }
    long lastSuccessfulGeneration() { return lastSuccessfulGeneration; }
    long lastSuccessfulTime() { return lastSuccessfulTime; }
    boolean snapshotWriterDaemonForTest() { return snapshotWriter != null && snapshotWriter.isDaemon(); }

    /** Installs verified snapshots through one lifecycle-fenced atomic replacement. */
    final class DetachedSnapshotWriter implements AutoCloseable {
        private final Path target;
        private final SnapshotCoordinator coordinator;
        private final long lifecycle;
        private final FileOperations operations;
        private volatile Thread workerThread;
        private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "digitalstorage-snapshot");
            thread.setDaemon(true);
            workerThread = thread;
            return thread;
        });
        private volatile boolean closed;
        private boolean accepting = true;
        private volatile String failure = "";
        private java.util.concurrent.Future<?> inFlight;

        DetachedSnapshotWriter(Path target, SnapshotCoordinator coordinator, long lifecycle) {
            this(target, coordinator, lifecycle, FILES);
        }

        DetachedSnapshotWriter(Path target, SnapshotCoordinator coordinator, long lifecycle, FileOperations operations) {
            this.target = target; this.coordinator = coordinator; this.lifecycle = lifecycle;
            this.operations = java.util.Objects.requireNonNull(operations);
        }

        synchronized boolean submit(CompoundTag detached, long capturedGeneration) {
            if (!accepting || closed || inFlight != null && !inFlight.isDone()) return false;
            inFlight = worker.submit(() -> write(detached, capturedGeneration));
            return true;
        }
        synchronized void stopAccepting() { accepting = false; }
        synchronized boolean submitFinal(CompoundTag detached, long capturedGeneration) {
            if (accepting || closed || inFlight != null && !inFlight.isDone()) return false;
            inFlight = worker.submit(() -> write(detached, capturedGeneration)); return true;
        }

        synchronized boolean busy() { return inFlight != null && !inFlight.isDone(); }

        private void write(CompoundTag detached, long capturedGeneration) {
            final boolean[] locked = {false};
            FileOperations fenced = new FileOperations() {
                public void atomicMove(Path source, Path destination) throws IOException {
                    try {
                        if (!coordinator.tryLock(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("Snapshot installation lock timed out");
                        locked[0] = true;
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Snapshot installation interrupted", e); }
                    if (closed || !coordinator.isCurrent(lifecycle) || changeGeneration.get() != capturedGeneration) {
                        throw new IOException("Stale snapshot was fenced before installation");
                    }
                    operations.atomicMove(source, destination);
                }
                public void replace(Path source, Path destination) throws IOException { operations.replace(source, destination); }
                public void copy(Path source, Path destination) throws IOException { operations.copy(source, destination); }
                public void deleteBackup(Path file) throws IOException { operations.deleteBackup(file); }
            };
            try {
                DigitalStorageData frozen = new DigitalStorageData();
                frozen.preserveFailedTemporary = shuttingDown;
                frozen.retainFallbackBackup = true;
                frozen.setDirty();
                frozen.saveWithWriter(target.toFile(), (tag, path) -> NbtIo.writeCompressed(tag, path.toFile()), fenced, detached);
                if (frozen.isDirty()) throw new IllegalStateException("Detached snapshot installation failed");
                synchronized (DigitalStorageData.this) {
                    fileBytes = frozen.fileBytes;
                    lastSuccessfulGeneration = capturedGeneration;
                    lastSuccessfulTime = System.currentTimeMillis();
                    if (!closed && changeGeneration.get() == capturedGeneration) DigitalStorageData.super.setDirty(false);
                }
                if (shuttingDown) DigitalStorage.LOGGER.info("Digital Storage snapshot installation completed after shutdown began: generation={} time={}", capturedGeneration, java.time.Instant.ofEpochMilli(lastSuccessfulTime));
                failure = "";
            } catch (RuntimeException exception) {
                failure = exception.toString();
                DigitalStorage.LOGGER.error("Detached snapshot worker failed; dirty={}, last successful snapshot={}", isDirty(), lastSuccessfulSnapshot(), exception);
            } finally {
                if (locked[0]) coordinator.unlock();
            }
        }

        synchronized boolean await(long timeoutMillis) throws InterruptedException {
            if (inFlight == null) return true;
            try { inFlight.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS); return failure.isEmpty(); }
            catch (java.util.concurrent.TimeoutException e) { return false; }
            catch (java.util.concurrent.ExecutionException e) { failure = e.toString(); return false; }
        }

        @Override public synchronized void close() { closed = true; worker.shutdown(); }
        boolean awaitTermination(long timeoutMillis) throws InterruptedException {
            return worker.awaitTermination(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        boolean isDaemon() { return workerThread != null && workerThread.isDaemon(); }
    }

    java.util.Optional<StorageVolume> createVolume(UUID owner, String name, int maximum) {
        TransactionId guard = new TransactionId();
        transactionRegistry.enter(guard);
        try {
            if (readOnly || globalBudget.failed() || legacyUnreadableAccounts.containsKey(owner)) return java.util.Optional.empty();
            PlayerStorageAccount account = accounts.get(owner);
            if (maximum > 0 && account != null && account.volumeIds().size() >= maximum) return java.util.Optional.empty();
            UUID id;
            do { id = UUID.randomUUID(); } while (volumes.containsKey(id) || legacyUnreadable.containsKey(id)
                    || references(id));
            StorageVolume volume = StorageVolume.create(id, owner, name, this::setDirty);
            volume.record().storage().attachAccounting(globalBudget, transactionRegistry);
            volume.record().storage().setRollbackCallback(this::invalidateTransaction);
            volume.record().storage().setActivePredicate(() -> !readOnly && !globalBudget.failed() && volumes.get(volume.id()) == volume);
            volumes.put(id, volume);
            accounts.computeIfAbsent(owner, PlayerStorageAccount::new).addVolume(id);
            setDirty();
            return java.util.Optional.of(volume);
        } finally { transactionRegistry.leave(guard); }
    }

    boolean references(UUID id) {
        return accounts.values().stream().anyMatch(account -> account.volumeIds().contains(id));
    }

    DigitalStorageState.VolumeLookup lookup(UUID id) {
        if (readOnly) return new DigitalStorageState.VolumeLookup(DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
        var volume = volumes.get(id);
        if (volume != null) return new DigitalStorageState.VolumeLookup(DigitalStorageState.VolumeStatus.PRESENT, volume);
        if (legacyUnreadable.containsKey(id)) return new DigitalStorageState.VolumeLookup(DigitalStorageState.VolumeStatus.UNREADABLE, null);
        if (references(id)) return new DigitalStorageState.VolumeLookup(DigitalStorageState.VolumeStatus.MISSING, null);
        if (accountsIncomplete) return new DigitalStorageState.VolumeLookup(DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE, null);
        return new DigitalStorageState.VolumeLookup(DigitalStorageState.VolumeStatus.CONFIRMED_DELETED, null);
    }

    boolean deleteEmptyVolume(UUID owner, UUID id) {
        if (readOnly || globalBudget.failed() || accountsIncomplete || !dev.kehai.digitalstorage.security.DigitalStorageMountTracker.mounts(id).isEmpty()) return false;
        var volume = volumes.get(id);
        if (volume == null || !volume.ownerId().equals(owner)) return false;
        var ledger = volume.record().storage();
        TransactionId token;
        try { token = ledger.beginDirectMutation(); }
        catch (IllegalStateException concurrent) { return false; }
        try {
            if (ledger.variantCount() != 0) return false;
            volumes.remove(id);
            var account = accounts.get(owner);
            if (account != null) {
                account.removeVolume(id);
                if (account.volumeIds().isEmpty()) accounts.remove(owner);
            }
            setDirty();
            return true;
        } finally { ledger.endDirectMutation(token); }
    }

    static DigitalStorageData open(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        return open(server.overworld().getDataStorage(), worldRoot);
    }

    static DigitalStorageData open(DimensionDataStorage storage, Path worldRoot) {
        return open(storage, worldRoot, null);
    }

    static DigitalStorageData open(DimensionDataStorage storage, Path worldRoot, TempWriter writerForTest) {
        Path file = worldRoot.resolve("data").resolve(ID + ".dat");
        Path legacy = worldRoot.resolve("digitalstorage");
        try {
            if (!recoverBackupIfNeeded(file)) {
                var unavailable = readOnly(); unavailable.corrupt = true; return unavailable;
            }
        } catch (IOException | RuntimeException failure) {
            DigitalStorage.LOGGER.error("Could not inspect or recover SavedData backups; preserving files and opening read-only", failure);
            var unavailable = readOnly(); unavailable.corrupt = true; return unavailable;
        }
        boolean exists;
        long existingBytes = 0;
        try {
            existingBytes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).size();
            exists = true;
        } catch (java.nio.file.NoSuchFileException missing) {
            exists = false;
        } catch (IOException | SecurityException failure) {
            DigitalStorage.LOGGER.error("Could not inspect Digital Storage SavedData {}; retaining read-only state", file, failure);
            return readOnly();
        }
        if (exists) {
            DigitalStorageData loaded = storage.get(DigitalStorageData::load, ID);
            if (loaded == null) {
                DigitalStorage.LOGGER.error("Digital Storage SavedData {} is unreadable; retaining the original file and read-only state", file);
                var corruptData = readOnly();
                corruptData.corrupt = true;
                corruptData.fileBytes = existingBytes;
                return corruptData;
            }
            loaded.fileBytes = existingBytes;
            if (!loaded.readOnly) loaded.backupStatus = finishLegacyBackup(worldRoot, legacy, loaded.legacyBackup);
            return loaded;
        }
        var data = new DigitalStorageData();
        data.writerForTest = writerForTest;
        try {
            if (Files.notExists(legacy)) {
                storage.set(ID, data);
                return data;
            }
            if (!Files.readAttributes(legacy, java.nio.file.attribute.BasicFileAttributes.class).isDirectory()) {
                throw new IOException("Legacy storage path is not a directory: " + legacy);
            }
            LegacyStorageMigrator.importFiles(legacy, data);
            data.volumes.values().forEach(volume -> {
                volume.record().storage().attachAccounting(data.globalBudget, data.transactionRegistry);
                volume.record().storage().setRollbackCallback(data::invalidateTransaction);
                volume.record().storage().setActivePredicate(() -> !data.readOnly && !data.globalBudget.failed() && data.volumes.get(volume.id()) == volume);
            });
            String suffix = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                    .format(java.time.LocalDateTime.now());
            Path backup = worldRoot.resolve("digitalstorage-legacy-" + suffix);
            for (int index = 1; Files.exists(backup); index++) {
                backup = worldRoot.resolve("digitalstorage-legacy-" + suffix + "-" + index);
            }
            // Record the planned path before the first commit so a crash after rename cannot lose it.
            data.legacyBackup = backup.getFileName().toString();
            data.setDirty();
            storage.set(ID, data);
            storage.save();
            if (data.isDirty()) throw new IOException("Initial SavedData write failed");
            CompoundTag written = NbtIo.readCompressed(file.toFile());
            CompoundTag writtenData = written.getCompound("data");
            DigitalStorageData verified = load(writtenData);
            if (!writtenData.equals(data.save(new CompoundTag())) || verified.readOnly
                    || !verified.legacyBackup.equals(data.legacyBackup)
                    || verified.accounts.size() != data.accounts.size() || verified.volumes.size() != data.volumes.size()) {
                throw new IOException("Migrated SavedData verification failed");
            }
            data.backupStatus = finishLegacyBackup(worldRoot, legacy, data.legacyBackup);
            if (!data.backupStatus.equals("completed")) data.setDirty();
            return data;
        } catch (IOException | RuntimeException failure) {
            data.migrationSaveRetry = data.isDirty();
            data.readOnly = true;
            DigitalStorage.LOGGER.error("Digital Storage migration could not finish; retained legacy data and stopped writes", failure);
            return data;
        }
    }

    private static String finishLegacyBackup(Path worldRoot, Path legacy, String backupName) {
        if (backupName.isEmpty() || !backupName.startsWith("digitalstorage-legacy-")
                || backupName.indexOf('/') >= 0 || backupName.indexOf('\\') >= 0 || backupName.indexOf(':') >= 0) {
            if (Files.exists(legacy)) DigitalStorage.LOGGER.warn("Legacy Digital Storage backup pending: no valid planned backup name");
            return Files.exists(legacy) ? "pending" : "none";
        }
        Path backup = worldRoot.resolve(backupName);
        if (Files.notExists(legacy)) return Files.isDirectory(backup) ? "completed" : "pending";
        if (!Files.exists(legacy)) return "pending";
        try {
            if (!Files.isDirectory(legacy) || Files.exists(backup) && !Files.isDirectory(backup)) return "conflict";
            // Only these directories belong to the pre-SavedData format. Other owners,
            // notably Forge recovery, may be actively using siblings in this directory.
            var names = java.util.List.of("accounts", "volumes", "deleted-volumes");
            for (String name : names) {
                if (Files.exists(legacy.resolve(name)) && Files.exists(backup.resolve(name))) {
                    DigitalStorage.LOGGER.error("Legacy Digital Storage backup conflict for {}; preserving both copies", name);
                    return "conflict";
                }
            }
            Files.createDirectories(backup);
            for (String name : names) {
                var source = legacy.resolve(name);
                if (Files.exists(source)) Files.move(source, backup.resolve(name));
            }
            try (var remaining = Files.list(legacy)) {
                var retained = remaining.toList();
                if (retained.isEmpty()) Files.delete(legacy);
                else DigitalStorage.LOGGER.info("Legacy backup preserved non-legacy entries in {}: {}", legacy,
                        retained.stream().map(path -> path.getFileName().toString()).toList());
            }
            DigitalStorage.LOGGER.info("Legacy Digital Storage backup completed: {}", backupName);
            return "completed";
        } catch (IOException | SecurityException failure) {
            DigitalStorage.LOGGER.error("Legacy Digital Storage backup pending; preserving original directory", failure);
            return "pending";
        }
    }

    static DigitalStorageData readOnly() {
        var data = new DigitalStorageData();
        data.readOnly = true;
        return data;
    }

    public static DigitalStorageData load(CompoundTag tag) {
        if (!tag.contains("SchemaVersion", Tag.TAG_INT)) throw new IllegalArgumentException("SavedData schema is missing");
        int schema = tag.getInt("SchemaVersion");
        var data = new DigitalStorageData();
        if (schema > SCHEMA_VERSION) {
            data.futureSchema = true;
            data.readOnly = true;
            DigitalStorage.LOGGER.error("Digital Storage SavedData schema {} is newer than supported {}; upgrade the mod", schema, SCHEMA_VERSION);
            return data;
        }
        ListTag accounts = checkedList(tag, "Accounts");
        ListTag volumes = checkedList(tag, "Volumes");
        ListTag unreadable = tag.contains("LegacyUnreadable") ? checkedList(tag, "LegacyUnreadable") : new ListTag();
        ListTag unreadableAccounts = tag.contains("LegacyUnreadableAccounts") ? checkedList(tag, "LegacyUnreadableAccounts") : new ListTag();
        for (int i = 0; i < accounts.size(); i++) {
            var account = PlayerStorageAccount.fromNbt(accounts.getCompound(i));
            if (data.accounts.putIfAbsent(account.ownerId(), account) != null) throw new IllegalArgumentException("Duplicate account");
        }
        for (int i = 0; i < volumes.size(); i++) {
            var volume = StorageVolume.fromNbt(volumes.getCompound(i), data::setDirty);
            if (data.volumes.putIfAbsent(volume.id(), volume) != null) throw new IllegalArgumentException("Duplicate volume");
            volume.record().storage().attachAccounting(data.globalBudget, data.transactionRegistry);
            volume.record().storage().setRollbackCallback(data::invalidateTransaction);
            volume.record().storage().setActivePredicate(() -> !data.readOnly && !data.globalBudget.failed() && data.volumes.get(volume.id()) == volume);
        }
        for (int i = 0; i < unreadable.size(); i++) {
            var entry = unreadable.getCompound(i);
            if (!entry.hasUUID("Id") || !entry.contains("Reason", Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid legacy unreadable record");
            data.legacyUnreadable.put(entry.getUUID("Id"), entry.getString("Reason"));
        }
        for (int i = 0; i < unreadableAccounts.size(); i++) {
            var entry = unreadableAccounts.getCompound(i);
            if (!entry.hasUUID("Id") || !entry.contains("Reason", Tag.TAG_STRING)) throw new IllegalArgumentException("Invalid legacy unreadable account record");
            data.legacyUnreadableAccounts.put(entry.getUUID("Id"), entry.getString("Reason"));
        }
        data.legacyBackup = tag.getString("LegacyBackup");
        data.accountsIncomplete = tag.getBoolean("AccountsIncomplete");
        if (schema < SCHEMA_VERSION) data.setDirty();
        return data;
    }

    private static ListTag checkedList(CompoundTag tag, String key) {
        if (!tag.contains(key)) throw new IllegalArgumentException("Missing SavedData list " + key);
        if (!(tag.get(key) instanceof ListTag list) || (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid SavedData list " + key);
        }
        return (ListTag) tag.get(key);
    }

    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putString("LegacyBackup", legacyBackup);
        tag.putBoolean("AccountsIncomplete", accountsIncomplete);
        var savedAccounts = new ListTag();
        accounts.values().forEach(account -> savedAccounts.add(account.snapshot().writeNbt()));
        tag.put("Accounts", savedAccounts);
        var savedVolumes = new ListTag();
        volumes.values().forEach(volume -> savedVolumes.add(volume.snapshot().writeNbt()));
        tag.put("Volumes", savedVolumes);
        var unreadable = new ListTag();
        legacyUnreadable.forEach((id, reason) -> {
            var entry = new CompoundTag();
            entry.putUUID("Id", id);
            entry.putString("Reason", reason);
            unreadable.add(entry);
        });
        tag.put("LegacyUnreadable", unreadable);
        var unreadableAccounts = new ListTag();
        legacyUnreadableAccounts.forEach((id, reason) -> {
            var entry = new CompoundTag();
            entry.putUUID("Id", id);
            entry.putString("Reason", reason);
            unreadableAccounts.add(entry);
        });
        tag.put("LegacyUnreadableAccounts", unreadableAccounts);
        return tag;
    }

    @Override public void setDirty() { setDirty(true); }
    @Override public synchronized void setDirty(boolean dirty) {
        if (!readOnly) {
            if (dirty && transactionRegistry.markGeneration(TransactionId.callbackContext())) changeGeneration.incrementAndGet();
            super.setDirty(dirty);
        }
    }

    private void invalidateTransaction(TransactionId transaction) {
        if (transactionRegistry.markGeneration(transaction)) changeGeneration.incrementAndGet();
    }

    @Override public synchronized boolean isDirty() { return super.isDirty(); }

    @Override public void save(File file) {
        if (snapshotWriter != null) {
            if (readOnly || shuttingDown || !isDirty() || snapshotWriter.busy()) return;
            try {
                SnapshotCapture snapshot = captureSnapshot(20);
                if (snapshot != null) snapshotWriter.submit(snapshot.data(), snapshot.generation());
                else DigitalStorage.LOGGER.warn("Digital Storage snapshot capture timed out while transactions remained active; changes remain dirty");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                DigitalStorage.LOGGER.error("Interrupted capturing Digital Storage snapshot", e);
            }
            return;
        }
        saveWithWriter(file, writerForTest != null ? writerForTest : (tag, path) -> NbtIo.writeCompressed(tag, path.toFile()));
    }

    @FunctionalInterface
    interface TempWriter { void write(CompoundTag tag, Path path) throws IOException; }

    interface FileOperations {
        void atomicMove(Path source, Path target) throws IOException;
        void replace(Path source, Path target) throws IOException;
        void copy(Path source, Path target) throws IOException;
        default void deleteBackup(Path file) throws IOException { Files.deleteIfExists(file); }
    }

    private static final FileOperations FILES = new FileOperations() {
        public void atomicMove(Path source, Path target) throws IOException { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        public void replace(Path source, Path target) throws IOException { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
        public void copy(Path source, Path target) throws IOException { Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING); }
    };

    void saveWithWriter(File file, TempWriter writer) {
        saveWithWriter(file, writer, FILES);
    }

    void saveWithWriter(File file, TempWriter writer, FileOperations operations) {
        saveWithWriter(file, writer, operations, null);
    }

    private void saveWithWriter(File file, TempWriter writer, FileOperations operations, CompoundTag detached) {
        if ((readOnly && !migrationSaveRetry) || !isDirty()) return;
        Path target = file.toPath();
        Path temporary = null;
        Path backup = null;
        boolean preserveTemporary = false;
        try {
            CompoundTag root = new CompoundTag();
            CompoundTag payload = detached == null ? save(new CompoundTag()) : detached;
            root.put("data", payload);
            NbtUtils.addCurrentDataVersion(root);
            Files.createDirectories(target.toAbsolutePath().getParent());
            temporary = Files.createTempFile(target.toAbsolutePath().getParent(), "digitalstorage-", ".tmp");
            writer.write(root, temporary);
            if (!root.equals(NbtIo.readCompressed(temporary.toFile()))) throw new IOException("SavedData verification failed");
            DigitalStorageData verified = load(root.getCompound("data"));
            if (verified.readOnly || verified.accounts.size() != payload.getList("Accounts", Tag.TAG_COMPOUND).size()
                    || verified.volumes.size() != payload.getList("Volumes", Tag.TAG_COMPOUND).size()
                    || !verified.legacyBackup.equals(payload.getString("LegacyBackup"))) {
                throw new IOException("SavedData semantic verification failed");
            }
            long savedBytes = Files.size(temporary);
            try {
                operations.atomicMove(temporary, target);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                DigitalStorage.LOGGER.warn("Atomic move unavailable for {}; using verified backup and non-atomic replacement", target);
                CompoundTag original = null;
                if (Files.exists(target)) {
                    original = verifiedBackup(target);
                    long timestamp = BACKUP_CLOCK.updateAndGet(last -> Math.max(System.currentTimeMillis(), last + 1));
                    String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(timestamp));
                    backup = Files.createTempFile(target.toAbsolutePath().getParent(), "digitalstorage.dat.bak-" + stamp + "-", "");
                    try {
                        operations.copy(target, backup);
                        if (!original.equals(verifiedBackup(backup))) throw new IOException("SavedData backup verification failed");
                    } catch (IOException | RuntimeException failure) {
                        Files.deleteIfExists(backup); backup = null; throw failure;
                    }
                }
                Path replacement = Files.createTempFile(target.toAbsolutePath().getParent(), "digitalstorage-replacement-", ".tmp");
                try {
                    Files.copy(temporary, replacement, StandardCopyOption.REPLACE_EXISTING);
                    if (!root.equals(verifiedFile(replacement))) throw new IOException("Replacement staging verification failed");
                    operations.replace(replacement, target);
                    if (!root.equals(verifiedFile(target))) throw new IOException("Non-atomic replacement verification failed");
                } catch (IOException | RuntimeException replacementFailure) {
                    if (backup != null) {
                        try {
                            operations.copy(backup, target);
                            if (!original.equals(verifiedFile(target))) throw new IOException("Restored backup verification failed");
                            if (!retainFallbackBackup) Files.deleteIfExists(backup);
                            backup = null;
                        } catch (IOException | RuntimeException restoreFailure) {
                            preserveTemporary = true;
                            replacementFailure.addSuppressed(restoreFailure);
                            DigitalStorage.LOGGER.error("SavedData restoration failed; retaining backup {} and temporary file {}", backup, temporary, restoreFailure);
                        }
                    } else preserveTemporary = true;
                    throw replacementFailure;
                } finally {
                    if (!preserveTemporary) Files.deleteIfExists(replacement);
                }
                if (backup != null) {
                    try { if (!retainFallbackBackup) Files.deleteIfExists(backup); }
                    catch (IOException | SecurityException cleanup) { DigitalStorage.LOGGER.warn("Could not remove SavedData backup {}", backup, cleanup); }
                    backup = null;
                }
                if (retainFallbackBackup) {
                    try {
                        var backups = backupFiles(target);
                        for (int i = 2; i < backups.size(); i++) operations.deleteBackup(backups.get(i));
                    } catch (IOException | RuntimeException cleanup) { DigitalStorage.LOGGER.warn("Could not prune SavedData backups after successful installation", cleanup); }
                }
                Files.deleteIfExists(temporary);
            }
            temporary = null;
            fileBytes = savedBytes;
            super.setDirty(false);
            migrationSaveRetry = false;
        } catch (IOException | RuntimeException failure) {
            if (preserveFailedTemporary) preserveTemporary = true;
            DigitalStorage.LOGGER.error("Could not safely save Digital Storage SavedData {}; changes remain dirty", target, failure);
        } finally {
            if (temporary != null && !preserveTemporary) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | RuntimeException cleanup) { DigitalStorage.LOGGER.warn("Could not remove incomplete Digital Storage temporary file {}", temporary, cleanup); }
            }
        }
    }

    private static java.util.List<Path> backupFiles(Path file) throws IOException {
        Path directory = file.toAbsolutePath().getParent();
        if (Files.notExists(directory)) return java.util.List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("digitalstorage.dat.bak-"))
                    .sorted(java.util.Comparator.reverseOrder()).toList();
        }
    }

    private static CompoundTag verifiedFile(Path file) throws IOException {
        CompoundTag root = NbtIo.readCompressed(file.toFile());
        if (!root.contains("data", Tag.TAG_COMPOUND)) throw new IOException("SavedData payload missing");
        CompoundTag payload = root.getCompound("data");
        if (!payload.contains("SchemaVersion", Tag.TAG_INT)) throw new IOException("SavedData schema missing");
        if (payload.getInt("SchemaVersion") <= SCHEMA_VERSION) {
            DigitalStorageData parsed = load(payload);
            if (parsed.readOnly || parsed.accounts.size() != payload.getList("Accounts", Tag.TAG_COMPOUND).size()
                    || parsed.volumes.size() != payload.getList("Volumes", Tag.TAG_COMPOUND).size()) {
                throw new IOException("SavedData account or volume validation failed");
            }
        }
        return root;
    }

    private static boolean recoverBackupIfNeeded(Path file) throws IOException {
        var backups = backupFiles(file);
        if (backups.isEmpty()) return true;
        if (Files.exists(file)) {
            try { verifiedFile(file); return true; }
            catch (IOException | RuntimeException failure) { DigitalStorage.LOGGER.warn("SavedData target is unreadable; trying retained backup", failure); }
        }
        for (Path backup : backups) {
            try {
                CompoundTag expected = verifiedBackup(backup);
                Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
                if (!expected.equals(verifiedFile(file))) throw new IOException("Restored SavedData differs from backup");
                DigitalStorage.LOGGER.info("Restored Digital Storage SavedData from retained backup {}", backup);
                return true;
            } catch (IOException | RuntimeException failure) {
                DigitalStorage.LOGGER.warn("SavedData backup recovery failed; preserving backup {}", backup, failure);
            }
        }
        return false;
    }

    private static CompoundTag verifiedBackup(Path file) throws IOException {
        CompoundTag root = verifiedFile(file);
        if (root.getCompound("data").getInt("SchemaVersion") > SCHEMA_VERSION) {
            throw new IOException("Cannot verify backup created by a newer SavedData schema");
        }
        return root;
    }

}
