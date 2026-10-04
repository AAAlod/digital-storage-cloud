package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import dev.kehai.digitalstorage.storage.MutationParticipant;
import dev.kehai.digitalstorage.storage.MutationScope;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

/** Fabric Transfer adapter. Quantities, policy, versions and persistence belong to the ledger. */
public final class FabricDigitalItemStorage implements Storage<ItemVariant> {
    public static final long MAX_AMOUNT_PER_VARIANT = VolumeLedger.MAX_AMOUNT_PER_VARIANT;
    private static final Map<VolumeLedger, WeakReference<FabricDigitalItemStorage>> CANONICAL = new WeakHashMap<>();
    private final VolumeLedger ledger;
    // The ledger already owns attached participants. Adapter-local strong bridges
    // survive GC between transactions; detached participants are removed below.
    // CANONICAL remains weak, so this adapter/ledger cycle cannot root a volume.
    private final Map<MutationParticipant<?>, Bridge<?>> bridges = new IdentityHashMap<>();
    private FabricMutationScope spareScope;
    // Adapter-owned live views do not root the weak canonical cache. Reuse their
    // ItemVariants without a weak-reference lookup or repeated NBT copies.
    private final Map<VolumeLedger.View, FabricView> views = new IdentityHashMap<>();
    private long viewStructureVersion = Long.MIN_VALUE;
    private FabricView[] attachedViews;
    private VolumeLedger.View[] attachedMembers;

    public FabricDigitalItemStorage(Runnable dirtyCallback, int variantCapacity) {
        this(new VolumeLedger(dirtyCallback, variantCapacity));
    }

    public FabricDigitalItemStorage(UUID volumeId, Runnable dirtyCallback, int variantCapacity) {
        this(new VolumeLedger(volumeId, dirtyCallback, variantCapacity));
    }

    public FabricDigitalItemStorage(Runnable dirtyCallback, IntSupplier capacity) {
        this(new VolumeLedger(dirtyCallback, capacity));
    }

    public FabricDigitalItemStorage(Runnable dirtyCallback, IntSupplier capacity, LongSupplier nbtBudget) {
        this(new VolumeLedger(dirtyCallback, capacity, nbtBudget));
    }

    public FabricDigitalItemStorage(Runnable dirtyCallback, IntSupplier capacity, Predicate<ItemKey> newVariantValidator) {
        this(new VolumeLedger(dirtyCallback, capacity, newVariantValidator));
    }

    public FabricDigitalItemStorage(Runnable dirtyCallback, IntSupplier capacity,
                       Predicate<ItemKey> insertValidator, Predicate<ItemKey> newVariantValidator) {
        this(new VolumeLedger(dirtyCallback, capacity, insertValidator, newVariantValidator));
    }

    private FabricDigitalItemStorage(VolumeLedger ledger) {
        this.ledger = ledger;
        synchronized (CANONICAL) {
            CANONICAL.put(ledger, new WeakReference<>(this));
        }
    }

    /** Live views/transactions retain the adapter; weak values break the adapter -> ledger cycle. */
    public static FabricDigitalItemStorage of(VolumeLedger ledger) {
        synchronized (CANONICAL) {
            WeakReference<FabricDigitalItemStorage> reference = CANONICAL.get(ledger);
            FabricDigitalItemStorage existing = reference == null ? null : reference.get();
            return existing == null ? new FabricDigitalItemStorage(ledger) : existing;
        }
    }

    public VolumeLedger ledger() {
        return ledger;
    }

    public void load(ItemVariant variant, long amount) {
        ledger.load(FabricItemKeys.fromVariant(variant), amount);
    }

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        return insertKey(FabricItemKeys.fromVariant(resource), maxAmount, transaction);
    }

    /** Only callers that already checked the source variant's exact identity may reuse its key. */
    long insertKey(ItemKey resource, long maxAmount, TransactionContext transaction) {
        FabricMutationScope scope = acquireScope(transaction);
        try {
            return ledger.insert(resource, maxAmount, scope);
        } finally {
            releaseScope(scope);
        }
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        return extractKey(FabricItemKeys.fromVariant(resource), maxAmount, transaction);
    }

    private long extractKey(ItemKey resource, long maxAmount, TransactionContext transaction) {
        FabricMutationScope scope = acquireScope(transaction);
        try {
            return ledger.extract(resource, maxAmount, scope);
        } finally {
            releaseScope(scope);
        }
    }

    private FabricMutationScope acquireScope(TransactionContext transaction) {
        // Shared transactions can remove entries without Fabric callbacks. Prune
        // when the cache exceeds live variants plus the one metrics participant;
        // ordinary growth need not scan every earlier bridge on each insertion.
        if (bridges.size() > (long) ledger.variantCount() + 1) {
            bridges.keySet().removeIf(participant -> !participant.isAttached());
        }
        FabricMutationScope scope = spareScope;
        spareScope = null;
        if (scope == null) scope = new FabricMutationScope();
        scope.transaction = transaction;
        return scope;
    }

    private void releaseScope(FabricMutationScope scope) {
        // Ledger mutations enlist synchronously. Never retain a closed transaction
        // or reuse an active scope if a validator reenters this adapter.
        scope.transaction = null;
        spareScope = scope;
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
        discardDetachedViews();
        if (attachedViews == null) return buildingIterator(viewStructureVersion);
        FabricView[] cachedMembers = attachedViews;
        VolumeLedger.View[] members = attachedMembers;
        long expectedStructureVersion = viewStructureVersion;
        return new Iterator<>() {
            private int index;
            private FabricView next;

            @Override
            public boolean hasNext() {
                if (ledger.structureVersion() != expectedStructureVersion) {
                    throw new ConcurrentModificationException("Ledger membership changed during Fabric iteration");
                }
                while (next == null && index < cachedMembers.length) {
                    int memberIndex = index++;
                    FabricView candidate = cachedMembers[memberIndex];
                    if (candidate == null) {
                        VolumeLedger.View member = members[memberIndex];
                        if (member.getAmount() <= 0) continue;
                        candidate = adaptView(member);
                        cachedMembers[memberIndex] = candidate;
                    }
                    if (candidate.getAmount() > 0) next = candidate;
                }
                return next != null;
            }

            @Override
            public StorageView<ItemVariant> next() {
                if (!hasNext()) throw new NoSuchElementException();
                FabricView result = next;
                next = null;
                return result;
            }
        };
    }

    private FabricView adaptView(VolumeLedger.View member) {
        FabricView cached = views.get(member);
        if (cached == null) {
            cached = new FabricView(member);
            views.put(member, cached);
        }
        return cached;
    }

    private Iterator<StorageView<ItemVariant>> buildingIterator(long expectedStructureVersion) {
        Iterator<? extends VolumeLedger.View> raw = ledger.attachedViewsIterator();
        return new Iterator<>() {
            private final List<VolumeLedger.View> members = new ArrayList<>();
            private final List<FabricView> adapted = new ArrayList<>();
            private FabricView next;
            private boolean complete;

            @Override
            public boolean hasNext() {
                if (ledger.structureVersion() != expectedStructureVersion) {
                    throw new ConcurrentModificationException("Ledger membership changed during Fabric iteration");
                }
                while (next == null && raw.hasNext()) {
                    VolumeLedger.View member = raw.next();
                    FabricView candidate = member.getAmount() > 0 ? adaptView(member) : null;
                    members.add(member);
                    adapted.add(candidate);
                    next = candidate;
                }
                if (next == null && !complete) {
                    // Publish only after natural exhaustion. Partial scans retain
                    // their original budget and never copy/adapt unvisited entries.
                    attachedMembers = members.toArray(VolumeLedger.View[]::new);
                    attachedViews = adapted.toArray(FabricView[]::new);
                    complete = true;
                }
                return next != null;
            }

            @Override
            public StorageView<ItemVariant> next() {
                if (!hasNext()) throw new NoSuchElementException();
                FabricView result = next;
                next = null;
                return result;
            }
        };
    }

    private void discardDetachedViews() {
        long current = ledger.structureVersion();
        if (viewStructureVersion != current) {
            // Shared ledger transactions can detach entries without Fabric callbacks.
            views.keySet().removeIf(view -> view instanceof MutationParticipant<?> participant
                    && !participant.isAttached());
            bridges.keySet().removeIf(participant -> !participant.isAttached());
            attachedMembers = null;
            attachedViews = null;
            viewStructureVersion = current;
        }
    }

    public int variantCount() { return ledger.variantCount(); }
    public Optional<UUID> volumeId() { return ledger.volumeId(); }
    public long totalItemCount() { return ledger.totalItemCount(); }
    public long totalVariantNbtBytes() { return ledger.totalVariantNbtBytes(); }
    public long contentVersion() { return ledger.contentVersion(); }

    public long amountOf(ItemVariant variant) {
        return ledger.amountOf(FabricItemKeys.fromVariant(variant));
    }

    public void forEach(LongEntryConsumer consumer) {
        ledger.forEach((key, amount) -> consumer.accept(FabricItemKeys.toVariant(key), amount));
    }

    public List<VolumeLedger.StoredEntrySnapshot> snapshotEntries() { return ledger.snapshotEntries(); }
    public VolumeLedger.SnapshotCursor snapshotCursor() { return ledger.snapshotCursor(); }

    private final class FabricMutationScope implements MutationScope {
        private TransactionContext transaction;

        @Override
        @SuppressWarnings("unchecked")
        public <S> void enlist(MutationParticipant<S> participant) {
            Bridge<S> bridge = (Bridge<S>) bridges.get(participant);
            if (bridge == null) {
                bridge = new Bridge<>(participant);
                bridges.put(participant, bridge);
            }
            bridge.updateSnapshots(transaction);
        }
    }

    // The non-static bridge retains this adapter while Fabric holds its callbacks.
    // Repeated canonical lookup cannot create another bridge mid-transaction.
    private final class Bridge<S> extends SnapshotParticipant<S> {
        private final MutationParticipant<S> participant;

        private Bridge(MutationParticipant<S> participant) {
            this.participant = participant;
        }

        @Override
        protected S createSnapshot() { return participant.snapshot(); }

        @Override
        protected void readSnapshot(S snapshot) {
            participant.restore(snapshot);
            discardDetached();
        }

        @Override
        protected void onFinalCommit() {
            participant.committed();
            discardDetached();
        }

        private void discardDetached() {
            if (!participant.isAttached()) {
                if (bridges.get(participant) == this) {
                    bridges.remove(participant);
                }
                if (participant instanceof VolumeLedger.View view) {
                    views.remove(view);
                    // Do not retain detached participants through the old array
                    // while waiting for the next iterator to rebuild membership.
                    attachedViews = null;
                    attachedMembers = null;
                }
            }
        }
    }

    private final class FabricView implements StorageView<ItemVariant> {
        private final VolumeLedger.View view;
        private final ItemVariant resource;

        private FabricView(VolumeLedger.View view) {
            this.view = view;
            this.resource = FabricItemKeys.toVariant(view.getResource());
        }

        @Override
        public boolean isResourceBlank() { return view.isResourceBlank(); }
        @Override
        public ItemVariant getResource() { return resource; }
        @Override
        public long getAmount() { return view.getAmount(); }
        @Override
        public long getCapacity() { return view.getCapacity(); }

        @Override
        public long extract(ItemVariant requested, long maxAmount, TransactionContext transaction) {
            // Equality has already established the exact immutable variant. The
            // live handle owns its ledger key; avoid converting it back from Fabric.
            return resource.equals(requested) ? extractKey(view.getResource(), maxAmount, transaction) : 0;
        }
    }

    @FunctionalInterface
    public interface LongEntryConsumer {
        void accept(ItemVariant variant, long amount);
    }
}
