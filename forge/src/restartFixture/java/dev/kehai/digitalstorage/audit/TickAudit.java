package dev.kehai.digitalstorage.audit;

import com.tom.storagemod.Content;
import com.tom.storagemod.block.BasicInventoryHopperBlock;
import com.tom.storagemod.block.InventoryCableConnectorBlock;
import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.forge.ForgeDigitalStorage;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;

/** Isolated mod fixture: actual world ticking, no manual Tom update calls. */
final class TickAudit {
    private static final List<BlockPos> devices = new ArrayList<>(), owned = new ArrayList<>();
    private static final Set<Long> forced = new HashSet<>();
    private static final Map<BlockPos, Long> first = new HashMap<>(), last = new HashMap<>();
    private static final Map<BlockPos, Integer> delivered = new HashMap<>();
    private static MinecraftServer server;
    private static long started;
    private static int oldSource, oldTarget;
    private static boolean finished;

    static void install() {
        MinecraftForge.EVENT_BUS.addListener(TickAudit::start);
        MinecraftForge.EVENT_BUS.addListener(TickAudit::tick);
    }
    private static void start(ServerStartedEvent event) {
        server = event.getServer();
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        expect(root.equals(Path.of(System.getProperty("digitalstorage.tickTestRoot")).toAbsolutePath().normalize())
                && root.toString().replace('\\', '/').contains("/maintenance/work/fabric-decoupling/")
                && root.getFileName().toString().equals("audit-world"), "Unsafe tick audit world");
        var config = dev.kehai.digitalstorage.config.DigitalStorageConfig.get();
        expect(config.optimizeTomsHopper && config.staggerConnectorScans && config.normalHopperBatchSize == 16
                && config.advancedHopperBatchSize == 64 && config.hopperSuccessCooldown == 20, "Tick audit requires default isolated configuration");
        ServerLevel world = server.overworld();
        for (int i = 0; i < 20; i++) {
            var pos = new BlockPos(24000 + i * 7, 160, 24000);
            long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (!world.getForcedChunks().contains(chunk) && forced.add(chunk)) world.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
            devices.add(pos);
            chest(world, pos.west(2), 128); chest(world, pos.east(), 0);
            connector(world, pos.west());
            own(world, pos);
            var block = i % 2 == 0 ? Content.invHopperBasic.get() : ForgeDigitalStorage.ADVANCED_HOPPER.get();
            world.setBlockAndUpdate(pos, block.defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.EAST));
            ((com.tom.storagemod.tile.BasicInventoryHopperBlockEntity) world.getBlockEntity(pos)).setFilter(new ItemStack(Items.STONE));
        }
        started = world.getGameTime();
        DigitalStorage.LOGGER.info("DSC natural tick audit started: twenty forced devices with distinct scan phases");
    }
    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null || event.getServer() != server || finished) return;
        var world = server.overworld();
        long tick = world.getGameTime(), age = tick - started;
        try {
            for (var pos : devices) {
                if (!first.containsKey(pos) && hasSource(world, pos)) {
                    expect(Math.floorMod(tick + Math.floorMod(pos.hashCode(), 20), 20) == 1, "First natural scan missed its configured phase");
                    first.put(pos, tick);
                }
                int count = amount(world, pos.east());
                int previous = delivered.getOrDefault(pos, 0);
                if (count > previous) {
                    Long prior = last.put(pos, tick);
                    if (prior != null) expect(tick - prior >= 21, "Natural cooldown transferred too early");
                    int batch = devices.indexOf(pos) % 2 == 0 ? 16 : 64;
                    expect(count - previous == batch, "Natural batch quantity changed");
                    delivered.put(pos, count);
                }
                expect(amount(world, pos.west(2)) + count == 128,
                        "Natural physical conservation changed: age=" + age + ", device=" + devices.indexOf(pos)
                                + ", source=" + amount(world, pos.west(2)) + ", target=" + count
                                + ", rotated source=" + (age > 80 && pos.equals(devices.get(0)) ? amount(world, pos.north(2)) : -1));
            }
            var rotating = devices.get(0);
            if (age == 80) {
                expect(first.size() == 20 && delivered.size() == 20 && new HashSet<>(first.values()).size() == 20,
                        "Natural scan phases synchronized or failed to tick: observed=" + first.size()
                                + ", transfers=" + delivered.size() + ", first source=" + amount(world, rotating.west(2)) + ", first target=" + amount(world, rotating.east()));
                chest(world, rotating.north(2), 64); chest(world, rotating.south(), 0);
                connector(world, rotating.north());
                world.setBlockAndUpdate(rotating, world.getBlockState(rotating).setValue(InventoryCableConnectorBlock.FACING, Direction.SOUTH));
            }
            if (age == 120) {
                expect(amount(world, rotating.south()) > 0, "Rotation did not reconnect to new physical endpoints");
                oldSource = amount(world, rotating.west(2)); oldTarget = amount(world, rotating.east());
            }
            if (age >= 120 && age < 180) {
                expect(amount(world, rotating.west(2)) == oldSource && amount(world, rotating.east()) == oldTarget,
                        "Rotated hopper kept stale endpoints beyond the scan period");
                expect(amount(world, rotating.north(2)) + amount(world, rotating.south()) == 64, "Rotated transfer did not conserve items");
            }
            if (age == 180) {
                world.setBlockAndUpdate(rotating, world.getBlockState(rotating).setValue(BasicInventoryHopperBlock.ENABLED, false));
                var chest = (ChestBlockEntity) world.getBlockEntity(rotating.north(2));
                chest.clearContent(); chest.setItem(0, new ItemStack(Items.STONE, 64));
            }
            if (age == 300) {
                expect(amount(world, rotating.north(2)) == 64, "Redstone disabled device extracted during natural ticks");
                cleanup(world);
                DigitalStorage.LOGGER.info("DSC natural tick audit passed: 300 real world ticks, twenty scan phases, batch/cooldown, rotation and redstone");
                var dispatcher = server.getCommands().getDispatcher();
                var source = server.createCommandSourceStack();
                expect(dispatcher.execute("digitalstorage selftest", source) == 1, "Final natural audit selftest failed");
                dispatcher.execute("digitalstorage diagnostics", source);
                expect(dispatcher.execute("digitalstorage flush", source) == 1, "Final natural audit flush failed");
                finished = true; server.halt(false);
            }
        } catch (Throwable failure) {
            DigitalStorage.LOGGER.error("DSC natural tick audit failed", failure);
            cleanup(world); finished = true; server.halt(false);
        }
    }
    private static void own(ServerLevel world, BlockPos pos) {
        expect(world.getBlockState(pos).isAir(), "Tick fixture would overwrite a block"); owned.add(pos);
    }
    private static void connector(ServerLevel world, BlockPos pos) {
        own(world, pos); world.setBlockAndUpdate(pos, Content.connector.get().defaultBlockState());
    }
    private static boolean hasSource(ServerLevel world, BlockPos pos) {
        try {
            var field = com.tom.storagemod.tile.AbstractInventoryHopperBlockEntity.class.getDeclaredField("top");
            field.setAccessible(true);
            return field.get(world.getBlockEntity(pos)) != null;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot observe natural endpoint discovery", failure); }
    }
    private static void chest(ServerLevel world, BlockPos pos, int count) {
        own(world, pos); world.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) world.getBlockEntity(pos);
        for (int slot = 0; count > 0; slot++) { int stack = Math.min(64, count); chest.setItem(slot, new ItemStack(Items.STONE, stack)); count -= stack; }
    }
    private static int amount(ServerLevel world, BlockPos pos) {
        var chest = (ChestBlockEntity) world.getBlockEntity(pos);
        int count = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) count += chest.getItem(slot).getCount();
        return count;
    }
    private static void cleanup(ServerLevel world) {
        for (var pos : owned) world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        for (long chunk : forced) world.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
    }
    private static void expect(boolean condition, String detail) { if (!condition) throw new IllegalStateException(detail); }
}
