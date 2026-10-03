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
        world.getChunkAt(chestPos);
        for (var position : new BlockPos[]{accessorPos, connectorPos, chestPos}) {
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
            // Seed the actual aggregate deterministically. Automatic scanner
            // discovery/refresh and proxy direction are separate world gates.
            network.clear(); network.add(digital); network.add(physical); network.refresh();
            var report = backend.analyze(accessor);
            expect(report.available() && report.targetEndpointCount() == 1 && report.candidates().size() == 1,
                    "Placed connector discovery did not produce a usable report");
            expect(backend.start(foreign, accessor, report) == NetworkServices.StartResult.NOT_OWNER,
                    "Production migration startup accepted another player");
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED,
                    "Placed owner migration did not start through the production backend");
            for (int tick = 0; tick < 40 && backend.status(volume.id()).active(); tick++) ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.COMPLETE
                    && backend.status(volume.id()).movedItems().equals("96") && chest.getItem(0).isEmpty() && chest.getItem(1).isEmpty()
                    && volume.record().storage().amountOf(ItemKey.of(Items.STONE)) == 96,
                    "Placed migration did not conserve actual chest/volume quantities");
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
            network.clear(); network.add(digital); network.add(physical); network.refresh();
            report = backend.analyze(accessor);
            expect(backend.start(player, accessor, report) == NetworkServices.StartResult.STARTED, "Placed disconnect fixture did not start");
            world.removeBlock(connectorPos, false);
            ForgeMigrationManager.tick(server);
            expect(backend.status(volume.id()).state() == MigrationTask.State.STOPPED && chest.getItem(0).getCount() == 16,
                    "Removed actual connector did not stop before extraction");
        } finally {
            backend.cancel(player, volume.id());
            if (chest != null) chest.clearContent();
            for (var position : new BlockPos[]{accessorPos, connectorPos, chestPos}) world.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            clear(volume.record().storage());
            expect(state.deleteEmptyVolume(owner, volume.id()), "Placed fixture volume could not be removed");
            state.flushNow();
        }
        expect(state.accountCount() == accounts && state.volumeCount() == volumes, "Placed fixture left account/volume records");
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Forge placed migration world self-test passed: production adjacency/start/tick/cancel/disconnect and actual chest quantities; aggregate seeded, scanner/proxy direction not covered");
    }
    private static void clear(dev.kehai.digitalstorage.storage.VolumeLedger ledger) {
        try (var transaction = LedgerTransaction.open()) { ledger.extract(ItemKey.of(Items.STONE), Long.MAX_VALUE, transaction); transaction.commit(); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
