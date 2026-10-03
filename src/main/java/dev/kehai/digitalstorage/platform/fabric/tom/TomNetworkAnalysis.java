package dev.kehai.digitalstorage.platform.fabric.tom;

import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.InventoryEndpoint;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.platform.fabric.FabricDigitalItemStorage;
import dev.kehai.digitalstorage.platform.fabric.FabricInventoryEndpoint;
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
        long tick = accessor.getWorld() == null ? 0 : accessor.getWorld().getTime();
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

    @SuppressWarnings("unchecked")
    private static void runTomIntrospectionSelfTest() {
        try {
            Class<?> mergedClass = Class.forName("com.tom.storagemod.util.MergedStorage");
            Object merged = mergedClass.getConstructor().newInstance();
            java.lang.reflect.Method add = mergedClass.getMethod("add", Storage.class);
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

            Class<?> predicateClass = Class.forName("com.tom.storagemod.util.ItemPredicate");
            Object predicate = java.lang.reflect.Proxy.newProxyInstance(
                    predicateClass.getClassLoader(),
                    new Class<?>[]{predicateClass},
                    (proxy, method, arguments) -> {
                        if (method.getReturnType() == boolean.class) return true;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getName().equals("toString")) return "allow-all predicate";
                        return null;
                    }
            );
            Class<?> filteredClass = Class.forName("com.tom.storagemod.util.FilteredStorage");
            Object wrappedTarget = filteredClass
                    .getConstructor(Storage.class, predicateClass, boolean.class)
                    .newInstance(target, predicate, false);
            Object duplicateWrappedTarget = filteredClass
                    .getConstructor(Storage.class, predicateClass, boolean.class)
                    .newInstance(target, predicate, false);

            TomNetworkIntrospection.NetworkParts rawParts = TomNetworkIntrospection.parts(
                    new SelfTestStorageGroup(List.of(
                            (Storage<ItemVariant>) wrappedTarget,
                            (Storage<ItemVariant>) duplicateWrappedTarget,
                            physical
                    ))
            );
            if (rawParts.physical().size() != 1 || rawParts.physical().get(0) != physical
                    || rawParts.digital().size() != 1 || rawParts.digital().get(0) != target
                    || rawParts.rawDigital().size() != 2
                    || rawParts.rawDigital().get(0) != target || rawParts.rawDigital().get(1) != target) {
                throw new IllegalStateException("Tom raw endpoint detection and identity dedup self-test failed");
            }

            add.invoke(merged, wrappedTarget);
            add.invoke(merged, duplicateWrappedTarget);
            add.invoke(merged, physical);

            ItemVariant stone = ItemVariant.of(net.minecraft.item.Items.STONE);
            try (net.fabricmc.fabric.api.transfer.v1.transaction.Transaction transaction =
                         net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
                target.insert(stone, 37, transaction);
                transaction.commit();
            }
            long terminalVisibleAmount = 0;
            for (StorageView<ItemVariant> view : (Storage<ItemVariant>) merged) {
                if (stone.equals(view.getResource())) {
                    terminalVisibleAmount += view.getAmount();
                }
            }
            if (terminalVisibleAmount != 37) {
                throw new IllegalStateException(
                        "Tom terminal duplicate Volume count: expected 37, got " + terminalVisibleAmount
                );
            }

            TomNetworkIntrospection.NetworkParts parts = TomNetworkIntrospection.parts((Storage<ItemVariant>) merged);
            if (parts.physical().size() != 1 || parts.physical().get(0) != physical
                    || parts.digital().size() != 1 || parts.digital().get(0) != target
                    || parts.rawDigital().size() != 2
                    || TomNetworkIntrospection.digitalEndpointCount(parts.rawDigital(), target) != 2
                    || TomNetworkIntrospection.duplicateDigitalEndpointCount(parts.rawDigital()) != 1) {
                throw new IllegalStateException("Tom duplicate Volume prevention self-test failed");
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Tom network introspection self-test failed", exception);
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
