package dev.kehai.digitalstorage.forge;

import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Command permissions everywhere; actual probe only in the declared private world. */
public final class ForgePerformanceCommandsSelfTest {
    private ForgePerformanceCommandsSelfTest() { }
    public static void run(MinecraftServer server) {
        var dispatcher = server.getCommands().getDispatcher();
        var root = dispatcher.getRoot().getChild("digitalstorage");
        var admin = server.createCommandSourceStack().withPermission(2);
        var low = admin.withPermission(0);
        for (String name : new String[]{"probe", "benchmark"}) {
            var node = root.getChild(name);
            expect(node != null && node.canUse(admin) && !node.canUse(low), "Performance command permissions");
        }
        expect(dispatcher.getRoot().getChild("dsc").getRedirect() == root, "Performance alias redirect");
        String configured = System.getProperty("digitalstorage.integrationTestRoot");
        if (configured == null) return;
        Path path = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        expect(path.equals(Path.of(configured).toAbsolutePath().normalize())
                && path.toString().replace('\\', '/').contains("/maintenance/work/fabric-decoupling/")
                && path.getFileName().toString().equals("audit-world"), "Unsafe probe fixture world");
        var world = server.overworld(); var pos = new BlockPos(32000, 160, 32000);
        expect(world.getBlockState(pos).isAir() && world.getBlockState(pos.above()).isAir(), "Probe fixture would overwrite blocks");
        world.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        try {
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) world.getBlockEntity(pos);
            chest.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 32));
            expect(dispatcher.execute("digitalstorage probe 32000 160 32000", admin) == 1
                    && dispatcher.execute("dsc probe 32000 160 32000", admin) == 1
                    && dispatcher.execute("digitalstorage probe 32000 161 32000", admin) == 0
                    && chest.getItem(0).getCount() == 32, "Actual probe/alias/missing handler changed inventory");
            world.getChunkAt(pos).setBlockEntity(new FailingChest(pos, world.getBlockState(pos)));
            expect(dispatcher.execute("digitalstorage probe 32000 160 32000", admin) == 0,
                    "Throwing capability acquisition escaped the probe command");
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Forge performance command fixture passed: administrator permissions, real chest probe/alias, absent handler and read-only quantity");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalStateException("Probe command fixture syntax failed", failure);
        } finally { world.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()); }
    }
    private static final class FailingChest extends net.minecraft.world.level.block.entity.ChestBlockEntity {
        FailingChest(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { super(pos, state); }
        @Override public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
                net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.core.Direction side) {
            if (capability == net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER) {
                throw new IllegalStateException("Injected probe capability acquisition failure");
            }
            return super.getCapability(capability, side);
        }
    }
    private static void expect(boolean condition, String detail) { if (!condition) throw new IllegalStateException(detail); }
}
