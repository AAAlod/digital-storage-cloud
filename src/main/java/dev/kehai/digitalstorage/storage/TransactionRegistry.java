package dev.kehai.digitalstorage.storage;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates transactions that have entered DSC with snapshot capture. */
public final class TransactionRegistry {
    private final ReentrantLock LOCK = new ReentrantLock();
    private final Condition CHANGED = LOCK.newCondition();
    private final Map<TransactionId, Thread> ACTIVE = new IdentityHashMap<>();
    private final java.util.Set<TransactionId> GENERATION_MARKED = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean capturing;
    private Thread captureOwner;

    public TransactionRegistry() { }

    public boolean enter(TransactionId outerId) {
        LOCK.lock();
        try {
            if (ACTIVE.containsKey(outerId)) return false;
            while (capturing && captureOwner != Thread.currentThread() && !ACTIVE.containsValue(Thread.currentThread())) CHANGED.awaitUninterruptibly();
            ACTIVE.put(outerId, Thread.currentThread());
            return true;
        } finally { LOCK.unlock(); }
    }

    public void leave(TransactionId outerId) {
        LOCK.lock();
        try { if (ACTIVE.remove(outerId) != null) { GENERATION_MARKED.remove(outerId); CHANGED.signalAll(); } }
        finally { LOCK.unlock(); }
    }

    boolean markGeneration(TransactionId id) {
        LOCK.lock();
        try { return id == null || !ACTIVE.containsKey(id) || GENERATION_MARKED.add(id); }
        finally { LOCK.unlock(); }
    }

    /** Returns null on timeout; close the returned guard after the entire deep copy. */
    public Capture tryCapture(long timeout, TimeUnit unit) throws InterruptedException {
        long remaining = unit.toNanos(timeout);
        LOCK.lockInterruptibly();
        try {
            while (capturing) {
                if (remaining <= 0) return null;
                remaining = CHANGED.awaitNanos(remaining);
            }
            capturing = true;
            captureOwner = Thread.currentThread();
            try {
                while (!ACTIVE.isEmpty()) {
                    if (remaining <= 0) { capturing = false; captureOwner = null; CHANGED.signalAll(); return null; }
                    remaining = CHANGED.awaitNanos(remaining);
                }
                return new Capture();
            } catch (InterruptedException | RuntimeException e) {
                capturing = false; captureOwner = null; CHANGED.signalAll(); throw e;
            }
        } finally { LOCK.unlock(); }
    }

    public final class Capture implements AutoCloseable {
        private boolean closed;
        private Capture() { }
        @Override public void close() {
            LOCK.lock();
            try { if (!closed) { closed = true; capturing = false; captureOwner = null; CHANGED.signalAll(); } }
            finally { LOCK.unlock(); }
        }
    }
}
