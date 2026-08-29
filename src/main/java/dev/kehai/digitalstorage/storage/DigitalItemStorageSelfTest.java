package dev.kehai.digitalstorage.storage;

import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.hopper.HopperTransferOptimizer;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import dev.kehai.digitalstorage.upgrade.DigitalStorageUpgradeService;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.item.Items;

public final class DigitalItemStorageSelfTest {
    private DigitalItemStorageSelfTest() {
    }

    public static String run() {
        ItemVariant stone = ItemVariant.of(Items.STONE);
        committedInsertPersistsAndMarksDirtyOnce(stone);
        abortedInsertRemovesTheProvisionalEntry(stone);
        abortedExtractRestoresThePreviousAmount(stone);
        extractingTheLastItemsRemovesTheViewAfterCommit(stone);
        insertSaturatesAtConfiguredLimitWithoutOverflowing(stone);
        storedOverLimitDataRemainsExtractable(stone);
        fullVariantCapacityRejectsOnlyNewVariants(stone);
        replacingTheLastVariantInOneTransactionIsSafe(stone);
        abortedNewVariantReleasesCapacity(stone);
        cachedMetricsFollowCommitAndRollback(stone);
        capturedSnapshotIsStableAfterLaterMutation(stone);
        missingTierFallsBackWithoutReducingCapacity(stone);
        duplicateStoredVariantsAreMergedWithoutLoss(stone);
        futureStorageSchemaIsRejected();
        corruptStorageRecordIsQuarantinedAndPreserved(stone);
        filterAppliesOnlyWhenCreatingANewVariant(stone);
        insertionPolicyAppliesToExistingVariantsButNeverExtraction(stone);
        volumeUnstackablePolicyIsDynamicAndPreservesExtraction();
        volumeUnstackablePolicyPersistsAndMigrates();
        oversizedVariantNbtIsMeasurableAndRejectable();
        aggregateVariantNbtBudgetIsTransactional();
        incrementalSnapshotRestartsAfterMutation();
        continuouslyMutatingVolumeCannotStarvePersistence();
        agedDirtyVolumeForcesSnapshotCompletion();
        DigitalStorageConfig.runSelfTest();
        ItemSecurityPolicy.runSelfTest();
        stressVariantCapacityAtHardLimit();
        randomizedTransactionsConserveItems();
        accountVolumeLimitAndCanonicalIdentityAreStable();
        forceClearResetsBothBindingFields();
        orphanedBindingSelfHealsAfterVolumeDeletion();
        dev.kehai.digitalstorage.security.DigitalStorageMountTracker.runSelfTest();
        hopperTierBatchLimitsAreDeviceScoped();
        tomsMergedStorageDeduplicatesAndFallsBack(stone);
        dev.kehai.digitalstorage.optimization.TomNetworkAnalysis.runSelfTest();
        dev.kehai.digitalstorage.optimization.TomMigrationManager.runSelfTest();
        DigitalStorageUpgradeService.runSelfTest();
        DigitalStorageScreenState.runCodecSelfTest();
        exactHopperTransferMovesOneBatchWithoutIteration(stone);
        filteredHopperTransferMovesOneBatch(stone);
        fullDestinationDoesNotTouchTheSource(stone);
        changingDestinationCapacityRollsBackTheSource(stone);
        return "Digital Storage self-test passed: transactions, content versions, cached metrics, immutable snapshots, persistence restart/fairness/liveness, limits, dynamic Volume unstackable policy/config migration/persistence, per-insert policy, filtering, NBT guard, stress, canonical aliasing, Tom raw endpoint detection/duplicate-count prevention/dedup/fallback/cursor invalidation, device-scoped hopper tiers, network scoring/recommendations, budgeted atomic migration/rollback/lifecycle invalidation, sharded file round-trip/deletion, storage size telemetry, force-clear, orphan binding self-heal, mount statistics semantics, schema guard, file quarantine, tier migration, payment reservation, screen codec, atomic hopper paths";
    }

    private static void committedInsertPersistsAndMarksDirtyOnce(ItemVariant stone) {
        AtomicInteger dirtyCalls = new AtomicInteger();
        DigitalItemStorage storage = new DigitalItemStorage(dirtyCalls::incrementAndGet, 64);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(64, storage.insert(stone, 64, transaction), "inserted amount");
            transaction.commit();
        }

        expectEquals(1, storage.variantCount(), "variant count after commit");
        expectEquals(64L, storage.totalItemCount(), "total items after commit");
        expectEquals(64, onlyView(storage).getAmount(), "stored amount after commit");
        expectEquals(1, dirtyCalls.get(), "dirty callback count");
        expectEquals(1L, storage.contentVersion(), "content version after commit");
    }

    private static void abortedInsertRemovesTheProvisionalEntry(ItemVariant stone) {
        AtomicInteger dirtyCalls = new AtomicInteger();
        DigitalItemStorage storage = new DigitalItemStorage(dirtyCalls::incrementAndGet, 64);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(64, storage.insert(stone, 64, transaction), "insert before abort");
        }

        expectEquals(0, storage.variantCount(), "variant count after abort");
        expectEquals(0L, storage.totalItemCount(), "total items after abort");
        expectFalse(storage.iterator().hasNext(), "aborted insert left a view");
        expectEquals(0, dirtyCalls.get(), "aborted insert marked state dirty");
        expectEquals(0L, storage.contentVersion(), "aborted insert changed content version");
    }

    private static void abortedExtractRestoresThePreviousAmount(ItemVariant stone) {
        DigitalItemStorage storage = storageWith(stone, 100);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(40, storage.extract(stone, 40, transaction), "extracted amount before abort");
        }

        expectEquals(100, onlyView(storage).getAmount(), "amount after extract abort");
        expectEquals(100L, storage.totalItemCount(), "total items after extract abort");
        expectEquals(0L, storage.contentVersion(), "aborted extract changed content version");
    }

    private static void extractingTheLastItemsRemovesTheViewAfterCommit(ItemVariant stone) {
        DigitalItemStorage storage = storageWith(stone, 32);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(32, storage.extract(stone, Long.MAX_VALUE, transaction), "last extraction");
            expectFalse(storage.iterator().hasNext(), "zero-sized entry exposed in transaction");
            transaction.commit();
        }

        expectEquals(0, storage.variantCount(), "variant count after emptying storage");
        expectEquals(0L, storage.totalItemCount(), "total items after emptying storage");
        expectFalse(storage.iterator().hasNext(), "empty entry remained after commit");
        expectEquals(1L, storage.contentVersion(), "content version after committed extract");
    }

    private static void insertSaturatesAtConfiguredLimitWithoutOverflowing(ItemVariant stone) {
        expectEquals(2_147_483_647L, DigitalItemStorage.MAX_AMOUNT_PER_VARIANT,
                "intentional per-variant safety ceiling");
        DigitalItemStorage storage = storageWith(stone, DigitalItemStorage.MAX_AMOUNT_PER_VARIANT - 1);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.insert(stone, 64, transaction), "saturating insert");
            transaction.commit();
        }

        expectEquals(DigitalItemStorage.MAX_AMOUNT_PER_VARIANT, onlyView(storage).getAmount(), "amount at configured limit");
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(0, storage.insert(stone, 1, transaction), "insert beyond configured limit");
            transaction.commit();
        }
    }

    private static void storedOverLimitDataRemainsExtractable(ItemVariant stone) {
        DigitalItemStorage storage = storageWith(stone, DigitalItemStorage.MAX_AMOUNT_PER_VARIANT + 1);
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(0, storage.insert(stone, 1, transaction), "insert into stored over-limit variant");
            expectEquals(1, storage.extract(stone, 1, transaction), "extract from stored over-limit variant");
            transaction.commit();
        }
        expectEquals(DigitalItemStorage.MAX_AMOUNT_PER_VARIANT, onlyView(storage).getAmount(),
                "stored over-limit amount after extraction");
    }

    private static void fullVariantCapacityRejectsOnlyNewVariants(ItemVariant stone) {
        ItemVariant dirt = ItemVariant.of(Items.DIRT);
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, 1);
        storage.load(stone, 10);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(0, storage.insert(dirt, 1, transaction), "new variant at full capacity");
            expectEquals(5, storage.insert(stone, 5, transaction), "existing variant at full capacity");
            transaction.commit();
        }

        expectEquals(1, storage.variantCount(), "variant count after full-capacity insert");
        expectEquals(15, onlyView(storage).getAmount(), "existing amount after full-capacity insert");
    }

    private static void replacingTheLastVariantInOneTransactionIsSafe(ItemVariant stone) {
        ItemVariant dirt = ItemVariant.of(Items.DIRT);
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, 1);
        storage.load(stone, 1);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.extract(stone, 1, transaction), "extract old variant before replacement");
            expectEquals(1, storage.insert(dirt, 1, transaction), "insert replacement variant");
            transaction.commit();
        }

        expectEquals(1, storage.variantCount(), "variant count after replacement");
        expectEquals(dirt, onlyView(storage).getResource(), "replacement variant after commit");
    }

    private static void abortedNewVariantReleasesCapacity(ItemVariant stone) {
        ItemVariant dirt = ItemVariant.of(Items.DIRT);
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, 1);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.insert(stone, 1, transaction), "provisional variant insert");
        }

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.insert(dirt, 1, transaction), "capacity after abort");
            transaction.commit();
        }

        expectEquals(dirt, onlyView(storage).getResource(), "variant remaining after abort");
    }

    private static void cachedMetricsFollowCommitAndRollback(ItemVariant stone) {
        ItemVariant dirt = ItemVariant.of(Items.DIRT);
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, 2);
        storage.load(stone, 10);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(10, storage.extract(stone, 10, transaction), "metric rollback extraction");
            expectEquals(7, storage.insert(dirt, 7, transaction), "metric rollback insertion");
            expectEquals(1, storage.variantCount(), "in-transaction variant count");
            expectEquals(7L, storage.totalItemCount(), "in-transaction total items");
        }

        expectEquals(1, storage.variantCount(), "variant count restored after rollback");
        expectEquals(10L, storage.totalItemCount(), "total items restored after rollback");
        expectEquals(stone, onlyView(storage).getResource(), "resource restored after metric rollback");
    }

    private static void capturedSnapshotIsStableAfterLaterMutation(ItemVariant stone) {
        DigitalStorageRecord record = DigitalStorageRecord.createNew(() -> { });
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(5, record.storage().insert(stone, 5, transaction), "snapshot seed insert");
            transaction.commit();
        }
        DigitalStorageRecord.Snapshot snapshot = record.snapshot();

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(4, record.storage().insert(stone, 4, transaction), "post-snapshot insert");
            transaction.commit();
        }

        net.minecraft.nbt.NbtCompound capturedNbt = new net.minecraft.nbt.NbtCompound();
        snapshot.writeNbt(capturedNbt);
        DigitalStorageRecord captured = DigitalStorageRecord.fromNbt(capturedNbt, () -> { });
        expectEquals(5, onlyView(captured.storage()).getAmount(), "captured snapshot changed after mutation");
        expectEquals(5L, captured.storage().totalItemCount(),
                "captured snapshot cached total");
        expectEquals(9, onlyView(record.storage()).getAmount(), "live record amount after snapshot mutation");
    }

    private static void filterAppliesOnlyWhenCreatingANewVariant(ItemVariant stone) {
        AtomicBoolean allowNewVariants = new AtomicBoolean(true);
        DigitalItemStorage storage = new DigitalItemStorage(
                () -> { },
                () -> 64,
                variant -> allowNewVariants.get()
        );
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.insert(stone, 1, transaction), "initial allowed variant");
            transaction.commit();
        }
        allowNewVariants.set(false);
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(5, storage.insert(stone, 5, transaction), "existing variant after filter tightened");
            expectEquals(0, storage.insert(ItemVariant.of(Items.DIRT), 1, transaction),
                    "new variant after filter tightened");
            transaction.commit();
        }
        expectEquals(6, onlyView(storage).getAmount(), "existing variant amount after filter test");
    }

    private static void insertionPolicyAppliesToExistingVariantsButNeverExtraction(ItemVariant stone) {
        AtomicBoolean allowInsert = new AtomicBoolean(true);
        DigitalItemStorage storage = new DigitalItemStorage(
                () -> { },
                () -> 64,
                variant -> allowInsert.get(),
                variant -> true
        );
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(4, storage.insert(stone, 4, transaction), "initial policy-permitted insert");
            transaction.commit();
        }

        allowInsert.set(false);
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(0, storage.insert(stone, 1, transaction), "existing variant after insert policy tightened");
            expectEquals(2, storage.extract(stone, 2, transaction), "extraction after insert policy tightened");
            transaction.commit();
        }
        expectEquals(2, onlyView(storage).getAmount(), "remaining amount after policy extraction");
    }

    private static void volumeUnstackablePolicyIsDynamicAndPreservesExtraction() {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        boolean originalServerPolicy = config.allowUnstackableItems;
        String originalFilterMode = config.itemFilterMode;
        java.util.List<String> originalFilterItems = new java.util.ArrayList<>(config.itemFilterItems);
        java.util.List<String> originalFilterTags = new java.util.ArrayList<>(config.itemFilterTags);
        try {
            config.allowUnstackableItems = true;
            config.itemFilterMode = "blacklist";
            config.itemFilterItems = new java.util.ArrayList<>();
            config.itemFilterTags = new java.util.ArrayList<>();
            ItemSecurityPolicy.reload();
            DigitalStorageRecord record = DigitalStorageRecord.createNew(() -> { });
            ItemVariant pickaxe = ItemVariant.of(Items.IRON_PICKAXE);

            expectFalse(record.acceptsUnstackableItems(), "new volume did not default to Reject");
            expectFalse(record.canInsert(pickaxe), "Tom-facing policy accepted a tool in Reject mode");
            try (Transaction transaction = Transaction.openOuter()) {
                expectEquals(0, record.storage().insert(pickaxe, 1, transaction),
                        "Reject volume accepted a new unstackable item");
            }

            expectTrue(record.setAcceptUnstackableItems(true), "Accept setting did not change");
            expectTrue(record.canInsert(pickaxe), "Tom-facing policy rejected a tool in Accept mode");
            try (Transaction transaction = Transaction.openOuter()) {
                expectEquals(1, record.storage().insert(pickaxe, 1, transaction),
                        "Accept volume rejected an unstackable item");
                transaction.commit();
            }

            expectTrue(record.setAcceptUnstackableItems(false), "Reject setting did not change");
            try (Transaction transaction = Transaction.openOuter()) {
                expectEquals(0, record.storage().insert(pickaxe, 1, transaction),
                        "tightened policy accepted another unstackable item");
                expectEquals(1, record.storage().extract(pickaxe, 1, transaction),
                        "tightened policy stranded an existing unstackable item");
                transaction.commit();
            }
            expectEquals(0L, record.storage().amountOf(pickaxe),
                    "extracted unstackable item remained in storage");
        } finally {
            config.allowUnstackableItems = originalServerPolicy;
            config.itemFilterMode = originalFilterMode;
            config.itemFilterItems = originalFilterItems;
            config.itemFilterTags = originalFilterTags;
            ItemSecurityPolicy.reload();
        }
    }

    private static void volumeUnstackablePolicyPersistsAndMigrates() {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        boolean originalServerPolicy = config.allowUnstackableItems;
        try {
            DigitalStorageRecord record = DigitalStorageRecord.createNew(() -> { });
            expectTrue(record.setAcceptUnstackableItems(true), "persistence seed policy did not change");
            net.minecraft.nbt.NbtCompound saved = new net.minecraft.nbt.NbtCompound();
            record.snapshot().writeNbt(saved);
            DigitalStorageRecord reloaded = DigitalStorageRecord.fromNbt(saved, () -> { });
            expectTrue(reloaded.acceptsUnstackableItems(), "saved Accept policy did not round trip");

            saved.remove("AcceptUnstackableItems");
            AtomicInteger migrations = new AtomicInteger();
            config.allowUnstackableItems = false;
            DigitalStorageRecord legacyReject = DigitalStorageRecord.fromNbt(saved, migrations::incrementAndGet);
            expectFalse(legacyReject.acceptsUnstackableItems(),
                    "legacy volume did not preserve server-wide Reject behavior");

            config.allowUnstackableItems = true;
            DigitalStorageRecord legacyAccept = DigitalStorageRecord.fromNbt(saved, migrations::incrementAndGet);
            expectTrue(legacyAccept.acceptsUnstackableItems(),
                    "legacy volume did not preserve server-wide Accept behavior");
            expectEquals(2, migrations.get(), "legacy volume policy migration did not mark records dirty");
        } finally {
            config.allowUnstackableItems = originalServerPolicy;
            ItemSecurityPolicy.reload();
        }
    }

    private static void oversizedVariantNbtIsMeasurableAndRejectable() {
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        nbt.putString("Payload", "x".repeat(70_000));
        ItemVariant variant = ItemVariant.of(Items.PAPER, nbt);
        int measuredBytes = ItemSecurityPolicy.variantNbtBytes(variant);
        expectFalse(ItemSecurityPolicy.isNbtWithinLimit(variant, 65_536),
                "oversized variant NBT passed the configured limit");
        expectTrue(ItemSecurityPolicy.isNbtWithinLimit(variant, measuredBytes),
                "variant NBT size estimator rejected a sufficient limit");
        expectFalse(ItemSecurityPolicy.isNbtWithinLimit(variant, measuredBytes - 1),
                "variant NBT size estimator accepted a one-byte-short limit");
    }

    private static void aggregateVariantNbtBudgetIsTransactional() {
        ItemVariant stone = ItemVariant.of(Items.STONE);
        ItemVariant dirt = ItemVariant.of(Items.DIRT);
        int stoneBytes = stone.toNbt().getSizeInBytes();
        int dirtBytes = dirt.toNbt().getSizeInBytes();
        long budget = stoneBytes + dirtBytes - 1L;
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, () -> 2, () -> budget);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.insert(stone, 1, transaction), "first aggregate-NBT variant");
            transaction.commit();
        }
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(0, storage.insert(dirt, 1, transaction), "aggregate-NBT budget overflow");
        }
        expectEquals(stoneBytes, storage.totalVariantNbtBytes(), "aggregate-NBT bytes after rejection");

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.extract(stone, 1, transaction), "remove aggregate-NBT variant");
            transaction.abort();
        }
        expectEquals(stoneBytes, storage.totalVariantNbtBytes(), "aggregate-NBT bytes after rollback");

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.extract(stone, 1, transaction), "free aggregate-NBT budget");
            transaction.commit();
        }
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(1, storage.insert(dirt, 1, transaction), "reuse aggregate-NBT budget");
            transaction.commit();
        }
        expectEquals(dirtBytes, storage.totalVariantNbtBytes(), "aggregate-NBT bytes after replacement");
    }

    private static void incrementalSnapshotRestartsAfterMutation() {
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, 4);
        ItemVariant stone = ItemVariant.of(Items.STONE);
        ItemVariant dirt = ItemVariant.of(Items.DIRT);
        ItemVariant gravel = ItemVariant.of(Items.GRAVEL);
        try (Transaction transaction = Transaction.openOuter()) {
            storage.insert(stone, 1, transaction);
            storage.insert(dirt, 2, transaction);
            storage.insert(gravel, 3, transaction);
            transaction.commit();
        }

        DigitalItemStorage.SnapshotCursor cursor = storage.snapshotCursor();
        DigitalItemStorage.CursorProgress first = cursor.advance(1);
        expectFalse(first.complete(), "incremental snapshot ignored variant budget");
        expectFalse(first.restarted(), "initial snapshot was reported as a restart");
        expectEquals(1, first.examinedEntries(), "incremental snapshot first-tick work");
        try (Transaction transaction = Transaction.openOuter()) {
            storage.insert(stone, 4, transaction);
            transaction.commit();
        }

        DigitalItemStorage.CursorProgress progress = cursor.advance(1);
        expectTrue(progress.restarted(), "mutation restart was not reported to the scheduler");
        do {
            expectTrue(progress.examinedEntries() <= 1, "incremental snapshot exceeded per-tick budget");
            if (!progress.complete()) {
                progress = cursor.advance(1);
            }
        } while (!progress.complete());
        long snapshotTotal = progress.snapshots().stream().mapToLong(DigitalItemStorage.StoredEntrySnapshot::amount).sum();
        expectEquals(10, snapshotTotal, "incremental snapshot did not restart after mutation");
    }

    private static void continuouslyMutatingVolumeCannotStarvePersistence() {
        java.nio.file.Path root = temporaryStorageDirectory("snapshot-liveness");
        DigitalStorageState state = DigitalStorageState.openForTest(root);
        try {
            java.util.UUID owner = java.util.UUID.randomUUID();
            StorageVolume hot = state.createVolume(owner, "Hot", 3).orElseThrow();
            StorageVolume coldOne = state.createVolume(owner, "Cold one", 3).orElseThrow();
            StorageVolume coldTwo = state.createVolume(owner, "Cold two", 3).orElseThrow();
            ItemVariant hotVariant = null;
            for (int index = 0; index < 8; index++) {
                net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
                nbt.putInt("SnapshotLiveness", index);
                ItemVariant variant = ItemVariant.of(Items.PAPER, nbt);
                if (hotVariant == null) {
                    hotVariant = variant;
                }
                try (Transaction transaction = Transaction.openOuter()) {
                    expectEquals(1, hot.record().storage().insert(variant, 1, transaction),
                            "hot-volume liveness seed " + index);
                    transaction.commit();
                }
            }
            try (Transaction transaction = Transaction.openOuter()) {
                coldOne.record().storage().insert(ItemVariant.of(Items.DIRT), 1, transaction);
                coldTwo.record().storage().insert(ItemVariant.of(Items.GRAVEL), 1, transaction);
                transaction.commit();
            }

            boolean coldOneCompleted = false;
            boolean coldTwoCompleted = false;
            boolean hotCompleted = false;
            int steps = 0;
            while (!hotCompleted && steps++ < 12) {
                Set<java.util.UUID> completed = state.drainVolumeSnapshotsForTest(1, 2);
                coldOneCompleted |= completed.contains(coldOne.id());
                coldTwoCompleted |= completed.contains(coldTwo.id());
                hotCompleted |= completed.contains(hot.id());
                if (!hotCompleted) {
                    try (Transaction transaction = Transaction.openOuter()) {
                        hot.record().storage().insert(hotVariant, 1, transaction);
                        transaction.commit();
                    }
                }
            }

            expectTrue(coldOneCompleted && coldTwoCompleted,
                    "continuously mutating volume starved a cold volume");
            expectTrue(hotCompleted, "continuously mutating volume never produced a snapshot");
            expectTrue(state.snapshotRestartCount() >= DigitalStorageState.MAX_INCREMENTAL_SNAPSHOT_RESTARTS,
                    "hot-volume restart threshold was not exercised");
            expectEquals(1L, state.forcedSnapshotCount(), "hot-volume forced snapshot count");
        } finally {
            state.closeForTest();
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void agedDirtyVolumeForcesSnapshotCompletion() {
        java.nio.file.Path root = temporaryStorageDirectory("snapshot-dirty-age");
        DigitalStorageState state = DigitalStorageState.openForTest(root);
        try {
            StorageVolume volume = state.createVolume(java.util.UUID.randomUUID(), "Aged", 1).orElseThrow();
            for (int index = 0; index < 8; index++) {
                net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
                nbt.putInt("SnapshotDirtyAge", index);
                try (Transaction transaction = Transaction.openOuter()) {
                    volume.record().storage().insert(ItemVariant.of(Items.PAPER, nbt), 1, transaction);
                    transaction.commit();
                }
            }
            state.advancePersistenceTicksForTest(DigitalStorageState.MAX_DIRTY_VOLUME_AGE_TICKS);

            Set<java.util.UUID> completed = state.drainVolumeSnapshotsForTest(1, 2);
            expectTrue(completed.contains(volume.id()), "aged dirty volume did not force snapshot completion");
            expectEquals(1L, state.forcedSnapshotCount(), "aged dirty-volume forced snapshot count");
        } finally {
            state.closeForTest();
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void stressVariantCapacityAtHardLimit() {
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, DigitalStorageRecord.ABSOLUTE_MAX_VARIANTS);
        for (int index = 0; index < DigitalStorageRecord.ABSOLUTE_MAX_VARIANTS; index++) {
            net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
            nbt.putInt("StressVariant", index);
            try (Transaction transaction = Transaction.openOuter()) {
                expectEquals(1, storage.insert(ItemVariant.of(Items.PAPER, nbt), 1, transaction),
                        "stress variant insert " + index);
                transaction.commit();
            }
        }
        net.minecraft.nbt.NbtCompound overflowNbt = new net.minecraft.nbt.NbtCompound();
        overflowNbt.putInt("StressVariant", DigitalStorageRecord.ABSOLUTE_MAX_VARIANTS);
        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(0, storage.insert(ItemVariant.of(Items.PAPER, overflowNbt), 1, transaction),
                    "variant beyond hard capacity");
            transaction.commit();
        }
        expectEquals(DigitalStorageRecord.ABSOLUTE_MAX_VARIANTS, storage.variantCount(),
                "stress variant count");
    }

    private static void randomizedTransactionsConserveItems() {
        ItemVariant[] variants = {
                ItemVariant.of(Items.STONE),
                ItemVariant.of(Items.DIRT),
                ItemVariant.of(Items.COBBLESTONE),
                ItemVariant.of(Items.DIAMOND)
        };
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, variants.length);
        Map<ItemVariant, Long> expected = new HashMap<>();
        Random random = new Random(0xD15EA5EL);
        for (int operation = 0; operation < 5_000; operation++) {
            ItemVariant variant = variants[random.nextInt(variants.length)];
            long requested = 1 + random.nextInt(100);
            boolean insert = random.nextBoolean();
            boolean commit = random.nextInt(4) != 0;
            long moved;
            try (Transaction transaction = Transaction.openOuter()) {
                moved = insert
                        ? storage.insert(variant, requested, transaction)
                        : storage.extract(variant, requested, transaction);
                if (commit) {
                    transaction.commit();
                }
            }
            if (commit) {
                long previous = expected.getOrDefault(variant, 0L);
                long updated = insert ? previous + moved : previous - moved;
                if (updated == 0) {
                    expected.remove(variant);
                } else {
                    expected.put(variant, updated);
                }
            }
        }

        Map<ItemVariant, Long> actual = new HashMap<>();
        storage.forEach(actual::put);
        expectEquals(expected, actual, "randomized committed storage ledger");
        long expectedTotal = expected.values().stream().mapToLong(Long::longValue).sum();
        expectEquals(expected.size(), storage.variantCount(), "randomized cached variant count");
        expectEquals(expectedTotal, storage.totalItemCount(), "randomized cached total item count");
    }

    private static void accountVolumeLimitAndCanonicalIdentityAreStable() {
        java.nio.file.Path root = temporaryStorageDirectory("round-trip");
        try {
            java.util.UUID owner = java.util.UUID.randomUUID();
            java.util.UUID volumeId;
            DigitalStorageState state = DigitalStorageState.openForTest(root);
            try {
                StorageVolume volume = state.createVolume(owner, "Primary", 1).orElseThrow();
                volumeId = volume.id();
                expectTrue(state.createVolume(owner, "Overflow", 1).isEmpty(),
                        "account volume limit was not enforced");
                expectEquals(1, state.volumes(owner).size(), "account volume index size");
                expectTrue(state.ownsVolume(owner, volumeId), "volume ownership lookup");
                expectFalse(state.ownsVolume(java.util.UUID.randomUUID(), volumeId),
                        "foreign volume ownership lookup");

                DigitalItemStorage first = volume.record().storage();
                DigitalItemStorage second = state.volume(volumeId).orElseThrow().record().storage();
                expectTrue(first == second, "same volume UUID did not return the canonical Storage object");
                try (Transaction transaction = Transaction.openOuter()) {
                    expectEquals(42, first.insert(ItemVariant.of(Items.STONE), 42, transaction),
                            "volume round-trip seed");
                    transaction.commit();
                }
                expectFalse(state.deleteEmptyVolume(owner, volumeId), "non-empty volume was deleted");
                state.flushAndWaitForTest();
                expectEquals(0, state.dirtyAccountCount(), "dirty account count after flush");
                expectEquals(0, state.dirtyVolumeCount(), "dirty volume count after flush");
                expectTrue(state.lastNbtEncodeThreadName().startsWith("DigitalStorage-Writer"),
                        "NBT snapshot was encoded outside the background writer");
                DigitalStorageState.StorageSizeStats sizes = state.storageSizeStats();
                expectTrue(sizes.accountDiskBytes() > 0, "account disk size telemetry was empty");
                expectTrue(sizes.volumeDiskBytes() > 0, "volume disk size telemetry was empty");
                expectTrue(sizes.totalEstimatedVolumeNbtBytes() > 0, "volume NBT size telemetry was empty");
                DigitalStorageState.ContentStats liveContent = state.contentStats(false);
                expectEquals(1L, liveContent.variantCount(), "live content variant count");
                expectEquals(java.math.BigInteger.valueOf(42), liveContent.totalItemCount(),
                        "live content item count");
                expectEquals(1, liveContent.inspectedVolumeCount(), "live inspected volume count");
                expectEquals(0, liveContent.uninspectedVolumeCount(), "live uninspected volume count");
                expectEquals(
                        sizes.totalEstimatedVolumeNbtBytes(),
                        sizes.largestEstimatedVolumeNbtBytes(),
                        "single-volume largest NBT size"
                );
                expectEquals(
                        sizes.totalEstimatedVolumeNbtBytes(),
                        sizes.averageEstimatedVolumeNbtBytes(),
                        "single-volume average NBT size"
                );
                volume.record();
                state.volume(volumeId).orElseThrow().record();
                expectEquals(0, state.dirtyVolumeCount(), "read-only record lookup marked the volume dirty");
                expectTrue(java.nio.file.Files.isRegularFile(root.resolve("accounts").resolve(owner + ".dat")),
                        "independent account file was not written");
                expectTrue(java.nio.file.Files.isRegularFile(root.resolve("volumes").resolve(volumeId + ".dat")),
                        "independent volume file was not written");
            } finally {
                state.closeForTest();
            }

            DigitalStorageState reloaded = DigitalStorageState.openForTest(root);
            try {
                expectEquals(0, reloaded.loadedVolumeCountForTest(),
                        "volume contents were eagerly loaded at startup");
                DigitalStorageState.ContentStats fastContent = reloaded.contentStats(false);
                expectEquals(0L, fastContent.variantCount(), "cold fast content variant count");
                expectEquals(java.math.BigInteger.ZERO, fastContent.totalItemCount(),
                        "cold fast content item count");
                expectEquals(0, fastContent.inspectedVolumeCount(), "cold fast inspected volume count");
                expectEquals(1, fastContent.uninspectedVolumeCount(), "cold fast uninspected volume count");
                expectEquals(0, reloaded.loadedVolumeCountForTest(),
                        "fast content statistics cold-loaded a volume");
                DigitalStorageState.ContentStats deepContent = reloaded.contentStats(true);
                expectEquals(1L, deepContent.variantCount(), "deep content variant count");
                expectEquals(java.math.BigInteger.valueOf(42), deepContent.totalItemCount(),
                        "deep content item count");
                expectEquals(1, deepContent.inspectedVolumeCount(), "deep inspected volume count");
                expectEquals(0, deepContent.uninspectedVolumeCount(), "deep uninspected volume count");
                StorageVolume reloadedVolume = reloaded.volume(volumeId).orElseThrow();
                expectEquals(1, reloaded.loadedVolumeCountForTest(),
                        "lazy volume was not retained while referenced");
                expectEquals(owner, reloadedVolume.ownerId(), "volume owner after file round trip");
                expectEquals("Primary", reloadedVolume.name(), "volume name after file round trip");
                expectEquals(42, onlyView(reloadedVolume.record().storage()).getAmount(),
                        "volume items after file round trip");
                expectEquals(1, reloaded.accountCount(), "account file round trip");
                try (Transaction transaction = Transaction.openOuter()) {
                    expectEquals(42, reloadedVolume.record().storage().extract(
                            ItemVariant.of(Items.STONE),
                            42,
                            transaction
                    ), "volume deletion test extraction");
                    transaction.commit();
                }
                expectTrue(reloaded.deleteEmptyVolume(owner, volumeId), "empty volume deletion was rejected");
            } finally {
                reloaded.closeForTest();
            }

            DigitalStorageState afterDeletion = DigitalStorageState.openForTest(root);
            try {
                expectEquals(0, afterDeletion.volumeCount(), "deleted volume returned after reload");
                expectEquals(0, afterDeletion.accountCount(), "empty account returned after reload");
                expectEquals(0, afterDeletion.storageSizeStats().liveDiskBytes(),
                        "deleted storage files remained in size telemetry");
            } finally {
                afterDeletion.closeForTest();
            }
        } finally {
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void forceClearResetsBothBindingFields() {
        dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity accessor =
                new dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity(
                        net.minecraft.util.math.BlockPos.ORIGIN,
                        dev.kehai.digitalstorage.DigitalStorageMod.DIGITAL_STORAGE_ACCESSOR.getDefaultState()
                );
        net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
        nbt.putUuid("ControllerId", java.util.UUID.randomUUID());
        nbt.putUuid("BoundVolumeId", java.util.UUID.randomUUID());
        accessor.readNbt(nbt);
        expectTrue(accessor.isBound(), "force-clear test accessor did not load as bound");
        expectEquals(
                dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity.BindResult.SUCCESS,
                accessor.forceClearBinding(),
                "force-clear result"
        );
        expectFalse(accessor.isBound(), "force-clear left accessor bound");
        expectTrue(accessor.controllerId().isEmpty(), "force-clear left controller UUID");
        expectTrue(accessor.boundVolumeId().isEmpty(), "force-clear left volume UUID");
        expectEquals(
                dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity.BindResult.NOT_BOUND,
                accessor.forceClearBinding(),
                "second force-clear result"
        );
    }

    private static void orphanedBindingSelfHealsAfterVolumeDeletion() {
        java.nio.file.Path root = temporaryStorageDirectory("orphan-binding");
        DigitalStorageState state = DigitalStorageState.openForTest(root);
        try {
            java.util.UUID ownerId = java.util.UUID.randomUUID();
            StorageVolume volume = state.createVolume(ownerId, "Orphan test", 1).orElseThrow();
            dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity accessor =
                    new dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity(
                            net.minecraft.util.math.BlockPos.ORIGIN,
                            dev.kehai.digitalstorage.DigitalStorageMod.DIGITAL_STORAGE_ACCESSOR.getDefaultState()
                    );
            net.minecraft.nbt.NbtCompound nbt = new net.minecraft.nbt.NbtCompound();
            nbt.putUuid("ControllerId", ownerId);
            nbt.putUuid("BoundVolumeId", volume.id());
            accessor.readNbt(nbt);

            expectFalse(accessor.clearOrphanedBinding(state), "valid binding was cleared");
            expectTrue(accessor.isBound(), "valid binding no longer appeared bound");
            expectTrue(state.deleteEmptyVolume(ownerId, volume.id()), "orphan test volume deletion failed");
            expectTrue(accessor.clearOrphanedBinding(state), "missing volume binding was not cleared");
            expectFalse(accessor.isBound(), "orphaned accessor remained bound");
            expectTrue(accessor.controllerId().isEmpty(), "orphan self-heal left controller UUID");
            expectTrue(accessor.boundVolumeId().isEmpty(), "orphan self-heal left volume UUID");
            expectFalse(accessor.clearOrphanedBinding(state), "orphan self-heal was not idempotent");
        } finally {
            state.closeForTest();
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void tomsMergedStorageDeduplicatesAndFallsBack(ItemVariant stone) {
        try {
            Class<?> mergedStorageClass = Class.forName("com.tom.storagemod.util.MergedStorage");
            Object mergedStorage = mergedStorageClass.getConstructor().newInstance();
            java.lang.reflect.Method add = mergedStorageClass.getMethod(
                    "add",
                    net.fabricmc.fabric.api.transfer.v1.storage.Storage.class
            );
            java.lang.reflect.Method getStorages = mergedStorageClass.getMethod("getStorages");
            java.lang.reflect.Method insert = mergedStorageClass.getMethod(
                    "insert",
                    net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.class,
                    long.class,
                    net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext.class
            );

            java.util.UUID volumeId = java.util.UUID.randomUUID();
            DigitalItemStorage canonical = new DigitalItemStorage(volumeId, () -> { }, 64);
            DigitalItemStorage alias = new DigitalItemStorage(volumeId, () -> { }, 64);
            add.invoke(mergedStorage, canonical);
            add.invoke(mergedStorage, alias);
            java.util.Collection<?> parts = (java.util.Collection<?>) getStorages.invoke(mergedStorage);
            expectEquals(1, parts.size(), "Tom MergedStorage Volume UUID deduplication");
            dev.kehai.digitalstorage.optimization.TomDigitalEndpointTracker endpointTracker =
                    (dev.kehai.digitalstorage.optimization.TomDigitalEndpointTracker) mergedStorage;
            expectEquals(2, endpointTracker.digitalstorage$rawDigitalEndpoints().size(),
                    "Tom pre-deduplication endpoint count");

            Object fallbackMerged = mergedStorageClass.getConstructor().newInstance();
            RejectingStorage rejecting = new RejectingStorage();
            DigitalItemStorage fallback = new DigitalItemStorage(() -> { }, 64);
            add.invoke(fallbackMerged, rejecting);
            add.invoke(fallbackMerged, fallback);
            try (Transaction transaction = Transaction.openOuter()) {
                long inserted = (long) insert.invoke(fallbackMerged, stone, 8L, transaction);
                expectEquals(8, inserted, "Tom fallback insert amount");
                transaction.commit();
            }
            expectEquals(1, rejecting.insertCalls, "Tom rejected-storage attempt count");
            expectEquals(8, onlyView(fallback).getAmount(), "Tom fallback destination amount");

            Object cursorMerged = mergedStorageClass.getConstructor().newInstance();
            java.lang.reflect.Method clear = mergedStorageClass.getMethod("clear");
            DigitalItemStorage oldSource = storageWith(stone, 128);
            DigitalItemStorage rebuiltSource = storageWith(stone, 64);
            DigitalItemStorage cursorDestination = new DigitalItemStorage(() -> { }, 1);
            add.invoke(cursorMerged, oldSource);
            try (Transaction transaction = Transaction.openOuter()) {
                expectEquals(64, HopperTransferOptimizer.moveFiltered(
                        (Storage<ItemVariant>) cursorMerged,
                        cursorDestination,
                        stone::equals,
                        64,
                        transaction
                ), "Tom cursor initial transfer");
                transaction.commit();
            }
            clear.invoke(cursorMerged);
            add.invoke(cursorMerged, rebuiltSource);
            try (Transaction transaction = Transaction.openOuter()) {
                expectEquals(64, HopperTransferOptimizer.moveFiltered(
                        (Storage<ItemVariant>) cursorMerged,
                        cursorDestination,
                        stone::equals,
                        64,
                        transaction
                ), "Tom cursor transfer after topology rebuild");
                transaction.commit();
            }
            expectEquals(64, onlyView(oldSource).getAmount(), "Tom cursor reused detached storage view");
            expectFalse(rebuiltSource.iterator().hasNext(), "Tom cursor did not drain rebuilt source");
            expectEquals(128, onlyView(cursorDestination).getAmount(), "Tom cursor destination total");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Tom MergedStorage compatibility self-test failed", exception);
        }
    }

    private static void hopperTierBatchLimitsAreDeviceScoped() {
        com.tom.storagemod.tile.BasicInventoryHopperBlockEntity normal =
                new com.tom.storagemod.tile.BasicInventoryHopperBlockEntity(
                        net.minecraft.util.math.BlockPos.ORIGIN,
                        com.tom.storagemod.Content.invHopperBasic.get().getDefaultState()
                );
        com.tom.storagemod.tile.BasicInventoryHopperBlockEntity advanced =
                new com.tom.storagemod.tile.BasicInventoryHopperBlockEntity(
                        net.minecraft.util.math.BlockPos.ORIGIN,
                        dev.kehai.digitalstorage.DigitalStorageMod.ADVANCED_INVENTORY_HOPPER.getDefaultState()
                );
        dev.kehai.digitalstorage.config.DigitalStorageConfig config =
                dev.kehai.digitalstorage.config.DigitalStorageConfig.get();
        expectEquals(config.normalHopperBatchSize, HopperTransferOptimizer.batchLimit(normal),
                "normal Tom hopper batch tier");
        expectEquals(config.advancedHopperBatchSize, HopperTransferOptimizer.batchLimit(advanced),
                "advanced hopper batch tier");
        expectTrue(com.tom.storagemod.Content.invHopperBasicTile.get().supports(advanced.getCachedState()),
                "Tom hopper block entity type does not support the advanced block");
    }

    private static void missingTierFallsBackWithoutReducingCapacity(ItemVariant stone) {
        net.minecraft.nbt.NbtCompound recordNbt = new net.minecraft.nbt.NbtCompound();
        recordNbt.putString("Tier", "digitalstorage:removed_tier");
        recordNbt.putInt("LastKnownVariantCapacity", 200);
        net.minecraft.nbt.NbtCompound itemNbt = new net.minecraft.nbt.NbtCompound();
        itemNbt.put("Variant", stone.toNbt());
        itemNbt.putLong("Amount", 1);
        net.minecraft.nbt.NbtList items = new net.minecraft.nbt.NbtList();
        items.add(itemNbt);
        recordNbt.put("Items", items);

        DigitalStorageRecord record = DigitalStorageRecord.fromNbt(recordNbt, () -> { });
        expectEquals(
                DigitalStorageTierRegistry.INSTANCE.tierAtLeastCapacity(200).variantCapacity(),
                record.variantCapacity(),
                "missing tier fallback"
        );
    }

    private static void duplicateStoredVariantsAreMergedWithoutLoss(ItemVariant stone) {
        net.minecraft.nbt.NbtCompound recordNbt = recordWithItems(stone, 10, 20);
        DigitalStorageRecord record = DigitalStorageRecord.fromNbt(recordNbt, () -> { });
        expectEquals(30, onlyView(record.storage()).getAmount(), "duplicate stored variant merge");
    }

    private static void futureStorageSchemaIsRejected() {
        java.nio.file.Path root = temporaryStorageDirectory("future-schema");
        try {
            java.util.UUID id = java.util.UUID.randomUUID();
            java.nio.file.Path volumes = root.resolve("volumes");
            java.nio.file.Files.createDirectories(volumes);
            net.minecraft.nbt.NbtCompound future = new net.minecraft.nbt.NbtCompound();
            future.putInt("SchemaVersion", DigitalStorageState.VOLUME_SCHEMA_VERSION + 1);
            future.putUuid("Id", id);
            net.minecraft.nbt.NbtIo.writeCompressed(future, volumes.resolve(id + ".dat").toFile());
            DigitalStorageState state = DigitalStorageState.openForTest(root);
            try {
                state.volume(id);
                throw new IllegalStateException("future volume schema was accepted");
            } catch (IllegalStateException expected) {
                expectTrue(expected.getMessage().contains("newer than supported"),
                        "future schema failure did not explain the downgrade risk");
            } finally {
                state.closeForTest();
            }
            expectTrue(java.nio.file.Files.isRegularFile(volumes.resolve(id + ".dat")),
                    "future-schema file was moved or overwritten");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not create future-schema self-test file", exception);
        } finally {
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static void corruptStorageRecordIsQuarantinedAndPreserved(ItemVariant stone) {
        java.nio.file.Path root = temporaryStorageDirectory("corrupt-volume");
        try {
            java.util.UUID id = java.util.UUID.randomUUID();
            net.minecraft.nbt.NbtCompound corruptRecord = recordWithItems(stone, Long.MAX_VALUE, 1);
            corruptRecord.putInt("SchemaVersion", DigitalStorageState.VOLUME_SCHEMA_VERSION);
            corruptRecord.putUuid("Id", id);
            corruptRecord.putUuid("Owner", java.util.UUID.randomUUID());
            corruptRecord.putString("Name", "Corrupt test volume");
            java.nio.file.Path volumes = root.resolve("volumes");
            java.nio.file.Files.createDirectories(volumes);
            java.nio.file.Path corruptFile = volumes.resolve(id + ".dat");
            net.minecraft.nbt.NbtIo.writeCompressed(corruptRecord, corruptFile.toFile());
            long originalSize = java.nio.file.Files.size(corruptFile);

            DigitalStorageState state = DigitalStorageState.openForTest(root);
            try {
                expectEquals(1, state.volumeCount(), "corrupt record was not lazily indexed");
                expectTrue(state.volume(id).isEmpty(), "corrupt record was returned after lazy load");
                expectEquals(0, state.volumeCount(), "corrupt record remained in the live volume index");
                expectEquals(1, state.quarantinedRecordCount(), "corrupt record quarantine count");
            } finally {
                state.closeForTest();
            }
            expectFalse(java.nio.file.Files.exists(corruptFile), "corrupt volume remained in the live directory");
            java.nio.file.Path quarantineDirectory = root.resolve("quarantine").resolve("volumes");
            try (java.util.stream.Stream<java.nio.file.Path> quarantined = java.nio.file.Files.list(quarantineDirectory)) {
                java.util.List<java.nio.file.Path> files = quarantined.filter(java.nio.file.Files::isRegularFile).toList();
                expectEquals(1, files.size(), "quarantined volume file count");
                expectEquals(originalSize, java.nio.file.Files.size(files.get(0)),
                        "quarantined volume file size changed");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not create corrupt-volume self-test file", exception);
        } finally {
            deleteTemporaryStorageDirectory(root);
        }
    }

    private static java.nio.file.Path temporaryStorageDirectory(String description) {
        try {
            return java.nio.file.Files.createTempDirectory("digitalstorage-" + description + "-");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not create Digital Storage self-test directory", exception);
        }
    }

    private static void deleteTemporaryStorageDirectory(java.nio.file.Path root) {
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Could not clean Digital Storage self-test directory " + root, exception);
        }
    }

    private static net.minecraft.nbt.NbtCompound recordWithItems(
            ItemVariant variant,
            long... amounts
    ) {
        net.minecraft.nbt.NbtCompound recordNbt = new net.minecraft.nbt.NbtCompound();
        recordNbt.putString("Tier", DigitalStorageTierRegistry.INSTANCE.first().id().toString());
        net.minecraft.nbt.NbtList items = new net.minecraft.nbt.NbtList();
        for (long amount : amounts) {
            net.minecraft.nbt.NbtCompound itemNbt = new net.minecraft.nbt.NbtCompound();
            itemNbt.put("Variant", variant.toNbt());
            itemNbt.putLong("Amount", amount);
            items.add(itemNbt);
        }
        recordNbt.put("Items", items);
        return recordNbt;
    }

    private static void exactHopperTransferMovesOneBatchWithoutIteration(ItemVariant stone) {
        NoIterationStorage source = new NoIterationStorage(storageWith(stone, 128));
        DigitalItemStorage destination = new DigitalItemStorage(() -> { }, 64);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(
                    64,
                    HopperTransferOptimizer.moveExact(source, destination, stone, 64, transaction),
                    "optimized hopper batch"
            );
            transaction.commit();
        }

        expectEquals(1, source.extractCalls, "exact source extraction calls");
        expectEquals(64, onlyView(source.delegate).getAmount(), "source amount after hopper batch");
        expectEquals(64, onlyView(destination).getAmount(), "destination amount after hopper batch");
    }

    private static void fullDestinationDoesNotTouchTheSource(ItemVariant stone) {
        NoIterationStorage source = new NoIterationStorage(storageWith(stone, 128));
        RejectingStorage destination = new RejectingStorage();

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(
                    0,
                    HopperTransferOptimizer.moveExact(source, destination, stone, 64, transaction),
                    "full destination transfer"
            );
            transaction.commit();
        }

        expectEquals(1, destination.insertCalls, "destination simulation calls");
        expectEquals(0, source.extractCalls, "source calls when destination is full");
        expectEquals(128, onlyView(source.delegate).getAmount(), "source changed when destination was full");
    }

    private static void changingDestinationCapacityRollsBackTheSource(ItemVariant stone) {
        NoIterationStorage source = new NoIterationStorage(storageWith(stone, 128));
        ChangingAcceptanceStorage destination = new ChangingAcceptanceStorage();

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(
                    0,
                    HopperTransferOptimizer.moveExact(source, destination, stone, 64, transaction),
                    "capacity changed between simulation and transfer"
            );
            transaction.commit();
        }

        expectEquals(128, onlyView(source.delegate).getAmount(), "failed exact transfer did not roll back source");
        expectEquals(2, destination.insertCalls, "changing destination insert calls");
    }

    private static void filteredHopperTransferMovesOneBatch(ItemVariant stone) {
        DigitalItemStorage source = storageWith(stone, 128);
        DigitalItemStorage destination = new DigitalItemStorage(() -> { }, 64);

        try (Transaction transaction = Transaction.openOuter()) {
            expectEquals(
                    64,
                    HopperTransferOptimizer.moveFiltered(source, destination, variant -> true, 64, transaction),
                    "filtered hopper batch"
            );
            transaction.commit();
        }

        expectEquals(64, onlyView(source).getAmount(), "filtered source amount after hopper batch");
        expectEquals(64, onlyView(destination).getAmount(), "filtered destination amount after hopper batch");
    }

    private static DigitalItemStorage storageWith(ItemVariant variant, long amount) {
        DigitalItemStorage storage = new DigitalItemStorage(() -> { }, 64);
        storage.load(variant, amount);
        return storage;
    }

    private static StorageView<ItemVariant> onlyView(DigitalItemStorage storage) {
        var iterator = storage.iterator();
        expectTrue(iterator.hasNext(), "expected one storage view");
        StorageView<ItemVariant> view = iterator.next();
        expectFalse(iterator.hasNext(), "expected exactly one storage view");
        return view;
    }

    private static void expectEquals(long expected, long actual, String description) {
        if (expected != actual) {
            throw new IllegalStateException(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expectEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expectTrue(boolean value, String description) {
        if (!value) {
            throw new IllegalStateException(description);
        }
    }

    private static void expectFalse(boolean value, String description) {
        expectTrue(!value, description);
    }

    private static final class NoIterationStorage implements Storage<ItemVariant> {
        private final DigitalItemStorage delegate;
        private int extractCalls;

        private NoIterationStorage(DigitalItemStorage delegate) {
            this.delegate = delegate;
        }

        @Override
        public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            return delegate.insert(resource, maxAmount, transaction);
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            extractCalls++;
            return delegate.extract(resource, maxAmount, transaction);
        }

        @Override
        public Iterator<StorageView<ItemVariant>> iterator() {
            throw new IllegalStateException("Exact hopper path must not enumerate source views");
        }
    }

    private static final class RejectingStorage implements Storage<ItemVariant> {
        private int insertCalls;

        @Override
        public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            insertCalls++;
            return 0;
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public Iterator<StorageView<ItemVariant>> iterator() {
            throw new IllegalStateException("Rejecting storage should not be enumerated");
        }
    }

    private static final class ChangingAcceptanceStorage implements Storage<ItemVariant> {
        private int insertCalls;

        @Override
        public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            insertCalls++;
            return insertCalls == 1 ? maxAmount : Math.max(0, maxAmount - 1);
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            return 0;
        }

        @Override
        public Iterator<StorageView<ItemVariant>> iterator() {
            throw new IllegalStateException("Changing acceptance storage should not be enumerated");
        }
    }
}
