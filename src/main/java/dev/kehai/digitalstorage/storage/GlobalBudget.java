package dev.kehai.digitalstorage.storage;

import com.mojang.logging.LogUtils;
import java.util.IdentityHashMap;
import java.util.Map;
import org.slf4j.Logger;

/** Shared transaction-delta accounting for variant identity NBT bytes. */
public final class GlobalBudget {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final Map<TransactionId, BudgetTx> open = new IdentityHashMap<>();
    private final java.util.function.LongSupplier maxBytes;
    private long committedBytes;
    private boolean failed;

    private final class BudgetTx implements MutationParticipant<Long> {
        final TransactionId parent;
        final java.util.List<java.util.function.LongSupplier> commitReleases = new java.util.ArrayList<>();
        long delta;
        long admittedLimit;
        BudgetTx(TransactionId parent) { this.parent = parent; }
        @Override public Long snapshot() { synchronized (GlobalBudget.this) { return delta; } }
        @Override public void restore(Long previous) { synchronized (GlobalBudget.this) { delta = previous; } }
        @Override public void committed() { /* Accounting is finalized by the transaction close observer. */ }
    }

    public GlobalBudget(long committedBytes, long maxBytes) {
        if (committedBytes < 0 || maxBytes < 0) throw new IllegalArgumentException("Negative budget");
        this.committedBytes = committedBytes;
        this.maxBytes = () -> maxBytes;
    }

    public GlobalBudget(long committedBytes, java.util.function.LongSupplier maxBytes) {
        if (committedBytes < 0) throw new IllegalArgumentException("Negative budget");
        this.committedBytes = committedBytes;
        this.maxBytes = java.util.Objects.requireNonNull(maxBytes);
    }

    public synchronized long committedBytes() { return committedBytes; }
    public long maxBytes() { return maxBytes.getAsLong(); }
    public synchronized boolean failed() { return failed; }

    /** Attach already persisted data without enforcing limits on legacy content. */
    public synchronized void addLoaded(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative loaded bytes");
        try { committedBytes = Math.addExact(committedBytes, bytes); }
        catch (ArithmeticException e) { fail("Loaded budget overflow", e); throw e; }
    }

    public synchronized boolean tryReserve(long bytes, MutationScope scope) {
        if (bytes < 0) throw new IllegalArgumentException("Negative reservation");
        if (failed) return false;
        BudgetTx own = scope == null ? null : enlist(scope);
        try {
            long projected = Math.addExact(committedBytes, own == null ? 0 : own.delta);
            for (BudgetTx tx : open.values()) if (tx != own) projected = Math.addExact(projected, Math.max(0, tx.delta));
            long limit = maxBytes();
            if (limit < 0 || projected > limit || bytes > limit - projected) return false;
            if (own == null) committedBytes = Math.addExact(committedBytes, bytes);
            else {
                own.delta = Math.addExact(own.delta, bytes);
                own.admittedLimit = Math.max(own.admittedLimit, limit);
            }
            return true;
        } catch (ArithmeticException e) { fail("Budget arithmetic overflow", e); return false; }
    }

    public synchronized void release(long bytes, MutationScope scope) {
        if (bytes < 0) throw new IllegalArgumentException("Negative release");
        try {
            if (scope == null) {
                long next = Math.subtractExact(committedBytes, bytes);
                if (next < 0) throw new IllegalStateException("Negative committed budget");
                committedBytes = next;
            } else {
                BudgetTx tx = enlist(scope);
                tx.delta = Math.subtractExact(tx.delta, bytes);
            }
        } catch (ArithmeticException | IllegalStateException e) { fail("Budget release failed", e); throw e; }
    }

    /** Finalize pending withdrawal charges before applying the same transaction's delta. */
    synchronized void releaseOnCommit(MutationScope scope, java.util.function.LongSupplier release) {
        enlist(scope).commitReleases.add(java.util.Objects.requireNonNull(release));
    }

    private BudgetTx enlist(MutationScope scope) {
        TransactionId parent = null;
        for (TransactionId id : scope.transactionPath()) {
            BudgetTx tx = open.get(id);
            if (tx == null) {
                tx = new BudgetTx(parent);
                open.put(id, tx);
                try { scope.onCompletion(id, committed -> complete(id, committed)); }
                catch (RuntimeException e) { open.remove(id); throw e; }
            } else if (tx.parent != parent) throw new IllegalStateException("Transaction parent changed");
            parent = id;
        }
        BudgetTx result = open.get(scope.transactionId());
        if (result == null) throw new IllegalStateException("Transaction path omitted current transaction");
        scope.enlist(result);
        return result;
    }

    private synchronized void complete(TransactionId id, boolean commit) {
        BudgetTx tx = open.remove(id);
        if (tx == null || !commit) return;
        try {
            for (var release : tx.commitReleases) {
                long bytes = release.getAsLong();
                if (bytes < 0) throw new IllegalStateException("Negative withdrawal release");
                tx.delta = Math.subtractExact(tx.delta, bytes);
            }
            if (tx.parent != null) {
                BudgetTx parent = open.get(tx.parent);
                if (parent == null) throw new IllegalStateException("Parent budget transaction closed first");
                parent.delta = Math.addExact(parent.delta, tx.delta);
                parent.admittedLimit = Math.max(parent.admittedLimit, tx.admittedLimit);
            } else {
                long next = Math.addExact(committedBytes, tx.delta);
                if (next < 0) throw new IllegalStateException("Committed budget outside safe bounds");
                committedBytes = next;
                long currentLimit = maxBytes();
                if (next > currentLimit && tx.delta > 0 && currentLimit >= tx.admittedLimit) {
                    throw new IllegalStateException("Committed budget exceeds limit");
                }
            }
        } catch (ArithmeticException | IllegalStateException e) { fail("Budget transaction completion failed", e); }
    }

    private void fail(String message, RuntimeException exception) {
        failed = true;
        LOGGER.error("{}; budget is now read-only", message, exception);
    }
}
