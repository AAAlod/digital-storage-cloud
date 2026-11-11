package dev.kehai.digitalstorage.platform.fabric.hopper;

import com.tom.storagemod.util.MergedStorage;
import com.tom.storagemod.block.BasicInventoryHopperBlock;
import com.tom.storagemod.block.InventoryCableConnectorBlock;
import dev.kehai.digitalstorage.DigitalStorageMod;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;

/** Runs only in the explicitly designated private audit world. */
public final class BlockStorageGuardSelfTest {
    private BlockStorageGuardSelfTest() { }

    public static void run(ServerLevel world) {
        BlockPos pos = world.getSharedSpawnPos().above(30);
        world.getChunkAt(pos);
        if (!world.getBlockState(pos).isAir()) throw new IllegalStateException("Guard fixture location is occupied");
        try {
            for (boolean network : new boolean[]{false, true}) {
                for (int batch : new int[]{1, 64}) {
                    world.setBlockAndUpdate(pos, Blocks.COMPOSTER.defaultBlockState());
                    Storage<ItemVariant> raw = ItemStorage.SIDED.find(world, pos, Direction.UP);
                    if (raw == null) throw new IllegalStateException("Missing composter storage");
                    Storage<ItemVariant> guarded = BlockStorageGuard.wrap(world, pos, null, raw);
                    Storage<ItemVariant> target = guarded;
                    if (network) {
                        MergedStorage merged = new MergedStorage();
                        merged.add(guarded);
                        target = merged;
                    }
                    SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS, 8));
                    Storage<ItemVariant> source = InventoryStorage.of(inventory, null);
                    try (Transaction tx = Transaction.openOuter()) {
                        expect(HopperTransferOptimizer.moveFiltered(source, target, v -> true, batch, tx) == 1, "valid composter insert");
                    }
                    expect(inventory.getItem(0).getCount() == 8 && world.getBlockState(pos).getValue(ComposterBlock.LEVEL) == 0, "rollback conservation");
                    try (Transaction tx = Transaction.openOuter()) {
                        expect(HopperTransferOptimizer.moveFiltered(source, target, v -> v.isOf(Items.WHEAT_SEEDS), batch, tx) == 1, "filtered insert");
                        tx.commit();
                    }
                    expect(inventory.getItem(0).getCount() == 7 && world.getBlockState(pos).getValue(ComposterBlock.LEVEL) == 1, "committed conservation");
                    world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    world.setBlockAndUpdate(pos, Blocks.COMPOSTER.defaultBlockState());
                    try (Transaction tx = Transaction.openOuter()) {
                        expect(HopperTransferOptimizer.moveFiltered(source, target, v -> true, batch, tx) == 0, "same-block replacement invalidates old storage");
                    }
                    Storage<ItemVariant> replacementStorage = BlockStorageGuard.wrap(world, pos, null,
                            ItemStorage.SIDED.find(world, pos, Direction.UP));
                    try (Transaction tx = Transaction.openOuter()) {
                        expect(HopperTransferOptimizer.moveFiltered(source, replacementStorage, v -> false, batch, tx) == 0, "filter rejection");
                        expect(HopperTransferOptimizer.moveFiltered(source, replacementStorage, v -> true, batch, tx) == 1, "replacement composter accepts items");
                        tx.commit();
                    }
                    expect(inventory.getItem(0).getCount() == 6, "replacement conservation");
                    for (var replacement : new net.minecraft.world.level.block.Block[]{Blocks.AIR, Blocks.CHEST, Blocks.COMPOSTER}) {
                        world.setBlockAndUpdate(pos, replacement.defaultBlockState());
                        try (Transaction tx = Transaction.openOuter()) {
                            for (int tick = 0; tick < 40; tick++) {
                                expect(HopperTransferOptimizer.moveFiltered(source, target, v -> true, batch, tx) == 0, "stale target rejected");
                            }
                        }
                        expect(inventory.getItem(0).getCount() == 6, "stale transfer conservation");
                    }
                    world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
            }
            world.setBlockAndUpdate(pos, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 8));
            Storage<ItemVariant> source = BlockStorageGuard.wrap(world, pos, null,
                    ItemStorage.SIDED.find(world, pos, Direction.DOWN));
            SimpleContainer output = new SimpleContainer(2);
            Storage<ItemVariant> destination = InventoryStorage.of(output, null);
            try (Transaction tx = Transaction.openOuter()) {
                expect(HopperTransferOptimizer.moveFiltered(source, destination, v -> true, 1, tx) == 1, "composter source");
                tx.commit();
            }
            world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            for (int tick = 0; tick < 40; tick++) {
                try (Transaction tx = Transaction.openOuter()) {
                    expect(HopperTransferOptimizer.moveFiltered(source, destination, v -> true, 64, tx) == 0, "stale source view");
                }
            }
            expect(output.getItem(0).getCount() == 1, "source conservation");
            testAdvancedHopper(world, pos.east(4));
            System.out.println("DSC block storage guard fixture passed");
        } finally {
            world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }

    private static void testAdvancedHopper(ServerLevel world, BlockPos hopperPos) {
        BlockPos sourcePos = hopperPos.above(), destinationPos = hopperPos.below();
        if (!world.getBlockState(hopperPos).isAir() || !world.getBlockState(sourcePos).isAir()
                || !world.getBlockState(destinationPos).isAir()) throw new IllegalStateException("Hopper fixture location is occupied");
        var data = world.getServer().getWorldData().overworldData();
        long originalTime = data.getGameTime();
        try {
            world.setBlockAndUpdate(sourcePos, Blocks.CHEST.defaultBlockState());
            world.setBlockAndUpdate(destinationPos, Blocks.COMPOSTER.defaultBlockState());
            world.setBlockAndUpdate(hopperPos, DigitalStorageMod.ADVANCED_INVENTORY_HOPPER.defaultBlockState()
                    .setValue(InventoryCableConnectorBlock.FACING, Direction.DOWN)
                    .setValue(BasicInventoryHopperBlock.ENABLED, true));
            var chest = (net.minecraft.world.Container) world.getBlockEntity(sourcePos);
            chest.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 8));
            var hopper = (FabricAdvancedInventoryHopperBlockEntity) world.getBlockEntity(hopperPos);
            for (int tick = 0; tick < 40; tick++) {
                data.setGameTime(originalTime + tick);
                hopper.updateServer();
            }
            expect(chest.getItem(0).getCount() < 8, "advanced hopper acquired composter and transferred");
            int remaining = chest.getItem(0).getCount();
            world.setBlockAndUpdate(destinationPos, Blocks.AIR.defaultBlockState());
            for (int tick = 40; tick < 80; tick++) {
                data.setGameTime(originalTime + tick);
                hopper.updateServer();
            }
            expect(chest.getItem(0).getCount() == remaining, "advanced hopper ticks after destruction");
        } finally {
            data.setGameTime(originalTime);
            world.setBlockAndUpdate(hopperPos, Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(sourcePos, Blocks.AIR.defaultBlockState());
            world.setBlockAndUpdate(destinationPos, Blocks.AIR.defaultBlockState());
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Block storage guard: " + message);
    }
}
