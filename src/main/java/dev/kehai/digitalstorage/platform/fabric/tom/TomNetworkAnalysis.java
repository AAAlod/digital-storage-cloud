package dev.kehai.digitalstorage.platform.fabric.tom;

import com.tom.storagemod.util.MergedStorage;
import com.tom.storagemod.util.FilteredStorage;
import com.tom.storagemod.util.ItemPredicate;

import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.InventoryEndpoint;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.platform.fabric.FabricDigitalItemStorage;
import dev.kehai.digitalstorage.platform.fabric.FabricInventoryEndpoint;
import dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkCache.Topology;
import dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkIntrospection.Discovery;
import dev.kehai.digitalstorage.platform.fabric.tom.TomScannerTelemetry.Snapshot;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;

/** Fabric/Tom discovery facade; policy and report types belong to NetworkAnalysis. */
public final class TomNetworkAnalysis {
    private TomNetworkAnalysis() {
    }

    public static NetworkAnalysis.Report analyze(DigitalStorageAccessorBlockEntity accessor) {
        FabricDigitalItemStorage target = dev.kehai.digitalstorage.platform.fabric.FabricAccessorBlockEntity.canonicalStorage(accessor);
        var discovery = TomNetworkIntrospection.discoverContext(accessor);
        if (target == null || discovery == null) {
            return NetworkAnalysis.Report.unavailable();
        }
        var topology = TomNetworkCache.topology(discovery.connector(), discovery.storage());
        TomScannerTelemetry.associate(target, topology.identity());
        long tick = accessor.getLevel() == null ? 0 : accessor.getLevel().getGameTime();
        var scanners = TomScannerTelemetry.snapshot(target, tick);
        List<InventoryEndpoint> physical = topology.physical().stream()
                .map(storage -> (InventoryEndpoint) new FabricInventoryEndpoint(storage)).toList();
        List<InventoryEndpoint.Reference> sources = topology.physicalEndpoints().stream()
                .map(endpoint -> (InventoryEndpoint.Reference) endpoint).toList();
        return NetworkAnalysis.analyze(accessor.getRecord(), new NetworkAnalysis.Snapshot(
                physical, topology.digital().stream().mapToInt(FabricDigitalItemStorage::variantCount).sum(),
                topology.duplicateDigitalEndpointCount(), topology.digitalEndpointCount(target),
                scanners.activeScanners(), scanners.failingScanners(), scanners.averageIntervalTicks(),
                topology.token(), sources));
    }

    public static void runSelfTest() {
        NetworkAnalysis.runSelfTest();
        runTomIntrospectionSelfTest();
    }

    private static void runTomIntrospectionSelfTest() {
        MergedStorage merged = new MergedStorage();
        FabricDigitalItemStorage target = new FabricDigitalItemStorage(() -> { }, 64);
        FabricDigitalItemStorage physicalDelegate = new FabricDigitalItemStorage(() -> { }, 64);
        Storage<ItemVariant> physical = new Storage<>() {
            @Override
            public long insert(ItemVariant resource, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                return physicalDelegate.insert(resource, maxAmount, transaction);
            }

            @Override
            public long extract(ItemVariant resource, long maxAmount, net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
                return physicalDelegate.extract(resource, maxAmount, transaction);
            }

            @Override
            public java.util.Iterator<StorageView<ItemVariant>> iterator() {
                return physicalDelegate.iterator();
            }
        };

        ItemPredicate predicate = variant -> true;
        var wrappedTarget = new FilteredStorage(target, predicate, false);
        var duplicateWrappedTarget = new FilteredStorage(target, predicate, false);

        TomNetworkIntrospection.NetworkParts rawParts = TomNetworkIntrospection.parts(
                new SelfTestStorageGroup(List.of(
                        wrappedTarget,
                        duplicateWrappedTarget,
                        physical
                ))
        );
        if (rawParts.physical().size() != 1 || rawParts.physical().get(0) != physical
                || rawParts.digital().size() != 1 || rawParts.digital().get(0) != target
                || rawParts.rawDigital().size() != 2
                || rawParts.rawDigital().get(0) != target || rawParts.rawDigital().get(1) != target) {
            throw new IllegalStateException("Tom raw endpoint detection and identity dedup self-test failed");
        }

        merged.add(wrappedTarget);
        merged.add(duplicateWrappedTarget);
        merged.add(physical);

        ItemVariant stone = ItemVariant.of(net.minecraft.world.item.Items.STONE);
        try (net.fabricmc.fabric.api.transfer.v1.transaction.Transaction transaction =
                     net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
            target.insert(stone, 37, transaction);
            transaction.commit();
        }
        long terminalVisibleAmount = 0;
        for (StorageView<ItemVariant> view : merged) {
            if (stone.equals(view.getResource())) {
                terminalVisibleAmount += view.getAmount();
            }
        }
        if (terminalVisibleAmount != 37) {
            throw new IllegalStateException(
                    "Tom terminal duplicate Volume count: expected 37, got " + terminalVisibleAmount
            );
        }

        TomNetworkIntrospection.NetworkParts parts = TomNetworkIntrospection.parts(merged);
        if (parts.physical().size() != 1 || parts.physical().get(0) != physical
                || parts.digital().size() != 1 || parts.digital().get(0) != target
                || parts.rawDigital().size() != 2
                || TomNetworkIntrospection.digitalEndpointCount(parts.rawDigital(), target) != 2
                || TomNetworkIntrospection.duplicateDigitalEndpointCount(parts.rawDigital()) != 1) {
            throw new IllegalStateException("Tom duplicate Volume prevention self-test failed");
        }
    }

    static final class SelfTestStorageGroup implements Storage<ItemVariant> {
        private final List<Storage<ItemVariant>> storages;

        SelfTestStorageGroup(List<Storage<ItemVariant>> storages) {
            this.storages = storages;
        }

        public List<Storage<ItemVariant>> getStorages() {
            return storages;
        }

        @Override
        public long insert(ItemVariant resource, long maxAmount,
                           net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
            return 0;
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount,
                            net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction) {
            return 0;
        }

        @Override
        public java.util.Iterator<StorageView<ItemVariant>> iterator() {
            return java.util.Collections.emptyIterator();
        }
    }

}
