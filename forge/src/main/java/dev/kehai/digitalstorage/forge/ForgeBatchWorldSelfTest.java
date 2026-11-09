package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.optimization.BatchTransfer;
import dev.kehai.digitalstorage.optimization.BatchTransfers;
import dev.kehai.digitalstorage.screen.BatchScreenSession;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

/** Independent, explicitly scoped placed-world batch and recommendation fixture. */
public final class ForgeBatchWorldSelfTest {
    private ForgeBatchWorldSelfTest() { }
    public static void run(MinecraftServer server) {
        String configured = System.getProperty("digitalstorage.integrationTestRoot");
        if (configured == null) {
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Forge independent batch world self-test skipped: digitalstorage.integrationTestRoot is not set");
            return;
        }
        Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        if (!root.equals(Path.of(configured).toAbsolutePath().normalize())
                || !root.toString().replace('\\', '/').contains("/maintenance/work/fabric-decoupling/")
                || !root.getFileName().toString().equals("audit-world")) throw new IllegalStateException("Batch fixture requires declared audit-world");
        var world = server.overworld();
        var accessorPos = world.getSharedSpawnPos().above(20);
        var connectorPos = accessorPos.east();
        var chestPos = connectorPos.east();
        var left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
        var otherPos = chestPos.relative(ChestBlock.getConnectedDirection(left));
        var positions = new net.minecraft.core.BlockPos[]{accessorPos, connectorPos, chestPos, otherPos};
        for (var pos : positions) {
            world.getChunkAt(pos);
            if (!world.getBlockState(pos).isAir() || world.getBlockEntity(pos) != null) throw new IllegalStateException("Batch fixture requires untouched air");
        }
        var state = DigitalStorageState.get(world);
        int accounts = state.accountCount(), volumes = state.volumeCount();
        UUID owner = UUID.randomUUID();
        var volume = state.createVolume(owner, "Forge batch fixture", 1).orElseThrow();
        var player = new ServerPlayer(server, world, new com.mojang.authlib.GameProfile(owner, "DSCBatchFixture"));
        ChestBlockEntity chest = null, other = null;
        try {
            world.setBlockAndUpdate(accessorPos, ForgeDigitalStorage.ACCESSOR.get().defaultBlockState());
            world.setBlockAndUpdate(connectorPos, com.tom.storagemod.Content.connector.get().defaultBlockState());
            world.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            var accessor = (ForgeAccessorBlockEntity) world.getBlockEntity(accessorPos);
            var connector = (com.tom.storagemod.tile.InventoryConnectorBlockEntity) world.getBlockEntity(connectorPos);
            chest = (ChestBlockEntity) world.getBlockEntity(chestPos);
            var binding = new CompoundTag(); binding.putUUID("ControllerId", owner); binding.putUUID("BoundVolumeId", volume.id());
            accessor.load(binding); accessor.onLoad();
            scan(world, connector);
            try { runLegacyBatchGateSelfTest(server, player, accessor, owner, volume.id(), root); }
            catch (java.io.IOException failure) { throw new IllegalStateException("Live+legacy batch gate fixture failed", failure); }
            BatchScreenSession.runRecommendedSubsetSelfTest(player, accessor, chest);
            BatchScreenSession.runRecommendedCapacitySelfTest(player, accessor, chest);
            var network = (com.tom.storagemod.util.MultiItemHandler) connector.getInventory().orElseThrow(() -> new IllegalStateException("Missing connector inventory"));
            var digital = accessor.getCapability(ForgeCapabilities.ITEM_HANDLER);
            var alias = LazyOptional.<IItemHandler>of(() -> digital.orElseThrow(() -> new IllegalStateException("Missing digital capability")));
            network.add(alias); network.refresh();
            BatchScreenSession.runDuplicateTargetSelfTest(player, accessor);
            scan(world, connector);
            var stone = ItemKey.of(Items.STONE);
            volume.record().storage().load(stone, 96);
            var route = BatchTransfers.open(player, accessor);
            expect(route.fastCurrent().getAsBoolean(), "Fresh batch route fast path was not current");
            scan(world, connector);
            expect(route.fastCurrent().getAsBoolean(),
                    "Periodic Tom rebuild with unchanged topology invalidated the batch fast path");
            var output = new BatchTransfer(volume.record(), route, List.of(new BatchTransfer.Entry(stone, 20)), true);
            for (int i = 0; i < 40 && output.active(); i++) output.tick(4);
            expect(output.moved() == 20 && chest.getItem(0).getCount() == 20, "Batch export did not conserve items");
            var input = new BatchTransfer(volume.record(), route, List.of(new BatchTransfer.Entry(stone, 19)), false);
            for (int i = 0; i < 40 && input.active(); i++) input.tick(4);
            expect(input.moved() == 19 && chest.getItem(0).getCount() == 1
                    && volume.record().storage().amountOf(stone) == 95, "Batch import did not conserve items");
            chest.clearContent();
            world.setBlock(chestPos, left, 2);
            world.setBlock(otherPos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
            other = (ChestBlockEntity) world.getBlockEntity(otherPos);
            chest.setItem(0, new ItemStack(Items.STONE, 64)); other.setItem(0, new ItemStack(Items.STONE, 32));
            scan(world, connector);
            expect(!route.fastCurrent().getAsBoolean(), "Changed topology failed to invalidate the old route");
            route = BatchTransfers.open(player, accessor);
            var doubleInput = new BatchTransfer(volume.record(), route, List.of(new BatchTransfer.Entry(stone, 96)), false);
            for (int i = 0; i < 40 && doubleInput.active(); i++) doubleInput.tick(4);
            expect(doubleInput.moved() == 96 && chest.isEmpty() && other.isEmpty()
                    && volume.record().storage().amountOf(stone) == 191, "Double chest batch import did not conserve items");
            chest.setItem(0, new ItemStack(Items.STONE, 64)); other.setItem(0, new ItemStack(Items.STONE, 64));
            var currentRoute = BatchTransfers.open(player, accessor);
            int[] calls = {0};
            var changingPorts = currentRoute.ports().stream().map(port -> (BatchTransfer.Port) new BatchTransfer.Port() {
                public Iterable<BatchTransfer.Entry> contents() { return port.contents(); }
                public BatchTransfer.Cursor cursor(boolean exporting) {
                    var delegate = port.cursor(exporting);
                    return new BatchTransfer.Cursor() {
                        public boolean hasNext() { return delegate.hasNext(); }
                        public BatchTransfer.Result next(dev.kehai.digitalstorage.storage.DigitalStorageRecord record, ItemKey key, long maximum) {
                            calls[0]++; var result = delegate.next(record, key, maximum); network.clear();
                            expect(!currentRoute.fastCurrent().getAsBoolean(), "Clear did not immediately invalidate the route");
                            return result;
                        }
                    };
                }
            }).toList();
            var changed = new BatchTransfer(volume.record(), new BatchTransfer.Route(changingPorts, currentRoute.current(),
                    currentRoute.fastCurrent(), currentRoute.location()), List.of(new BatchTransfer.Entry(stone, 128)), false);
            changed.tick(128);
            expect(changed.state().equals("STOPPED") && changed.detail().equals("network_changed") && calls[0] == 1
                    && changed.moved() + chest.getItem(0).getCount() + other.getItem(0).getCount() == 128
                    && volume.record().storage().amountOf(stone) == 191 + changed.moved(),
                    "Mid-step Tom rebuild did not stop or conserve items");
        } finally {
            if (chest != null) chest.clearContent(); if (other != null) other.clearContent();
            for (var pos : positions) world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            try (var tx = LedgerTransaction.open()) { volume.record().storage().extract(ItemKey.of(Items.STONE), Long.MAX_VALUE, tx); tx.commit(); }
            expect(state.deleteEmptyVolume(owner, volume.id()), "Batch fixture volume could not be removed");
            state.flushNow();
        }
        expect(state.accountCount() == accounts && state.volumeCount() == volumes, "Batch fixture left volume records");
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Forge independent batch world self-test passed: recommendation subsets, duplicate target, equivalent and changed topology, mid-step clear limited to one move, single and double chest conservation");
    }
    private static void runLegacyBatchGateSelfTest(MinecraftServer server, ServerPlayer player,
            ForgeAccessorBlockEntity accessor, UUID owner, UUID volume, Path worldRoot) throws java.io.IOException {
        Path legacy = worldRoot.resolve("digitalstorage-legacy-batch-gate");
        try {
            new ForgeTransferRecovery(legacy.resolve("forge-transfer/recovery"))
                    .hold(owner, volume, ItemKey.of(Items.STONE), 1, "batch gate fixture");
            var session = ForgeTransferSessions.get(server);
            session.refreshLegacy();
            expect(!session.recoveryAvailable(), "Live+legacy pending recovery was not fenced");
            boolean rejected = false;
            try {
                BatchTransfers.open(player, accessor);
            } catch (IllegalStateException expected) {
                rejected = "recovery_required".equals(expected.getMessage());
            }
            expect(rejected, "Batch entry did not reject live+legacy pending recovery");
        } finally {
            if (Files.exists(legacy)) try (var paths = Files.walk(legacy)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
            ForgeTransferSessions.get(server).refreshLegacy();
        }
        expect(ForgeTransferSessions.get(server).recoveryAvailable(), "Batch entry stayed fenced after legacy cleanup");
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info(
                "Forge live+legacy batch entry self-test passed: pending recovery rejected with recovery_required, cleanup restored availability");
    }
    private static void scan(net.minecraft.server.level.ServerLevel world, com.tom.storagemod.tile.InventoryConnectorBlockEntity connector) {
        var data = world.getServer().getWorldData().overworldData(); long old = world.getGameTime();
        try { data.setGameTime(old - Math.floorMod(old, 20)); connector.updateServer(); }
        finally { data.setGameTime(old); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
