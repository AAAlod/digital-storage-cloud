package dev.kehai.digitalstorage.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.Items;

/** Behavioral regressions for single-file persistence. */
public final class StoragePersistenceSelfTest {
    private StoragePersistenceSelfTest() { }

    public static void run() {
        DigitalStorageDataSelfTest.run();
        savedDataFileSafetyRoundTrip();
        corruptExistingFileIsNeverReplaced();
        nonAtomicReplacementRecovery();
        LegacyStorageMigrationSelfTest.run();
        savedDataRoundTripSurvivesReopen();
        failedSaveKeepsDirtyAndPreservesTarget();
        corruptSavedDataIsReadOnlyAndNotOverwritten();
        futureSchemaIsReadOnlyAndNotOverwritten();
        accessorBindingSurvivesUnavailableVolume();
        legacyUnreadableBindingSurvives();
        flushNowFailsWhenSaveCannotComplete();
        deleteVolumePersistsWithoutMarker();
        overBudgetVolumeLoadsAndRejectsNewVariants();
        configuredLimitAndExistingOverflow();
        closeIsIdempotent();
        DetachedSnapshotWriterSelfTest.run();
        degradedStateReloadsAfterLock();
        degradedCorruptDataRemainsReadOnly();
    }

    private static void degradedCorruptDataRemainsReadOnly() {
        Path root = temporary("degraded-corrupt"); var release = new java.util.concurrent.CountDownLatch(1);
        Thread worker = null;
        try {
            var directory = Files.createDirectories(root.resolve("data"));
            var target = directory.resolve("digitalstorage.dat"); var backup = directory.resolve("digitalstorage.dat.bak-fixture");
            byte[] targetBytes = {1, 2, 3}; byte[] backupBytes = {4, 5, 6};
            Files.write(target, targetBytes); Files.write(backup, backupBytes);
            var coordinator = SnapshotCoordinator.forRoot(root); var held = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            worker = new Thread(() -> {
                try {
                    if (!coordinator.tryLock(1, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("lock");
                    try { held.countDown(); if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                    finally { coordinator.unlock(); }
                } catch (Throwable e) { failure.set(e); }
            }, "dsc-degraded-corrupt-test");
            worker.start(); expect(held.await(3, java.util.concurrent.TimeUnit.SECONDS), "corrupt fixture lock");
            var state = DigitalStorageState.openForTest(root);
            expect(state.savedDataMode().equals("degraded-read-only"), "corrupt fixture did not degrade");
            release.countDown(); worker.join(5000);
            expect(!worker.isAlive() && failure.get() == null, "corrupt fixture worker failed");
            state.recoverIfDegraded();
            expect(state.savedDataMode().equals("corrupt") && state.createVolume(UUID.randomUUID(), "denied", 0).isEmpty()
                    && Arrays.equals(targetBytes, Files.readAllBytes(target)) && Arrays.equals(backupBytes, Files.readAllBytes(backup)), "corrupt degraded reload changed files");
            state.closeForTest();
        } catch (Exception e) { throw new IllegalStateException("degraded corrupt test failed", e); }
        finally {
            release.countDown(); if (worker != null) try { worker.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cleanup(root);
        }
    }

    private static void degradedStateReloadsAfterLock() {
        Path root = temporary("degraded-reload");
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread worker = null;
        try {
            UUID owner = UUID.randomUUID();
            var seed = DigitalStorageState.openForTest(root);
            var volume = seed.createVolume(owner, "saved", 0).orElseThrow();
            var stone = ItemKey.of(Items.STONE);
            try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(stone, 7, tx) == 7, "degraded seed"); tx.commit(); }
            seed.flushNow(); seed.closeForTest();
            var file = root.resolve("data").resolve("digitalstorage.dat"); byte[] before = Files.readAllBytes(file);
            var coordinator = SnapshotCoordinator.forRoot(root);
            var held = new java.util.concurrent.CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            worker = new Thread(() -> {
                try {
                    if (!coordinator.tryLock(1, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("old writer lock");
                    try { held.countDown(); if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("old writer timeout"); }
                    finally { coordinator.unlock(); }
                } catch (Throwable e) { failure.set(e); }
            }, "dsc-degraded-test");
            worker.start(); expect(held.await(3, java.util.concurrent.TimeUnit.SECONDS), "old writer did not hold lock");
            var degraded = DigitalStorageState.openForTest(root);
            expect(degraded.savedDataMode().equals("degraded-read-only") && degraded.volume(volume.id()).isEmpty()
                    && degraded.createVolume(owner, "blocked", 0).isEmpty() && Arrays.equals(before, Files.readAllBytes(file)), "degraded state accessed storage");
            degraded.recoverIfDegraded();
            expect(degraded.savedDataMode().equals("degraded-read-only"), "degraded state recovered while lock held");
            release.countDown(); worker.join(5000);
            expect(!worker.isAlive() && failure.get() == null, "old writer failed");
            degraded.recoverIfDegraded();
            expect(degraded.savedDataMode().equals("read-write")
                    && degraded.volume(volume.id()).orElseThrow().record().storage().amountOf(stone) == 7, "degraded reload lost data");
            degraded.closeForTest();
        } catch (Exception e) { throw new IllegalStateException("degraded lifecycle test failed", e); }
        finally {
            release.countDown();
            if (worker != null) try { worker.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            cleanup(root);
        }
    }

    private static void savedDataFileSafetyRoundTrip() {
        try {
            var root = java.nio.file.Files.createTempDirectory("dsc-saveddata-");
            try {
                var data = new DigitalStorageData();
                var owner = java.util.UUID.randomUUID();
                var id = java.util.UUID.randomUUID();
                var volume = StorageVolume.create(id, owner, "SavedData", data::setDirty);
                volume.record().storage().load(ItemKey.of(Items.STONE), 7);
                var unknown = new CompoundTag();
                unknown.putString("item", "missingmod:lost_item");
                volume.record().storage().loadOpaque(unknown, 9);
                var account = new PlayerStorageAccount(owner);
                account.addVolume(id);
                data.accounts.put(owner, account);
                data.volumes.put(id, volume);
                data.setDirty();
                var file = root.resolve("digitalstorage.dat");
                data.save(file.toFile());
                expect(!data.isDirty() && java.nio.file.Files.isRegularFile(file), "SavedData file save failed");
                var loaded = DigitalStorageData.load(net.minecraft.nbt.NbtIo.readCompressed(file.toFile()).getCompound("data"));
                expect(loaded.volumes.get(id).record().storage().totalItemCount() == 16
                        && loaded.volumes.get(id).record().storage().opaqueVariantCount() == 1
                        && loaded.accounts.get(owner).volumeIds().contains(id), "SavedData round trip lost records");
                var previous = java.nio.file.Files.readAllBytes(file);
                data.setDirty();
                data.saveWithWriter(file.toFile(), (tag, path) -> {
                    java.nio.file.Files.write(path, new byte[] {1, 2, 3});
                    throw new java.io.IOException("Injected partial write");
                });
                expect(data.isDirty() && java.util.Arrays.equals(previous, java.nio.file.Files.readAllBytes(file)), "Partial write damaged the previous file");
                data.saveWithWriter(file.toFile(), (tag, path) -> net.minecraft.nbt.NbtIo.writeCompressed(new CompoundTag(), path.toFile()));
                expect(data.isDirty() && java.util.Arrays.equals(previous, java.nio.file.Files.readAllBytes(file)), "Invalid temporary data replaced the previous file");
                var blocked = root.resolve("blocked");
                java.nio.file.Files.createDirectory(blocked);
                java.nio.file.Files.writeString(blocked.resolve("keep"), "unchanged");
                data.setDirty();
                data.save(blocked.toFile());
                expect(data.isDirty() && java.nio.file.Files.readString(blocked.resolve("keep")).equals("unchanged")
                        && java.util.Arrays.equals(previous, java.nio.file.Files.readAllBytes(file)), "Failed save did not preserve data and dirty flag");
                try (var files = java.nio.file.Files.list(root)) {
                    expect(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "Failed save left a temporary file");
                }
                data.save(file.toFile());
                expect(!data.isDirty(), "SavedData retry did not clear dirty");
                previous = java.nio.file.Files.readAllBytes(file);

            } finally {
                try (var paths = java.nio.file.Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void corruptExistingFileIsNeverReplaced() {
        try {
            var root = java.nio.file.Files.createTempDirectory("dsc-corrupt-saveddata-");
            try {
                var directory = java.nio.file.Files.createDirectory(root.resolve("data"));
                var file = directory.resolve("digitalstorage.dat");
                byte[] corrupt = {1, 2, 3, 4};
                java.nio.file.Files.write(file, corrupt);
                var storage = new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                var data = DigitalStorageData.open(storage, root);
                data.setDirty();
                storage.save();
                expect(data.readOnly && !data.isDirty() && storage.get(DigitalStorageData::load, DigitalStorageData.ID) == null
                        && java.util.Arrays.equals(corrupt, java.nio.file.Files.readAllBytes(file)), "Corrupt SavedData was registered or overwritten");
                var repaired = new DigitalStorageData();
                repaired.setDirty();
                repaired.save(file.toFile());
                var reopened = new net.minecraft.world.level.storage.DimensionDataStorage(directory.toFile(), net.minecraft.util.datafix.DataFixers.getDataFixer());
                expect(!DigitalStorageData.open(reopened, root).readOnly, "Repaired SavedData was not available after reopening");
            } finally {
                try (var paths = java.nio.file.Files.walk(root)) {
                    for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }





    private static void savedDataRoundTripSurvivesReopen() {
        Path root = temporary("roundtrip");
        UUID owner = UUID.randomUUID();
        try {
            var state = DigitalStorageState.openForTest(root);
            var volume = state.createVolume(owner, "round trip", 1).orElseThrow();
            var key = ItemKey.of(Items.STONE);
            volume.record().storage().load(key, 7);
            var opaque = new CompoundTag(); opaque.putString("item", "missingmod:unavailable");
            volume.record().storage().loadOpaque(opaque, 9);
            long budgetBytes = volume.record().storage().totalVariantNbtBytes();
            expect(state.totalVariantNbtBytes() == budgetBytes, "loaded bytes did not update root budget");
            state.flushNow();
            state.closeForTest();
            var reopened = DigitalStorageState.openForTest(root);
            try {
                var ledger = reopened.volume(volume.id()).orElseThrow().record().storage();
                expect(ledger.amountOf(key) == 7 && ledger.totalItemCount() == 16 && ledger.opaqueVariantCount() == 1,
                        "reopen: expected 7 stone and 9 opaque, got total=" + ledger.totalItemCount());
                expect(reopened.totalVariantNbtBytes() == budgetBytes, "reopen: global budget lost bytes");
                expect(reopened.ownsVolume(owner, volume.id()), "reopen: account lost volume");
            } finally { reopened.closeForTest(); }
        } finally { cleanup(root); }
    }

    private static void nonAtomicReplacementRecovery() {
        for (int mode = 0; mode < 6; mode++) {
            Path root = temporary("non-atomic-" + mode);
            try {
                Path directory = Files.createDirectories(root.resolve("data"));
                Path target = directory.resolve("digitalstorage.dat");
                var data = new DigitalStorageData(); data.setDirty(); data.save(target.toFile());
                byte[] original = Files.readAllBytes(target);
                data.createVolume(UUID.randomUUID(), "pending", 0).orElseThrow();
                final int scenario = mode;
                var operations = new DigitalStorageData.FileOperations() {
                    private int copies;
                    public void atomicMove(Path source, Path destination) throws IOException {
                        if (scenario == 5) throw new IOException("injected ordinary atomic move failure");
                        throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected");
                    }
                    public void replace(Path source, Path destination) throws IOException {
                        if (scenario == 1 || scenario == 2) {
                            Files.write(destination, new byte[] {1, 2, 3});
                            throw new IOException("injected replacement failure");
                        }
                        Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    public void copy(Path source, Path destination) throws IOException {
                        copies++;
                        if ((scenario == 2 && copies == 2) || (scenario == 3 && copies == 1)) throw new IOException("injected copy failure");
                        if (scenario == 4 && copies == 1) { Files.write(destination, new byte[] {1}); return; }
                        Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                };
                data.saveWithWriter(target.toFile(), (tag, file) -> NbtIo.writeCompressed(tag, file.toFile()), operations);
                try (var paths = Files.list(directory)) {
                    var names = paths.map(path -> path.getFileName().toString()).toList();
                    long backups = names.stream().filter(name -> name.startsWith("digitalstorage.dat.bak-")).count();
                    long temporaries = names.stream().filter(name -> name.endsWith(".tmp")).count();
                    if (mode == 0) {
                        expect(!data.isDirty() && backups == 0 && temporaries == 0
                                && !Arrays.equals(original, Files.readAllBytes(target)), "non-atomic success: expected new target and no backup");
                    } else if (mode == 2) {
                        expect(data.isDirty() && backups == 1 && temporaries >= 1, "restore failure: expected dirty, backup, and temporary file");
                    } else {
                        expect(data.isDirty() && backups == 0 && temporaries == 0 && Arrays.equals(original, Files.readAllBytes(target)),
                                "non-atomic failure " + mode + ": expected original bytes and dirty state");
                    }
                }
                if (mode == 2) {
                    var reopened = DigitalStorageState.openForTest(root);
                    try { expect(reopened.volumeCount() == 0 && reopened.savedDataMode().equals("read-write")
                            && Arrays.equals(original, Files.readAllBytes(target)), "startup did not recover the valid backup"); }
                    finally { reopened.closeForTest(); }
                    try (var paths = Files.list(directory)) {
                        expect(paths.noneMatch(path -> path.getFileName().toString().startsWith("digitalstorage.dat.bak-")), "startup retained a recovered backup");
                    }
                } else if (mode == 0) {
                    data.createVolume(UUID.randomUUID(), "second", 0).orElseThrow();
                    data.saveWithWriter(target.toFile(), (tag, file) -> NbtIo.writeCompressed(tag, file.toFile()), operations);
                    try (var paths = Files.list(directory)) {
                        expect(!data.isDirty() && paths.noneMatch(path -> path.getFileName().toString().startsWith("digitalstorage.dat.bak-")),
                                "repeated non-atomic saves accumulated backups");
                    }
                } else {
                    data.save(target.toFile());
                    expect(!data.isDirty(), "non-atomic failure retry did not succeed: " + mode);
                }
            } catch (IOException failure) { throw new IllegalStateException(failure); }
            finally { cleanup(root); }
        }
        startupRecoversBackupBeforeReplacement();
        nonAtomicFirstSave();
    }

    private static void nonAtomicFirstSave() {
        Path root = temporary("non-atomic-first");
        try {
            Path target = root.resolve("digitalstorage.dat");
            var data = new DigitalStorageData(); data.setDirty();
            data.saveWithWriter(target.toFile(), (tag, temp) -> NbtIo.writeCompressed(tag, temp.toFile()), new DigitalStorageData.FileOperations() {
                public void atomicMove(Path source, Path destination) throws IOException {
                    throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected");
                }
                public void replace(Path source, Path destination) throws IOException { Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                public void copy(Path source, Path destination) { throw new IllegalStateException("first save must not create backup"); }
            });
            expect(!data.isDirty() && Files.isRegularFile(target), "first non-atomic save failed");
            try (var paths = Files.list(root)) {
                expect(paths.noneMatch(path -> path.getFileName().toString().startsWith("digitalstorage.dat.bak-")), "first save created backup");
            }
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { cleanup(root); }
    }

    private static void startupRecoversBackupBeforeReplacement() {
        Path root = temporary("backup-crash");
        try {
            var directory = Files.createDirectories(root.resolve("data")); var target = directory.resolve("digitalstorage.dat");
            var data = new DigitalStorageData(); data.setDirty(); data.save(target.toFile());
            byte[] original = Files.readAllBytes(target);
            var backup = directory.resolve("digitalstorage.dat.bak-20261106-fixture");
            Files.copy(target, backup); Files.delete(target);
            var state = DigitalStorageState.openForTest(root);
            try { expect(state.savedDataMode().equals("read-write") && Arrays.equals(original, Files.readAllBytes(target)),
                    "backup-before-replacement crash: target was not recovered"); }
            finally { state.closeForTest(); }
            expect(Files.notExists(backup), "backup-before-replacement crash: backup was not cleaned");
            Files.copy(target, backup);
            var valid = DigitalStorageState.openForTest(root);
            expect(valid.savedDataMode().equals("read-write") && Files.notExists(backup), "valid target did not discard stale backup");
            valid.closeForTest();
            byte[] badTarget = {1, 2, 3}; byte[] badBackup = {4, 5, 6};
            Files.write(target, badTarget); Files.write(backup, badBackup);
            var unavailable = DigitalStorageState.openForTest(root);
            expect(unavailable.savedDataMode().equals("corrupt") && Arrays.equals(badTarget, Files.readAllBytes(target))
                    && Arrays.equals(badBackup, Files.readAllBytes(backup)), "invalid backup did not preserve files read-only");
            unavailable.closeForTest();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { cleanup(root); }
    }

    private static void failedSaveKeepsDirtyAndPreservesTarget() {
        Path root = temporary("save-failure");
        try {
            var file = root.resolve("digitalstorage.dat");
            var data = new DigitalStorageData(); data.setDirty(); data.save(file.toFile());
            byte[] previous = Files.readAllBytes(file); data.setDirty();
            data.saveWithWriter(file.toFile(), (tag, temp) -> { Files.write(temp, new byte[] {1, 2}); throw new IOException("injected partial write"); });
            expect(data.isDirty() && Arrays.equals(previous, Files.readAllBytes(file)), "failed save: dirty cleared or previous target changed");
            try (var paths = Files.list(root)) {
                expect(paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "failed save: temporary file remains");
            }
            data.save(file.toFile());
            expect(!data.isDirty(), "save retry: expected dirty=false");
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { cleanup(root); }
    }

    private static void corruptSavedDataIsReadOnlyAndNotOverwritten() {
        Path root = temporary("corrupt");
        try {
            Path file = Files.createDirectories(root.resolve("data")).resolve("digitalstorage.dat");
            byte[] broken = {1, 2, 3}; Files.write(file, broken);
            var state = DigitalStorageState.openForTest(root);
            expect(state.savedDataMode().equals("corrupt") && state.createVolume(UUID.randomUUID(), "denied", 0).isEmpty(), "corrupt file: expected read-only corrupt mode");
            state.closeForTest();
            expect(Arrays.equals(broken, Files.readAllBytes(file)), "corrupt file: bytes changed");
            var repaired = new DigitalStorageData(); repaired.setDirty(); repaired.save(file.toFile());
            var reopened = DigitalStorageState.openForTest(root);
            try { expect(reopened.savedDataMode().equals("read-write"), "repaired file: expected read-write mode"); }
            finally { reopened.closeForTest(); }
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { cleanup(root); }
    }

    private static void futureSchemaIsReadOnlyAndNotOverwritten() {
        Path root = temporary("future");
        try {
            Path file = Files.createDirectories(root.resolve("data")).resolve("digitalstorage.dat");
            var payload = new CompoundTag(); payload.putInt("SchemaVersion", DigitalStorageData.SCHEMA_VERSION + 1);
            var envelope = new CompoundTag(); envelope.put("data", payload); NbtIo.writeCompressed(envelope, file.toFile());
            byte[] previous = Files.readAllBytes(file);
            var state = DigitalStorageState.openForTest(root);
            expect(state.savedDataMode().equals("future-schema") && state.createVolume(UUID.randomUUID(), "denied", 0).isEmpty(), "future schema: expected read-only mode");
            state.closeForTest();
            expect(Arrays.equals(previous, Files.readAllBytes(file)), "future schema: target changed");
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { cleanup(root); }
    }

    private static void flushNowFailsWhenSaveCannotComplete() {
        Path root = temporary("flush-failure");
        try {
            var state = DigitalStorageState.openForTest(root);
            var owner = UUID.randomUUID(); var volume = state.createVolume(owner, "flush", 1).orElseThrow();
            state.flushNow();
            var target = root.resolve("data/digitalstorage.dat"); var saved = root.resolve("saved.dat");
            Files.move(target, saved); Files.createDirectory(target);
            expect(state.renameVolume(owner, volume.id(), "changed"), "flush fixture: rename failed");
            boolean threw = false;
            try { state.flushNow(); } catch (IllegalStateException expected) { threw = true; }
            expect(threw && state.savedDataDirty(), "failed flush: expected exception and dirty=true");
            Files.delete(target); Files.move(saved, target);
            state.flushNow(); expect(!state.savedDataDirty(), "flush retry: expected dirty=false"); state.closeForTest();
            var reopened = DigitalStorageState.openForTest(root);
            try { expect(reopened.volume(volume.id()).isPresent(), "flush retry: volume lost"); }
            finally { reopened.closeForTest(); }
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { cleanup(root); }
    }

    private static void accessorBindingSurvivesUnavailableVolume() {
        Path root = temporary("binding");
        var owner = UUID.randomUUID(); var id = UUID.randomUUID();
        try {
            var file = Files.createDirectories(root.resolve("data")).resolve("digitalstorage.dat");
            Files.write(file, new byte[] {1, 2, 3});
            var state = DigitalStorageState.openForTest(root);
            var block = ((net.minecraft.world.item.BlockItem) dev.kehai.digitalstorage.DigitalStorageContent.accessorItem()).getBlock();
            var accessor = new dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity(net.minecraft.core.BlockPos.ZERO, block.defaultBlockState());
            var tag = new CompoundTag(); tag.putUUID("ControllerId", owner); tag.putUUID("BoundVolumeId", id); accessor.load(tag);
            expect(state.lookupVolume(id).status() == DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE
                    && !accessor.clearOrphanedBinding(state) && accessor.isBound(), "corrupt data: accessor binding was cleared");
            state.closeForTest();
            var payload = new CompoundTag(); payload.putInt("SchemaVersion", DigitalStorageData.SCHEMA_VERSION + 1);
            var envelope = new CompoundTag(); envelope.put("data", payload); NbtIo.writeCompressed(envelope, file.toFile());
            var future = DigitalStorageState.openForTest(root);
            expect(future.lookupVolume(id).status() == DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE
                    && !accessor.clearOrphanedBinding(future) && accessor.isBound(), "future data: accessor binding was cleared");
            future.closeForTest();
            var valid = new DigitalStorageData(); valid.setDirty(); valid.save(file.toFile());
            var repaired = DigitalStorageState.openForTest(root);
            try { expect(repaired.lookupVolume(id).status() == DigitalStorageState.VolumeStatus.CONFIRMED_DELETED
                    && accessor.clearOrphanedBinding(repaired) && !accessor.isBound(), "confirmed deletion: binding was not cleared"); }
            finally { repaired.closeForTest(); }
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { cleanup(root); }
    }

    private static void deleteVolumePersistsWithoutMarker() {
        Path root = temporary("delete"); var owner = UUID.randomUUID();
        try {
            var state = DigitalStorageState.openForTest(root);
            var volume = state.createVolume(owner, "delete", 1).orElseThrow(); state.flushNow();
            expect(state.deleteEmptyVolume(owner, volume.id()), "delete: empty volume rejected"); state.closeForTest();
            var reopened = DigitalStorageState.openForTest(root);
            try { expect(reopened.volume(volume.id()).isEmpty() && !reopened.ownsVolume(owner, volume.id())
                    && !Files.exists(root.resolve("deleted-volumes")), "delete: volume or marker survived restart"); }
            finally { reopened.closeForTest(); }
        } finally { cleanup(root); }
    }

    private static void legacyUnreadableBindingSurvives() {
        Path root = temporary("legacy-binding"); var owner = UUID.randomUUID(); var id = UUID.randomUUID();
        try {
            var volumes = Files.createDirectories(root.resolve("digitalstorage/volumes"));
            Files.write(volumes.resolve(id + ".dat"), new byte[] {1, 2, 3});
            var state = DigitalStorageState.openForTest(root);
            try {
                var block = ((net.minecraft.world.item.BlockItem) dev.kehai.digitalstorage.DigitalStorageContent.accessorItem()).getBlock();
                var accessor = new dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity(net.minecraft.core.BlockPos.ZERO, block.defaultBlockState());
                var tag = new CompoundTag(); tag.putUUID("ControllerId", owner); tag.putUUID("BoundVolumeId", id); accessor.load(tag);
                expect(state.lookupVolume(id).status() == DigitalStorageState.VolumeStatus.UNREADABLE
                        && !accessor.clearOrphanedBinding(state) && accessor.isBound(), "unreadable legacy volume: accessor binding was cleared");
            } finally { state.closeForTest(); }
        } catch (IOException failure) { throw new IllegalStateException(failure); }
        finally { cleanup(root); }
    }

    private static void overBudgetVolumeLoadsAndRejectsNewVariants() {
        Path root = temporary("budget"); var config = dev.kehai.digitalstorage.config.DigitalStorageConfig.get();
        int original = config.maxVolumeVariantNbtBytes;
        try {
            var owner = UUID.randomUUID(); var state = DigitalStorageState.openForTest(root);
            var volume = state.createVolume(owner, "budget", 1).orElseThrow(); var stone = ItemKey.of(Items.STONE);
            volume.record().storage().load(stone, 7); state.closeForTest();
            config.maxVolumeVariantNbtBytes = 1;
            state = DigitalStorageState.openForTest(root);
            var ledger = state.volume(volume.id()).orElseThrow().record().storage();
            expect(ledger.totalVariantNbtBytes() > 1 && ledger.amountOf(stone) == 7, "budget: saved data failed to load");
            try (var tx = LedgerTransaction.open()) {
                expect(ledger.insert(ItemKey.of(Items.DIRT), 1, tx) == 0 && ledger.extract(stone, 2, tx) == 2, "budget: expected reject new and extract existing"); tx.commit();
            }
            state.closeForTest(); state = DigitalStorageState.openForTest(root);
            try { expect(state.volume(volume.id()).orElseThrow().record().storage().amountOf(stone) == 5, "budget: round trip lost items"); }
            finally { state.closeForTest(); }
        } finally { config.maxVolumeVariantNbtBytes = original; cleanup(root); }
    }

    private static void configuredLimitAndExistingOverflow() {
        Path root = temporary("limits"); var owner = UUID.randomUUID(); var limited = UUID.randomUUID(); UUID extra = null;
        try {
            var state = DigitalStorageState.openForTest(root);
            for (int i = 0; i < 65; i++) {
                extra = state.createVolume(owner, "existing " + i, 0).orElseThrow().id();
                expect(state.createVolume(limited, "limited " + i, 65).isPresent(), "limit: failed at " + i);
            }
            expect(state.createVolume(limited, "66th", 65).isEmpty(), "limit: 66th volume accepted"); state.closeForTest();
            state = DigitalStorageState.openForTest(root);
            try { expect(state.volumeCount(owner) == 65 && state.volumes(owner, 64).size() == 64 && state.ownsVolume(owner, extra)
                    && state.createVolume(owner, "new", 65).isEmpty(), "limit: existing overflow lost"); }
            finally { state.closeForTest(); }
        } finally { cleanup(root); }
    }

    private static void closeIsIdempotent() {
        Path root = temporary("close");
        try {
            var state = DigitalStorageState.openForTest(root); var owner = UUID.randomUUID();
            var volume = state.createVolume(owner, "close", 1).orElseThrow();
            state.closeForTest(); state.closeForTest();
            expect(state.createVolume(owner, "closed", 0).isEmpty() && state.volume(volume.id()).isEmpty(), "close: closed state remained writable");
            try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(ItemKey.of(Items.STONE), 1, tx) == 0, "close: retained ledger accepted insert"); }
        } finally { cleanup(root); }
    }

    private static Path temporary(String name) { try { return Files.createTempDirectory("dsc-" + name + "-"); } catch (IOException e) { throw new IllegalStateException(e); } }
    private static void cleanup(Path root) {
        try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    private static void expect(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
