package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.NetworkServices;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

/** Explicit task-only fixture: real placed accessor/connector/chest and production start/tick/cancel paths. */
public final class ForgeMigrationWorldSelfTest {
    private ForgeMigrationWorldSelfTest() { }
    public static void run(MinecraftServer server) {
        String configured = System.getProperty("digitalstorage.integrationTestRoot");
        if (configured == null) return;
        Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        if (!root.equals(Path.of(configured).toAbsolutePath().normalize())
                || !root.toString().replace('\\', '/').contains("/maintenance/work/fabric-decoupling/")
                || !root.getFileName().toString().equals("audit-world")) {
            throw new IllegalStateException("World fixture is restricted to a declared task audit-world");
        }
        var world = server.overworld();
        BlockPos accessorPos = world.getSharedSpawnPos().above(16);
        BlockPos connectorPos = accessorPos.east();
        BlockPos chestPos = connectorPos.east();
        var leftChestState = Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.LEFT);
        BlockPos secondChestPos = chestPos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(leftChestState));
        BlockPos cablePos = connectorPos.south();
        BlockPos proxyPos = cablePos.south();
        BlockPos furnacePos = proxyPos.below();
        var positions = new BlockPos[]{accessorPos, connectorPos, chestPos, secondChestPos, cablePos, proxyPos, furnacePos};
        world.getChunkAt(chestPos);
        world.getChunkAt(furnacePos);
        for (var position : positions) {
            if (!world.getBlockState(position).isAir() || world.getBlockEntity(position) != null) {
                throw new IllegalStateException("World fixture requires untouched air positions");
            }
        }
        var state = DigitalStorageState.get(world);
        int accounts = state.accountCount();
        int volumes = state.volumeCount();
        UUID owner = UUID.randomUUID();
        var volume = state.createVolume(owner, "Forge placed migration fixture", 1).orElseThrow();
        ChestBlockEntity chest = null;
        ChestBlockEntity secondChest = null;
        net.minecraft.world.level.block.entity.FurnaceBlockEntity furnace = null;
        var player = new ServerPlayer(server, world, new com.mojang.authlib.GameProfile(owner, "DSCFixture"));
        var foreign = new ServerPlayer(server, world, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "DSCForeign"));
        var backend = NetworkServices.get();
        try {
            world.setBlockAndUpdate(accessorPos, ForgeDigitalStorage.ACCESSOR.get().defaultBlockState());
            world.setBlockAndUpdate(connectorPos, com.tom.storagemod.Content.connector.get().defaultBlockState());
            world.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            var accessor = (ForgeAccessorBlockEntity) world.getBlockEntity(accessorPos);
            var connector = (com.tom.storagemod.tile.InventoryConnectorBlockEntity) world.getBlockEntity(connectorPos);
            chest = (ChestBlockEntity) world.getBlockEntity(chestPos);
            var binding = new CompoundTag();
            binding.putUUID("ControllerId", owner);
            binding.putUUID("BoundVolumeId", volume.id());
            accessor.load(binding);
            accessor.onLoad();
            chest.setItem(0, new ItemStack(Items.STONE, 64));
            chest.setItem(1, new ItemStack(Items.STONE, 32));
            var physical = chest.getCapability(ForgeCapabilities.ITEM_HANDLER, net.minecraft.core.Direction.UP);
            var digital = accessor.getCapability(ForgeCapabilities.ITEM_HANDLER);
            var network = (com.tom.storagemod.util.MultiItemHandler) connector.getInventory()
                    .orElseThrow(() -> new IllegalStateException("Placed connector inventory missing"));
            scan(connector);
            var report = backend.analyze(accessor);
            expect(report.available() && report.targetEndpointCount() == 1 && report.candidates().size() == 1,
                    "Placed connector discovery did not produce a usable report");
            expect(backend.start(foreign, accessor, report) == NetworkServices.StartResult.NOT_OWNER,
                    "Production migration startup accepted another player");
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED,
                    "Placed owner migration did not start through the production backend");
            scan(connector);
            expect(dev.kehai.digitalstorage.optimization.TopologyToken.isCurrent(report.topology()),
                    "Actual single chest scanner rebuild invalidated an unchanged network");
            for (int tick = 0; tick < 40 && backend.status(volume.id()).active(); tick++) ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.COMPLETE
                    && backend.status(volume.id()).movedItems().equals("96") && chest.getItem(0).isEmpty() && chest.getItem(1).isEmpty()
                    && volume.record().storage().amountOf(ItemKey.of(Items.STONE)) == 96,
                    "Placed migration did not conserve actual chest/volume quantities");
            var route = dev.kehai.digitalstorage.optimization.BatchTransfers.open(player, accessor);
            var stone = ItemKey.of(Items.STONE);
            var output = new dev.kehai.digitalstorage.optimization.BatchTransfer(volume.record(), route,
                    java.util.List.of(new dev.kehai.digitalstorage.optimization.BatchTransfer.Entry(stone, 20)), true);
            for (int tick = 0; tick < 40 && output.active(); tick++) output.tick(4);
            expect(output.moved() == 20 && volume.record().storage().amountOf(stone) == 76
                    && chest.getItem(0).getCount() == 20, "Placed batch export did not reach the real physical chest");
            var input = new dev.kehai.digitalstorage.optimization.BatchTransfer(volume.record(), route,
                    java.util.List.of(new dev.kehai.digitalstorage.optimization.BatchTransfer.Entry(stone, 19)), false);
            for (int tick = 0; tick < 40 && input.active(); tick++) input.tick(4);
            expect(input.moved() == 19 && volume.record().storage().amountOf(stone) == 95
                    && chest.getItem(0).getCount() == 1, "Placed batch import exceeded selected quantity");
            dev.kehai.digitalstorage.forge.tom.ForgeScannerTelemetrySelfTest.verifyBackend(server, accessor, network);
            clear(volume.record().storage());
            chest.setItem(0, new ItemStack(Items.STONE, 16));
            report = backend.analyze(accessor);
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED
                    && !backend.cancel(foreign, volume.id()) && backend.cancel(player, volume.id()),
                    "Production cancellation did not require the migration owner");
            ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.CANCELLED && chest.getItem(0).getCount() == 16,
                    "Cancelled placed migration still extracted chest items");
            var alias = LazyOptional.<IItemHandler>of(() -> digital.orElseThrow(() -> new IllegalStateException("Placed digital capability missing")));
            network.add(alias); network.refresh();
            report = backend.analyze(accessor);
            expect(report.targetEndpointCount() == 2
                    && backend.start(player, accessor, report) == NetworkServices.StartResult.DUPLICATE_TARGET_ENDPOINTS,
                    "Placed duplicate target did not block migration");
            chest.clearContent();
            world.setBlock(chestPos, leftChestState, 2);
            world.setBlock(secondChestPos, Blocks.CHEST.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT), 2);
            secondChest = (ChestBlockEntity) world.getBlockEntity(secondChestPos);
            chest.setItem(0, new ItemStack(Items.STONE, 64));
            secondChest.setItem(0, new ItemStack(Items.STONE, 32));
            scan(connector);
            report = backend.analyze(accessor);
            expect(report.available() && report.physicalInventories() == 1 && report.totalViews() == 54
                    && report.candidates().size() == 1 && report.candidates().get(0).amount() == 96,
                    "Actual double chest scan lost or duplicated slots: " + report);
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED,
                    "Actual double chest migration did not start");
            scan(connector);
            expect(dev.kehai.digitalstorage.optimization.TopologyToken.isCurrent(report.topology()),
                    "Actual double chest scanner rebuild invalidated unchanged sides/slots");
            for (int tick = 0; tick < 40 && backend.status(volume.id()).active(); tick++) ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.COMPLETE
                    && volume.record().storage().amountOf(ItemKey.of(Items.STONE)) == 96
                    && chest.getItem(0).isEmpty() && secondChest.getItem(0).isEmpty(),
                    "Double chest transfer did not conserve both halves");
            clear(volume.record().storage());
            chest.setItem(0, new ItemStack(Items.STONE, 9));
            report = backend.analyze(accessor);
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED,
                    "Double chest half-removal fixture did not start");
            secondChest.clearContent();
            world.removeBlock(secondChestPos, false);
            world.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            scan(connector);
            ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.STOPPED && chest.getItem(0).getCount() == 9
                    && volume.record().storage().amountOf(ItemKey.of(Items.STONE)) == 0,
                    "Changed double chest slot mapping did not stop before extraction");
            chest.clearContent();
            world.setBlockAndUpdate(proxyPos, com.tom.storagemod.Content.invProxy.get().defaultBlockState()
                    .setValue(com.tom.storagemod.block.InventoryProxyBlock.FACING, net.minecraft.core.Direction.DOWN));
            world.setBlockAndUpdate(furnacePos, Blocks.FURNACE.defaultBlockState());
            var cableBlock = com.tom.storagemod.Content.invCableConnector.get();
            world.setBlockAndUpdate(cablePos, cableBlock.defaultBlockState()
                    .setValue(com.tom.storagemod.block.InventoryCableConnectorBlock.FACING, net.minecraft.core.Direction.SOUTH));
            world.setBlockAndUpdate(cablePos, cableBlock.withConnectionProperties(world.getBlockState(cablePos), world, cablePos));
            var proxy = (com.tom.storagemod.tile.InventoryProxyBlockEntity) world.getBlockEntity(proxyPos);
            var cable = (com.tom.storagemod.tile.InventoryCableConnectorBlockEntity) world.getBlockEntity(cablePos);
            furnace = (net.minecraft.world.level.block.entity.FurnaceBlockEntity) world.getBlockEntity(furnacePos);
            furnace.setItem(0, new ItemStack(Items.IRON_ORE, 11));
            furnace.setItem(1, new ItemStack(Items.COAL, 7));
            furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 5));
            periodic(world, 18, proxy::updateServer);
            periodic(world, 19, cable::updateServer);
            periodic(world, 20, connector::updateServer);
            expect(!proxy.getCapability(ForgeCapabilities.ITEM_HANDLER, net.minecraft.core.Direction.DOWN).isPresent(),
                    "Actual proxy exposed its pointed-at face");
            report = backend.analyze(accessor);
            expect(report.available() && report.targetEndpointCount() == 1 && report.candidates().size() == 1
                    && report.candidates().get(0).variant().equals(ItemKey.of(Items.IRON_ORE))
                    && report.candidates().get(0).amount() == 11,
                    "Actual cable/proxy scan bypassed the furnace input side: " + report);
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED,
                    "Actual cable/proxy migration did not start");
            for (int tick = 0; tick < 40 && backend.status(volume.id()).active(); tick++) ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.COMPLETE
                    && volume.record().storage().amountOf(ItemKey.of(Items.IRON_ORE)) == 11 && furnace.getItem(0).isEmpty()
                    && furnace.getItem(1).getCount() == 7 && furnace.getItem(2).getCount() == 5,
                    "Proxy transfer bypassed sided slots or failed to conserve quantities");
            clear(volume.record().storage());
            furnace.setItem(0, new ItemStack(Items.IRON_ORE, 9));
            report = backend.analyze(accessor);
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED,
                    "Proxy removal fixture did not start");
            world.removeBlock(proxyPos, false);
            ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.STOPPED && furnace.getItem(0).getCount() == 9
                    && volume.record().storage().amountOf(ItemKey.of(Items.IRON_ORE)) == 0,
                    "Removed actual proxy did not stop before sided extraction");
            world.removeBlock(cablePos, false);
            furnace.clearContent();
            world.removeBlock(furnacePos, false);
            chest.setItem(0, new ItemStack(Items.STONE, 16));
            scan(connector);
            report = backend.analyze(accessor);
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED, "Placed disconnect fixture did not start");
            world.removeBlock(connectorPos, false);
            ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.STOPPED && chest.getItem(0).getCount() == 16,
                    "Removed actual connector did not stop before extraction");
        } finally {
            backend.cancel(player, volume.id());
            if (chest != null) chest.clearContent();
            if (secondChest != null) secondChest.clearContent();
            if (furnace != null) furnace.clearContent();
            for (var position : positions) world.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            clear(volume.record().storage());
            expect(state.deleteEmptyVolume(owner, volume.id()), "Placed fixture volume could not be removed");
            state.flushNow();
        }
        expect(state.accountCount() == accounts && state.volumeCount() == volumes, "Placed fixture left account/volume records");
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Forge placed migration world self-test passed: actual Tom scanner entry, single/double chest rebuild and cable/proxy/furnace side, production start/tick/cancel/disconnect and actual quantities; duplicate alias injected, periodic entries explicitly driven");
    }
    private static void scan(com.tom.storagemod.tile.InventoryConnectorBlockEntity connector) {
        var world = (net.minecraft.server.level.ServerLevel) connector.getLevel();
        periodic(world, 0, connector::updateServer);
    }
    private static void periodic(net.minecraft.server.level.ServerLevel world, int phase, Runnable update) {
        var levelData = world.getServer().getWorldData().overworldData();
        long previous = world.getGameTime();
        try {
            // Drive the actual periodic scan entry without waiting inside a server
            // command. Only this declared task-world's clock is changed, then restored.
            levelData.setGameTime(previous - Math.floorMod(previous, 20) + phase);
            update.run();
        } finally { levelData.setGameTime(previous); }
    }
    private static void clear(dev.kehai.digitalstorage.storage.VolumeLedger ledger) {
        try (var transaction = LedgerTransaction.open()) {
            ledger.extract(ItemKey.of(Items.STONE), Long.MAX_VALUE, transaction);
            ledger.extract(ItemKey.of(Items.IRON_ORE), Long.MAX_VALUE, transaction);
            transaction.commit();
        }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
