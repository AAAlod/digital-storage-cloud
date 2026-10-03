package dev.kehai.digitalstorage.storage;

/** Incremental reversible state, independent of the caller's transaction implementation. */
public interface MutationParticipant<S> {
    S snapshot();

    void restore(S snapshot);

    void committed();

    /** Allows adapters to discard cached bridges once a ledger entry is detached. */
    default boolean isAttached() {
        return true;
    }
}
