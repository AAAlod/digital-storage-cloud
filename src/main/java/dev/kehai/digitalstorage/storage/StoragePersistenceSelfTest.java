package dev.kehai.digitalstorage.storage;

/** Platform-neutral disk, lifecycle, and legacy account regression fixtures. */
public final class StoragePersistenceSelfTest {
    private StoragePersistenceSelfTest() { }

    public static void run() {
        deletedVolumeDoesNotReturnAfterPendingWrite();
        deletionMarkerFailureKeepsVolume();
        hardVolumeLimitAndLegacyOverflow();
        futureSchemaAccountDoesNotBlockStartup();
        itemsTagOfWrongTypeIsUnreadableNotEmpty();
        missingVolumeCanReturnWithoutLosingAccount();
        ownerIndexCanBeRebuilt();
        closeIsIdempotent();
        failedClosePreservesLastSavedData();
        degradedStateWaitsWithoutReading();
        oldQueuedWriterIsFenced();
        timedOutCloseAllowsDegradedRestart();
        deletionMarkersRecoverAndConverge();
        unavailableVolumesKeepAccessorBindings();
        overBudgetVolumeSurvivesDiskRoundTrip();
    }

    private static void overBudgetVolumeSurvivesDiskRoundTrip() {
        var root = temporaryStorageDirectory("over-budget-disk");
        var config = dev.kehai.digitalstorage.config.DigitalStorageConfig.get();
        int originalBudget = config.maxVolumeVariantNbtBytes;
        var owner = java.util.UUID.randomUUID();
        try {
            var stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
            var dirt = ItemKey.of(net.minecraft.world.item.Items.DIRT);
            var seed = DigitalStorageState.openForTest(root);
            var volume = seed.createVolume(owner, "Over budget", 1).orElseThrow();
            volume.record().storage().load(stone, 7);
            seed.closeForTest();
            var file = root.resolve("volumes").resolve(volume.id() + ".dat");
            expectTrue(java.nio.file.Files.isRegularFile(file), "budget fixture was not saved");
            config.maxVolumeVariantNbtBytes = 1;
            var loaded = DigitalStorageState.openForTest(root);
            try {
                var ledger = loaded.volume(volume.id()).orElseThrow().record().storage();
                expectTrue(ledger.totalVariantNbtBytes() > config.maxVolumeVariantNbtBytes
                        && ledger.amountOf(stone) == 7 && java.nio.file.Files.isRegularFile(file), "over-budget volume was not loaded intact");
                expectTrue(loaded.renameVolume(owner, volume.id(), "Over budget saved"), "over-budget volume could not be marked for save");
                loaded.flushNow();
            } finally { loaded.closeForTest(); }
            var reopened = DigitalStorageState.openForTest(root);
            try {
                var ledger = reopened.volume(volume.id()).orElseThrow().record().storage();
                expectEquals(7, ledger.amountOf(stone), "over-budget volume round trip lost items");
                try (var transaction = LedgerTransaction.open()) {
                    expectEquals(0, ledger.insert(dirt, 1, transaction), "over-budget volume accepted new variant");
                    expectEquals(2, ledger.extract(stone, 2, transaction), "over-budget volume rejected extraction");
                    transaction.commit();
                }
                reopened.flushNow();
            } finally { reopened.closeForTest(); }
            var finalState = DigitalStorageState.openForTest(root);
            try {
                expectEquals(5, finalState.volume(volume.id()).orElseThrow().record().storage().amountOf(stone), "over-budget extraction did not persist");
                expectTrue(java.nio.file.Files.isRegularFile(file), "over-budget volume file was moved");
            } finally { finalState.closeForTest(); }
        } finally {
            config.maxVolumeVariantNbtBytes = originalBudget;
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity boundAccessor(java.util.UUID owner, java.util.UUID volume) {
        var block = ((net.minecraft.world.item.BlockItem) dev.kehai.digitalstorage.DigitalStorageContent.accessorItem()).getBlock();
        var accessor = new dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity(net.minecraft.core.BlockPos.ZERO, block.defaultBlockState());
        var nbt = new net.minecraft.nbt.CompoundTag();
        nbt.putUUID("ControllerId", owner);
        nbt.putUUID("BoundVolumeId", volume);
        accessor.load(nbt);
        return accessor;
    }

    private static void unavailableVolumesKeepAccessorBindings() {
        var root = temporaryStorageDirectory("accessor-unavailable");
        var owner = java.util.UUID.randomUUID();
        try {
            var seed = DigitalStorageState.openForTest(root);
            var volume = seed.createVolume(owner, "Binding", 1).orElseThrow();
            seed.closeForTest();
            var file = root.resolve("volumes").resolve(volume.id() + ".dat");
            byte[] original = java.nio.file.Files.readAllBytes(file);
            java.nio.file.Files.delete(file);
            var missing = DigitalStorageState.openForTest(root);
            try {
                var accessor = boundAccessor(owner, volume.id());
                expectTrue(missing.lookupVolume(volume.id()).status() == DigitalStorageState.VolumeStatus.MISSING
                        && !accessor.clearOrphanedBinding(missing) && accessor.isBound(), "missing volume cleared accessor binding");
                var absent = java.util.UUID.randomUUID();
                var orphan = boundAccessor(owner, absent);
                expectTrue(missing.lookupVolume(absent).status() == DigitalStorageState.VolumeStatus.CONFIRMED_DELETED
                        && orphan.clearOrphanedBinding(missing) && !orphan.isBound(), "unreferenced absent volume did not clear binding");
            } finally { missing.closeForTest(); }
            java.nio.file.Files.createDirectory(file);
            var unavailable = DigitalStorageState.openForTest(root);
            try {
                var accessor = boundAccessor(owner, volume.id());
                expectTrue(unavailable.lookupVolume(volume.id()).status() == DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE
                        && !accessor.clearOrphanedBinding(unavailable) && accessor.isBound(), "unavailable volume cleared accessor binding");
            } finally { unavailable.closeForTest(); }
            java.nio.file.Files.delete(file);
            java.nio.file.Files.write(file, original);
            var corrupt = net.minecraft.nbt.NbtIo.readCompressed(file.toFile());
            corrupt.putString("Items", "bad");
            net.minecraft.nbt.NbtIo.writeCompressed(corrupt, file.toFile());
            var unreadable = DigitalStorageState.openForTest(root);
            try {
                var accessor = boundAccessor(owner, volume.id());
                expectTrue(unreadable.lookupVolume(volume.id()).status() == DigitalStorageState.VolumeStatus.UNREADABLE
                        && !accessor.clearOrphanedBinding(unreadable) && accessor.isBound(), "unreadable volume cleared accessor binding");
            } finally { unreadable.closeForTest(); }
            var future = new net.minecraft.nbt.CompoundTag();
            future.putInt("SchemaVersion", DigitalStorageState.ACCOUNT_SCHEMA_VERSION + 1);
            net.minecraft.nbt.NbtIo.writeCompressed(future, root.resolve("accounts").resolve(java.util.UUID.randomUUID() + ".dat").toFile());
            var incomplete = DigitalStorageState.openForTest(root);
            try {
                var unknown = java.util.UUID.randomUUID();
                var accessor = boundAccessor(owner, unknown);
                expectTrue(incomplete.lookupVolume(unknown).status() == DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE
                        && !accessor.clearOrphanedBinding(incomplete) && accessor.isBound(), "incomplete accounts cleared accessor binding");
            } finally { incomplete.closeForTest(); }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        finally { deleteTemporaryStorageDirectory(root); }
    }

    private static void timedOutCloseAllowsDegradedRestart() {
        var root = temporaryStorageDirectory("close-timeout");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var old = DigitalStorageState.openForTest(root);
        try {
            var owner = java.util.UUID.randomUUID();
            var volume = old.createVolume(owner, "Delayed", 1).orElseThrow();
            old.beforeVolumeWriteCallbackForTest(id -> {
                if (!id.equals(volume.id())) return;
                entered.countDown();
                try {
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("writer fixture timed out");
                } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            });
            old.flushAsyncForTest();
            expectTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "writer did not reach timeout fixture");
            old.flushTimeoutForTest(1);
            old.closeForTest();
            expectTrue(old.persistenceFailureCount() > 0, "timed-out close was not diagnosed");
            var restarted = DigitalStorageState.openForTest(root);
            try {
                expectTrue(!restarted.readyForTest() && restarted.volume(volume.id()).isEmpty(), "restart did not enter degraded state");
                release.countDown();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (old.pendingBatchesForTest() != 0 && System.nanoTime() < deadline) Thread.yield();
                expectTrue(old.pendingBatchesForTest() == 0, "old writer did not finish after release");
                restarted.tickForTest();
                expectTrue(restarted.readyForTest() && restarted.volume(volume.id()).isPresent(), "restart did not recover after timeout");
            } finally { restarted.closeForTest(); }
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        finally {
            release.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (old.pendingBatchesForTest() != 0 && System.nanoTime() < deadline) Thread.yield();
            old.closeForTest();
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void failedClosePreservesLastSavedData() {
        var root = temporaryStorageDirectory("close-write-failure");
        var owner = java.util.UUID.randomUUID();
        try {
            var state = DigitalStorageState.openForTest(root);
            var volume = state.createVolume(owner, "Failure", 1).orElseThrow();
            var stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
            try (var transaction = LedgerTransaction.open()) {
                volume.record().storage().insert(stone, 7, transaction);
                transaction.commit();
            }
            state.flushNow();
            var volumes = root.resolve("volumes");
            var backup = root.resolve("volumes-saved");
            java.nio.file.Files.move(volumes, backup);
            java.nio.file.Files.writeString(volumes, "not a directory");
            try (var transaction = LedgerTransaction.open()) {
                volume.record().storage().insert(stone, 1, transaction);
                transaction.commit();
            }
            state.closeForTest();
            expectTrue(state.persistenceFailureCount() > 0 && !state.lastPersistenceFailure().isEmpty(), "failed close was not diagnosed");
            java.nio.file.Files.delete(volumes);
            java.nio.file.Files.move(backup, volumes);
            var reopened = DigitalStorageState.openForTest(root);
            try { expectEquals(7, reopened.volume(volume.id()).orElseThrow().record().storage().amountOf(stone), "failed close damaged prior saved data"); }
            finally { reopened.closeForTest(); }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        finally { deleteTemporaryStorageDirectory(root); }
    }

    private static void deletionMarkersRecoverAndConverge() {
        for (int stage = 0; stage < 3; stage++) {
            var root = temporaryStorageDirectory("deletion-stage-" + stage);
            try {
                var owner = java.util.UUID.randomUUID();
                var seed = DigitalStorageState.openForTest(root);
                var volume = seed.createVolume(owner, "Tombstone", 1).orElseThrow();
                seed.closeForTest();
                var volumeFile = root.resolve("volumes").resolve(volume.id() + ".dat");
                var accountFile = root.resolve("accounts").resolve(owner + ".dat");
                var marker = root.resolve("deleted-volumes").resolve(volume.id() + ".dat");
                java.nio.file.Files.write(marker, new byte[0]);
                if (stage >= 1) java.nio.file.Files.delete(volumeFile);
                if (stage >= 2) java.nio.file.Files.delete(accountFile);
                var recovered = DigitalStorageState.openForTest(root);
                try {
                    expectTrue(recovered.volume(volume.id()).isEmpty() && !recovered.ownsVolume(owner, volume.id()), "tombstone did not protect deleted volume at stage " + stage);
                    recovered.flushNow();
                    expectTrue(!java.nio.file.Files.exists(volumeFile) && !java.nio.file.Files.exists(marker), "tombstone did not converge at stage " + stage);
                } finally { recovered.closeForTest(); }
                recovered = DigitalStorageState.openForTest(root);
                try { expectTrue(recovered.volume(volume.id()).isEmpty(), "deleted volume returned at stage " + stage); }
                finally { recovered.closeForTest(); }
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            finally { deleteTemporaryStorageDirectory(root); }
        }
        var root = temporaryStorageDirectory("deletion-many");
        try {
            var owner = java.util.UUID.randomUUID();
            var state = DigitalStorageState.openForTest(root);
            try {
                var ids = new java.util.ArrayList<java.util.UUID>();
                for (int i = 0; i < 100; i++) ids.add(state.createVolume(owner, "Delete " + i, 0).orElseThrow().id());
                state.flushNow();
                for (var id : ids) expectTrue(state.deleteEmptyVolume(owner, id), "empty volume deletion failed");
                state.flushNow();
            } finally { state.closeForTest(); }
            var reopened = DigitalStorageState.openForTest(root);
            try {
                reopened.flushNow();
                try (var files = java.nio.file.Files.list(root.resolve("deleted-volumes"))) {
                    expectTrue(files.noneMatch(path -> path.toString().endsWith(".dat")), "completed deletion markers accumulated");
                }
            } finally { reopened.closeForTest(); }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        finally { deleteTemporaryStorageDirectory(root); }
    }

    private static void degradedStateWaitsWithoutReading() {
        var root = temporaryStorageDirectory("directory-degraded");
        var owner = java.util.UUID.randomUUID();
        var coordinator = StorageDirectoryCoordinator.forRoot(root);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = new Thread(() -> {
            coordinator.writeLock.lock();
            try {
                entered.countDown();
                if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("lock fixture timed out");
            } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            finally { coordinator.writeLock.unlock(); }
        }, "dsc-directory-lock-selftest");
        try {
            var seed = DigitalStorageState.openForTest(root);
            var volume = seed.createVolume(owner, "Seed", 1).orElseThrow();
            seed.closeForTest();
            var file = root.resolve("volumes").resolve(volume.id() + ".dat");
            byte[] original = java.nio.file.Files.readAllBytes(file);
            holder.start();
            expectTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "lock fixture did not start");
            long start = System.nanoTime();
            var waiting = DigitalStorageState.openForTest(root);
            try {
                expectTrue(!waiting.readyForTest() && System.nanoTime() - start < java.util.concurrent.TimeUnit.SECONDS.toNanos(5), "busy directory did not degrade promptly");
                expectTrue(waiting.volume(volume.id()).isEmpty() && waiting.volumeCount(owner) == 0
                        && waiting.createVolume(owner, "Blocked", 0).isEmpty() && !waiting.ownsVolume(owner, volume.id()), "degraded state exposed data or accepted writes");
                var accessor = boundAccessor(owner, volume.id());
                expectTrue(waiting.lookupVolume(volume.id()).status() == DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE
                        && !accessor.clearOrphanedBinding(waiting) && accessor.isBound(), "degraded state cleared accessor binding");
                expectTrue(java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(file)), "degraded state modified the volume");
                release.countDown();
                holder.join(5000);
                expectTrue(!holder.isAlive(), "lock fixture did not release");
                waiting.tickForTest();
                expectTrue(waiting.readyForTest() && waiting.volume(volume.id()).isPresent(), "degraded state did not recover");
            } finally { waiting.closeForTest(); }
        } catch (java.io.IOException | InterruptedException failure) { throw new IllegalStateException(failure); }
        finally {
            release.countDown();
            try { holder.join(5000); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void oldQueuedWriterIsFenced() {
        var root = temporaryStorageDirectory("directory-generation");
        var coordinator = StorageDirectoryCoordinator.forRoot(root);
        var owner = java.util.UUID.randomUUID();
        var old = DigitalStorageState.openForTest(root);
        try {
            var stale = old.createVolume(owner, "Stale", 1).orElseThrow();
            coordinator.writeLock.lock();
            DigitalStorageState current;
            try {
                old.flushAsyncForTest();
                current = DigitalStorageState.openForTest(root);
                expectTrue(current.readyForTest(), "new generation could not initialize under test lock");
            } finally { coordinator.writeLock.unlock(); }
            try {
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (old.pendingBatchesForTest() != 0 && System.nanoTime() < deadline) Thread.yield();
                expectTrue(old.pendingBatchesForTest() == 0 && old.persistenceFailureCount() > 0, "old queued writer was not fenced");
                expectTrue(!java.nio.file.Files.exists(root.resolve("volumes").resolve(stale.id() + ".dat")), "old writer published a stale volume");
                var fresh = current.createVolume(owner, "Fresh", 1).orElseThrow();
                current.flushAndWaitForTest();
                expectTrue(java.nio.file.Files.exists(root.resolve("volumes").resolve(fresh.id() + ".dat")), "new generation did not persist");
            } finally { current.closeForTest(); }
        } finally { old.closeForTest(); deleteTemporaryStorageDirectory(root); }
    }

    private static void closeIsIdempotent() {
        var root = temporaryStorageDirectory("closed-state");
        try {
            var state = DigitalStorageState.openForTest(root);
            var owner = java.util.UUID.randomUUID();
            var volume = state.createVolume(owner, "Closed", 1).orElseThrow();
            state.closeForTest();
            state.closeForTest();
            expectTrue(state.createVolume(owner, "After close", 0).isEmpty(), "closed state accepted a new volume");
            expectTrue(state.volume(volume.id()).isEmpty(), "closed state returned a volume");
            try (var transaction = LedgerTransaction.open()) {
                expectEquals(0, volume.record().storage().insert(ItemKey.of(net.minecraft.world.item.Items.STONE), 1, transaction), "retained closed ledger accepted insertion");
            }
            var reopened = DigitalStorageState.openForTest(root);
            try { expectTrue(reopened.volume(volume.id()).isPresent(), "normal close did not persist the volume"); }
            finally { reopened.closeForTest(); }
        } finally { deleteTemporaryStorageDirectory(root); }
    }

    private static void missingVolumeCanReturnWithoutLosingAccount() {
        var root = temporaryStorageDirectory("missing-volume");
        var owner = java.util.UUID.randomUUID();
        try {
            var initial = DigitalStorageState.openForTest(root);
            var volume = initial.createVolume(owner, "Missing", 1).orElseThrow();
            volume.record().storage().load(ItemKey.of(net.minecraft.world.item.Items.STONE), 7);
            initial.flushAndWaitForTest();
            initial.closeForTest();
            var file = root.resolve("volumes").resolve(volume.id() + ".dat");
            var accountFile = root.resolve("accounts").resolve(owner + ".dat");
            byte[] saved = java.nio.file.Files.readAllBytes(file);
            byte[] account = java.nio.file.Files.readAllBytes(accountFile);
            java.nio.file.Files.delete(file);
            var reopened = DigitalStorageState.openForTest(root);
            try {
                expectTrue(reopened.ownsVolume(owner, volume.id()) && reopened.volumeCount(owner) == 1,
                        "missing volume reference was lost");
                expectTrue(reopened.volume(volume.id()).isEmpty(), "missing volume returned a record");
                expectTrue(java.util.Arrays.equals(account, java.nio.file.Files.readAllBytes(accountFile)), "missing volume rewrote account");
                java.nio.file.Files.write(file, saved);
                expectEquals(7, reopened.volume(volume.id()).orElseThrow().record().storage().totalItemCount(), "restored volume contents");
            } finally { reopened.closeForTest(); }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        finally { deleteTemporaryStorageDirectory(root); }
    }

    private static void ownerIndexCanBeRebuilt() {
        var root = temporaryStorageDirectory("owner-index");
        var owner = java.util.UUID.randomUUID();
        var actualOwner = java.util.UUID.randomUUID();
        try {
            var state = DigitalStorageState.openForTest(root);
            var volume = state.createVolume(owner, "Orphan", 1).orElseThrow();
            state.flushAndWaitForTest();
            state.closeForTest();
            var file = root.resolve("volumes").resolve(volume.id() + ".dat");
            java.nio.file.Files.delete(root.resolve("accounts").resolve(owner + ".dat"));
            state = DigitalStorageState.openForTest(root);
            try {
                expectTrue(state.ownsVolume(owner, volume.id()), "orphan volume owner index not rebuilt");
                state.flushAndWaitForTest();
                expectTrue(java.nio.file.Files.exists(root.resolve("accounts").resolve(owner + ".dat")), "rebuilt account was not saved");
            } finally { state.closeForTest(); }
            var saved = net.minecraft.nbt.NbtIo.readCompressed(file.toFile());
            saved.putUUID("Owner", actualOwner);
            net.minecraft.nbt.NbtIo.writeCompressed(saved, file.toFile());
            state = DigitalStorageState.openForTest(root);
            try {
                expectTrue(!state.ownsVolume(owner, volume.id()) && state.ownsVolume(actualOwner, volume.id()), "volume Owner did not repair the index");
                state.flushAndWaitForTest();
            } finally { state.closeForTest(); }
            state = DigitalStorageState.openForTest(root);
            try { expectTrue(!state.ownsVolume(owner, volume.id()) && state.ownsVolume(actualOwner, volume.id()), "owner index repair was not saved"); }
            finally { state.closeForTest(); }
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        finally { deleteTemporaryStorageDirectory(root); }
    }

    private static void deletionMarkerFailureKeepsVolume() {
        var root = temporaryStorageDirectory("delete-marker-failure");
        var state = DigitalStorageState.openForTest(root);
        var markerDirectory = root.resolve("deleted-volumes");
        try {
            var owner = java.util.UUID.randomUUID();
            var volume = state.createVolume(owner, "Retained", 0).orElseThrow();
            java.nio.file.Files.delete(markerDirectory);
            java.nio.file.Files.writeString(markerDirectory, "not a directory");
            expectTrue(!state.deleteEmptyVolume(owner, volume.id()), "deletion rejected if marker cannot be persisted");
            expectTrue(state.volume(volume.id()).isPresent() && state.ownsVolume(owner, volume.id()), "failed deletion preserves volume and ownership");
            java.nio.file.Files.delete(markerDirectory);
            java.nio.file.Files.createDirectory(markerDirectory);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not construct marker failure fixture", failure);
        } finally {
            state.closeForTest();
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void deletedVolumeDoesNotReturnAfterPendingWrite() {
        var root = temporaryStorageDirectory("delete-pending-write");
        var state = DigitalStorageState.openForTest(root);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            var owner = java.util.UUID.randomUUID();
            var deleted = state.createVolume(owner, "Deleted", 0).orElseThrow();
            var other = state.createVolume(owner, "Retained", 0).orElseThrow();
            state.beforeVolumeWriteCallbackForTest(id -> {
                if (!id.equals(deleted.id())) return;
                entered.countDown();
                try {
                    if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS))
                        throw new IllegalStateException("Timed out waiting for deletion");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            });
            state.flushAsyncForTest();
            try {
                expectTrue(entered.await(30, java.util.concurrent.TimeUnit.SECONDS), "writer callback reached");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            expectTrue(!state.deleteEmptyVolume(owner, deleted.id()), "deletion must wait while writer holds the directory lock");
            release.countDown();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (state.pendingBatchesForTest() != 0 && System.nanoTime() < deadline) Thread.yield();
            expectTrue(state.pendingBatchesForTest() == 0, "writer did not finish after release");
            state.beforeVolumeWriteCallbackForTest(null);
            expectTrue(state.deleteEmptyVolume(owner, deleted.id()), "delete after pending write");
            // Snapshot the crash window after the marker but before deletion is flushed.
            var crashImage = temporaryStorageDirectory("delete-crash-image");
            try {
                try (var paths = java.nio.file.Files.walk(root)) {
                    for (var source : paths.toList()) {
                        var target = crashImage.resolve(root.relativize(source));
                        if (java.nio.file.Files.isDirectory(source)) java.nio.file.Files.createDirectories(target);
                        else java.nio.file.Files.copy(source, target);
                    }
                }
                expectTrue(java.nio.file.Files.exists(crashImage.resolve("volumes").resolve(deleted.id() + ".dat")), "crash image contains stale volume");
                java.nio.file.Files.writeString(crashImage.resolve("deleted-volumes").resolve("invalid.dat"), "invalid");
                var afterCrash = DigitalStorageState.openForTest(crashImage);
                try {
                    expectTrue(afterCrash.volume(deleted.id()).isEmpty(), "committed deletion survives crash window");
                    expectTrue(!afterCrash.ownsVolume(owner, deleted.id()), "deleted account entry removed on recovery");
                    expectTrue(java.nio.file.Files.exists(crashImage.resolve("deleted-volumes").resolve(deleted.id() + ".dat")), "marker retained until disk cleanup");
                    expectTrue(java.nio.file.Files.exists(crashImage.resolve("deleted-volumes").resolve("invalid.dat")), "invalid marker preserved in active directory");
                    try (var quarantined = java.nio.file.Files.list(crashImage.resolve("quarantine").resolve("deleted-volumes"))) {
                        expectTrue(quarantined.noneMatch(path -> path.getFileName().toString().startsWith("invalid.dat.broken-")), "invalid marker must not be quarantined");
                    }
                } finally { afterCrash.closeForTest(); }
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not snapshot deletion crash window", failure);
            } finally { deleteTemporaryStorageDirectory(crashImage); }
            state.flushAndWaitForTest();
            expectTrue(state.volume(deleted.id()).isEmpty(), "deleted volume must not reappear");
            expectTrue(!java.nio.file.Files.exists(root.resolve("volumes").resolve(deleted.id() + ".dat")), "deleted file absent");
            expectTrue(state.volume(other.id()).isPresent(), "other volume survives");
            var restored = DigitalStorageState.openForTest(root);
            try {
                expectTrue(restored.volume(deleted.id()).isEmpty(), "deleted volume absent after restart");
                expectTrue(restored.volume(other.id()).isPresent(), "other volume survives restart");
            } finally { restored.closeForTest(); }
        } finally {
            release.countDown();
            state.beforeVolumeWriteCallbackForTest(null);
            state.closeForTest();
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void hardVolumeLimitAndLegacyOverflow() {
        var root = temporaryStorageDirectory("volume-hard-limit");
        var owner = java.util.UUID.randomUUID();
        var otherOwner = java.util.UUID.randomUUID();
        java.util.UUID overflowId;
        var state = DigitalStorageState.openForTest(root);
        try {
            for (int i = 0; i < 64; i++)
                expectTrue(state.createVolume(owner, "Volume " + i, 65).isPresent(), "create within configured limit " + i);
            var unlimitedOwner = java.util.UUID.randomUUID();
            for (int i = 0; i < 65; i++)
                expectTrue(state.createVolume(unlimitedOwner, "Unlimited " + i, 0).isPresent(), "unlimited volume " + i);
            var limitedOwner = java.util.UUID.randomUUID();
            for (int i = 0; i < 65; i++)
                expectTrue(state.createVolume(limitedOwner, "Limited " + i, 65).isPresent(), "configured 65 volume " + i);
            expectTrue(state.createVolume(limitedOwner, "Limited overflow", 65).isEmpty(), "configured 65 blocks 66th volume");
            var overflow = state.createVolume(otherOwner, "Legacy overflow", 1).orElseThrow();
            overflowId = overflow.id();
            try (var transaction = LedgerTransaction.open()) {
                expectEquals(19, overflow.record().storage().insert(ItemKey.of(net.minecraft.world.item.Items.STONE), 19, transaction), "legacy seed");
                transaction.commit();
            }
            state.flushAndWaitForTest();
        } finally { state.closeForTest(); }
        try {
            var accountPath = root.resolve("accounts").resolve(owner + ".dat");
            var volumePath = root.resolve("volumes").resolve(overflowId + ".dat");
            var account = net.minecraft.nbt.NbtIo.readCompressed(accountPath.toFile());
            var entry = new net.minecraft.nbt.CompoundTag();
            entry.putUUID("Id", overflowId);
            account.getList("Volumes", net.minecraft.nbt.Tag.TAG_COMPOUND).add(entry);
            net.minecraft.nbt.NbtIo.writeCompressed(account, accountPath.toFile());
            var volume = net.minecraft.nbt.NbtIo.readCompressed(volumePath.toFile());
            volume.putUUID("Owner", owner);
            net.minecraft.nbt.NbtIo.writeCompressed(volume, volumePath.toFile());
            java.nio.file.Files.delete(root.resolve("accounts").resolve(otherOwner + ".dat"));
            var restored = DigitalStorageState.openForTest(root);
            try {
                expectEquals(65, restored.volumeCount(owner), "legacy overflow remains indexed");
                expectEquals(64, restored.volumes(owner, 64).size(), "bounded GUI volume list");
                expectEquals(65, restored.volumes(owner).size(), "all legacy volumes remain accessible");
                expectTrue(restored.ownsVolume(owner, overflowId), "legacy overflow ownership retained");
                expectTrue(restored.createVolume(owner, "No new volume", 65).isEmpty(), "configured limit blocks 66th volume");
                var ledger = restored.volume(overflowId).orElseThrow().record().storage();
                expectEquals(19, ledger.totalItemCount(), "legacy overflow items retained");
                try (var transaction = LedgerTransaction.open()) {
                    expectEquals(7, ledger.extract(ItemKey.of(net.minecraft.world.item.Items.STONE), 7, transaction), "legacy overflow extraction");
                    transaction.commit();
                }
            } finally { restored.closeForTest(); }
            var reopened = DigitalStorageState.openForTest(root);
            try {
                expectEquals(12, reopened.volume(overflowId).orElseThrow().record().storage().totalItemCount(), "legacy overflow extraction persisted");
            } finally { reopened.closeForTest(); }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not construct legacy volume fixture", failure);
        } finally { deleteTemporaryStorageDirectory(root); }
    }

    private static void futureSchemaAccountDoesNotBlockStartup() {
        var root = temporaryStorageDirectory("future-account");
        var owner = java.util.UUID.randomUUID();
        try {
            var directory = root.resolve("accounts");
            java.nio.file.Files.createDirectories(directory);
            var file = directory.resolve(owner + ".dat");
            var nbt = new net.minecraft.nbt.CompoundTag();
            nbt.putInt("SchemaVersion", DigitalStorageState.ACCOUNT_SCHEMA_VERSION + 1);
            net.minecraft.nbt.NbtIo.writeCompressed(nbt, file.toFile());
            byte[] original = java.nio.file.Files.readAllBytes(file);
            var invalidFile = directory.resolve("not-a-uuid.dat");
            net.minecraft.nbt.NbtIo.writeCompressed(nbt, invalidFile.toFile());
            byte[] invalidOriginal = java.nio.file.Files.readAllBytes(invalidFile);
            var state = DigitalStorageState.openForTest(root);
            try {
                expectEquals(0, state.volumeCount(owner), "future account skipped");
                expectTrue(state.createVolume(owner, "No overwrite", 0).isEmpty(), "future account accepted create");
            } finally { state.closeForTest(); }
            expectTrue(java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(file)), "future account changed");
            expectTrue(java.util.Arrays.equals(invalidOriginal, java.nio.file.Files.readAllBytes(invalidFile)), "invalid future account changed");
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        finally { deleteTemporaryStorageDirectory(root); }
    }

    private static void itemsTagOfWrongTypeIsUnreadableNotEmpty() {
        var root = temporaryStorageDirectory("invalid-items");
        var id = java.util.UUID.randomUUID();
        try {
            var directory = root.resolve("volumes");
            java.nio.file.Files.createDirectories(directory);
            var file = directory.resolve(id + ".dat");
            var nbt = new net.minecraft.nbt.CompoundTag();
            nbt.putUUID("Id", id);
            nbt.putUUID("Owner", java.util.UUID.randomUUID());
            nbt.putString("Items", "invalid");
            net.minecraft.nbt.NbtIo.writeCompressed(nbt, file.toFile());
            byte[] original = java.nio.file.Files.readAllBytes(file);
            var state = DigitalStorageState.openForTest(root);
            try { expectTrue(state.volume(id).isEmpty(), "invalid Items accepted"); }
            finally { state.closeForTest(); }
            expectTrue(java.util.Arrays.equals(original, java.nio.file.Files.readAllBytes(file)), "invalid Items file changed");
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        finally { deleteTemporaryStorageDirectory(root); }
    }

    private static java.nio.file.Path temporaryStorageDirectory(String name) {
        try { return java.nio.file.Files.createTempDirectory("dsc-" + name + "-"); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void deleteTemporaryStorageDirectory(java.nio.file.Path root) {
        try (var paths = java.nio.file.Files.walk(root)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                java.nio.file.Files.delete(path);
        } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void expectTrue(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }

    private static void expectEquals(long expected, long actual, String description) {
        if (expected != actual) throw new IllegalStateException(description + ": expected " + expected + ", got " + actual);
    }
}
