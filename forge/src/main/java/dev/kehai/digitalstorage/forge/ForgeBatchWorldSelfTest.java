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
            var aggregate = (com.tom.storagemod.util.MultiItemHandler) connector.getInventory().orElseThrow(() -> new IllegalStateException("Missing connector inventory"));
            var rejectedHandler = new net.minecraftforge.items.ItemStackHandler(1) { };
            rejectedHandler.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT, 8));
            var rejectedCapability = LazyOptional.<IItemHandler>of(() -> rejectedHandler);
            chest.setItem(0, new ItemStack(Items.OAK_PLANKS, 8));
            chest.setItem(1, new ItemStack(Items.DIAMOND_SWORD));
            aggregate.add(rejectedCapability); aggregate.refresh();
            volume.record().setAcceptUnstackableItems(true);
            BatchScreenSession.runToolsRecommendationSelfTest(player, accessor, ItemKey.of(chest.getItem(1)));
            volume.record().setAcceptUnstackableItems(false);
            chest.setItem(1, ItemStack.EMPTY);
            BatchScreenSession.runTransferableRecommendationSelfTest(player, accessor, ItemKey.of(Items.OAK_PLANKS), ItemKey.of(Items.GOLD_INGOT));
            expect(rejectedHandler.getStackInSlot(0).getCount() == 8 && chest.isEmpty(), "Untrusted recommendation fixture did not conserve source items");
            try (var tx = LedgerTransaction.open()) { volume.record().storage().extract(ItemKey.of(Items.OAK_PLANKS), 8, tx); tx.commit(); }
            scan(world, connector);
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Forge recommendation route self-test passed: rejected Tom-visible handler excluded; trusted chest moved eight");
            var measurementNetwork = (com.tom.storagemod.util.MultiItemHandler) connector.getInventory().orElseThrow(() -> new IllegalStateException("Missing timing network"));
            var timingHandlers = new java.util.ArrayList<LazyOptional<IItemHandler>>();
            for (int i = 0; i < 128; i++) {
                var handler = new net.minecraftforge.items.ItemStackHandler(27);
                handler.setStackInSlot(0, new ItemStack(Items.STONE, 1));
                var capability = LazyOptional.<IItemHandler>of(() -> handler);
                timingHandlers.add(capability); measurementNetwork.add(capability);
            }
            measurementNetwork.refresh();
            BatchScreenSession.runRecommendationTimingSelfTest(player, accessor,
                    dev.kehai.digitalstorage.forge.tom.ForgeTomTopology::resetAuditMetrics,
                    dev.kehai.digitalstorage.forge.tom.ForgeTomTopology::auditMetrics);
            scan(world, connector);
            runRemovedSingleChestCheck(world, player, accessor, connector, chestPos, chest, volume.record());
            world.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            chest = (ChestBlockEntity) world.getBlockEntity(chestPos);
            scan(world, connector);
            runRemovedSingleExportCheck(world, player, accessor, chestPos, chest, volume.record());
            runRemovedDoubleCheck(world, player, accessor, connector, chestPos, otherPos, left, false, volume.record());
            runRemovedDoubleCheck(world, player, accessor, connector, chestPos, otherPos, left, true, volume.record());
            if (net.minecraftforge.fml.ModList.get().isLoaded("sophisticatedstorage"))
                runRemovedSophCheck(world, player, accessor, connector, chestPos, volume.record());
            else dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("BLOCK-01 Sophisticated removal self-test skipped: mod absent");
            world.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            chest = (ChestBlockEntity) world.getBlockEntity(chestPos);
            scan(world, connector);
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
    private static void runRemovedSingleChestCheck(net.minecraft.server.level.ServerLevel world, ServerPlayer player,
            ForgeAccessorBlockEntity accessor, com.tom.storagemod.tile.InventoryConnectorBlockEntity connector,
            net.minecraft.core.BlockPos position, ChestBlockEntity chest, dev.kehai.digitalstorage.storage.DigitalStorageRecord record) {
        var key = ItemKey.of(Items.STONE);
        chest.setItem(0, new ItemStack(Items.STONE, 64)); chest.setItem(1, new ItemStack(Items.STONE, 64));
        var route = BatchTransfers.open(player, accessor);
        var task = new BatchTransfer(record, route, List.of(new BatchTransfer.Entry(key, 128)), false);
        task.tick(1);
        expect(task.active() && task.moved() > 0 && task.moved() < 128, "BLOCK-01 single chest fixture did not begin before removal");
        var bounds = new net.minecraft.world.phys.AABB(position).inflate(4);
        expect(world.destroyBlock(position, true), "BLOCK-01 could not break single chest");
        var drops = world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bounds,
                entity -> entity.getItem().is(Items.STONE));
        long dropped = drops.stream().mapToLong(entity -> entity.getItem().getCount()).sum();
        boolean currentBefore = route.fastCurrent().getAsBoolean();
        task.tick(128);
        long stored = record.storage().amountOf(key);
        long total = stored + dropped;
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("BLOCK-01 single chest removed without Tom scan: beforeTickCurrent={}, moved={}, stored={}, drops={}, total={}, expected=128, state={}", currentBefore, task.moved(), stored, dropped, total, task.state());
        if (total != 128) throw new IllegalStateException("BLOCK-01 single chest removal conservation failed: stored=" + stored + ", drops=" + dropped + ", total=" + total + ", expected=128");
        drops.forEach(net.minecraft.world.entity.Entity::discard);
        try (var tx = LedgerTransaction.open()) { record.storage().extract(key, Long.MAX_VALUE, tx); tx.commit(); }
    }
    private static void runRemovedSingleExportCheck(net.minecraft.server.level.ServerLevel world, ServerPlayer player,
            ForgeAccessorBlockEntity accessor, net.minecraft.core.BlockPos position, ChestBlockEntity chest,
            dev.kehai.digitalstorage.storage.DigitalStorageRecord record) {
        var key = ItemKey.of(Items.STONE);
        record.storage().load(key, 128);
        var route = BatchTransfers.open(player, accessor);
        var task = new BatchTransfer(record, route, List.of(new BatchTransfer.Entry(key, 128)), true);
        task.tick(1);
        expect(task.active() && task.moved() > 0 && task.moved() < 128, "BLOCK-01 single export did not begin before removal");
        expect(world.destroyBlock(position, true), "BLOCK-01 could not break export chest");
        var drops = world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(position).inflate(4), e -> e.getItem().is(Items.STONE));
        long dropped = drops.stream().mapToLong(e -> e.getItem().getCount()).sum();
        boolean fast = route.fastCurrent().getAsBoolean();
        task.tick(128);
        long stored = record.storage().amountOf(key), total = stored + dropped;
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("BLOCK-01 single chest export removed without Tom scan: beforeTickCurrent={}, moved={}, stored={}, drops={}, total={}, expected=128, state={}", fast, task.moved(), stored, dropped, total, task.state());
        if (total != 128) throw new IllegalStateException("BLOCK-01 single chest export conservation failed: stored=" + stored + ", drops=" + dropped + ", total=" + total + ", expected=128");
        drops.forEach(net.minecraft.world.entity.Entity::discard);
        try (var tx = LedgerTransaction.open()) { record.storage().extract(key, Long.MAX_VALUE, tx); tx.commit(); }
    }
    private static void runRemovedDoubleCheck(net.minecraft.server.level.ServerLevel world, ServerPlayer player,
            ForgeAccessorBlockEntity accessor, com.tom.storagemod.tile.InventoryConnectorBlockEntity connector,
            net.minecraft.core.BlockPos position, net.minecraft.core.BlockPos otherPosition,
            net.minecraft.world.level.block.state.BlockState left, boolean exporting,
            dev.kehai.digitalstorage.storage.DigitalStorageRecord record) {
        world.setBlock(position, left, 2);
        world.setBlock(otherPosition, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
        var first = (ChestBlockEntity) world.getBlockEntity(position);
        var other = (ChestBlockEntity) world.getBlockEntity(otherPosition);
        var key = ItemKey.of(Items.STONE);
        if (exporting) record.storage().load(key, 128);
        else { first.setItem(0, new ItemStack(Items.STONE, 64)); other.setItem(0, new ItemStack(Items.STONE, 64)); }
        scan(world, connector);
        var route = BatchTransfers.open(player, accessor);
        var task = new BatchTransfer(record, route, List.of(new BatchTransfer.Entry(key, 128)), exporting);
        task.tick(1);
        expect(task.active() && task.moved() > 0 && task.moved() < 128, "BLOCK-01 double chest fixture did not begin before removal");
        expect(world.destroyBlock(position, true), "BLOCK-01 could not break double chest");
        var drops = world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(position).inflate(4), e -> e.getItem().is(Items.STONE));
        long dropped = drops.stream().mapToLong(e -> e.getItem().getCount()).sum();
        boolean fast = route.fastCurrent().getAsBoolean();
        task.tick(128);
        long surviving = 0; for (int i = 0; i < other.getContainerSize(); i++) if (other.getItem(i).is(Items.STONE)) surviving += other.getItem(i).getCount();
        long stored = record.storage().amountOf(key), total = stored + dropped + surviving;
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("BLOCK-01 double chest {} removed without Tom scan: beforeTickCurrent={}, moved={}, stored={}, drops={}, other={}, total={}, expected=128, state={}", exporting ? "export" : "import", fast, task.moved(), stored, dropped, surviving, total, task.state());
        if (total != 128) throw new IllegalStateException("BLOCK-01 double chest " + (exporting ? "export" : "import") + " conservation failed: stored=" + stored + ", drops=" + dropped + ", other=" + surviving + ", total=" + total + ", expected=128");
        drops.forEach(net.minecraft.world.entity.Entity::discard);
        other.clearContent(); world.removeBlock(otherPosition, false);
        try (var tx = LedgerTransaction.open()) { record.storage().extract(key, Long.MAX_VALUE, tx); tx.commit(); }
    }
    private static void runRemovedSophCheck(net.minecraft.server.level.ServerLevel world, ServerPlayer player,
            ForgeAccessorBlockEntity accessor, com.tom.storagemod.tile.InventoryConnectorBlockEntity connector,
            net.minecraft.core.BlockPos position, dev.kehai.digitalstorage.storage.DigitalStorageRecord record) {
        var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("sophisticatedstorage", "barrel"));
        expect(block != null, "BLOCK-01 Sophisticated barrel unavailable");
        world.setBlockAndUpdate(position, block.defaultBlockState());
        var entity = world.getBlockEntity(position);
        expect(entity != null, "BLOCK-01 Sophisticated block entity unavailable");
        var handler = entity.getCapability(ForgeCapabilities.ITEM_HANDLER, net.minecraft.core.Direction.UP)
                .orElseThrow(() -> new IllegalStateException("BLOCK-01 Sophisticated item handler unavailable"));
        expect(handler.insertItem(0, new ItemStack(Items.STONE, 64), false).isEmpty()
                && handler.insertItem(1, new ItemStack(Items.STONE, 64), false).isEmpty(), "BLOCK-01 Sophisticated seed failed");
        scan(world, connector);
        var key = ItemKey.of(Items.STONE);
        var route = BatchTransfers.open(player, accessor);
        var task = new BatchTransfer(record, route, List.of(new BatchTransfer.Entry(key, 128)), false);
        task.tick(1);
        expect(task.active() && task.moved() > 0 && task.moved() < 128, "BLOCK-01 Sophisticated task did not begin");
        expect(world.destroyBlock(position, true), "BLOCK-01 Sophisticated removal failed");
        var allDrops = world.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(position).inflate(4));
        long dropped = allDrops.stream().filter(e -> e.getItem().is(Items.STONE)).mapToLong(e -> e.getItem().getCount()).sum();
        boolean fast = route.fastCurrent().getAsBoolean(); task.tick(128);
        long stored = record.storage().amountOf(key), total = stored + dropped;
        dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("BLOCK-01 Sophisticated barrel removed without Tom scan: beforeTickCurrent={}, moved={}, stored={}, stoneDrops={}, allDrops={}, total={}, expected=128, state={}", fast, task.moved(), stored, dropped, allDrops.stream().map(e -> e.getItem().toString()).toList(), total, task.state());
        if (total != 128) throw new IllegalStateException("BLOCK-01 Sophisticated barrel conservation requires inspection: stored=" + stored + ", stoneDrops=" + dropped + ", total=" + total + ", expected=128");
        allDrops.forEach(net.minecraft.world.entity.Entity::discard);
        try (var tx = LedgerTransaction.open()) { record.storage().extract(key, Long.MAX_VALUE, tx); tx.commit(); }
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
