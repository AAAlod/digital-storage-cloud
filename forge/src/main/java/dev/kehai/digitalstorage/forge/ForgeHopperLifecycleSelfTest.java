package dev.kehai.digitalstorage.forge;

import com.tom.storagemod.Content;
import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import dev.kehai.digitalstorage.DigitalStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.items.ItemStackHandler;

/** Actual chunk hooks run only in the explicitly declared task audit-world. */
public final class ForgeHopperLifecycleSelfTest {
    private ForgeHopperLifecycleSelfTest() { }
    public static void run(MinecraftServer server) {
        String configured = System.getProperty("digitalstorage.integrationTestRoot");
        if (configured == null) return;
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!worldRoot.equals(Path.of(configured).toAbsolutePath().normalize())
                || !worldRoot.toString().replace('\\', '/').contains("/maintenance/work/fabric-decoupling/")
                || !worldRoot.getFileName().toString().equals("audit-world")) {
            throw new IllegalStateException("Hopper lifecycle fixture requires a private audit-world");
        }
        Path root = null;
        try {
            root = Files.createTempDirectory("digitalstorage-hopper-lifecycle-");
            Path fixtureRoot = root;
            var world = server.overworld();
            var pos = new BlockPos(16000, 160, 16000);
            var chunk = world.getChunkAt(pos);
            expect(world.getBlockState(pos).isAir() && chunk.getBlockEntities().isEmpty(), "Untouched private chunk");
            for (var block : new net.minecraft.world.level.block.Block[]{Content.invHopperBasic.get(), ForgeDigitalStorage.ADVANCED_HOPPER.get()}) {
                Path records = fixtureRoot.resolve(UUID.randomUUID().toString());
                var custody = new ForgeHopperCustody(records);
                ForgeHopperLifecycle.withStoreForTest(custody, () -> {
                    try {
                        world.setBlockAndUpdate(pos, block.defaultBlockState());
                        var hopper = (BasicInventoryHopperBlockEntity) world.getBlockEntity(pos);
                        var engine = transfer(hopper);
                        var source = new ItemStackHandler(1) {
                            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
                        };
                        source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
                        var target = new ItemStackHandler(1) {
                            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                                return simulate ? ItemStack.EMPTY : stack;
                            }
                        };
                        expect(engine.move(source, 0, target, 8).stopped(), "Stopped physical transfer");
                        int previous = custody.pendingCount();
                        // Remove before saving: the chunk hook must own the original
                        // engine before setRemoved/endpoint invalidation happens.
                        chunk.removeBlockEntity(pos);
                        var saved = hopper.saveWithFullMetadata();
                        UUID id = saved.getUUID(ForgeHopperState.ID_KEY);
                        expect(custody.pendingCount() == previous + 1 && custody.retainsIdentity(id, engine)
                                && !custody.available(), "Real chunk removal retains engine");
                        var restored = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(pos, block.defaultBlockState(), saved);
                        chunk.setBlockEntity(restored);
                        expect(transfer(restored) == engine && custody.pendingCount() == previous + 1,
                                "Reload binds same-process canonical engine without duplicate ownership");
                        chunk.clearAllBlockEntities();
                        expect(chunk.getBlockEntities().isEmpty() && custody.pendingCount() == previous + 1,
                                "Real unload clear preserves one record");
                        var reopened = new ForgeHopperCustody(records);
                        ForgeHopperLifecycle.withReopenedStoreForTest(reopened, () -> {
                            var afterRestart = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(pos, block.defaultBlockState(), saved);
                            chunk.setBlockEntity(afterRestart);
                            expect(transfer(afterRestart).heldCount() == 8 && transfer(afterRestart).blocked()
                                    && reopened.pendingCount() == previous + 1, "Disk reopen binds mirror once");
                            var conflicting = saved.copy();
                            conflicting.getCompound(ForgeHopperState.NBT_KEY).putInt("Amount", 7);
                            var mismatch = (BasicInventoryHopperBlockEntity) BlockEntity.loadStatic(pos, block.defaultBlockState(), conflicting);
                            chunk.setBlockEntity(mismatch);
                            expect(reopened.pendingCount() == previous + 2 && transfer(mismatch).blocked()
                                    && !mismatch.saveWithFullMetadata().getUUID(ForgeHopperState.ID_KEY).equals(id)
                                    && ((net.minecraft.nbt.CompoundTag) reopened.state(id)).getInt("Amount") == 8,
                                    "Replacement preserves conflicting mirror separately");
                            world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        });
                    } finally { world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); }
                });
            }
            DigitalStorage.LOGGER.info("Forge hopper lifecycle world self-test passed: actual normal/advanced chunk removal, unload clear, same-process canonical ownership, disk reopen binding and mirror conflict isolation; natural chunk eviction and crash delivery not covered");
        } catch (IOException failure) { throw new IllegalStateException("Hopper lifecycle fixture failed", failure); }
        finally {
            if (root != null) {
                try (var files = Files.walk(root)) {
                    for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
                } catch (IOException failure) { throw new IllegalStateException("Cannot clean lifecycle fixture", failure); }
            }
        }
    }
    private static ForgeHopperTransfer transfer(BasicInventoryHopperBlockEntity entity) {
        return ((ForgeHopperState) entity).digitalstorage$transferState();
    }
    private static void expect(boolean condition, String detail) {
        if (!condition) throw new IllegalStateException("Hopper lifecycle: " + detail);
    }
}
