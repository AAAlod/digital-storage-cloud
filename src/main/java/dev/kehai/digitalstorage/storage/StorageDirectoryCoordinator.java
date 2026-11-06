package dev.kehai.digitalstorage.storage;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

final class StorageDirectoryCoordinator {
    private static final ConcurrentHashMap<Path, StorageDirectoryCoordinator> DIRECTORIES = new ConcurrentHashMap<>();
    final ReentrantLock writeLock = new ReentrantLock();
    volatile long generation;

    static StorageDirectoryCoordinator forRoot(Path root) {
        return DIRECTORIES.computeIfAbsent(root.toAbsolutePath().normalize(), ignored -> new StorageDirectoryCoordinator());
    }
}
