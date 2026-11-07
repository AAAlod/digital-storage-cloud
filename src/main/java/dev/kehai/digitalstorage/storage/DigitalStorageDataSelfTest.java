package dev.kehai.digitalstorage.storage;

import net.minecraft.nbt.CompoundTag;

final class DigitalStorageDataSelfTest {
    private DigitalStorageDataSelfTest() { }

    static void run() {
        modelMetadataAndLookupRoundTrip();
        schemaVersionsAreHandledInMemory();
        detachedSnapshotAndRootGate();
        generationCoalescesAcrossVolumes();
    }

    private static void generationCoalescesAcrossVolumes() {
        var data = new DigitalStorageData(); var owner = java.util.UUID.randomUUID();
        var a = data.createVolume(owner, "a", 0).orElseThrow();
        var b = data.createVolume(owner, "b", 0).orElseThrow();
        long before = data.generationForTest(); var stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
        try (var tx = LedgerTransaction.open()) {
            expect(a.record().storage().insert(stone, 1, tx) == 1 && b.record().storage().insert(stone, 1, tx) == 1, "cross-volume generation fixture");
            tx.commit();
        }
        expect(data.generationForTest() == before + 1, "one cross-volume commit advanced more than once");
    }

    private static void detachedSnapshotAndRootGate() {
        var a = new DigitalStorageData(); var b = new DigitalStorageData();
        var owner = java.util.UUID.randomUUID();
        var va = a.createVolume(owner, "a", 0).orElseThrow();
        var vb = b.createVolume(owner, "b", 0).orElseThrow();
        var stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
        try {
            a.setDirty(false);
            long beforeRollback = a.generationForTest();
            try (var tx = LedgerTransaction.open()) { expect(va.record().storage().insert(stone, 1, tx) == 1, "rollback fixture"); }
            expect(a.generationForTest() > beforeRollback && !a.isDirty(), "rollback did not invalidate generation cleanly");
            try (var tx = LedgerTransaction.open()) {
                expect(va.record().storage().insert(stone, 1, tx) == 1, "snapshot fixture failed");
                expect(a.captureSnapshotForTest(10) == null, "captured an open transaction");
                expect(b.captureSnapshotForTest(100) != null, "other root was blocked");
                tx.commit();
            }
            var snapshot = a.captureSnapshotForTest(1000);
            expect(snapshot != null, "snapshot did not recover after transaction");
            try (var tx = LedgerTransaction.open()) {
                expect(va.record().storage().insert(stone, 1, tx) == 1, "snapshot mutation fixture failed");
                expect(vb.record().storage().insert(stone, 2, tx) == 2, "second root fixture failed");
                tx.commit();
            }
            var restored = DigitalStorageData.load(snapshot);
            expect(restored.volumes.get(va.id()).record().storage().amountOf(stone) == 1, "snapshot aliased live data");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private static void modelMetadataAndLookupRoundTrip() {
        var data = new DigitalStorageData();
        var firstOwner = java.util.UUID.randomUUID();
        var secondOwner = java.util.UUID.randomUUID();
        var first = data.createVolume(firstOwner, "first", 0).orElseThrow();
        var second = data.createVolume(secondOwner, "second", 0).orElseThrow();
        var icon = new net.minecraft.resources.ResourceLocation("minecraft", "diamond_sword");
        expect(first.setIcon(icon), "metadata fixture failed to set icon");
        first.setAcceptUnstackableItems(true);
        var upgraded = dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry.INSTANCE.next(first.record().tierId())
                .orElseThrow(() -> new IllegalStateException("non-default tier fixture requires a second tier"));
        expect(first.record().setTier(upgraded.id()), "metadata fixture failed to set non-default tier");
        var stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
        first.record().storage().load(stone, 7);
        var tier = first.record().tierId();
        int capacity = first.record().variantCapacity();
        var missing = java.util.UUID.randomUUID();
        data.legacyUnreadable.put(missing, "legacy unreadable fixture");
        var loaded = DigitalStorageData.load(data.save(new CompoundTag()));
        var restored = loaded.volumes.get(first.id());
        expect(loaded.accounts.size() == 2 && loaded.volumes.size() == 2
                && restored.name().equals("first") && restored.icon().equals(icon)
                && restored.acceptsUnstackableItems() && restored.record().tierId().equals(tier)
                && restored.record().variantCapacity() == capacity && restored.record().storage().amountOf(stone) == 7,
                "metadata round trip lost account, name, icon, tier, or policy");
        expect(loaded.lookup(first.id()).status() == DigitalStorageState.VolumeStatus.PRESENT
                && loaded.lookup(missing).status() == DigitalStorageState.VolumeStatus.UNREADABLE
                && loaded.lookup(java.util.UUID.randomUUID()).status() == DigitalStorageState.VolumeStatus.CONFIRMED_DELETED,
                "SavedData lookup status mismatch");
        expect(loaded.deleteEmptyVolume(secondOwner, second.id()), "empty volume deletion failed");
        var afterDelete = DigitalStorageData.load(loaded.save(new CompoundTag()));
        expect(!afterDelete.volumes.containsKey(second.id()) && !afterDelete.accounts.containsKey(secondOwner),
                "deleted volume or account returned after serialization");
    }



    private static void schemaVersionsAreHandledInMemory() {
        var data = new DigitalStorageData();
        var old = data.save(new CompoundTag()); old.putInt("SchemaVersion", 0);
        expect(DigitalStorageData.load(old).isDirty(), "old schema did not request a rewrite");
        var newer = data.save(new CompoundTag()); newer.putInt("SchemaVersion", DigitalStorageData.SCHEMA_VERSION + 1);
        var future = DigitalStorageData.load(newer); future.setDirty();
        expect(future.readOnly && !future.isDirty()
                && future.lookup(java.util.UUID.randomUUID()).status() == DigitalStorageState.VolumeStatus.TEMPORARILY_UNAVAILABLE
                && future.createVolume(java.util.UUID.randomUUID(), "denied", 0).isEmpty(), "future schema did not stay read-only");
    }

    private static void expect(boolean value, String description) {
        if (!value) throw new IllegalStateException(description);
    }
}
