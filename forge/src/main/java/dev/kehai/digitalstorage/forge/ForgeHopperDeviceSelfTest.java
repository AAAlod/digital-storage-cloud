package dev.kehai.digitalstorage.forge;

import com.tom.storagemod.Content;
import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.DigitalStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;

/** Actual mixed-in Tom entities; no block placement or player data mutations. */
public final class ForgeHopperDeviceSelfTest {
    private ForgeHopperDeviceSelfTest() { }
    public static void run(MinecraftServer server) {
        for (var block : new net.minecraft.world.level.block.Block[]{Content.invHopperBasic.get(), ForgeDigitalStorage.ADVANCED_HOPPER.get()}) {
            var state = block.defaultBlockState();
            var hopper = new BasicInventoryHopperBlockEntity(BlockPos.ZERO, state);
            hopper.setFilter(new ItemStack(Items.STONE));
            var legacy = hopper.saveWithFullMetadata();
            legacy.remove(ForgeHopperState.NBT_KEY);
            var restored = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(BlockPos.ZERO, state, legacy);
            expect(restored != null && !transfer(restored).blocked() && restored.getFilter().is(Items.STONE),
                    "Legacy filter-only Tom entity remains ready");

            var source = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            };
            source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
            var target = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                    return simulate ? ItemStack.EMPTY : stack;
                }
            };
            expect(transfer(hopper).move(source, 0, target, 8).stopped() && transfer(hopper).heldCount() == 8,
                    "Device owns rejected remainder");
            var saved = hopper.saveWithFullMetadata();
            restored = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(BlockPos.ZERO, state, saved);
            expect(restored != null && transfer(restored).blocked() && !transfer(restored).uncertain()
                    && transfer(restored).heldCount() == 8 && restored.getFilter().is(Items.STONE),
                    "Actual entity save/load retains known remainder and filter");
            var invalidIdentity = saved.copy();
            invalidIdentity.put(ForgeHopperState.ID_KEY, StringTag.valueOf("future identity"));
            var unidentified = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(BlockPos.ZERO, state, invalidIdentity);
            expect(unidentified != null && transfer(unidentified).uncertain() && transfer(unidentified).heldCount() == 8
                    && unidentified.saveWithFullMetadata().get(ForgeHopperState.ID_KEY).equals(invalidIdentity.get(ForgeHopperState.ID_KEY)),
                    "Invalid identity preserves items and evidence but cannot authorize handoff");
            evidence(unidentified, true, StringTag.valueOf("future identity"), false);
            var missingIdentity = saved.copy(); missingIdentity.remove(ForgeHopperState.ID_KEY);
            unidentified = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(BlockPos.ZERO, state, missingIdentity);
            expect(unidentified != null && transfer(unidentified).uncertain() && transfer(unidentified).heldCount() == 8,
                    "Stopped legacy mirror without identity requires reconciliation");
            evidence(unidentified, false, null, false);
            var original = transfer(restored);
            restored.load(legacy);
            expect(transfer(restored) == original && transfer(restored).heldCount() == 8,
                    "Reload cannot replace live pending ownership with missing state");

            var future = legacy.copy();
            var opaque = new CompoundTag(); opaque.putInt("Version", 99); opaque.putString("Future", "preserve");
            future.put(ForgeHopperState.NBT_KEY, opaque);
            restored = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(BlockPos.ZERO, state, future);
            expect(restored != null && transfer(restored).blocked() && restored.saveWithFullMetadata()
                    .getCompound(ForgeHopperState.NBT_KEY).equals(opaque), "Future state survives entity round trip");

            var malformed = legacy.copy();
            malformed.put(ForgeHopperState.NBT_KEY, StringTag.valueOf("unknown typed data"));
            restored = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(BlockPos.ZERO, state, malformed);
            expect(restored != null && transfer(restored).blocked() && restored.saveWithFullMetadata()
                    .get(ForgeHopperState.NBT_KEY).equals(malformed.get(ForgeHopperState.NBT_KEY)),
                    "Wrong typed device tag is preserved, not coerced to empty compound");
            malformed.put(ForgeHopperState.ID_KEY, StringTag.valueOf("opaque identity"));
            restored = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(BlockPos.ZERO, state, malformed);
            evidence(restored, true, StringTag.valueOf("opaque identity"), true);
        }

        var source = new ItemStackHandler(1); source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        var target = new ItemStackHandler(1);
        var hopper = new Hopper(server, source, target);
        var saved = hopper.saveWithFullMetadata();
        var future = new CompoundTag(); future.putInt("Version", 99);
        saved.put(ForgeHopperState.NBT_KEY, future);
        hopper.load(saved);
        hopper.attempt();
        expect(source.getStackInSlot(0).getCount() == 10 && target.getStackInSlot(0).isEmpty()
                && transfer(hopper).blocked(), "Blocked state prevents original Tom extraction");
        hopper.setRemoved();
        DigitalStorage.LOGGER.info("Forge hopper device state self-test passed: actual normal/advanced Tom entities, legacy filters, known remainder, reload ownership guard, future/wrong typed tags and original update block; chunk hooks covered by separate world fixture");
    }

    private static ForgeHopperTransfer transfer(BasicInventoryHopperBlockEntity entity) {
        return ((ForgeHopperState) entity).digitalstorage$transferState();
    }
    private static void evidence(BasicInventoryHopperBlockEntity entity, boolean present,
                                 net.minecraft.nbt.Tag raw, boolean unknown) {
        java.nio.file.Path directory = null;
        try {
            directory = java.nio.file.Files.createTempDirectory("digitalstorage-hopper-identity-");
            var custody = new ForgeHopperCustody(directory);
            ((ForgeHopperState) entity).digitalstorage$retain(custody, "minecraft:overworld", BlockPos.ZERO);
            expect(custody.unsavedCount() == 0 && custody.pendingCount() == 1, "Identity evidence saved independently");
            var reopened = new ForgeHopperCustody(directory);
            var entry = reopened.entries().get(0);
            var identity = (CompoundTag) reopened.identityEvidence(entry.id());
            expect(identity != null && identity.getBoolean("PresentAtLoad") == present
                    && (raw == null ? !identity.contains("RawIdentity") : raw.equals(identity.get("RawIdentity"))),
                    "Original missing or wrong typed identity survives external reopen");
            identity.putString("RawIdentity", "mutated");
            expect(!identity.equals(reopened.identityEvidence(entry.id())), "Identity evidence is a defensive copy");
            expect(!entry.confirmed() && entry.observedKnown() != unknown
                    && ForgeHopperCommands.observed(entry).equals(unknown ? "未知" : "8"),
                    "Uncertain observed quantity is distinct from unknown quantity");
            if (unknown) expect(reopened.state(entry.id()).equals(StringTag.valueOf("unknown typed data")),
                    "Opaque transfer state is not wrapped or replaced by identity evidence");
            var oldMirror = entity.saveWithFullMetadata();
            expect(oldMirror.hasUUID(ForgeHopperState.CUSTODY_ID_KEY), "Opaque or missing identity still has stable journal lineage");
            reopened.retire(entry.id(), java.util.UUID.randomUUID(), "External device state inspected; no deliverable remainder");
            var mirrorEntity = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(BlockPos.ZERO, entity.getBlockState(), oldMirror);
            ((ForgeHopperState) mirrorEntity).digitalstorage$reconcile(reopened, "minecraft:overworld", BlockPos.ZERO);
            var ready = mirrorEntity.saveWithFullMetadata();
            expect(!transfer(mirrorEntity).blocked() && transfer(mirrorEntity).heldCount() == 0
                    && ready.hasUUID(ForgeHopperState.ID_KEY)
                    && !ready.getUUID(ForgeHopperState.CUSTODY_ID_KEY).equals(entry.id()),
                    "Actual unknown-identity mirror retires lineage without resurrection");
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Hopper identity evidence fixture failed", failure);
        } finally {
            if (directory != null) try (var files = java.nio.file.Files.walk(directory)) {
                for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
            } catch (java.io.IOException failure) { throw new IllegalStateException("Identity fixture cleanup failed", failure); }
        }
    }
    private static void expect(boolean value, String detail) {
        if (!value) throw new IllegalStateException("Forge hopper device: " + detail);
    }
    private static final class Hopper extends BasicInventoryHopperBlockEntity {
        Hopper(MinecraftServer server, ItemStackHandler source, ItemStackHandler destination) {
            super(BlockPos.ZERO, Content.invHopperBasic.get().defaultBlockState());
            setLevel(server.overworld());
            top = LazyOptional.of(() -> source);
            bottom = LazyOptional.of(() -> destination);
            topNet = false;
        }
        void attempt() { super.update(); }
    }
}
