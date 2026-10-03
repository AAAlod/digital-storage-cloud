package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKeyCodec;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import dev.kehai.digitalstorage.storage.StorageVolume;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

public final class ForgeAccessorSelfTest {
    private ForgeAccessorSelfTest() { }

    public static void run(MinecraftServer server) {
        var world = server.overworld();
        var state = DigitalStorageState.get(world);
        int accounts = state.accountCount();
        int count = state.volumeCount();
        UUID owner = UUID.randomUUID();
        var created = new ArrayList<StorageVolume>();
        var first = new ForgeAccessorBlockEntity(new BlockPos(0, world.getMinBuildHeight(), 0),
                ForgeDigitalStorage.ACCESSOR.get().defaultBlockState());
        var alias = new ForgeAccessorBlockEntity(new BlockPos(0, world.getMinBuildHeight(), 1),
                ForgeDigitalStorage.ACCESSOR.get().defaultBlockState());
        first.setLevel(world);
        alias.setLevel(world);
        try {
            expect(!first.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent(), "Unbound accessor exposed item capability");
            var volumeA = state.createVolume(owner, "Forge capability selftest A", 2).orElseThrow();
            created.add(volumeA);
            var volumeB = state.createVolume(owner, "Forge capability selftest B", 2).orElseThrow();
            created.add(volumeB);
            first.load(binding(owner, volumeA.id()));
            alias.load(binding(owner, volumeA.id()));
            first.onLoad();
            alias.onLoad();
            var capability = first.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.DOWN);
            var handler = capability.orElseThrow(() -> new IllegalStateException("Bound item capability missing"));
            for (Direction side : Direction.values()) {
                expect(first.getCapability(ForgeCapabilities.ITEM_HANDLER, side) == capability,
                        "Accessor produced distinct capabilities per direction");
            }
            var aliasCapability = alias.getCapability(ForgeCapabilities.ITEM_HANDLER);
            var aliasHandler = aliasCapability.orElseThrow(() -> new IllegalStateException("Alias capability missing"));
            expect(ForgeDigitalItemStorage.resolveDigital(handler) == ForgeDigitalItemStorage.resolveDigital(aliasHandler),
                    "Volume aliases did not resolve the same canonical ledger");
            expect(handler.insertItem(17, new ItemStack(Items.STONE, 8), false).isEmpty()
                            && aliasHandler.getStackInSlot(17).getCount() == 8, "Alias handler did not observe committed insertion");
            AtomicInteger invalidations = new AtomicInteger();
            capability.addListener(ignored -> invalidations.incrementAndGet());
            first.load(binding(owner, volumeB.id()));
            expect(invalidations.get() == 1 && !capability.isPresent() && handler.getSlots() == 0
                            && handler.extractItem(17, 8, false).isEmpty() && aliasHandler.getStackInSlot(17).getCount() == 8,
                    "Rebinding failed to revoke the old handle or altered an alias");
            var reboundCapability = first.getCapability(ForgeCapabilities.ITEM_HANDLER);
            var rebound = reboundCapability.orElseThrow(() -> new IllegalStateException("Rebound capability missing"));
            expect(rebound != handler && rebound.insertItem(3, new ItemStack(Items.DIRT, 2), false).isEmpty(),
                    "Rebinding reused a revoked handle");
            first.forceClearBinding();
            expect(!reboundCapability.isPresent() && rebound.getSlots() == 0
                            && rebound.insertItem(3, new ItemStack(Items.DIRT, 2), false).getCount() == 2,
                    "Force-clear failed to revoke the bound capability");
            alias.setRemoved();
            expect(!aliasCapability.isPresent() && aliasHandler.extractItem(17, 8, false).isEmpty(),
                    "Removed accessor retained a writable handle");
            alias.clearRemoved();
            alias.onLoad();
            var revived = alias.getCapability(ForgeCapabilities.ITEM_HANDLER)
                    .orElseThrow(() -> new IllegalStateException("Revived capability missing"));
            expect(revived != aliasHandler && aliasHandler.getSlots() == 0 && revived.getStackInSlot(17).getCount() == 8,
                    "Revival reactivated a revoked lease or lost volume contents");
            expect(revived.extractItem(17, 8, false).getCount() == 8 && volumeA.record().storage().totalItemCount() == 0,
                    "Revived accessor failed to extract its ledger contents");
            expect(state.deleteEmptyVolume(owner, volumeA.id()), "Could not remove the empty test volume");
            expect(!alias.getCapability(ForgeCapabilities.ITEM_HANDLER).isPresent() && !alias.isBound()
                            && revived.getSlots() == 0, "Orphan self-heal did not revoke the capability");
        } finally {
            first.setRemoved();
            alias.setRemoved();
            for (var volume : created) {
                var ledger = volume.record().storage();
                try (LedgerTransaction transaction = LedgerTransaction.open()) {
                    for (var snapshot : ledger.snapshotEntries()) {
                        ledger.extract(ItemKeyCodec.read(snapshot.serializedVariant()), Long.MAX_VALUE, transaction);
                    }
                    transaction.commit();
                }
                state.deleteEmptyVolume(owner, volume.id());
            }
        }
        expect(state.accountCount() == accounts && state.volumeCount() == count, "Capability fixtures left an account or volume behind");
        cleanVolumeIsRetainedByLiveCapability(state, world);
    }

    private static void cleanVolumeIsRetainedByLiveCapability(DigitalStorageState state, net.minecraft.server.level.ServerLevel world) {
        Retained fixture = retainedFixture(state, world);
        try {
            state.flushNow(); // Dirty volumes are intentionally strong; test the clean weak-cache case.
            System.gc();
            var retained = fixture.volume.get();
            expect(retained != null && state.volume(fixture.volumeId).orElseThrow() == retained,
                    "Live capability lost its clean volume owner and allowed a second ledger load");
            expect(ForgeDigitalItemStorage.resolveDigital(fixture.handler).ledger() == retained.record().storage(),
                    "Live capability and weak state cache no longer refer to the same ledger");
        } finally {
            fixture.accessor.forceClearBinding();
            fixture.accessor.setRemoved();
            state.deleteEmptyVolume(fixture.owner, fixture.volumeId);
        }
    }

    private static Retained retainedFixture(DigitalStorageState state, net.minecraft.server.level.ServerLevel world) {
        UUID owner = UUID.randomUUID();
        StorageVolume volume = state.createVolume(owner, "Forge clean capability lifetime selftest", 1).orElseThrow();
        var accessor = new ForgeAccessorBlockEntity(new BlockPos(1, world.getMinBuildHeight(), 0),
                ForgeDigitalStorage.ACCESSOR.get().defaultBlockState());
        accessor.setLevel(world);
        accessor.load(binding(owner, volume.id()));
        var handler = accessor.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(() ->
                new IllegalStateException("Lifetime capability missing"));
        return new Retained(owner, volume.id(), new java.lang.ref.WeakReference<>(volume), accessor, handler);
    }

    private record Retained(UUID owner, UUID volumeId, java.lang.ref.WeakReference<StorageVolume> volume,
                            ForgeAccessorBlockEntity accessor, net.minecraftforge.items.IItemHandler handler) { }

    private static CompoundTag binding(UUID owner, UUID volume) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("ControllerId", owner);
        tag.putUUID("BoundVolumeId", volume);
        return tag;
    }
    private static void expect(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
