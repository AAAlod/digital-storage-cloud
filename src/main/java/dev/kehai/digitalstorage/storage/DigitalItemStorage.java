package dev.kehai.digitalstorage.storage;

import java.util.ArrayList;
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
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.nbt.NbtCompound;

public final class DigitalItemStorage implements Storage<ItemVariant> {
    /**
     * Intentional safety ceiling for one exact item variant. Keeping this at the
     * signed 32-bit maximum bounds aggregate volume totals and avoids exposing
     * extreme long values to integrations. Stored over-limit data is preserved
     * and remains extractable; only new insertion is capped.
     */
    public static final long MAX_AMOUNT_PER_VARIANT = Integer.MAX_VALUE;

    private final Map<ItemVariant, Entry> entries = new HashMap<>();
    private final UUID volumeId;
    private final Runnable dirtyCallback;
    private final IntSupplier variantCapacitySupplier;
    private final LongSupplier variantNbtBudgetSupplier;
    private final Predicate<ItemVariant> insertValidator;
    private final Predicate<ItemVariant> newVariantValidator;
    private final MetricsParticipant metricsParticipant = new MetricsParticipant();
    private int variantCount;
    private long totalItemCount;
    private long totalVariantNbtBytes;
    private long contentVersion;

    public DigitalItemStorage(Runnable dirtyCallback, int variantCapacity) {
        this(null, dirtyCallback, () -> variantCapacity, () -> Long.MAX_VALUE, variant -> true, variant -> true);
    }

    public DigitalItemStorage(UUID volumeId, Runnable dirtyCallback, int variantCapacity) {
        this(volumeId, dirtyCallback, () -> variantCapacity, () -> Long.MAX_VALUE, variant -> true, variant -> true);
    }

    DigitalItemStorage(Runnable dirtyCallback, IntSupplier variantCapacitySupplier) {
        this(null, dirtyCallback, variantCapacitySupplier, () -> Long.MAX_VALUE);
    }

    DigitalItemStorage(
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            LongSupplier variantNbtBudgetSupplier
    ) {
        this(null, dirtyCallback, variantCapacitySupplier, variantNbtBudgetSupplier);
    }

    DigitalItemStorage(
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

    DigitalItemStorage(
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            Predicate<ItemVariant> newVariantValidator
    ) {
        this(null, dirtyCallback, variantCapacitySupplier, () -> Long.MAX_VALUE, variant -> true, newVariantValidator);
    }

    DigitalItemStorage(
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            Predicate<ItemVariant> insertValidator,
            Predicate<ItemVariant> newVariantValidator
    ) {
        this(null, dirtyCallback, variantCapacitySupplier, () -> Long.MAX_VALUE, insertValidator, newVariantValidator);
    }

    DigitalItemStorage(
            UUID volumeId,
            Runnable dirtyCallback,
            IntSupplier variantCapacitySupplier,
            LongSupplier variantNbtBudgetSupplier,
            Predicate<ItemVariant> insertValidator,
            Predicate<ItemVariant> newVariantValidator
    ) {
        this.volumeId = volumeId;
        this.dirtyCallback = dirtyCallback;
        this.variantCapacitySupplier = variantCapacitySupplier;
        this.variantNbtBudgetSupplier = variantNbtBudgetSupplier;
        this.insertValidator = insertValidator;
        this.newVariantValidator = newVariantValidator;
    }

    public void load(ItemVariant resource, long amount) {
        load(resource, amount, resource.toNbt());
    }

    void load(ItemVariant resource, long amount, NbtCompound serializedVariant) {
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

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        if (resource.isBlank() || maxAmount <= 0 || !insertValidator.test(resource)) {
            return 0;
        }

        Entry entry = entries.get(resource);
        if (entry == null) {
            if (variantCount >= variantCapacitySupplier.getAsInt() || !newVariantValidator.test(resource)) {
                return 0;
            }
            NbtCompound serializedVariant = resource.toNbt();
            int variantNbtBytes = serializedVariant.getSizeInBytes();
            if (!isVariantNbtWithinBudget(variantNbtBytes)) {
                return 0;
            }
            entry = new Entry(resource, 0, serializedVariant, variantNbtBytes);
            entries.put(resource, entry);
        }

        long availableSpace = Math.max(0, MAX_AMOUNT_PER_VARIANT - entry.amount);
        long inserted = Math.min(maxAmount, availableSpace);
        if (inserted <= 0) {
            return 0;
        }

        entry.updateSnapshots(transaction);
        metricsParticipant.updateSnapshots(transaction);
        if (entry.amount == 0) {
            variantCount++;
            totalVariantNbtBytes = Math.addExact(totalVariantNbtBytes, entry.variantNbtBytes);
        }
        entry.amount += inserted;
        totalItemCount = checkedAdd(totalItemCount, inserted);
        return inserted;
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        if (resource.isBlank() || maxAmount <= 0) {
            return 0;
        }

        Entry entry = entries.get(resource);
        if (entry == null || entry.amount <= 0) {
            return 0;
        }

        long extracted = Math.min(maxAmount, entry.amount);
        entry.updateSnapshots(transaction);
        metricsParticipant.updateSnapshots(transaction);
        entry.amount -= extracted;
        totalItemCount = Math.subtractExact(totalItemCount, extracted);
        if (entry.amount == 0) {
            variantCount--;
            totalVariantNbtBytes = Math.subtractExact(totalVariantNbtBytes, entry.variantNbtBytes);
        }
        return extracted;
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
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

    public long amountOf(ItemVariant variant) {
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

    List<StoredEntrySnapshot> snapshotEntries() {
        List<StoredEntrySnapshot> snapshots = new ArrayList<>(variantCount);
        entries.values().forEach(entry -> {
            if (entry.amount > 0) {
                snapshots.add(new StoredEntrySnapshot(entry.serializedVariant, entry.amount));
            }
        });
        return List.copyOf(snapshots);
    }

    SnapshotCursor snapshotCursor() {
        return new SnapshotCursor();
    }

    final class SnapshotCursor {
        private Iterator<Entry> iterator;
        private List<StoredEntrySnapshot> snapshots;
        private long expectedVersion = Long.MIN_VALUE;
        private boolean complete;

        CursorProgress advance(int maximumEntries) {
            if (maximumEntries <= 0) {
                return new CursorProgress(false, false, 0, List.of());
            }
            boolean restarted = expectedVersion != Long.MIN_VALUE && expectedVersion != contentVersion;
            if (expectedVersion != contentVersion) {
                expectedVersion = contentVersion;
                iterator = entries.values().iterator();
                snapshots = new ArrayList<>(variantCount);
                complete = false;
            }
            if (complete) {
                return new CursorProgress(true, restarted, 0, snapshots);
            }

            int examined = 0;
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
            return new CursorProgress(complete, restarted, examined, complete ? snapshots : List.of());
        }
    }

    private final class Entry extends SnapshotParticipant<Long> implements StorageView<ItemVariant> {
        private final ItemVariant resource;
        private final NbtCompound serializedVariant;
        private final int variantNbtBytes;
        private long amount;

        private Entry(ItemVariant resource, long amount, NbtCompound serializedVariant, int variantNbtBytes) {
            this.resource = resource;
            this.amount = amount;
            this.serializedVariant = serializedVariant;
            this.variantNbtBytes = variantNbtBytes;
        }

        @Override
        protected Long createSnapshot() {
            return amount;
        }

        @Override
        protected void readSnapshot(Long snapshot) {
            amount = snapshot;
            if (amount == 0) {
                entries.remove(resource, this);
            }
        }

        @Override
        protected void onFinalCommit() {
            if (amount == 0) {
                entries.remove(resource, this);
            }
            dirtyCallback.run();
        }

        @Override
        public boolean isResourceBlank() {
            return false;
        }

        @Override
        public ItemVariant getResource() {
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
        public long extract(ItemVariant requested, long maxAmount, TransactionContext transaction) {
            if (!resource.equals(requested)) {
                return 0;
            }
            return DigitalItemStorage.this.extract(requested, maxAmount, transaction);
        }
    }

    private final class MetricsParticipant extends SnapshotParticipant<MetricsSnapshot> {
        @Override
        protected MetricsSnapshot createSnapshot() {
            return new MetricsSnapshot(variantCount, totalItemCount, totalVariantNbtBytes);
        }

        @Override
        protected void readSnapshot(MetricsSnapshot snapshot) {
            variantCount = snapshot.variantCount();
            totalItemCount = snapshot.totalItemCount();
            totalVariantNbtBytes = snapshot.totalVariantNbtBytes();
        }

        @Override
        protected void onFinalCommit() {
            contentVersion++;
        }
    }

    record StoredEntrySnapshot(NbtCompound serializedVariant, long amount) {
    }

    record CursorProgress(
            boolean complete,
            boolean restarted,
            int examinedEntries,
            List<StoredEntrySnapshot> snapshots
    ) {
    }

    private record MetricsSnapshot(int variantCount, long totalItemCount, long totalVariantNbtBytes) {
    }

    private static final class NonEmptyViewIterator implements Iterator<StorageView<ItemVariant>> {
        private final Iterator<? extends StorageView<ItemVariant>> delegate;
        private StorageView<ItemVariant> next;

        private NonEmptyViewIterator(Iterator<? extends StorageView<ItemVariant>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            while (next == null && delegate.hasNext()) {
                StorageView<ItemVariant> candidate = delegate.next();
                if (candidate.getAmount() > 0) {
                    next = candidate;
                }
            }
            return next != null;
        }

        @Override
        public StorageView<ItemVariant> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            StorageView<ItemVariant> result = next;
            next = null;
            return result;
        }
    }

    @FunctionalInterface
    public interface LongEntryConsumer {
        void accept(ItemVariant variant, long amount);
    }
}
