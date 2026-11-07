package dev.kehai.digitalstorage.storage;

import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.Items;

/** Isolated pre-integration checks for the detached writer. */
final class DetachedSnapshotWriterSelfTest {
    private DetachedSnapshotWriterSelfTest() { }

    static void run() {
        java.nio.file.Path root = null;
        try {
            root = Files.createTempDirectory("dsc-detached-writer-");
            var target = Files.createDirectories(root.resolve("data")).resolve("digitalstorage.dat");
            var data = new DigitalStorageData();
            var volume = data.createVolume(UUID.randomUUID(), "writer", 0).orElseThrow();
            var key = ItemKey.of(Items.STONE);
            try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(key, 2, tx) == 2, "seed"); tx.commit(); }
            var coordinator = SnapshotCoordinator.forRoot(root);
            expect(coordinator.tryLock(1, TimeUnit.SECONDS), "initial lock");
            long lifecycle = coordinator.nextGeneration(); coordinator.unlock();
            try (var writer = data.new DetachedSnapshotWriter(target, coordinator, lifecycle)) {
                var first = data.captureSnapshotForTest(1000); long firstVersion = data.generationForTest();
                expect(first != null && writer.submit(first, firstVersion) && writer.await(10_000), "initial save");
                expect(!data.isDirty(), "successful generation remained dirty");
                byte[] before = Files.readAllBytes(target);
                var parsed = DigitalStorageData.load(NbtIo.readCompressed(target.toFile()).getCompound("data"));
                expect(parsed.volumes.get(volume.id()).record().storage().amountOf(key) == 2, "saved snapshot mismatch");
                data.setDirty(); var stale = data.captureSnapshotForTest(1000); long staleVersion = data.generationForTest();
                expect(coordinator.tryLock(1, TimeUnit.SECONDS), "stale fixture lock");
                try {
                    expect(writer.submit(stale, staleVersion), "stale submit");
                    try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(key, 1, tx) == 1, "newer mutation"); tx.commit(); }
                } finally { coordinator.unlock(); }
                expect(!writer.await(10_000) && data.isDirty() && java.util.Arrays.equals(before, Files.readAllBytes(target)), "stale snapshot was installed");
                expect(coordinator.tryLock(1, TimeUnit.SECONDS), "lifecycle change fixture lock");
                try { coordinator.nextGeneration(); } finally { coordinator.unlock(); }
                expect(writer.submit(data.captureSnapshotForTest(1000), data.generationForTest()), "old lifecycle submit");
                expect(!writer.await(10_000) && data.isDirty() && java.util.Arrays.equals(before, Files.readAllBytes(target)), "old lifecycle installed after new generation");
            }
        } catch (Exception e) { throw new IllegalStateException("Detached writer fixture failed", e); }
        finally {
            if (root != null) try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
        blockedInstallAndReopen();
        newerMutationStaysDirty();
        nonAtomicFallbackUsesFence();
    }

    private static void nonAtomicFallbackUsesFence() {
        java.nio.file.Path root = null;
        try {
            root = Files.createTempDirectory("dsc-detached-nonatomic-"); var target = root.resolve("digitalstorage.dat");
            var data = new DigitalStorageData(); var volume = data.createVolume(UUID.randomUUID(), "fallback", 0).orElseThrow();
            var key = ItemKey.of(Items.STONE);
            try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(key, 1, tx) == 1, "fallback seed"); tx.commit(); }
            var coordinator = SnapshotCoordinator.forRoot(root); expect(coordinator.tryLock(1, TimeUnit.SECONDS), "fallback lock");
            long generation = coordinator.nextGeneration(); coordinator.unlock();
            DigitalStorageData.FileOperations nonAtomic = new DigitalStorageData.FileOperations() {
                public void atomicMove(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException {
                    throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected");
                }
                public void replace(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException { Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                public void copy(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException { Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            };
            try (var writer = data.new DetachedSnapshotWriter(target, coordinator, generation, nonAtomic)) {
                expect(writer.submit(data.captureSnapshotForTest(1000), data.generationForTest()) && writer.await(10_000), "non-atomic initial save");
                try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(key, 1, tx) == 1, "fallback mutation"); tx.commit(); }
                expect(writer.submit(data.captureSnapshotForTest(1000), data.generationForTest()) && writer.await(10_000), "non-atomic replacement");
                var reread = DigitalStorageData.load(NbtIo.readCompressed(target.toFile()).getCompound("data"));
                expect(!data.isDirty() && reread.volumes.get(volume.id()).record().storage().amountOf(key) == 2, "non-atomic result mismatch");
                try (var paths = Files.list(root)) { expect(paths.noneMatch(path -> path.getFileName().toString().contains(".bak-") || path.getFileName().toString().endsWith(".tmp")), "non-atomic artifacts remained"); }
            }
        } catch (Exception e) { throw new IllegalStateException("Non-atomic detached writer fixture failed", e); }
        finally {
            if (root != null) try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }

    private static void newerMutationStaysDirty() {
        java.nio.file.Path root = null;
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            root = Files.createTempDirectory("dsc-detached-newer-"); var target = root.resolve("digitalstorage.dat");
            var data = new DigitalStorageData(); var volume = data.createVolume(UUID.randomUUID(), "newer", 0).orElseThrow();
            var key = ItemKey.of(Items.STONE);
            try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(key, 1, tx) == 1, "newer seed"); tx.commit(); }
            var coordinator = SnapshotCoordinator.forRoot(root); expect(coordinator.tryLock(1, TimeUnit.SECONDS), "newer lock");
            long generation = coordinator.nextGeneration(); coordinator.unlock();
            var entered = new java.util.concurrent.CountDownLatch(1);
            DigitalStorageData.FileOperations slow = new DigitalStorageData.FileOperations() {
                public void atomicMove(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("move timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                    Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                public void replace(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException { Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                public void copy(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException { Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            };
            try (var writer = data.new DetachedSnapshotWriter(target, coordinator, generation, slow)) {
                expect(writer.submit(data.captureSnapshotForTest(1000), data.generationForTest()) && entered.await(5, TimeUnit.SECONDS), "newer install did not start");
                try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(key, 1, tx) == 1, "newer mutation"); tx.commit(); }
                release.countDown(); expect(writer.await(10_000) && data.isDirty(), "old save cleared new generation");
                expect(writer.submit(data.captureSnapshotForTest(1000), data.generationForTest()) && writer.await(10_000), "newer save failed");
                var loaded = DigitalStorageData.load(NbtIo.readCompressed(target.toFile()).getCompound("data"));
                expect(!data.isDirty() && loaded.volumes.get(volume.id()).record().storage().amountOf(key) == 2, "newer save did not persist");
            }
        } catch (Exception e) { throw new IllegalStateException("Newer generation fixture failed", e); }
        finally {
            release.countDown();
            if (root != null) try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }

    private static void blockedInstallAndReopen() {
        java.nio.file.Path root = null;
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            root = Files.createTempDirectory("dsc-detached-blocked-");
            var target = Files.createDirectories(root.resolve("data")).resolve("digitalstorage.dat");
            var data = new DigitalStorageData();
            var volume = data.createVolume(UUID.randomUUID(), "blocked", 0).orElseThrow();
            var key = ItemKey.of(Items.STONE);
            try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(key, 4, tx) == 4, "blocked seed"); tx.commit(); }
            var coordinator = SnapshotCoordinator.forRoot(root);
            expect(coordinator.tryLock(1, TimeUnit.SECONDS), "blocked initial lock");
            long generation = coordinator.nextGeneration(); coordinator.unlock();
            var entered = new java.util.concurrent.CountDownLatch(1);
            DigitalStorageData.FileOperations slow = new DigitalStorageData.FileOperations() {
                public void atomicMove(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("blocked move timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                    Files.move(source, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                public void replace(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException { Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                public void copy(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException { Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            };
            var writer = data.new DetachedSnapshotWriter(target, coordinator, generation, slow);
            try {
                var snapshot = data.captureSnapshotForTest(1000);
                expect(writer.submit(snapshot, data.generationForTest()) && entered.await(5, TimeUnit.SECONDS), "old install did not enter");
                expect(!writer.await(20) && data.isDirty(), "blocked worker did not time out with dirty state");
                writer.close();
                expect(!coordinator.tryLock(20, TimeUnit.MILLISECONDS), "new lifecycle did not remain degraded");
                var degraded = DigitalStorageState.openForTest(root);
                expect(degraded.savedDataMode().equals("degraded-read-only") && degraded.volume(volume.id()).isEmpty(), "new state did not degrade without loading");
                release.countDown();
                expect(writer.await(10_000), "old install did not finish");
                degraded.recoverIfDegraded();
                expect(degraded.savedDataMode().equals("read-write")
                        && degraded.volume(volume.id()).orElseThrow().record().storage().amountOf(key) == 4, "disk reload mismatch");
                degraded.closeForTest();
                expect(!writer.submit(snapshot, data.generationForTest()), "closed old writer accepted new work");
            } finally { release.countDown(); writer.close(); }
        } catch (Exception e) { throw new IllegalStateException("Blocked installation fixture failed", e); }
        finally {
            release.countDown();
            if (root != null) try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }

    private static void expect(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
