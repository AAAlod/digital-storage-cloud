package dev.kehai.digitalstorage.storage;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Local ledger transaction. External inventories require their own platform transaction contract. */
public final class LedgerTransaction implements MutationScope, AutoCloseable {
    private static final ThreadLocal<LedgerTransaction> CURRENT = new ThreadLocal<>();
    private final LedgerTransaction parent;
    private final Thread owner;
    private final Map<MutationParticipant<?>, Saved<?>> snapshots = new IdentityHashMap<>();
    private final List<Saved<?>> order = new ArrayList<>();
    private LedgerTransaction child;
    private boolean commitRequested;
    private boolean closed;

    private LedgerTransaction(LedgerTransaction parent) {
        this.parent = parent;
        this.owner = Thread.currentThread();
    }

    public static LedgerTransaction open() {
        if (CURRENT.get() != null) {
            throw new IllegalStateException("An outer ledger transaction is already open; use openNested");
        }
        LedgerTransaction transaction = new LedgerTransaction(null);
        CURRENT.set(transaction);
        return transaction;
    }

    public LedgerTransaction openNested() {
        checkMutable();
        child = new LedgerTransaction(this);
        CURRENT.set(child);
        return child;
    }

    @Override
    public <S> void enlist(MutationParticipant<S> participant) {
        checkMutable();
        if (!snapshots.containsKey(participant)) {
            Saved<S> saved = new Saved<>(participant, Objects.requireNonNull(participant.snapshot(), "snapshot"));
            snapshots.put(participant, saved);
            order.add(saved);
        }
    }

    public void commit() {
        checkMutable();
        commitRequested = true;
    }

    private void checkMutable() {
        checkOwner();
        if (closed || commitRequested || child != null || CURRENT.get() != this) {
            throw new IllegalStateException("Ledger transaction is closed, committed or has an open child");
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Ledger transactions must stay on their owning thread");
        }
    }

    @Override
    public void close() {
        checkOwner();
        if (closed) {
            return;
        }
        if (child != null) {
            throw new IllegalStateException("Close the nested ledger transaction before its parent");
        }
        closed = true;
        RuntimeException failure = null;
        try {
            if (commitRequested && parent != null) {
                for (Saved<?> saved : order) {
                    if (!parent.snapshots.containsKey(saved.participant())) {
                        parent.snapshots.put(saved.participant(), saved);
                        parent.order.add(saved);
                    }
                }
            } else {
                for (int index = order.size() - 1; index >= 0; index--) {
                    Saved<?> saved = order.get(index);
                    try {
                        if (commitRequested) {
                            saved.participant().committed();
                        } else {
                            saved.restore();
                        }
                    } catch (RuntimeException exception) {
                        if (failure == null) {
                            failure = exception;
                        } else if (failure != exception) {
                            failure.addSuppressed(exception);
                        }
                    }
                }
            }
        } finally {
            snapshots.clear();
            order.clear();
            if (parent != null) {
                parent.child = null;
                CURRENT.set(parent);
            } else {
                CURRENT.remove();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record Saved<S>(MutationParticipant<S> participant, S snapshot) {
        void restore() {
            participant.restore(snapshot);
        }
    }
}
