package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.util.UUID;

/** Explicit owner delivery; independently persisted intent prevents automatic crash replay. */
public final class ForgeRecoveryDelivery {
    private ForgeRecoveryDelivery() { }
    public static Result deliver(DigitalStorageState state, ForgeTransferRecovery recovery,
                                 UUID owner, UUID id, long maximum) {
        var entry = recovery.ownedEntry(id, owner);
        var volume = state.volume(entry.volume()).orElseThrow(() -> new IllegalStateException("Recovery target volume is unavailable"));
        if (!state.ownsVolume(owner, entry.volume()) || !volume.ownerId().equals(owner)) {
            throw new IllegalArgumentException("Recovery target is not owned by this player");
        }
        return deliver(recovery, entry, owner, maximum, volume.record().storage(), state::flushNow);
    }

    static Result deliver(ForgeTransferRecovery recovery, ForgeTransferRecovery.Entry entry, UUID owner,
                          long maximum, VolumeLedger target, Runnable flushTarget) {
        if (!entry.owner().equals(owner) || target.volumeId().filter(entry.volume()::equals).isEmpty()) {
            throw new IllegalArgumentException("Recovery ownership or target does not match");
        }
        if (maximum <= 0 || entry.state() != ForgeTransferRecovery.State.HELD) {
            throw new IllegalStateException("Recovery entry is not eligible for delivery");
        }
        long accepted = 0;
        boolean intentAttempted = false;
        boolean commitRequested = false;
        UUID deliveryId = null;
        try {
            try (var transaction = LedgerTransaction.open()) {
                accepted = target.insert(entry.key(), Math.min(maximum, entry.amount()), transaction);
                if (accepted == 0) return new Result(State.BLOCKED, 0, entry.amount(), "target capacity or item policy rejected delivery");
                intentAttempted = true;
                var persisted = recovery.beginDelivery(entry.id(), owner, accepted);
                deliveryId = persisted.deliveryId();
                if (!persisted.volume().equals(entry.volume()) || !persisted.key().equals(entry.key())
                        || persisted.amount() != entry.amount()) {
                    throw new IllegalStateException("Recovery identity changed before delivery intent");
                }
                transaction.commit();
                commitRequested = true;
            }
            flushTarget.run();
            var finished = recovery.finishDelivery(entry.id(), owner, deliveryId);
            return new Result(finished.state() == ForgeTransferRecovery.State.DELIVERED ? State.DELIVERED : State.PARTIAL,
                    accepted, finished.state() == ForgeTransferRecovery.State.DELIVERED ? 0 : finished.amount(), "");
        } catch (RuntimeException failure) {
            if (!intentAttempted) throw failure;
            // No replay and no invented rollback of an independently persisted target.
            // Even a failed intent write may have left a .tmp or a replaced file.
            return new Result(State.UNCERTAIN, commitRequested ? accepted : 0, entry.amount(),
                    "delivery requires administrator reconciliation: " + failure.getClass().getSimpleName());
        }
    }
    public enum State { DELIVERED, PARTIAL, BLOCKED, UNCERTAIN }
    public record Result(State state, long settled, long remaining, String detail) { }
}
