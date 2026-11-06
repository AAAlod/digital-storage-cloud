package dev.kehai.digitalstorage.storage;

/** Platform-neutral disk, lifecycle, and legacy account regression fixtures. */
public final class StoragePersistenceSelfTest {
    private StoragePersistenceSelfTest() { }

    public static void run() {
        deletedVolumeDoesNotReturnAfterPendingWrite();
        deletionMarkerFailureKeepsVolume();
        hardVolumeLimitAndLegacyOverflow();
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
            expectTrue(state.deleteEmptyVolume(owner, deleted.id()), "delete during pending write");
            // Snapshot the exact crash window before the old writer callback is released.
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
                var afterCrash = DigitalStorageState.openForTest(crashImage);
                try {
                    expectTrue(afterCrash.volume(deleted.id()).isEmpty(), "committed deletion survives crash window");
                    expectTrue(!afterCrash.ownsVolume(owner, deleted.id()), "deleted account entry removed on recovery");
                } finally { afterCrash.closeForTest(); }
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Could not snapshot deletion crash window", failure);
            } finally { deleteTemporaryStorageDirectory(crashImage); }
            release.countDown();
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
                expectTrue(state.createVolume(owner, "Volume " + i, 0).isPresent(), "create within hard limit " + i);
            expectTrue(state.createVolume(owner, "Overflow zero", 0).isEmpty(), "legacy zero must not bypass hard limit");
            expectTrue(state.createVolume(owner, "Overflow large", Integer.MAX_VALUE).isEmpty(), "large limit must not bypass hard limit");
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
                expectTrue(restored.createVolume(owner, "No new volume", 0).isEmpty(), "legacy overflow cannot grow");
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
