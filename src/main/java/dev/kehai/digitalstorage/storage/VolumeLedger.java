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
import net.minecraft.nbt.CompoundTag;

public final class VolumeLedger implements Iterable<VolumeLedger.View> {
    /**
     * Intentional safety ceiling for one exact item variant. Keeping this at the
     * signed 32-bit maximum bounds aggregate volume totals and avoids exposing
     * extreme long values to integrations. Stored over-limit data is preserved
     * and remains extractable; only new insertion is capped.
     */
    public static final long MAX_AMOUNT_PER_VARIANT = Integer.MAX_VALUE;

    private final Map<ItemKey, Entry> entries = new HashMap<>();
    private final Map<CompoundTag, Long> opaqueEntries = new java.util.LinkedHashMap<>();
    private final Map<Class<?>, Object> platformAdapters = new HashMap<>();
    private java.util.function.BooleanSupplier active = () -> true;
    private GlobalBudget globalBudget;
    private TransactionRegistry transactionRegistry;
    private java.util.function.Consumer<TransactionId> rollbackCallback = ignored -> { };
    private TransactionId mutationOwner;
    private Thread mutationOwnerThread;
    private long mutationConflicts;

    private boolean tryEnterMutation(MutationScope scope) {
        TransactionId root = scope.transactionPath().get(0);
        synchronized (this) {
            if (mutationOwner == root) return true;
            if (mutationOwner != null) {
                mutationConflicts++;
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.debug("Concurrent mutation rejected for storage volume {}", volumeId);
                return false;
            }
            mutationOwner = root;
            mutationOwnerThread = Thread.currentThread();
            try { scope.onCompletion(root, ignored -> leaveMutation(root)); }
            catch (RuntimeException exception) { mutationOwner = null; mutationOwnerThread = null; throw exception; }
            return true;
        }
    }

    private synchronized void leaveMutation(TransactionId root) {
        if (mutationOwner == root) { mutationOwner = null; mutationOwnerThread = null; }
    }

    long mutationConflictsForTest() { synchronized (this) { return mutationConflicts; } }
    synchronized boolean mutationHeldByCurrentThread() { return mutationOwner != null && mutationOwnerThread == Thread.currentThread(); }

    void attachAccounting(GlobalBudget budget, TransactionRegistry registry) {
        if (globalBudget != null) throw new IllegalStateException("Ledger accounting already attached");
        budget.addLoaded(totalVariantNbtBytes);
        globalBudget = java.util.Objects.requireNonNull(budget);
        transactionRegistry = java.util.Objects.requireNonNull(registry);
    }

    void setRollbackCallback(java.util.function.Consumer<TransactionId> callback) { rollbackCallback = java.util.Objects.requireNonNull(callback); }

    public boolean enterScope(MutationScope scope) {
        if (!tryEnterMutation(scope)) return false;
        scope.enter(transactionRegistry);
        return true;
    }

    void setActivePredicate(java.util.function.BooleanSupplier active) { this.active = active; }

    public synchronized <T> T canonicalAdapter(Class<T> type, java.util.function.Supplier<T> factory) {
        Object adapter = platformAdapters.get(type);
        if (adapter == null) {
            adapter = factory.get();
            platformAdapters.put(type, adapter);
        }
        return type.cast(adapter);
    }

    public synchronized <T> void registerAdapter(Class<T> type, T adapter) {
        platformAdapters.putIfAbsent(type, adapter);
    }
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

    public void loadOpaque(CompoundTag rawVariant, long amount) {
        TransactionId direct = enterDirect();
        try { loadOpaqueInternal(rawVariant, amount); }
        finally { leaveDirect(direct); }
    }

    private void loadOpaqueInternal(CompoundTag rawVariant, long amount) {
        if (rawVariant == null || amount <= 0) return;
        CompoundTag raw = rawVariant.copy();
        Long previous = opaqueEntries.get(raw);
        long newTotal = checkedAdd(totalItemCount, amount);
        if (previous == null) {
            long newBytes = Math.addExact(totalVariantNbtBytes, raw.sizeInBytes());
            if (globalBudget != null) globalBudget.addLoaded(raw.sizeInBytes());
            opaqueEntries.put(raw, amount);
            variantCount++;
            structureVersion++;
            totalVariantNbtBytes = newBytes;
        } else {
            opaqueEntries.put(raw, Math.addExact(previous, amount));
        }
        totalItemCount = newTotal;
        if (globalBudget != null) dirtyCallback.run();
    }

    public int opaqueVariantCount() { return opaqueEntries.size(); }
    public long opaqueItemCount() { return opaqueEntries.values().stream().mapToLong(Long::longValue).sum(); }

    void load(ItemKey resource, long amount, CompoundTag serializedVariant) {
        TransactionId direct = enterDirect();
        try { loadInternal(resource, amount, serializedVariant); }
        finally { leaveDirect(direct); }
    }

    private void loadInternal(ItemKey resource, long amount, CompoundTag serializedVariant) {
        if (resource.isBlank() || amount <= 0) {
            return;
        }

        Entry existing = entries.get(resource);
        if (existing == null) {
            CompoundTag storedVariant = serializedVariant.copy();
            int variantNbtBytes = storedVariant.sizeInBytes();
            long newVariantNbtBytes = Math.addExact(totalVariantNbtBytes, variantNbtBytes);
            long newItemCount = checkedAdd(totalItemCount, amount);
            if (globalBudget != null) globalBudget.addLoaded(variantNbtBytes);
            entries.put(resource, new Entry(resource, amount, storedVariant, variantNbtBytes));
            structureVersion++;
            variantCount++;
            totalItemCount = newItemCount;
            totalVariantNbtBytes = newVariantNbtBytes;
            if (globalBudget != null) dirtyCallback.run();
            return;
        }

        try {
            long mergedAmount = Math.addExact(existing.amount, amount);
            long newItemCount = checkedAdd(totalItemCount, amount);
            existing.amount = mergedAmount;
            totalItemCount = newItemCount;
            if (globalBudget != null) dirtyCallback.run();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Duplicate stored variant amount exceeds the long range", exception);
        }
    }

    private TransactionId enterDirect() {
        TransactionId id = new TransactionId();
        synchronized (this) {
            if (mutationOwner != null) {
                mutationConflicts++;
                throw new IllegalStateException("Concurrent direct storage mutation rejected");
            }
        }
        if (transactionRegistry != null) transactionRegistry.enter(id);
        synchronized (this) {
            if (mutationOwner != null) {
                if (transactionRegistry != null) transactionRegistry.leave(id);
                mutationConflicts++;
                throw new IllegalStateException("Concurrent direct storage mutation rejected");
            }
            mutationOwner = id;
            mutationOwnerThread = Thread.currentThread();
        }
        return id;
    }

    private void leaveDirect(TransactionId id) {
        if (id != null) {
            leaveMutation(id);
            if (transactionRegistry != null) transactionRegistry.leave(id);
        }
    }

    TransactionId beginDirectMutation() { return enterDirect(); }
    void endDirectMutation(TransactionId id) { leaveDirect(id); }

    public long insert(ItemKey resource, long maxAmount, MutationScope mutation) {
        if (!tryEnterMutation(mutation)) return 0;
        mutation.enter(transactionRegistry);
        if (!active.getAsBoolean() || resource.isBlank() || maxAmount <= 0 || !insertValidator.test(resource)) {
            return 0;
        }

        Entry entry = entries.get(resource);
        boolean newEntry = entry == null;
        if (entry == null) {
            if (variantCount >= variantCapacitySupplier.getAsInt() || !newVariantValidator.test(resource)) {
                return 0;
            }
            CompoundTag serializedVariant = ItemKeyCodec.write(resource);
            int variantNbtBytes = serializedVariant.sizeInBytes();
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

        long charge = entry.amount == 0 && entry.pendingWithdrawals == 0 ? entry.variantNbtBytes : 0;
        if (globalBudget != null && !globalBudget.tryReserve(charge, mutation)) return 0;

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
        return extract(resource, maxAmount, mutation, false);
    }

    private long extract(ItemKey resource, long maxAmount, MutationScope mutation, boolean reserve) {
        if (!tryEnterMutation(mutation)) return 0;
        mutation.enter(transactionRegistry);
        if (!active.getAsBoolean() || resource.isBlank() || maxAmount <= 0) {
            return 0;
        }

        Entry entry = entries.get(resource);
        if (entry == null || entry.amount <= 0) {
            return 0;
        }

        long extracted = Math.min(maxAmount, entry.amount);
        mutation.enlist(entry);
        mutation.enlist(metricsParticipant);
        if (reserve) entry.pendingWithdrawals++;
        entry.amount -= extracted;
        totalItemCount = Math.subtractExact(totalItemCount, extracted);
        if (entry.amount == 0) {
            variantCount--;
            totalVariantNbtBytes = Math.subtractExact(totalVariantNbtBytes, entry.variantNbtBytes);
            if (globalBudget != null && entry.pendingWithdrawals == 0) globalBudget.release(entry.variantNbtBytes, mutation);
        }
        return extracted;
    }

    @Override
    public Iterator<View> iterator() {
        return new NonEmptyViewIterator(entries.values().iterator());
    }

    /** A one-shot receipt can restore only its own unaccepted withdrawal, even after insertion is disabled. */
    public Extraction reserveExtraction(ItemKey key, long maximum, MutationScope mutation) {
        long reserved = extract(key, maximum, mutation, true);
        var receipt = new Extraction(entries.get(key), reserved, mutation);
        if (globalBudget != null && reserved > 0) globalBudget.releaseOnCommit(mutation, receipt::releaseIfUnsettled);
        return receipt;
    }

    public final class Extraction {
        private final Entry entry;
        private final long reserved;
        private final MutationScope mutation;
        private boolean settled;
        private Extraction(Entry entry, long reserved, MutationScope mutation) {
            this.entry = entry; this.reserved = reserved; this.mutation = mutation;
        }
        public long amount() { return reserved; }
        private long releaseIfUnsettled() {
            if (settled) return 0;
            settled = true;
            entry.pendingWithdrawals--;
            return entry.amount == 0 && entry.pendingWithdrawals == 0 ? entry.variantNbtBytes : 0;
        }
        public void settle(long delivered) {
            if (settled || delivered < 0 || delivered > reserved) throw new IllegalStateException("Invalid withdrawal settlement");
            if (reserved == 0) { settled = true; return; }
            if (entries.get(entry.resource) != entry) throw new IllegalStateException("Withdrawal identity changed");
            mutation.enlist(entry);
            mutation.enlist(metricsParticipant);
            long returned = reserved - delivered;
            if (returned > 0) {
                if (entry.amount == 0) { variantCount++; totalVariantNbtBytes = Math.addExact(totalVariantNbtBytes, entry.variantNbtBytes); }
                entry.amount = Math.addExact(entry.amount, returned);
                totalItemCount = Math.addExact(totalItemCount, returned);
            }
            entry.pendingWithdrawals--;
            if (globalBudget != null && entry.amount == 0 && entry.pendingWithdrawals == 0) {
                globalBudget.release(entry.variantNbtBytes, mutation);
            }
            settled = true;
        }
    }

    /**
     * Lazy live membership, including provisional zero entries. Adapters must
     * filter quantities when visiting and invalidate completed membership caches
     * when structureVersion changes. Opening this iterator does not copy a volume.
     */
    public Iterator<? extends View> attachedViewsIterator() {
        return java.util.Collections.unmodifiableCollection(entries.values()).iterator();
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

    public long amountOf(ItemKey variant) {
        Entry entry = entries.get(variant);
        return entry == null ? 0 : entry.amount;
    }

    public long contentVersion() {
        return contentVersion;
    }

    /** Key-set changes, including provisional mutations and rollback. */
    public long structureVersion() {
        return structureVersion;
    }

    /** Live configured type capacity, independent of the number of stored identities. */
    public int variantCapacity() {
        return variantCapacitySupplier.getAsInt();
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
                snapshots.add(new StoredEntrySnapshot(entry.serializedVariant.copy(), entry.amount));
            }
        });
        opaqueEntries.forEach((raw, amount) -> snapshots.add(new StoredEntrySnapshot(raw.copy(), amount)));
        return List.copyOf(snapshots);
    }

    private final class Entry implements MutationParticipant<EntrySnapshot>, View {
        private final ItemKey resource;
        private final CompoundTag serializedVariant;
        private final int variantNbtBytes;
        private long amount;
        private int pendingWithdrawals;

        private Entry(ItemKey resource, long amount, CompoundTag serializedVariant, int variantNbtBytes) {
            this.resource = resource;
            this.amount = amount;
            this.serializedVariant = serializedVariant;
            this.variantNbtBytes = variantNbtBytes;
        }

        @Override
        public EntrySnapshot snapshot() {
            return new EntrySnapshot(amount, pendingWithdrawals);
        }

        @Override
        public void restore(EntrySnapshot snapshot) {
            amount = snapshot.amount();
            pendingWithdrawals = snapshot.pendingWithdrawals();
            if (amount == 0 && entries.remove(resource, this)) {
                structureVersion++;
            }
            rollbackCallback.accept(mutationOwner);
        }

        @Override
        public void committed() {
            if (amount == 0 && entries.remove(resource, this)) {
                structureVersion++;
            }
            TransactionId.runCallback(mutationOwner, dirtyCallback);
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

    public record StoredEntrySnapshot(CompoundTag serializedVariant, long amount) {
    }

    private record MetricsSnapshot(int variantCount, long totalItemCount, long totalVariantNbtBytes) {
    }

    private record EntrySnapshot(long amount, int pendingWithdrawals) { }

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
