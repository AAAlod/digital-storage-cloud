package dev.kehai.digitalstorage.platform.fabric.tom;

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
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.server.level.ServerPlayer;

public final class FabricBatchNetwork implements BatchTransfers.Backend {
    @Override public void runWorldSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        runChestPreviewSelfTest(player, accessor, false);
        runChestPreviewSelfTest(player, accessor, true);
    }
    private void runChestPreviewSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor, boolean doubleChest) {
        var world = player.serverLevel();
        var connectorPos = accessor.getBlockPos().east();
        var chestPos = connectorPos.east();
        var secondChestPos = chestPos.east();
        var cloud = dev.kehai.digitalstorage.storage.DigitalStorageState.get(world);
        var volume = cloud.createVolume(player.getUUID(), "Physical batch fixture", Integer.MAX_VALUE).orElseThrow();
        if (!world.getBlockState(connectorPos).isAir() || !world.getBlockState(chestPos).isAir()
                || !world.getBlockState(secondChestPos).isAir())
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
            accessor.bind(player, volume.id());
            var connector = (com.tom.storagemod.tile.InventoryConnectorBlockEntity) world.getBlockEntity(connectorPos);
            long time = world.getGameTime();
            try {
                world.getServer().getWorldData().overworldData().setGameTime(time - time % 20);
                connector.updateServer();
                dev.kehai.digitalstorage.screen.BatchScreenSession.runNetworkPreviewSelfTest(player, accessor,
                        () -> { for (int i = 1; i <= 5; i++) {
                            world.getServer().getWorldData().overworldData().setGameTime(time - time % 20 + i * 20);
                            connector.updateServer();
                        } }, () -> { world.removeBlock(doubleChest ? secondChestPos : chestPos, false); connector.updateServer(); });
                if (!chest.isEmpty()) throw new IllegalStateException("Imported chest was not emptied");
            } finally { world.getServer().getWorldData().overworldData().setGameTime(time); }
        } finally {
            accessor.clearBinding(player);
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
        try {
            var predicateClass = Class.forName("com.tom.storagemod.util.ItemPredicate");
            var deny = java.lang.reflect.Proxy.newProxyInstance(predicateClass.getClassLoader(), new Class<?>[]{predicateClass},
                    (proxy, method, args) -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null);
            @SuppressWarnings("unchecked") var filtered = (Storage<ItemVariant>) Class.forName("com.tom.storagemod.util.FilteredStorage")
                    .getConstructor(Storage.class, predicateClass, boolean.class).newInstance(physical, deny, false);
            var filteredParts = TomNetworkIntrospection.transferParts(filtered);
            if (filteredParts.size() != 1 || filteredParts.get(0) != filtered) throw new IllegalStateException("Physical Fabric filter was stripped");
            var rejected = new BatchTransfer(record, new BatchTransfer.Route(java.util.List.of(new Port(filteredParts.get(0))), () -> true, "fixture"),
                    java.util.List.of(new BatchTransfer.Entry(stone, 5)), true);
            for (int i = 0; i < 10 && rejected.active(); i++) rejected.tick(2);
            if (rejected.moved() != 0 || box.getItem(0).getCount() != 24 || record.storage().amountOf(stone) != 72)
                throw new IllegalStateException("Fabric export bypassed Tom filter");
            @SuppressWarnings("unchecked") var unsafe = (Storage<ItemVariant>) Class.forName("com.tom.storagemod.util.FilteredStorage")
                    .getConstructor(Storage.class, predicateClass, boolean.class).newInstance(mixed, deny, false);
            if (!TomNetworkIntrospection.transferParts(unsafe).isEmpty()) throw new IllegalStateException("Mixed filtered Fabric aggregate became writable");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Tom filter fixture unavailable", failure); }
    }
    public BatchTransfer.Route open(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        var discovery = TomNetworkIntrospection.discoverContext(accessor);
        if (discovery == null) return null;
        var volume = accessor.getVolume();
        var topology = TomNetworkCache.topology(discovery.connector(), discovery.storage());
        var ports = TomNetworkIntrospection.transferParts(discovery.storage()).stream()
                .map(storage -> (BatchTransfer.Port) new Port(storage)).toList();
        return new BatchTransfer.Route(ports, () -> !accessor.isRemoved() && player.serverLevel() == accessor.getLevel()
                && accessor.getLevel().hasChunkAt(accessor.getBlockPos())
                && accessor.getLevel().getBlockEntity(accessor.getBlockPos()) == accessor
                && accessor.getVolume() == volume && volume.ownerId().equals(player.getUUID())
                && topology.token().isCurrent(), discovery.connector().getBlockPos().toShortString());
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
                            long offered = Math.min(maximum, record.storage().amountOf(key));
                            long accepted = storage.insert(variant, offered, transaction);
                            if (accepted == 0) return BatchTransfer.Result.empty();
                            if (accepted > offered || digital.extract(variant, accepted, transaction) != accepted)
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
