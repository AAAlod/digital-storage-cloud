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
