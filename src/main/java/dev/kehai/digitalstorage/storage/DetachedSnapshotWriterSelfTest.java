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
        nonAtomicFallback();
        boundedFallbackBackups();
        shutdownPaths();
        shutdownBlockedInstall();
    }

    private static void boundedFallbackBackups() {
        java.nio.file.Path root = null;
        try {
            root = Files.createTempDirectory("dsc-bounded-backup-");
            var target = root.resolve("digitalstorage.dat");
            var data = new DigitalStorageData(); data.createVolume(UUID.randomUUID(), "first", 0).orElseThrow();
            var coordinator = SnapshotCoordinator.forRoot(root);
            expect(coordinator.tryLock(1, TimeUnit.SECONDS), "backup lifecycle lock");
            long lifecycle = coordinator.nextGeneration(); coordinator.unlock();
            boolean[] failCleanup = {false};
            DigitalStorageData.FileOperations ops = new DigitalStorageData.FileOperations() {
                public void atomicMove(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException { throw new java.nio.file.AtomicMoveNotSupportedException(a.toString(), b.toString(), "fixture"); }
                public void replace(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException { Files.move(a, b, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                public void copy(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException { Files.copy(a, b, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                public void deleteBackup(java.nio.file.Path p) throws java.io.IOException { if (failCleanup[0]) throw new java.io.IOException("injected cleanup failure"); Files.deleteIfExists(p); }
            };
            try (var writer = data.new DetachedSnapshotWriter(target, coordinator, lifecycle, ops)) {
                for (int i = 0; i < 10; i++) {
                    data.createVolume(UUID.randomUUID(), "iteration", 0).orElseThrow();
                    expect(writer.submit(data.captureSnapshotForTest(1000), data.generationForTest()) && writer.await(10_000), "bounded backup save");
                }
                try (var paths = Files.list(root)) { expect(paths.filter(p -> p.getFileName().toString().startsWith("digitalstorage.dat.bak-")).count() <= 2, "backup count unbounded"); }
                failCleanup[0] = true;
                var volume = data.createVolume(UUID.randomUUID(), "cleanup-failure", 0).orElseThrow();
                expect(writer.submit(data.captureSnapshotForTest(1000), data.generationForTest()) && writer.await(10_000), "cleanup failure changed save result");
                var loaded = DigitalStorageData.load(NbtIo.readCompressed(target.toFile()).getCompound("data"));
                expect(loaded.volumes.containsKey(volume.id()) && !data.isDirty(), "cleanup failure damaged installed target");
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("SavedData backup self-test passed: ten non-atomic saves bounded to two backups, cleanup failure retained installed snapshot");
            }
        } catch (Exception e) { throw new IllegalStateException("Bounded backup fixture failed", e); }
        finally { if (root != null) try (var paths = Files.walk(root)) { for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } catch (java.io.IOException e) { throw new IllegalStateException(e); } }
    }

    // An in-flight atomic move may complete after the bounded shutdown wait.
    private static void shutdownBlockedInstall() {
        java.nio.file.Path root = null;
        var release = new java.util.concurrent.CountDownLatch(1);
        DigitalStorageData data = null;
        try {
            root = Files.createTempDirectory("dsc-shutdown-blocked-");
            var target = root.resolve("digitalstorage.dat");
            var original = new DigitalStorageData();
            var originalVolume = original.createVolume(UUID.randomUUID(), "old", 0).orElseThrow();
            original.saveWithWriter(target.toFile(), (tag, path) -> NbtIo.writeCompressed(tag, path.toFile()));
            var coordinator = SnapshotCoordinator.forRoot(root);
            expect(coordinator.tryLock(1, TimeUnit.SECONDS), "blocked shutdown lifecycle lock");
            long lifecycle = coordinator.nextGeneration(); coordinator.unlock();
            var entered = new java.util.concurrent.CountDownLatch(1);
            DigitalStorageData.FileOperations slow = new DigitalStorageData.FileOperations() {
                public void atomicMove(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("blocked move timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.IOException(e); }
                    Files.move(a, b, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                public void replace(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException { Files.move(a, b, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                public void copy(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException { Files.copy(a, b, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            };
            data = new DigitalStorageData();
            var newVolume = data.createVolume(UUID.randomUUID(), "blocked", 0).orElseThrow();
            long generation = data.generationForTest();
            data.enableSnapshotWriterForTest(target, coordinator, lifecycle, slow);
            data.save(target.toFile());
            expect(entered.await(5, TimeUnit.SECONDS), "blocked installation did not enter");
            boolean timedOut = false;
            try { data.shutdownSnapshot(20); } catch (IllegalStateException expected) { timedOut = true; }
            long start = System.nanoTime();
            expect(timedOut && data.snapshotWriterDaemonForTest() && !data.awaitSnapshotWriterStop(20), "blocked shutdown did not report a daemon worker");
            expect(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000, "worker termination wait was unbounded");
            var beforeRelease = DigitalStorageData.load(NbtIo.readCompressed(target.toFile()).getCompound("data"));
            expect(beforeRelease.volumes.containsKey(originalVolume.id()), "old snapshot unreadable during blocked install");
            release.countDown();
            expect(data.awaitSnapshotWriterStop(10_000), "in-flight installation did not terminate");
            var loaded = DigitalStorageData.load(NbtIo.readCompressed(target.toFile()).getCompound("data"));
            expect(!loaded.readOnly && (loaded.volumes.containsKey(originalVolume.id()) || loaded.volumes.containsKey(newVolume.id())), "target is not a complete old or new snapshot");
            expect(loaded.volumes.containsKey(newVolume.id()) && data.lastSuccessfulGenerationForTest() == generation && data.lastSuccessfulTimeForTest() > 0, "completed installation generation was not recorded");
        } catch (Exception e) { throw new IllegalStateException("Blocked shutdown fixture failed", e); }
        finally {
            release.countDown();
            if (data != null) try { data.awaitSnapshotWriterStop(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (root != null) try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }

    private static void shutdownPaths() {
        java.nio.file.Path root = null;
        try {
            root = Files.createTempDirectory("dsc-shutdown-");
            var coordinator = SnapshotCoordinator.forRoot(root);
            expect(coordinator.tryLock(1, TimeUnit.SECONDS), "shutdown lifecycle lock");
            long lifecycle = coordinator.nextGeneration(); coordinator.unlock();
            var target = root.resolve("digitalstorage.dat");
            var data = new DigitalStorageData();
            data.createVolume(UUID.randomUUID(), "final", 0).orElseThrow();
            data.enableSnapshotWriter(target, coordinator, lifecycle);
            data.shutdownSnapshot(10_000);
            expect(data.awaitSnapshotWriterStop(10_000) && !data.isDirty() && Files.exists(target), "normal shutdown");
            expect(!data.lastSuccessfulSnapshot().equals("unknown"), "missing successful generation");
            boolean rejected = false;
            try { data.createVolume(UUID.randomUUID(), "late", 0); } catch (IllegalStateException expected) { rejected = true; }
            expect(rejected, "shutdown accepted mutation");

            byte[] before = Files.readAllBytes(target);
            var failed = new DigitalStorageData();
            failed.createVolume(UUID.randomUUID(), "failure", 0).orElseThrow();
            DigitalStorageData.FileOperations failing = new DigitalStorageData.FileOperations() {
                public void atomicMove(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException { throw new java.io.IOException("injected shutdown failure"); }
                public void replace(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException { throw new java.io.IOException("injected shutdown failure"); }
                public void copy(java.nio.file.Path a, java.nio.file.Path b) throws java.io.IOException { Files.copy(a, b); }
            };
            failed.enableSnapshotWriterForTest(target, coordinator, lifecycle, failing);
            boolean error = false;
            try { failed.shutdownSnapshot(10_000); } catch (IllegalStateException expected) { error = true; }
            expect(error && failed.awaitSnapshotWriterStop(10_000) && failed.isDirty() && java.util.Arrays.equals(before, Files.readAllBytes(target)), "failed shutdown did not preserve old file and dirty state");

            var active = new DigitalStorageData();
            active.createVolume(UUID.randomUUID(), "active", 0).orElseThrow();
            active.enableSnapshotWriter(root.resolve("active.dat"), coordinator, lifecycle);
            var id = new TransactionId(); active.transactionRegistry.enter(id);
            error = false;
            try { active.shutdownSnapshot(20); } catch (IllegalStateException expected) { error = true; }
            finally { active.transactionRegistry.leave(id); }
            expect(error && active.isDirty() && active.awaitSnapshotWriterStop(10_000) && !Files.exists(root.resolve("active.dat")), "active transaction shutdown timeout");
        } catch (Exception e) { throw new IllegalStateException("Shutdown persistence fixture failed", e); }
        finally {
            if (root != null) try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }

    private static void nonAtomicFallback() {
        for (boolean failInstall : new boolean[] { false, true }) {
        java.nio.file.Path root = null;
        try {
            root = Files.createTempDirectory("dsc-detached-nonatomic-"); var target = root.resolve("digitalstorage.dat");
            var old = new DigitalStorageData();
            var oldVolume = old.createVolume(UUID.randomUUID(), "old", 0).orElseThrow();
            old.saveWithWriter(target.toFile(), (tag, path) -> NbtIo.writeCompressed(tag, path.toFile()));
            var data = new DigitalStorageData(); var volume = data.createVolume(UUID.randomUUID(), "fallback", 0).orElseThrow();
            var key = ItemKey.of(Items.STONE);
            try (var tx = LedgerTransaction.open()) { expect(volume.record().storage().insert(key, 1, tx) == 1, "fallback seed"); tx.commit(); }
            var coordinator = SnapshotCoordinator.forRoot(root); expect(coordinator.tryLock(1, TimeUnit.SECONDS), "fallback lock");
            long generation = coordinator.nextGeneration(); coordinator.unlock();
            DigitalStorageData.FileOperations nonAtomic = new DigitalStorageData.FileOperations() {
                public void atomicMove(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException {
                    throw new java.nio.file.AtomicMoveNotSupportedException(source.toString(), destination.toString(), "injected");
                }
                public void replace(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException {
                    if (failInstall) { Files.write(destination, new byte[] {1, 2, 3}); throw new java.io.IOException("injected replacement failure"); }
                    Files.move(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                public void copy(java.nio.file.Path source, java.nio.file.Path destination) throws java.io.IOException { Files.copy(source, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            };
            try (var writer = data.new DetachedSnapshotWriter(target, coordinator, generation, nonAtomic)) {
                expect(writer.submit(data.captureSnapshotForTest(1000), data.generationForTest()), "non-atomic submission");
                expect(writer.await(10_000) != failInstall, "non-atomic result");
                var loaded = DigitalStorageData.load(NbtIo.readCompressed(target.toFile()).getCompound("data"));
                expect(failInstall ? data.isDirty() && loaded.volumes.containsKey(oldVolume.id())
                        : !data.isDirty() && loaded.volumes.containsKey(volume.id()), "non-atomic target mismatch");
                try (var paths = Files.list(root)) {
                    var backup = paths.filter(path -> path.getFileName().toString().startsWith("digitalstorage.dat.bak-")).findFirst().orElseThrow();
                    var backupData = DigitalStorageData.load(NbtIo.readCompressed(backup.toFile()).getCompound("data"));
                    expect(backupData.volumes.containsKey(oldVolume.id()), "old backup was lost or damaged");
                }
            }
        } catch (Exception e) { throw new IllegalStateException("Non-atomic detached writer fixture failed", e); }
        finally {
            if (root != null) try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
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
