package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.optimization.BatchTransfer;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

/** Unknown external delivery consumes the reserved withdrawal and blocks replay until reconciliation. */
public final class ForgeBatchExport {
    private ForgeBatchExport() { }
    public static BatchTransfer.Result move(DigitalStorageRecord record, ItemKey key, long maximum,
            IItemHandler target, int slot, UUID owner, UUID volume, ForgeTransferRecovery recovery, Runnable flush) {
        if (!recovery.available() || recovery.inFlightCount() != 0) return new BatchTransfer.Result(0, "recovery_required");
        int offered = (int) Math.min(maximum, Math.min(record.storage().amountOf(key), key.maximumStackSize()));
        if (offered <= 0) return BatchTransfer.Result.empty();
        var probe = target.insertItem(slot, key.toStack(offered), true);
        check(probe, key, offered);
        int accepted = offered - probe.getCount();
        if (accepted <= 0) return BatchTransfer.Result.empty();
        UUID receipt = null, delivery = null;
        long moved = 0;
        try (var transaction = LedgerTransaction.open()) {
            var withdrawal = record.storage().reserveExtraction(key, accepted, transaction);
            if (withdrawal.amount() != accepted) return BatchTransfer.Result.empty();
            var held = recovery.holdWithdrawal(owner, volume, key, accepted);
            receipt = held.id();
            delivery = held.deliveryId();
            try {
                var remainder = target.insertItem(slot, key.toStack(accepted), false);
                check(remainder, key, accepted);
                moved = accepted - remainder.getCount();
            } catch (RuntimeException failure) {
                // Never restore a possibly accepted external offer. The persisted
                // DELIVERING receipt remains inaccessible to automatic replay.
                withdrawal.settle(accepted);
                transaction.commit();
                return new BatchTransfer.Result(0, "recovery_required");
            }
            withdrawal.settle(moved);
            transaction.commit();
        } catch (RuntimeException failure) {
            if (receipt != null) return new BatchTransfer.Result(0, "recovery_required");
            throw failure;
        }
        try {
            flush.run();
            recovery.finishWithdrawal(receipt, owner, delivery, moved);
            return new BatchTransfer.Result(moved, "");
        } catch (RuntimeException failure) { return new BatchTransfer.Result(moved, "recovery_required"); }
    }
    private static void check(ItemStack remainder, ItemKey key, int offered) {
        if (remainder == null || !remainder.isEmpty() && (remainder.getCount() < 0 || remainder.getCount() > offered
                || !ItemKey.of(remainder).equals(key))) throw new IllegalStateException("Invalid export remainder");
    }
}
