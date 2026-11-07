package dev.kehai.digitalstorage.platform.fabric.tom;

import com.tom.storagemod.Config;
import com.tom.storagemod.util.FilteredStorage;
import com.tom.storagemod.util.ItemPredicate;

import dev.kehai.digitalstorage.optimization.BatchTransfer;
import dev.kehai.digitalstorage.optimization.BatchTransfers;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.platform.fabric.FabricDigitalItemStorage;
import dev.kehai.digitalstorage.platform.fabric.FabricItemKeys;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.util.ArrayList;
import java.util.Iterator;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.server.level.ServerPlayer;

public final class FabricBatchNetwork implements BatchTransfers.Backend {
    @Override public void runWorldSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        runChestPreviewSelfTest(player, accessor, false);
        runChestPreviewSelfTest(player, accessor, true);
        runChestPreviewSelfTest(player, accessor, true, true);
        runChestPreviewSelfTest(player, accessor, true, true, true);
    }
    private void runChestPreviewSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor, boolean doubleChest) {
        runChestPreviewSelfTest(player, accessor, doubleChest, false);
    }
    private void runChestPreviewSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor, boolean doubleChest, boolean cableNetwork) {
        runChestPreviewSelfTest(player, accessor, doubleChest, cableNetwork, false);
    }
    private void runChestPreviewSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor,
            boolean doubleChest, boolean cableNetwork, boolean cableDiscovery) {
        var world = player.serverLevel();
        var connectorPos = accessor.getBlockPos().east();
        var chestPos = connectorPos.east();
        var secondChestPos = chestPos.east();
        var cablePos = connectorPos.south();
        var cableAccessorPos = cablePos.south();
        var cloud = dev.kehai.digitalstorage.storage.DigitalStorageState.get(world);
        var volume = cloud.createVolume(player.getUUID(), "Physical batch fixture", Integer.MAX_VALUE).orElseThrow();
        if (!world.getBlockState(connectorPos).isAir() || !world.getBlockState(chestPos).isAir()
                || !world.getBlockState(secondChestPos).isAir() || !world.getBlockState(cablePos).isAir()
                || !world.getBlockState(cableAccessorPos).isAir())
            throw new IllegalStateException("Physical batch fixture requires untouched positions");
        try {
            world.setBlockAndUpdate(connectorPos, com.tom.storagemod.Content.connector.get().defaultBlockState());
            world.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, doubleChest
                            ? net.minecraft.world.level.block.state.properties.ChestType.LEFT : net.minecraft.world.level.block.state.properties.ChestType.SINGLE));
            if (doubleChest) world.setBlockAndUpdate(secondChestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ChestBlock.TYPE, net.minecraft.world.level.block.state.properties.ChestType.RIGHT));
            var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) world.getBlockEntity(chestPos);
            for (int i = 0; i < 9; i++) chest.setItem(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 64));
            if (cableDiscovery) world.setBlockAndUpdate(cableAccessorPos, accessor.getBlockState());
            var fixtureAccessor = cableDiscovery ? (DigitalStorageAccessorBlockEntity) world.getBlockEntity(cableAccessorPos) : accessor;
            fixtureAccessor.bind(player, volume.id());
            if (cableNetwork) world.setBlockAndUpdate(cablePos, com.tom.storagemod.Content.invCableConnector.get().defaultBlockState()
                    .setValue(com.tom.storagemod.block.InventoryCableConnectorBlock.FACING, net.minecraft.core.Direction.SOUTH)
                    .setValue(com.tom.storagemod.block.InventoryCableBlock.DIR_TO_PROPERTY[net.minecraft.core.Direction.NORTH.ordinal()], true));
            var connector = (com.tom.storagemod.tile.InventoryConnectorBlockEntity) world.getBlockEntity(connectorPos);
            var networkIdentity = connector.getInventory();
            var cable = cableNetwork ? (com.tom.storagemod.tile.InventoryCableConnectorBlockEntity) world.getBlockEntity(cablePos) : null;
            long time = world.getGameTime();
            try {
                if (cable != null) {
                    world.getServer().getWorldData().overworldData().setGameTime(time - time % 20 + 19);
                    cable.updateServer();
                    if (cable.get() == null) throw new IllegalStateException("Cable fixture did not connect to master");
                }
                world.getServer().getWorldData().overworldData().setGameTime(time - time % 20 + 20);
                connector.updateServer();
                if (networkIdentity == null || connector.getInventory() != networkIdentity)
                    throw new IllegalStateException("Tom connector public inventory changed identity after rebuild");
                dev.kehai.digitalstorage.screen.BatchScreenSession.runNetworkPreviewSelfTest(player, fixtureAccessor,
                        () -> { for (int i = 1; i <= 5; i++) {
                            if (cable != null) {
                                world.getServer().getWorldData().overworldData().setGameTime(time - time % 20 + (i + 1) * 20 - 1);
                                cable.updateServer();
                            }
                            world.getServer().getWorldData().overworldData().setGameTime(time - time % 20 + (i + 1) * 20);
                            connector.updateServer();
                            if (connector.getInventory() != networkIdentity)
                                throw new IllegalStateException("Tom connector public inventory changed identity during rebuild");
                        } }, () -> { world.removeBlock(doubleChest ? secondChestPos : chestPos, false); connector.updateServer(); });
                if (!chest.isEmpty()) throw new IllegalStateException("Imported chest was not emptied");
            } finally { world.getServer().getWorldData().overworldData().setGameTime(time); }
        } finally {
            if (cableDiscovery && world.getBlockEntity(cableAccessorPos) instanceof DigitalStorageAccessorBlockEntity mounted)
                mounted.clearBinding(player);
            accessor.clearBinding(player);
            if (cableDiscovery) world.removeBlock(cableAccessorPos, false);
            if (cableNetwork) world.removeBlock(cablePos, false);
            if (doubleChest) world.removeBlock(secondChestPos, false);
            world.removeBlock(chestPos, false); world.removeBlock(connectorPos, false);
            try (var tx = dev.kehai.digitalstorage.storage.LedgerTransaction.open()) {
                volume.record().storage().extract(ItemKey.of(net.minecraft.world.item.Items.OAK_PLANKS), 576, tx); tx.commit();
            }
            if (!cloud.deleteEmptyVolume(player.getUUID(), volume.id())) throw new IllegalStateException("Physical batch fixture cleanup failed");
        }
    }
    public static void runSelfTest() {
        var record = DigitalStorageRecord.createNew(() -> { });
        var tool = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
        tool.setDamageValue(23);
        var key = ItemKey.of(tool); record.storage().load(key, 3);
        var box = new net.minecraft.world.SimpleContainer(1);
        var physical = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(box, net.minecraft.core.Direction.UP);
        var route = new BatchTransfer.Route(java.util.List.of(new Port(physical)), () -> true, "fixture");
        var output = new BatchTransfer(record, route, java.util.List.of(new BatchTransfer.Entry(key, 3)), true);
        for (int i = 0; i < 10 && output.active(); i++) output.tick(2);
        if (output.moved() != 1 || record.storage().amountOf(key) != 2 || !ItemKey.of(box.getItem(0)).equals(key)
                || record.acceptsUnstackableItems()) throw new IllegalStateException("Fabric closed-policy cleanup/partial export failed");
        var input = new BatchTransfer(record, route, java.util.List.of(new BatchTransfer.Entry(key, 1)), false);
        for (int i = 0; i < 10 && input.active(); i++) input.tick(2);
        if (input.moved() != 0 || box.getItem(0).isEmpty() || record.storage().amountOf(key) != 2)
            throw new IllegalStateException("Fabric import bypassed unstackable policy");
        box.clearContent();
        var stone = ItemKey.of(net.minecraft.world.item.Items.STONE); record.storage().load(stone, 96);
        var selected = new BatchTransfer(record, route, java.util.List.of(new BatchTransfer.Entry(stone, 19)), true);
        for (int i = 0; i < 10 && selected.active(); i++) selected.tick(2);
        if (selected.moved() != 19 || box.getItem(0).getCount() != 19 || record.storage().amountOf(stone) != 77)
            throw new IllegalStateException("Fabric quantity-limited export failed");
        var digital = FabricDigitalItemStorage.of(record.storage());
        var mixed = new net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage<ItemVariant, Storage<ItemVariant>>(java.util.List.of(digital, physical));
        var parts = TomNetworkIntrospection.transferParts(mixed);
        if (parts.size() != 1 || TomNetworkIntrospection.canonicalDigitalEndpoint(parts.get(0)) != null)
            throw new IllegalStateException("Fabric batch route included digital storage");
        var separated = new BatchTransfer(record, new BatchTransfer.Route(parts.stream().map(p -> (BatchTransfer.Port) new Port(p)).toList(), () -> true, "fixture"),
                java.util.List.of(new BatchTransfer.Entry(stone, 5)), true);
        for (int i = 0; i < 10 && separated.active(); i++) separated.tick(2);
        if (separated.moved() != 5 || box.getItem(0).getCount() != 24 || record.storage().amountOf(stone) != 72)
            throw new IllegalStateException("Mixed Fabric network output returned to a digital volume");
        ItemPredicate deny = variant -> false;
        var filtered = new FilteredStorage(physical, deny, false);
        var filteredParts = TomNetworkIntrospection.transferParts(filtered);
        if (filteredParts.size() != 1 || filteredParts.get(0) != filtered) throw new IllegalStateException("Physical Fabric filter was stripped");
        var rejected = new BatchTransfer(record, new BatchTransfer.Route(java.util.List.of(new Port(filteredParts.get(0))), () -> true, "fixture"),
                java.util.List.of(new BatchTransfer.Entry(stone, 5)), true);
        for (int i = 0; i < 10 && rejected.active(); i++) rejected.tick(2);
        if (rejected.moved() != 0 || box.getItem(0).getCount() != 24 || record.storage().amountOf(stone) != 72)
            throw new IllegalStateException("Fabric export bypassed Tom filter");
        var unsafe = new FilteredStorage(mixed, deny, false);
        if (!TomNetworkIntrospection.transferParts(unsafe).isEmpty()) throw new IllegalStateException("Mixed filtered Fabric aggregate became writable");
        multiSlotExportSelfTest();
    }

    private static void multiSlotExportSelfTest() {
        var key = ItemKey.of(net.minecraft.world.item.Items.STONE);
        var box = new net.minecraft.world.SimpleContainer(4);
        SlottedStorage<ItemVariant> slots = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(box, net.minecraft.core.Direction.UP);
        SlottedStorage<ItemVariant> cached = new SlottedStorage<>() {
            public int getSlotCount() { return slots.getSlotCount(); }
            public net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage<ItemVariant> getSlot(int i) { return slots.getSlot(i); }
            public long insert(ItemVariant variant, long amount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext tx) { return 0; }
            public long extract(ItemVariant variant, long amount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext tx) { return slots.extract(variant, amount, tx); }
            public Iterator<StorageView<ItemVariant>> iterator() { return slots.iterator(); }
        };
        var record = DigitalStorageRecord.createNew(() -> { });
        record.storage().load(key, 256);
        var batch = exportFixture(record, key, cached);
        batch.tick(8);
        if (batch.active() || batch.moved() != 256 || record.storage().amountOf(key) != 0)
            throw new IllegalStateException("Slotted cached batch export failed");
        for (int i = 0; i < 4; i++) if (box.getItem(i).getCount() != 64)
            throw new IllegalStateException("Slotted cached batch did not fill every slot");

        var plainBox = new net.minecraft.world.SimpleContainer(4);
        var plainSlots = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(plainBox, net.minecraft.core.Direction.UP);
        Storage<ItemVariant> plain = limitedFixture(plainSlots);
        record = DigitalStorageRecord.createNew(() -> { }); record.storage().load(key, 256);
        batch = exportFixture(record, key, plain);
        for (int i = 0; i < 10 && batch.active(); i++) batch.tick(8);
        if (batch.active() || batch.moved() != 256 || record.storage().amountOf(key) != 0)
            throw new IllegalStateException("Non-slotted partial insertion batch failed");
        for (int i = 0; i < 4; i++) if (plainBox.getItem(i).getCount() != 64)
            throw new IllegalStateException("Non-slotted batch did not fill every slot");

        var partialBox = new net.minecraft.world.SimpleContainer(2);
        var partialSlots = net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage.of(partialBox, net.minecraft.core.Direction.UP);
        record = DigitalStorageRecord.createNew(() -> { }); record.storage().load(key, 256);
        batch = exportFixture(record, key, limitedFixture(partialSlots));
        for (int i = 0; i < 10 && batch.active(); i++) batch.tick(8);
        if (!batch.state().equals("STOPPED") || batch.moved() != 128 || record.storage().amountOf(key) != 128
                || partialBox.getItem(0).getCount() != 64 || partialBox.getItem(1).getCount() != 64)
            throw new IllegalStateException("Partial batch export failed conservation or stop");
    }

    private static Storage<ItemVariant> limitedFixture(Storage<ItemVariant> underlying) {
        return new Storage<>() {
            public long insert(ItemVariant variant, long amount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext tx) {
                return underlying.insert(variant, Math.min(64, amount), tx);
            }
            public long extract(ItemVariant variant, long amount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext tx) {
                return underlying.extract(variant, amount, tx);
            }
            public Iterator<StorageView<ItemVariant>> iterator() { return underlying.iterator(); }
        };
    }

    private static BatchTransfer exportFixture(DigitalStorageRecord record, ItemKey key, Storage<ItemVariant> storage) {
        return new BatchTransfer(record, new BatchTransfer.Route(java.util.List.of(new Port(storage)), () -> true, "fixture"),
                java.util.List.of(new BatchTransfer.Entry(key, 256)), true);
    }
    public BatchTransfer.Route open(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        var discovery = TomNetworkIntrospection.discoverContext(accessor);
        if (discovery == null) return null;
        var volume = accessor.getVolume();
        var topology = TomNetworkCache.topology(discovery.connector(), discovery.storage());
        var ports = new ArrayList<BatchTransfer.Port>();
        TomNetworkIntrospection.transferParts(discovery.storage()).stream()
                .map(storage -> (BatchTransfer.Port) new Port(storage)).forEach(ports::add);
        var soph = safeDirectSoph(discovery.connector(), discovery.storage());
        if (soph != null) for (var storage : soph) ports.add(new Port(storage));
        return new BatchTransfer.Route(ports, () -> !accessor.isRemoved() && player.serverLevel() == accessor.getLevel()
                && accessor.getLevel().hasChunkAt(accessor.getBlockPos())
                && accessor.getLevel().getBlockEntity(accessor.getBlockPos()) == accessor
                && accessor.getVolume() == volume && volume.ownerId().equals(player.getUUID())
                    && topology.token().isCurrent()
                    && (soph == null || sameSoph(soph, safeDirectSoph(discovery.connector(), discovery.storage()))), discovery.connector().getBlockPos().toShortString());
    }
    private static boolean sameSoph(java.util.List<Storage<ItemVariant>> a, java.util.List<Storage<ItemVariant>> b) {
        if (b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }
    static java.util.List<Storage<ItemVariant>> safeDirectSoph(net.minecraft.world.level.block.entity.BlockEntity connector, Storage<ItemVariant> root) {
        var parts = TomNetworkIntrospection.parts(root).physical();
        var candidates = parts.stream().filter(part -> part.getClass().getName().equals(
                "net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler")).toList();
        if (candidates.isEmpty()) {
            var level = connector.getLevel();
            if (level != null) for (var direction : net.minecraft.core.Direction.values()) {
                var pos = connector.getBlockPos().relative(direction);
                if (level.hasChunkAt(pos) && dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.isSupportedBlock(level.getBlockEntity(pos))) {
                    dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.report("HANDLER_UNSUPPORTED", "fabric"); break;
                }
            }
            return null;
        }
        if (!(root instanceof com.tom.storagemod.util.MergedStorage merged)) return null;
        int directMatches = 0;
        for (var child : merged.getStorages()) {
            if (candidates.stream().anyMatch(candidate -> child == candidate)) directMatches++;
            else if (TomNetworkIntrospection.canonicalDigitalEndpoint(child) == null
                    && !TomNetworkIntrospection.safePhysical(child, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))) return null;
        }
        if (directMatches != candidates.size()) return null;
        var level = connector.getLevel();
        if (level == null || connector.isRemoved()) return null;
        var matched = new java.util.IdentityHashMap<Storage<ItemVariant>, net.minecraft.core.BlockPos>();
        var allowed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Storage<ItemVariant>, Boolean>());
        var denied = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Storage<ItemVariant>, Boolean>());
        for (var block : dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.connectedContainers(connector, tomRange())) {
            var pos = block.getBlockPos();
            var mods = net.fabricmc.loader.api.FabricLoader.getInstance();
            boolean verifiedLegacy = dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.knownLegacy(
                    mods.getModContainer("sophisticatedstorage").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(""),
                    mods.getModContainer("sophisticatedcore").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse(""), true, block.getClass().getClassLoader());
            var upgrades = dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.upgradeInventory(block, !verifiedLegacy);
            boolean eligible = upgrades instanceof Storage<?>;
            if (upgrades instanceof Storage<?> upgradeStorage) for (var view : upgradeStorage) if (!view.isResourceBlank()
                    && (!(view.getResource() instanceof ItemVariant variant)
                    || !dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.isStackUpgrade(variant.getItem()))) { dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.report("UPGRADE_NOT_ALLOWED", block.getClass().getName()); eligible = false; break; }
            for (var side : net.minecraft.core.Direction.values()) {
                var direct = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(level, pos, side);
                for (var candidate : candidates) if (direct == candidate) {
                    var prior = matched.putIfAbsent(candidate, pos);
                    if (prior != null && !prior.equals(pos)
                            && !dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.sameDoubleChest(level.getBlockEntity(prior), block)) return null;
                    if (!eligible) { denied.add(candidate); allowed.remove(candidate); }
                    else if (!denied.contains(candidate)) allowed.add(candidate);
                }
            }
        }
        if (matched.size() != candidates.size() || new java.util.HashSet<>(matched.values()).size() != candidates.size()) { dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.report("HANDLER_MISMATCH", "fabric"); return null; }
        var result = candidates.stream().filter(allowed::contains).toList();
        return result.isEmpty() ? null : result;
    }
    private static int tomRange() {
        try {
            return Config.get().invRange;
        } catch (RuntimeException failure) {
            dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.report("NETWORK_RANGE_UNKNOWN", failure.getClass().getSimpleName());
            return 1;
        }
    }
    public static final class Port implements BatchTransfer.Port {
        private final Storage<ItemVariant> storage;
        public Port(Storage<ItemVariant> storage) { this.storage = storage; }
        public Iterable<BatchTransfer.Entry> contents() {
            var entries = new ArrayList<BatchTransfer.Entry>();
            if (storage.supportsExtraction()) for (var view : storage)
                if (!view.isResourceBlank() && view.getAmount() > 0)
                    entries.add(new BatchTransfer.Entry(FabricItemKeys.fromVariant(view.getResource()), view.getAmount()));
            return entries;
        }
        public BatchTransfer.Cursor cursor(boolean exporting) {
            return new BatchTransfer.Cursor() {
                final Iterator<StorageView<ItemVariant>> views = storage.iterator();
                StorageView<ItemVariant> pending;
                boolean outputDone;
                public boolean hasNext() { return exporting ? !outputDone && storage.supportsInsertion() : pending != null || views.hasNext(); }
                public BatchTransfer.Result next(DigitalStorageRecord record, ItemKey key, long maximum) {
                    ItemVariant variant = ItemVariant.of(key.toStack(1));
                    var digital = FabricDigitalItemStorage.of(record.storage());
                    if (exporting) {
                        outputDone = true;
                        try (Transaction transaction = Transaction.openOuter()) {
                            long remaining = Math.min(maximum, record.storage().amountOf(key));
                            long accepted = 0;
                            if (storage instanceof SlottedStorage<ItemVariant> slotted) {
                                for (int slot = 0; slot < slotted.getSlotCount() && remaining > 0; slot++) {
                                    long inserted = slotted.getSlot(slot).insert(variant, remaining, transaction);
                                    if (inserted < 0 || inserted > remaining) throw new IllegalStateException("Invalid batch insertion");
                                    accepted += inserted;
                                    remaining -= inserted;
                                }
                            } else {
                                while (remaining > 0) {
                                    long inserted = storage.insert(variant, remaining, transaction);
                                    if (inserted < 0 || inserted > remaining) throw new IllegalStateException("Invalid batch insertion");
                                    if (inserted == 0) break;
                                    accepted += inserted;
                                    remaining -= inserted;
                                }
                            }
                            if (accepted == 0) return BatchTransfer.Result.empty();
                            if (digital.extract(variant, accepted, transaction) != accepted)
                                throw new IllegalStateException("Invalid batch insertion");
                            transaction.commit();
                            return new BatchTransfer.Result(accepted, "");
                        }
                    }
                    var view = pending == null ? views.next() : pending; pending = null;
                    if (view.isResourceBlank() || !view.getResource().equals(variant)) return BatchTransfer.Result.empty();
                    try (Transaction transaction = Transaction.openOuter()) {
                        long accepted = digital.insert(variant, Math.min(maximum, view.getAmount()), transaction);
                        if (accepted == 0) return BatchTransfer.Result.empty();
                        if (view.extract(variant, accepted, transaction) != accepted) return BatchTransfer.Result.empty();
                        transaction.commit();
                        if (view.getAmount() > 0) pending = view;
                        return new BatchTransfer.Result(accepted, "");
                    }
                }
            };
        }
    }
}
