package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import dev.kehai.digitalstorage.storage.MutationParticipant;
import dev.kehai.digitalstorage.storage.MutationScope;

import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    private final Map<MutationParticipant<?>, WeakReference<Bridge<?>>> bridges = new WeakHashMap<>();
    // Adapter-owned live views do not root the weak canonical cache. Reuse their
    // ItemVariants without a weak-reference lookup or repeated NBT copies.
    private final Map<VolumeLedger.View, FabricView> views = new IdentityHashMap<>();
    private long viewStructureVersion = Long.MIN_VALUE;

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
        return ledger.insert(FabricItemKeys.fromVariant(resource), maxAmount, new FabricMutationScope(transaction));
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        return ledger.extract(FabricItemKeys.fromVariant(resource), maxAmount, new FabricMutationScope(transaction));
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
        discardDetachedViews();
        Iterator<VolumeLedger.View> iterator = ledger.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public StorageView<ItemVariant> next() {
                VolumeLedger.View view = iterator.next();
                FabricView existing = views.get(view);
                if (existing != null) {
                    return existing;
                }
                FabricView created = new FabricView(view);
                views.put(view, created);
                return created;
            }
        };
    }

    private void discardDetachedViews() {
        long current = ledger.structureVersion();
        if (viewStructureVersion != current) {
            // Shared ledger transactions can detach entries without Fabric callbacks.
            views.keySet().removeIf(view -> view instanceof MutationParticipant<?> participant
                    && !participant.isAttached());
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
        private final TransactionContext transaction;

        private FabricMutationScope(TransactionContext transaction) {
            this.transaction = transaction;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <S> void enlist(MutationParticipant<S> participant) {
            WeakReference<Bridge<?>> reference = bridges.get(participant);
            Bridge<S> bridge = reference == null ? null : (Bridge<S>) reference.get();
            if (bridge == null) {
                bridge = new Bridge<>(participant);
                bridges.put(participant, new WeakReference<>(bridge));
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
                WeakReference<Bridge<?>> reference = bridges.get(participant);
                if (reference != null && reference.get() == this) {
                    bridges.remove(participant);
                }
                if (participant instanceof VolumeLedger.View view) {
                    views.remove(view);
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
            return resource.equals(requested) ? FabricDigitalItemStorage.this.extract(requested, maxAmount, transaction) : 0;
        }
    }

    @FunctionalInterface
    public interface LongEntryConsumer {
        void accept(ItemVariant variant, long amount);
    }
}
