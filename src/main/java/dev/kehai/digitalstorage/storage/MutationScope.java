package dev.kehai.digitalstorage.storage;

/** Enlists each changed participant before mutation; the caller owns commit/rollback. */
public interface MutationScope {
    <S> void enlist(MutationParticipant<S> participant);
}
