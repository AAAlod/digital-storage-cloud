package dev.kehai.digitalstorage.storage;

import java.util.concurrent.atomic.AtomicLong;

/** Immutable identity for one logical transaction, independent of adapter scope reuse. */
public final class TransactionId {
    private static final AtomicLong NEXT = new AtomicLong();
    private static final ThreadLocal<TransactionId> CALLBACK_CONTEXT = new ThreadLocal<>();
    private final long value = NEXT.incrementAndGet();

    public TransactionId() { }

    static TransactionId callbackContext() { return CALLBACK_CONTEXT.get(); }

    static void runCallback(TransactionId id, Runnable callback) {
        TransactionId previous = CALLBACK_CONTEXT.get();
        if (id == null) CALLBACK_CONTEXT.remove(); else CALLBACK_CONTEXT.set(id);
        try { callback.run(); }
        finally { if (previous == null) CALLBACK_CONTEXT.remove(); else CALLBACK_CONTEXT.set(previous); }
    }

    @Override public String toString() { return "transaction-" + value; }
}
