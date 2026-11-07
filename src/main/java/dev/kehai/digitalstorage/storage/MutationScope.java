package dev.kehai.digitalstorage.storage;

/** Enlists each changed participant before mutation; the caller owns commit/rollback. */
public interface MutationScope {
    TransactionId transactionId();

    default TransactionId parentTransactionId() { return null; }

    /** Logical transaction chain, outermost first. */
    java.util.List<TransactionId> transactionPath();

    /** Enlist the outer transaction before touching a particular storage root. */
    default void enter(TransactionRegistry registry) {
        if (registry == null) return;
        TransactionId outer = transactionPath().get(0);
        if (registry.enter(outer)) {
            try { onCompletion(outer, ignored -> registry.leave(outer)); }
            catch (RuntimeException exception) { registry.leave(outer); throw exception; }
        }
    }

    /** Register a completion observer without changing participant order. */
    void onCompletion(TransactionId transaction, java.util.function.Consumer<Boolean> observer);

    <S> void enlist(MutationParticipant<S> participant);
}
