package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.registry.Registries;

public final class TomNetworkAnalysis {
    private static final int MAX_INVENTORY_PENALTY = 20;
    private static final int MAX_VIEW_PENALTY = 15;
    private static final int MAX_DIGITAL_VIEW_PENALTY = 5;
    private static final int MAX_NON_EMPTY_PENALTY = 40;
    private static final int MAX_SCANNER_PENALTY = 15;
    private static final int MAX_FAILURE_PENALTY = 10;
    private static final int MAX_FREQUENCY_PENALTY = 5;
    private static final int MAX_DUPLICATE_ENDPOINT_PENALTY = 40;

    private TomNetworkAnalysis() {
    }

    public static Report analyze(DigitalStorageAccessorBlockEntity accessor) {
        DigitalItemStorage target = accessor.getCanonicalStorage();
        TomNetworkIntrospection.Discovery discovery = TomNetworkIntrospection.discoverContext(accessor);
        if (target == null || discovery == null) {
            return Report.unavailable();
        }
        Storage<ItemVariant> network = discovery.storage();
        TomNetworkCache.Topology topology = TomNetworkCache.topology(discovery.connector(), network);
        List<Storage<ItemVariant>> sources = topology.physical();
        int digitalViews = topology.digital().stream().mapToInt(DigitalItemStorage::variantCount).sum();
        int duplicateDigitalEndpoints = topology.duplicateDigitalEndpointCount();
        int targetEndpointCount = topology.digitalEndpointCount(target);
        TomScannerTelemetry.associate(target, topology.identity());
        Map<ItemVariant, MutableCandidate> grouped = new HashMap<>();
        int totalViews = 0;
        int nonEmptyViews = 0;
        for (Storage<ItemVariant> source : sources) {
            boolean extractable = source.supportsExtraction();
            for (StorageView<ItemVariant> view : source) {
                totalViews++;
                if (view.isResourceBlank() || view.getAmount() <= 0) {
                    continue;
                }
                nonEmptyViews++;
                if (extractable) {
                    grouped.computeIfAbsent(view.getResource(), MutableCandidate::new)
                            .add(view.getAmount());
                }
            }
        }

        int remainingVariants = Math.max(0, accessor.getRecord().variantCapacity() - target.variantCount());
        List<Candidate> candidates = new ArrayList<>();
        for (MutableCandidate candidate : grouped.values()) {
            boolean existing = target.amountOf(candidate.variant) > 0;
            if (!accessor.getRecord().canInsert(candidate.variant)) {
                continue;
            }
            long available = DigitalItemStorage.MAX_AMOUNT_PER_VARIANT - target.amountOf(candidate.variant);
            if (candidate.amount > available) {
                continue;
            }
            candidates.add(new Candidate(candidate.variant, candidate.amount, candidate.views, existing));
        }
        candidates.sort(Comparator
                .comparing(Candidate::existingInTarget).reversed()
                .thenComparing(Candidate::physicalViews, Comparator.reverseOrder())
                .thenComparing(Candidate::amount, Comparator.reverseOrder())
                .thenComparing(candidate -> Registries.ITEM.getId(candidate.variant().getItem()).toString())
                .thenComparing(candidate -> candidate.variant().toNbt().asString()));

        long remainingVariantNbtBytes = Math.max(
                0,
                (long) DigitalStorageConfig.get().maxVolumeVariantNbtBytes - target.totalVariantNbtBytes()
        );
        List<Candidate> selected = selectWithinVariantBudget(
                candidates,
                remainingVariants,
                remainingVariantNbtBytes
        );
        int estimatedFreedViews = selected.stream().mapToInt(Candidate::physicalViews).sum();

        long tick = accessor.getWorld() == null ? 0 : accessor.getWorld().getTime();
        TomScannerTelemetry.Snapshot scanners = TomScannerTelemetry.snapshot(target, tick);
        int score = score(
                sources.size(), totalViews, nonEmptyViews, digitalViews,
                scanners.activeScanners(), scanners.failingScanners(), scanners.averageIntervalTicks(),
                duplicateDigitalEndpoints
        );
        return new Report(
                true,
                score,
                grade(score),
                sources.size(),
                totalViews,
                nonEmptyViews,
                digitalViews,
                duplicateDigitalEndpoints,
                targetEndpointCount,
                scanners.activeScanners(),
                scanners.failingScanners(),
                scanners.averageIntervalTicks(),
                estimatedFreedViews,
                List.copyOf(selected),
                topology.token(),
                topology.physicalEndpoints()
        );
    }

    private static List<Candidate> selectWithinVariantBudget(
            List<Candidate> candidates,
            int remainingVariants,
            long remainingVariantNbtBytes
    ) {
        List<Candidate> selected = new ArrayList<>();
        int newVariants = 0;
        long selectedVariantNbtBytes = 0;
        for (Candidate candidate : candidates) {
            if (!candidate.existingInTarget()) {
                int variantNbtBytes = candidate.variant().toNbt().getSizeInBytes();
                if (newVariants >= remainingVariants
                        || variantNbtBytes > remainingVariantNbtBytes - selectedVariantNbtBytes) {
                    continue;
                }
                newVariants++;
                selectedVariantNbtBytes += variantNbtBytes;
            }
            selected.add(candidate);
        }
        return selected;
    }

    private static int score(
            int inventories,
            int views,
            int nonEmptyViews,
            int digitalViews,
            int scanners,
            int failures,
            int averageIntervalTicks,
            int duplicateDigitalEndpoints
    ) {
        int inventoryPenalty = Math.min(MAX_INVENTORY_PENALTY, inventories / 4);
        int viewPenalty = Math.min(MAX_VIEW_PENALTY, views / 256);
        int nonEmptyPenalty = Math.min(MAX_NON_EMPTY_PENALTY, nonEmptyViews / 128);
        int digitalViewPenalty = Math.min(
                MAX_DIGITAL_VIEW_PENALTY,
                digitalViews / DigitalStorageConfig.get().digitalViewScoreDivisor
        );
        int scannerPenalty = Math.min(MAX_SCANNER_PENALTY, scanners * 3);
        int failurePenalty = Math.min(MAX_FAILURE_PENALTY, failures * 5);
        int frequencyPenalty = averageIntervalTicks <= 0 ? 0
                : Math.min(MAX_FREQUENCY_PENALTY, Math.max(1, 6 - averageIntervalTicks / 5));
        int duplicateEndpointPenalty = Math.min(
                MAX_DUPLICATE_ENDPOINT_PENALTY,
                duplicateDigitalEndpoints * 20
        );
        return Math.max(0, 100 - inventoryPenalty - viewPenalty - nonEmptyPenalty - digitalViewPenalty
                - scannerPenalty - failurePenalty - frequencyPenalty - duplicateEndpointPenalty);
    }

    private static String grade(int score) {
        if (score >= 90) return "A";
        if (score >= 75) return "B";
        if (score >= 60) return "C";
        if (score >= 40) return "D";
        return "E";
    }

    public static void runSelfTest() {
        Candidate existing = new Candidate(
                ItemVariant.of(net.minecraft.item.Items.STONE), 64, 2, true
        );
        Candidate firstNew = new Candidate(
                ItemVariant.of(net.minecraft.item.Items.DIRT), 640, 10, false
        );
        Candidate secondNew = new Candidate(
                ItemVariant.of(net.minecraft.item.Items.COBBLESTONE), 576, 9, false
        );
        List<Candidate> selected = selectWithinVariantBudget(
                List.of(existing, firstNew, secondNew), 1, Long.MAX_VALUE
        );
        if (!selected.equals(List.of(existing, firstNew))) {
            throw new IllegalStateException("Tom recommendation variant budget self-test failed");
        }
        if (!"A".equals(grade(90)) || !"D".equals(grade(43)) || !"E".equals(grade(39))) {
            throw new IllegalStateException("Tom network health grade self-test failed");
        }
        if (score(0, 0, 0, 0, 0, 0, 0, 0) != 100
                || score(0, 0, 0, DigitalStorageConfig.get().digitalViewScoreDivisor, 0, 0, 0, 0) != 99
                || score(0, 0, 0, 0, 0, 0, 0, 1) != 80) {
            throw new IllegalStateException("Tom digital-view health weight self-test failed");
        }
        runTomIntrospectionSelfTest();
    }

    @SuppressWarnings("unchecked")
    private static void runTomIntrospectionSelfTest() {
        try {
            Class<?> mergedClass = Class.forName("com.tom.storagemod.util.MergedStorage");
            Object merged = mergedClass.getConstructor().newInstance();
            java.lang.reflect.Method add = mergedClass.getMethod("add", Storage.class);
            DigitalItemStorage target = new DigitalItemStorage(() -> { }, 64);
            DigitalItemStorage physicalDelegate = new DigitalItemStorage(() -> { }, 64);
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
                    || parts.rawDigital().size() != 1 || parts.rawDigital().get(0) != target) {
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

    public record Candidate(
            ItemVariant variant,
            long amount,
            int physicalViews,
            boolean existingInTarget
    ) {
        public String itemId() {
            return Registries.ITEM.getId(variant.getItem()).toString();
        }
    }

    public record Report(
            boolean available,
            int healthScore,
            String grade,
            int physicalInventories,
            int totalViews,
            int nonEmptyViews,
            int digitalViews,
            int duplicateDigitalEndpoints,
            int targetEndpointCount,
            int activeScanners,
            int failingScanners,
            int averageScanIntervalTicks,
            int estimatedFreedViews,
            List<Candidate> candidates,
            TomNetworkCache.Token topology,
            List<TomNetworkCache.Endpoint> sourceEndpoints
    ) {
        private static Report unavailable() {
            return new Report(false, 0, "-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null, List.of());
        }

        public String topCandidateId() {
            return candidates.isEmpty() ? "" : candidates.get(0).itemId();
        }
    }

    private static final class MutableCandidate {
        private final ItemVariant variant;
        private long amount;
        private int views;

        private MutableCandidate(ItemVariant variant) {
            this.variant = variant;
        }

        private void add(long addedAmount) {
            long rejectionThreshold = DigitalItemStorage.MAX_AMOUNT_PER_VARIANT + 1;
            amount = addedAmount >= rejectionThreshold - amount
                    ? rejectionThreshold
                    : amount + addedAmount;
            views++;
        }
    }
}
