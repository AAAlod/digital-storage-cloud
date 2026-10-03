package dev.kehai.digitalstorage.storage;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.minecraft.nbt.NbtCompound;

public final class VolumeLedger implements Iterable<VolumeLedger.View> {
    /**
     * Intentional safety ceiling for one exact item variant. Keeping this at the
     * signed 32-bit maximum bounds aggregate volume totals and avoids exposing
     * extreme long values to integrations. Stored over-limit data is preserved
     * and remains extractable; only new insertion is capped.
     */
    public static final long MAX_AMOUNT_PER_VARIANT = Integer.MAX_VALUE;

    private final Map<ItemKey, Entry> entries = new HashMap<>();
    private final UUID volumeId;
    private final Runnable dirtyCallback;
    private final IntSupplier variantCapacitySupplier;
    private final LongSupplier variantNbtBudgetSupplier;
    private final Predicate<ItemKey> insertValidator;
    private final Predicate<ItemKey> newVariantValidator;
    private final MetricsParticipant metricsParticipant = new MetricsParticipant();
    private int variantCount;
    private long totalItemCount;
    private long totalVariantNbtBytes;
    private long contentVersion;
    // Unlike committed contentVersion, this also tracks provisional insert/rollback.
    // A restored key set does not restore a HashMap iterator's modCount.
    private long structureVersion;

    public VolumeLedger(Runnable dirtyCallback, int variantCapacity) {
        this(null, dirtyCallback, () -> variantCapacity, () -> Long.MAX_VALUE, variant -> true, variant -> true);
    }

    public VolumeLedger(UUID volumeId, Runnable dirtyCallback, int variantCapacity) {
        this(volumeId, dirtyCallback, () -> variantCapacity, () -> Long.MAX_VALUE, variant -> true, variant -> true);
    }

    public VolumeLedger(Runnable dirtyCallback, IntSupplier variantCapacitySupplier) {
        this(null, dirtyCallback, variantCapacitySupplier, () -> Long.MAX_VALUE);
    }

    public VolumeLedger(
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            LongSupplier variantNbtBudgetSupplier
    ) {
        this(null, dirtyCallback, variantCapacitySupplier, variantNbtBudgetSupplier);
    }

    public VolumeLedger(
            UUID volumeId,
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            LongSupplier variantNbtBudgetSupplier
    ) {
        this(
                volumeId,
                dirtyCallback,
                variantCapacitySupplier,
                variantNbtBudgetSupplier,
                dev.kehai.digitalstorage.security.ItemSecurityPolicy::allowsInsert,
                dev.kehai.digitalstorage.security.ItemSecurityPolicy::allowsNewVariant
        );
    }

    public VolumeLedger(
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            Predicate<ItemKey> newVariantValidator
    ) {
        this(null, dirtyCallback, variantCapacitySupplier, () -> Long.MAX_VALUE, variant -> true, newVariantValidator);
    }

    public VolumeLedger(
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            Predicate<ItemKey> insertValidator,
            Predicate<ItemKey> newVariantValidator
    ) {
        this(null, dirtyCallback, variantCapacitySupplier, () -> Long.MAX_VALUE, insertValidator, newVariantValidator);
    }

    public VolumeLedger(
            UUID volumeId,
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            LongSupplier variantNbtBudgetSupplier,
            Predicate<ItemKey> insertValidator,
            Predicate<ItemKey> newVariantValidator
    ) {
        this.volumeId = volumeId;
        this.dirtyCallback = dirtyCallback;
        this.variantCapacitySupplier = variantCapacitySupplier;
        this.variantNbtBudgetSupplier = variantNbtBudgetSupplier;
        this.insertValidator = insertValidator;
        this.newVariantValidator = newVariantValidator;
    }

    public void load(ItemKey resource, long amount) {
        load(resource, amount, ItemKeyCodec.write(resource));
    }

    void load(ItemKey resource, long amount, NbtCompound serializedVariant) {
        if (resource.isBlank() || amount <= 0) {
            return;
        }

        Entry existing = entries.get(resource);
        if (existing == null) {
            NbtCompound storedVariant = serializedVariant.copy();
            int variantNbtBytes = storedVariant.getSizeInBytes();
            long newVariantNbtBytes = checkedVariantNbtTotal(variantNbtBytes);
            long newItemCount = checkedAdd(totalItemCount, amount);
            entries.put(resource, new Entry(resource, amount, storedVariant, variantNbtBytes));
            structureVersion++;
            variantCount++;
            totalItemCount = newItemCount;
            totalVariantNbtBytes = newVariantNbtBytes;
            return;
        }

        try {
            long mergedAmount = Math.addExact(existing.amount, amount);
            long newItemCount = checkedAdd(totalItemCount, amount);
            existing.amount = mergedAmount;
            totalItemCount = newItemCount;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Duplicate stored variant amount exceeds the long range", exception);
        }
    }

    public long insert(ItemKey resource, long maxAmount, MutationScope mutation) {
        if (resource.isBlank() || maxAmount <= 0 || !insertValidator.test(resource)) {
            return 0;
        }

        Entry entry = entries.get(resource);
        boolean newEntry = entry == null;
        if (entry == null) {
            if (variantCount >= variantCapacitySupplier.getAsInt() || !newVariantValidator.test(resource)) {
                return 0;
            }
            NbtCompound serializedVariant = ItemKeyCodec.write(resource);
            int variantNbtBytes = serializedVariant.getSizeInBytes();
            if (!isVariantNbtWithinBudget(variantNbtBytes)) {
                return 0;
            }
            entry = new Entry(resource, 0, serializedVariant, variantNbtBytes);
        }

        long availableSpace = Math.max(0, MAX_AMOUNT_PER_VARIANT - entry.amount);
        long inserted = Math.min(maxAmount, availableSpace);
        if (inserted <= 0) {
            return 0;
        }
        long newItemCount = checkedAdd(totalItemCount, inserted);
        long newNbtBytes = entry.amount == 0
                ? Math.addExact(totalVariantNbtBytes, entry.variantNbtBytes)
                : totalVariantNbtBytes;

        mutation.enlist(entry);
        mutation.enlist(metricsParticipant);
        if (newEntry) {
            entries.put(resource, entry);
            structureVersion++;
        }
        if (entry.amount == 0) {
            variantCount++;
        }
        totalVariantNbtBytes = newNbtBytes;
        entry.amount += inserted;
        totalItemCount = newItemCount;
        return inserted;
    }

    public long extract(ItemKey resource, long maxAmount, MutationScope mutation) {
        if (resource.isBlank() || maxAmount <= 0) {
            return 0;
        }

        Entry entry = entries.get(resource);
        if (entry == null || entry.amount <= 0) {
            return 0;
        }

        long extracted = Math.min(maxAmount, entry.amount);
        mutation.enlist(entry);
        mutation.enlist(metricsParticipant);
        entry.amount -= extracted;
        totalItemCount = Math.subtractExact(totalItemCount, extracted);
        if (entry.amount == 0) {
            variantCount--;
            totalVariantNbtBytes = Math.subtractExact(totalVariantNbtBytes, entry.variantNbtBytes);
        }
        return extracted;
    }

    @Override
    public Iterator<View> iterator() {
        return new NonEmptyViewIterator(entries.values().iterator());
    }

    public int variantCount() {
        return variantCount;
    }

    public Optional<UUID> volumeId() {
        return Optional.ofNullable(volumeId);
    }

    public long totalItemCount() {
        return totalItemCount;
    }

    private static long checkedAdd(long current, long amount) {
        try {
            return Math.addExact(current, amount);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Stored item total exceeds the long range", exception);
        }
    }

    private boolean isVariantNbtWithinBudget(int addedBytes) {
        long maximum = variantNbtBudgetSupplier.getAsLong();
        return addedBytes <= maximum && totalVariantNbtBytes <= maximum - addedBytes;
    }

    private long checkedVariantNbtTotal(int addedBytes) {
        if (!isVariantNbtWithinBudget(addedBytes)) {
            throw new IllegalArgumentException("Stored variant NBT exceeds the per-volume hard limit");
        }
        return Math.addExact(totalVariantNbtBytes, addedBytes);
    }

    public long amountOf(ItemKey variant) {
        Entry entry = entries.get(variant);
        return entry == null ? 0 : entry.amount;
    }

    public long contentVersion() {
        return contentVersion;
    }

    public long totalVariantNbtBytes() {
        return totalVariantNbtBytes;
    }

    public void forEach(LongEntryConsumer consumer) {
        entries.forEach((variant, entry) -> {
            if (entry.amount > 0) {
                consumer.accept(variant, entry.amount);
            }
        });
    }

    public List<StoredEntrySnapshot> snapshotEntries() {
        List<StoredEntrySnapshot> snapshots = new ArrayList<>(variantCount);
        entries.values().forEach(entry -> {
            if (entry.amount > 0) {
                snapshots.add(new StoredEntrySnapshot(entry.serializedVariant, entry.amount));
            }
        });
        return List.copyOf(snapshots);
    }

    public SnapshotCursor snapshotCursor() {
        return new SnapshotCursor();
    }

    public final class SnapshotCursor {
        private Iterator<Entry> iterator;
        private List<StoredEntrySnapshot> snapshots;
        private long expectedVersion = Long.MIN_VALUE;
        private long expectedStructureVersion;
        private boolean complete;

        public CursorProgress advance(int maximumEntries) {
            if (maximumEntries <= 0) {
                return new CursorProgress(false, false, 0, List.of());
            }
            boolean invalidated = expectedVersion != contentVersion || expectedStructureVersion != structureVersion;
            boolean restarted = iterator != null && invalidated;
            if (iterator == null || invalidated) {
                expectedVersion = contentVersion;
                expectedStructureVersion = structureVersion;
                iterator = entries.values().iterator();
                snapshots = new ArrayList<>(variantCount);
                complete = false;
            }
            if (complete) {
                return new CursorProgress(true, restarted, 0, snapshots);
            }

            int examined = 0;
            try {
                while (examined < maximumEntries && iterator.hasNext()) {
                    Entry entry = iterator.next();
                    examined++;
                    if (entry.amount > 0) {
                        snapshots.add(new StoredEntrySnapshot(entry.serializedVariant, entry.amount));
                    }
                }
                if (!iterator.hasNext()) {
                    snapshots = List.copyOf(snapshots);
                    complete = true;
                }
            } catch (ConcurrentModificationException exception) {
                // Last-resort protection for an untracked structural mutation. Never
                // publish partial data or retry unbounded work in the same tick.
                iterator = null;
                snapshots = null;
                complete = false;
                return new CursorProgress(false, true, examined, List.of());
            }
            return new CursorProgress(complete, restarted, examined, complete ? snapshots : List.of());
        }
    }

    private final class Entry implements MutationParticipant<Long>, View {
        private final ItemKey resource;
        private final NbtCompound serializedVariant;
        private final int variantNbtBytes;
        private long amount;

        private Entry(ItemKey resource, long amount, NbtCompound serializedVariant, int variantNbtBytes) {
            this.resource = resource;
            this.amount = amount;
            this.serializedVariant = serializedVariant;
            this.variantNbtBytes = variantNbtBytes;
        }

        @Override
        public Long snapshot() {
            return amount;
        }

        @Override
        public void restore(Long snapshot) {
            amount = snapshot;
            if (amount == 0 && entries.remove(resource, this)) {
                structureVersion++;
            }
        }

        @Override
        public void committed() {
            if (amount == 0 && entries.remove(resource, this)) {
                structureVersion++;
            }
            dirtyCallback.run();
        }

        @Override
        public boolean isResourceBlank() {
            return false;
        }

        @Override
        public ItemKey getResource() {
            return resource;
        }

        @Override
        public long getAmount() {
            return amount;
        }

        @Override
        public long getCapacity() {
            return MAX_AMOUNT_PER_VARIANT;
        }

        @Override
        public boolean isAttached() {
            return entries.get(resource) == this;
        }
    }

    private final class MetricsParticipant implements MutationParticipant<MetricsSnapshot> {
        @Override
        public MetricsSnapshot snapshot() {
            return new MetricsSnapshot(variantCount, totalItemCount, totalVariantNbtBytes);
        }

        @Override
        public void restore(MetricsSnapshot snapshot) {
            variantCount = snapshot.variantCount();
            totalItemCount = snapshot.totalItemCount();
            totalVariantNbtBytes = snapshot.totalVariantNbtBytes();
        }

        @Override
        public void committed() {
            contentVersion++;
        }
    }

    public record StoredEntrySnapshot(NbtCompound serializedVariant, long amount) {
    }

    public record CursorProgress(
            boolean complete,
            boolean restarted,
            int examinedEntries,
            List<StoredEntrySnapshot> snapshots
    ) {
    }

    private record MetricsSnapshot(int variantCount, long totalItemCount, long totalVariantNbtBytes) {
    }

    public interface View {
        boolean isResourceBlank();
        ItemKey getResource();
        long getAmount();
        long getCapacity();
    }

    private static final class NonEmptyViewIterator implements Iterator<View> {
        private final Iterator<? extends View> delegate;
        private View next;

        private NonEmptyViewIterator(Iterator<? extends View> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            while (next == null && delegate.hasNext()) {
                View candidate = delegate.next();
                if (candidate.getAmount() > 0) {
                    next = candidate;
                }
            }
            return next != null;
        }

        @Override
        public View next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            View result = next;
            next = null;
            return result;
        }
    }

    @FunctionalInterface
    public interface LongEntryConsumer {
        void accept(ItemKey variant, long amount);
    }
}
