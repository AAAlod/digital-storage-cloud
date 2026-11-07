package dev.kehai.digitalstorage.storage;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SnapshotCoordinatorSelfTest {
    private SnapshotCoordinatorSelfTest() { }

    static void run() {
        var root = Path.of(System.getProperty("java.io.tmpdir"), "dsc-fence-" + UUID.randomUUID());
        var coordinator = SnapshotCoordinator.forRoot(root);
        var other = SnapshotCoordinator.forRoot(root.resolveSibling(root.getFileName() + "-other"));
        try {
            expect(coordinator.tryLock(1, TimeUnit.SECONDS), "initial lock");
            long old = coordinator.nextGeneration(); coordinator.unlock();
            var inInstall = new CountDownLatch(1); var finish = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                try {
                    expect(coordinator.tryLock(1, TimeUnit.SECONDS), "worker lock");
                    try {
                        expect(coordinator.isCurrent(old), "old generation not current");
                        inInstall.countDown(); expect(finish.await(3, TimeUnit.SECONDS), "worker timeout");
                    } finally { coordinator.unlock(); }
                } catch (Throwable e) { failure.set(e); }
            }, "dsc-install-fence-test");
            worker.start();
            try {
                expect(inInstall.await(3, TimeUnit.SECONDS), "worker never entered install");
                expect(!coordinator.tryLock(20, TimeUnit.MILLISECONDS), "new lifecycle did not degrade");
                expect(other.tryLock(1, TimeUnit.SECONDS), "other root blocked"); other.unlock();
            } finally { finish.countDown(); }
            worker.join(5000);
            expect(!worker.isAlive() && failure.get() == null, "install worker failed: " + failure.get());
            expect(coordinator.tryLock(1, TimeUnit.SECONDS), "reopen lock");
            try { long next = coordinator.nextGeneration(); expect(next > old && !coordinator.isCurrent(old), "stale generation accepted"); }
            finally { coordinator.unlock(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static void expect(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
}
