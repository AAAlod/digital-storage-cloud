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
import net.minecraft.nbt.CompoundTag;
import dev.kehai.digitalstorage.storage.ItemKeyCodec;

/** Forge simulation reserves capacity, but only an actual returned stack can settle into DSC.
 * Not connected to player migration until session incident persistence/lifecycle is installed.
 */
public final class ForgeInventoryTransferExecutor implements InventoryTransferExecutor {
    private final UUID owner;
    private final UUID volume;
    private final ForgeTransferRecovery recovery;
    private final java.util.function.Consumer<Incident> incidentSink;
    private final java.util.function.BooleanSupplier sessionAvailable;
    private final Origin origin;
    private final List<Incident> incidents = new ArrayList<>();
    private boolean halted;

    public ForgeInventoryTransferExecutor(UUID owner, UUID volume, ForgeTransferRecovery recovery) {
        this(owner, volume, recovery, incident -> { });
    }
    public ForgeInventoryTransferExecutor(UUID owner, UUID volume, ForgeTransferRecovery recovery,
                                         java.util.function.Consumer<Incident> incidentSink) {
        this(owner, volume, recovery, incidentSink, () -> true);
    }
    public ForgeInventoryTransferExecutor(UUID owner, UUID volume, ForgeTransferRecovery recovery,
                                         java.util.function.Consumer<Incident> incidentSink,
                                         java.util.function.BooleanSupplier sessionAvailable) {
        this(owner, volume, recovery, incidentSink, sessionAvailable, Origin.unknown());
    }
    public ForgeInventoryTransferExecutor(UUID owner, UUID volume, ForgeTransferRecovery recovery,
                                         java.util.function.Consumer<Incident> incidentSink,
                                         java.util.function.BooleanSupplier sessionAvailable, Origin origin) {
        this.owner = java.util.Objects.requireNonNull(owner);
        this.volume = java.util.Objects.requireNonNull(volume);
        this.recovery = java.util.Objects.requireNonNull(recovery);
        this.incidentSink = java.util.Objects.requireNonNull(incidentSink);
        this.sessionAvailable = java.util.Objects.requireNonNull(sessionAvailable);
        this.origin = java.util.Objects.requireNonNull(origin);
    }

    public List<Incident> incidents() { return List.copyOf(incidents); }
    public boolean halted() { return halted; }

    @Override public MoveResult move(InventoryEndpoint.View source, VolumeLedger target, ItemKey resource, long maximum) {
        if (halted || !recovery.available() || !sessionAvailable.getAsBoolean()) {
            return new MoveResult(0, 0, "transfer recovery requires attention");
        }
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
        Attempt attempt = new Attempt(resource, maximum);
        try {
            if (ForgeTomEndpoints.underlyingDigital(view.handler()) != null) {
                return stop(view, 0, operations, "digital inventory cannot be a physical migration source", attempt);
            }
            ItemStack current = view.handler().getStackInSlot(view.slot());
            if (current.isEmpty() || !ItemKey.of(current).equals(resource)) return new MoveResult(0, operations);
            attempt.observed = current.getCount();
            int requested = (int) Math.min(maximum, Math.min(current.getCount(), current.getMaxStackSize()));
            attempt.requested = requested;
            if (requested <= 0) return new MoveResult(0, operations);
            operations++;
            attempt.stage = Stage.SIMULATION;
            ItemStack simulated = view.handler().extractItem(view.slot(), requested, true);
            if (simulated.isEmpty()) return new MoveResult(0, operations);
            if (simulated.getCount() > requested || !ItemKey.of(simulated).equals(resource)) {
                return stop(view, 0, operations, "source simulation changed quantity or identity", attempt);
            }
            attempt.stage = Stage.RESERVATION;
            transaction = LedgerTransaction.open();
            long reserved = target.insert(resource, simulated.getCount(), transaction);
            attempt.reserved = reserved;
            if (reserved <= 0) return new MoveResult(0, operations);
            operations++;
            attempt.stage = Stage.EXTRACTION;
            attempt.actualStarted = true;
            // Assign before invoking any capability serializer: this instance is
            // the only proof of ownership, even if the handler behaved incorrectly.
            returned = java.util.Objects.requireNonNull(
                    view.handler().extractItem(view.slot(), (int) reserved, false), "extraction returned null");
            attempt.stage = Stage.SETTLEMENT;
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
            return stop(view, committed ? settled : 0, operations, failure, attempt);
        }
        if (!committed) return new MoveResult(0, operations);
        try {
            attempt.stage = Stage.INSPECTION;
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
                        return stop(view, settled, operations, "settled source simulation changed quantity or identity", attempt);
                    }
                }
            }
            return new MoveResult(settled, operations, "", revisit);
        } catch (RuntimeException exception) {
            return stop(view, settled, operations, "settled source inspection failed: " + exception.getClass().getSimpleName(), attempt);
        }
    }

    private MoveResult stop(ForgeInventoryEndpoint.View source, long settled, int operations, String reason, Attempt attempt) {
        halted = true;
        // Observations are not held items. In particular, a throwing handler may
        // have sent items elsewhere; no stack is fabricated from a source delta.
        var incident = new Incident(owner, volume, source.handler().getClass().getName(), source.slot(),
                settled, System.currentTimeMillis(), reason, origin, attempt.snapshot());
        incidents.add(incident);
        try { incidentSink.accept(incident); }
        catch (RuntimeException failure) { reason += "; incident retained unsaved"; }
        return new MoveResult(settled, operations, reason);
    }

    private static final class Attempt {
        private final CompoundTag expected;
        private final long maximum;
        private long observed;
        private long requested;
        private long reserved;
        private boolean actualStarted;
        private Stage stage = Stage.PREFLIGHT;
        private Attempt(ItemKey key, long maximum) { expected = ItemKeyCodec.write(key); this.maximum = maximum; }
        private Observation snapshot() { return new Observation(true, expected, maximum, observed, requested, reserved, actualStarted, stage); }
    }
    public enum Stage { UNKNOWN, PREFLIGHT, SIMULATION, RESERVATION, EXTRACTION, SETTLEMENT, INSPECTION }
    /** World/network entry positions, not an assertion that the physical source is at either position. */
    public record Origin(boolean known, String dimension, long accessorPosition, long connectorPosition) {
        public Origin {
            java.util.Objects.requireNonNull(dimension);
            if (dimension.length() > 256 || (known ? dimension.isBlank() : !dimension.isEmpty() || accessorPosition != 0 || connectorPosition != 0)) {
                throw new IllegalArgumentException("Invalid transfer origin");
            }
        }
        public static Origin unknown() { return new Origin(false, "", 0, 0); }
    }
    /** Expected identity and requested amounts are observations, never owned recovery quantities. */
    public record Observation(boolean known, CompoundTag expectedVariant, long maximum, long observed, long requested,
                              long reserved, boolean actualStarted, Stage stage) {
        public Observation {
            expectedVariant = java.util.Objects.requireNonNull(expectedVariant).copy();
            java.util.Objects.requireNonNull(stage);
            if (maximum < 0 || observed < 0 || requested < 0 || reserved < 0 || reserved > requested || requested > maximum
                    || (known ? stage == Stage.UNKNOWN || !expectedVariant.contains("item", net.minecraft.nbt.Tag.TAG_STRING)
                    || (actualStarted && reserved == 0) : stage != Stage.UNKNOWN || !expectedVariant.isEmpty()
                    || maximum != 0 || observed != 0 || requested != 0 || reserved != 0 || actualStarted)) {
                throw new IllegalArgumentException("Invalid transfer observation");
            }
        }
        @Override public CompoundTag expectedVariant() { return expectedVariant.copy(); }
        public static Observation unknown() { return new Observation(false, new CompoundTag(), 0, 0, 0, 0, false, Stage.UNKNOWN); }
    }
    public record Incident(UUID owner, UUID volume, String handlerClass, int slot,
                           long settled, long createdMillis, String reason, Origin origin, Observation observation) {
        public Incident(UUID owner, UUID volume, String handlerClass, int slot, long settled, long createdMillis, String reason) {
            this(owner, volume, handlerClass, slot, settled, createdMillis, reason, Origin.unknown(), Observation.unknown());
        }
        public Incident { java.util.Objects.requireNonNull(origin); java.util.Objects.requireNonNull(observation); }
    }
}
