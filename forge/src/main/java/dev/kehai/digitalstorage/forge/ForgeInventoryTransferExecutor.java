package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.forge.tom.ForgeTomEndpoints;
import dev.kehai.digitalstorage.optimization.InventoryEndpoint;
import dev.kehai.digitalstorage.optimization.InventoryTransferExecutor;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

/** Forge simulation reserves capacity, but only an actual returned stack can settle into DSC.
 * Not connected to player migration until session incident persistence/lifecycle is installed.
 */
public final class ForgeInventoryTransferExecutor implements InventoryTransferExecutor {
    private final UUID owner;
    private final UUID volume;
    private final ForgeTransferRecovery recovery;
    private final List<Incident> incidents = new ArrayList<>();
    private boolean halted;

    public ForgeInventoryTransferExecutor(UUID owner, UUID volume, ForgeTransferRecovery recovery) {
        this.owner = java.util.Objects.requireNonNull(owner);
        this.volume = java.util.Objects.requireNonNull(volume);
        this.recovery = java.util.Objects.requireNonNull(recovery);
    }

    public List<Incident> incidents() { return List.copyOf(incidents); }
    public boolean halted() { return halted; }

    @Override public MoveResult move(InventoryEndpoint.View source, VolumeLedger target, ItemKey resource, long maximum) {
        if (halted || !recovery.available()) return new MoveResult(0, 0, "transfer recovery requires attention");
        if (!(source instanceof ForgeInventoryEndpoint.View view)
                || target.volumeId().filter(volume::equals).isEmpty()) {
            throw new IllegalArgumentException("Forge transfer needs its own source view and target volume");
        }
        if (maximum <= 0 || resource.isBlank()) return new MoveResult(0, 0);
        int operations = 0;
        ItemStack returned = ItemStack.EMPTY;
        long settled = 0;
        boolean committed = false;
        String failure = "";
        LedgerTransaction transaction = null;
        try {
            if (ForgeTomEndpoints.underlyingDigital(view.handler()) != null) {
                return stop(view, 0, operations, "digital inventory cannot be a physical migration source");
            }
            ItemStack current = view.handler().getStackInSlot(view.slot());
            if (current.isEmpty() || !ItemKey.of(current).equals(resource)) return new MoveResult(0, operations);
            int requested = (int) Math.min(maximum, Math.min(current.getCount(), current.getMaxStackSize()));
            if (requested <= 0) return new MoveResult(0, operations);
            operations++;
            ItemStack simulated = view.handler().extractItem(view.slot(), requested, true);
            if (simulated.isEmpty()) return new MoveResult(0, operations);
            if (simulated.getCount() > requested || !ItemKey.of(simulated).equals(resource)) {
                return stop(view, 0, operations, "source simulation changed quantity or identity");
            }
            transaction = LedgerTransaction.open();
            long reserved = target.insert(resource, simulated.getCount(), transaction);
            if (reserved <= 0) return new MoveResult(0, operations);
            operations++;
            // Assign before invoking any capability serializer: this instance is
            // the only proof of ownership, even if the handler behaved incorrectly.
            returned = java.util.Objects.requireNonNull(
                    view.handler().extractItem(view.slot(), (int) reserved, false), "extraction returned null");
            if (!returned.isEmpty()) {
                if (returned.getCount() > reserved || !ItemKey.of(returned).equals(resource)) {
                    failure = "actual extraction changed quantity or identity";
                } else {
                    settled = returned.getCount();
                    long unused = reserved - settled;
                    if (unused > 0 && target.extract(resource, unused, transaction) != unused) {
                        throw new IllegalStateException("Target reservation could not be reduced");
                    }
                    transaction.commit();
                    // close invokes notifications after the ledger commit point.
                    // A failing notification does not make the returned items unowned.
                    committed = true;
                }
            }
        } catch (RuntimeException exception) {
            failure = "transfer callback failed: " + exception.getClass().getSimpleName();
        } finally {
            if (transaction != null) {
                try { transaction.close(); }
                catch (RuntimeException exception) {
                    failure = (committed ? "committed target notification failed: " : "target rollback failed: ")
                            + exception.getClass().getSimpleName();
                }
            }
        }
        if (!failure.isEmpty()) {
            if (!committed && !returned.isEmpty()) {
                // Reservation is closed before capturing a third-party capability.
                // Recovery takes the original instance even when serialization fails.
                try { recovery.holdReturnedStack(owner, volume, returned, failure); }
                catch (RuntimeException exception) { failure += "; recovery retained unsaved ownership"; }
            }
            return stop(view, committed ? settled : 0, operations, failure);
        }
        if (!committed) return new MoveResult(0, operations);
        try {
            ItemStack remaining = view.handler().getStackInSlot(view.slot());
            boolean revisit = !remaining.isEmpty() && ItemKey.of(remaining).equals(resource);
            if (revisit) {
                // A retained keep-last item is visible but not extractable. Use
                // the same original wrapper rather than bypassing its filter.
                int requested = Math.min(remaining.getCount(), remaining.getMaxStackSize());
                if (requested <= 0) revisit = false;
                else {
                    operations++;
                    ItemStack probe = view.handler().extractItem(view.slot(), requested, true);
                    revisit = !probe.isEmpty();
                    if (revisit && (probe.getCount() > requested || !ItemKey.of(probe).equals(resource))) {
                        return stop(view, settled, operations, "settled source simulation changed quantity or identity");
                    }
                }
            }
            return new MoveResult(settled, operations, "", revisit);
        } catch (RuntimeException exception) {
            return stop(view, settled, operations, "settled source inspection failed: " + exception.getClass().getSimpleName());
        }
    }

    private MoveResult stop(ForgeInventoryEndpoint.View source, long settled, int operations, String reason) {
        halted = true;
        // Observations are not held items. In particular, a throwing handler may
        // have sent items elsewhere; no stack is fabricated from a source delta.
        incidents.add(new Incident(owner, volume, source.handler().getClass().getName(), source.slot(),
                settled, System.currentTimeMillis(), reason));
        return new MoveResult(settled, operations, reason);
    }

    public record Incident(UUID owner, UUID volume, String handlerClass, int slot,
                           long settled, long createdMillis, String reason) { }
}
