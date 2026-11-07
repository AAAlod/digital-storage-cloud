package dev.kehai.digitalstorage.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Per-directory installation fence; serialization and compression stay outside the lock. */
final class SnapshotCoordinator {
    private static final ConcurrentHashMap<Path, SnapshotCoordinator> ROOTS = new ConcurrentHashMap<>();
    private final ReentrantLock installLock = new ReentrantLock();
    private long generation;

    static SnapshotCoordinator forRoot(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        try { if (Files.exists(normalized)) normalized = normalized.toRealPath(); }
        catch (IOException | SecurityException ignored) { /* retain normalized path */ }
        return ROOTS.computeIfAbsent(normalized, ignored -> new SnapshotCoordinator());
    }

    boolean tryLock(long timeout, TimeUnit unit) throws InterruptedException { return installLock.tryLock(timeout, unit); }
    void unlock() { installLock.unlock(); }

    long nextGeneration() {
        requireLock();
        return ++generation;
    }

    boolean isCurrent(long expected) {
        requireLock();
        return generation == expected;
    }

    private void requireLock() {
        if (!installLock.isHeldByCurrentThread()) throw new IllegalStateException("Snapshot coordinator lock is required");
    }
}
