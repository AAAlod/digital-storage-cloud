package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.ItemKeyCodec;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;

/** Shared network scoring and migration recommendations over platform-neutral inventory views. */
public final class NetworkAnalysis {
    private NetworkAnalysis() {
    }

    public static Report analyze(DigitalStorageRecord record, Snapshot snapshot) {
        if (record == null || snapshot == null) {
            return Report.unavailable();
        }
        VolumeLedger target = record.storage();
        Map<ItemKey, MutableCandidate> grouped = new HashMap<>();
        int totalViews = 0;
        int nonEmptyViews = 0;
        for (InventoryEndpoint source : snapshot.physical()) {
            boolean extractable = source.supportsExtraction();
            for (InventoryEndpoint.View view : source) {
                totalViews++;
                if (view.isBlank() || view.amount() <= 0) {
                    continue;
                }
                nonEmptyViews++;
                if (extractable) {
                    grouped.computeIfAbsent(view.resource(), MutableCandidate::new).add(view.amount());
                }
            }
        }
        int remainingVariants = Math.max(0, record.variantCapacity() - target.variantCount());
        List<Candidate> candidates = new ArrayList<>();
        for (MutableCandidate candidate : grouped.values()) {
            long amountInTarget = target.amountOf(candidate.variant);
            if (!record.canInsert(candidate.variant) || candidate.amount > VolumeLedger.MAX_AMOUNT_PER_VARIANT - amountInTarget) {
                continue;
            }
            candidates.add(new Candidate(candidate.variant, candidate.amount, candidate.views, amountInTarget > 0));
        }
        candidates.sort(Comparator.comparing(Candidate::existingInTarget).reversed()
                .thenComparing(Candidate::physicalViews, Comparator.reverseOrder())
                .thenComparing(Candidate::amount, Comparator.reverseOrder())
                .thenComparing(Candidate::itemId)
                .thenComparing(candidate -> ItemKeyCodec.write(candidate.variant()).getAsString()));
        long remainingNbt = Math.max(0, (long) DigitalStorageConfig.get().maxVolumeVariantNbtBytes - target.totalVariantNbtBytes());
        List<Candidate> selected = snapshot.targetEndpointCount() > 1 ? List.of()
                : selectWithinVariantBudget(candidates, remainingVariants, remainingNbt);
        int score = score(snapshot.physical().size(), totalViews, nonEmptyViews, snapshot.digitalViews(),
                snapshot.activeScanners(), snapshot.failingScanners(), snapshot.averageIntervalTicks(),
                snapshot.duplicateDigitalEndpoints());
        return new Report(true, score, grade(score), snapshot.physical().size(), totalViews, nonEmptyViews,
                snapshot.digitalViews(), snapshot.duplicateDigitalEndpoints(), snapshot.targetEndpointCount(),
                snapshot.activeScanners(), snapshot.failingScanners(), snapshot.averageIntervalTicks(),
                selected.stream().mapToInt(Candidate::physicalViews).sum(), selected, snapshot.topology(), snapshot.sources());
    }

    private static List<Candidate> selectWithinVariantBudget(List<Candidate> candidates, int remainingVariants, long remainingNbt) {
        List<Candidate> selected = new ArrayList<>();
        int newVariants = 0;
        long selectedNbt = 0;
        for (Candidate candidate : candidates) {
            if (!candidate.existingInTarget()) {
                int bytes = ItemKeyCodec.write(candidate.variant()).sizeInBytes();
                if (newVariants >= remainingVariants || bytes > remainingNbt - selectedNbt) {
                    continue;
                }
                newVariants++;
                selectedNbt += bytes;
            }
            selected.add(candidate);
        }
        return List.copyOf(selected);
    }

    private static int score(int inventories, int views, int nonEmptyViews, int digitalViews,
                             int scanners, int failures, int interval, int duplicateEndpoints) {
        return Math.max(0, 100 - Math.min(20, inventories / 4) - Math.min(15, views / 256)
                - Math.min(40, nonEmptyViews / 128)
                - Math.min(5, digitalViews / DigitalStorageConfig.get().digitalViewScoreDivisor)
                - Math.min(15, scanners * 3) - Math.min(10, failures * 5)
                - (interval <= 0 ? 0 : Math.min(5, Math.max(1, 6 - interval / 5)))
                - Math.min(40, duplicateEndpoints * 20));
    }

    private static String grade(int score) {
        if (score >= 90) return "A";
        if (score >= 75) return "B";
        if (score >= 60) return "C";
        if (score >= 40) return "D";
        return "E";
    }

    public static void runSelfTest() {
        Candidate existing = new Candidate(ItemKey.of(net.minecraft.world.item.Items.STONE), 64, 2, true);
        Candidate first = new Candidate(ItemKey.of(net.minecraft.world.item.Items.DIRT), 640, 10, false);
        Candidate second = new Candidate(ItemKey.of(net.minecraft.world.item.Items.COBBLESTONE), 576, 9, false);
        if (!selectWithinVariantBudget(List.of(existing, first, second), 1, Long.MAX_VALUE).equals(List.of(existing, first))
                || !selectWithinVariantBudget(List.of(existing, first), 1, 0).equals(List.of(existing))) {
            throw new IllegalStateException("Shared recommendation capacity/NBT budget regression failed");
        }
        if (!"A".equals(grade(90)) || !"D".equals(grade(43)) || !"E".equals(grade(39))
                || score(0, 0, 0, 0, 0, 0, 0, 0) != 100
                || score(0, 0, 0, DigitalStorageConfig.get().digitalViewScoreDivisor, 0, 0, 0, 0) != 99
                || score(0, 0, 0, 0, 0, 0, 0, 1) != 80) {
            throw new IllegalStateException("Shared network health grade/weights regression failed");
        }
        MutableCandidate huge = new MutableCandidate(ItemKey.of(net.minecraft.world.item.Items.STONE));
        huge.add(Long.MAX_VALUE);
        huge.add(Long.MAX_VALUE);
        if (huge.amount != VolumeLedger.MAX_AMOUNT_PER_VARIANT + 1) {
            throw new IllegalStateException("Recommendation quantity saturation overflowed");
        }
        runEndpointSelfTest();
    }

    private static void runEndpointSelfTest() {
        ItemKey stone = ItemKey.of(net.minecraft.world.item.Items.STONE);
        ItemKey dirt = ItemKey.of(net.minecraft.world.item.Items.DIRT);
        ItemKey sword = ItemKey.of(net.minecraft.world.item.Items.DIAMOND_SWORD);
        DigitalStorageRecord record = DigitalStorageRecord.createNew(() -> { });
        record.storage().load(stone, 1);
        InventoryEndpoint extractable = testEndpoint(true, List.of(
                new TestView(stone, 10), new TestView(stone, 20), new TestView(dirt, 64),
                new TestView(ItemKey.blank(), 99), new TestView(dirt, 0), new TestView(sword, 1)));
        InventoryEndpoint locked = testEndpoint(false, List.of(new TestView(dirt, 500)));
        Snapshot snapshot = new Snapshot(List.of(extractable, locked), 0, 0, 1, 0, 0, 0, null, List.of());
        Report report = analyze(record, snapshot);
        if (report.physicalInventories() != 2 || report.totalViews() != 7 || report.nonEmptyViews() != 5
                || report.candidates().size() != 2 || report.estimatedFreedViews() != 3
                || !report.candidates().get(0).equals(new Candidate(stone, 30, 2, true))
                || !report.candidates().get(1).equals(new Candidate(dirt, 64, 1, false))) {
            throw new IllegalStateException("Shared endpoint filtering/grouping/recommendation regression failed");
        }
        if (!analyze(record, new Snapshot(snapshot.physical(), 0, 1, 2, 0, 0, 0, null, List.of()))
                .candidates().isEmpty()) {
            throw new IllegalStateException("Duplicate target endpoints produced a migration recommendation");
        }
        record.storage().load(stone, VolumeLedger.MAX_AMOUNT_PER_VARIANT);
        if (analyze(record, snapshot).candidates().stream().anyMatch(candidate -> candidate.variant().equals(stone))) {
            throw new IllegalStateException("Recommendation exceeded target quantity capacity");
        }
        Report oversized = analyze(record, new Snapshot(List.of(testEndpoint(true,
                List.of(new TestView(dirt, Long.MAX_VALUE), new TestView(dirt, Long.MAX_VALUE)))),
                0, 0, 1, 0, 0, 0, null, List.of()));
        if (!oversized.candidates().isEmpty() || analyze(null, snapshot).available()) {
            throw new IllegalStateException("Oversized or unavailable network recommendation regression failed");
        }
    }

    private static InventoryEndpoint testEndpoint(boolean extractable, List<InventoryEndpoint.View> views) {
        return new InventoryEndpoint() {
            @Override public boolean supportsExtraction() { return extractable; }
            @Override public java.util.Iterator<InventoryEndpoint.View> iterator() { return views.iterator(); }
        };
    }

    private record TestView(ItemKey resource, long amount) implements InventoryEndpoint.View {
        @Override public boolean isBlank() { return resource.isBlank(); }
    }

    public record Snapshot(List<InventoryEndpoint> physical, int digitalViews, int duplicateDigitalEndpoints,
                           int targetEndpointCount, int activeScanners, int failingScanners, int averageIntervalTicks,
                           TopologyToken topology, List<InventoryEndpoint.Reference> sources) {
        public Snapshot {
            physical = List.copyOf(physical);
            sources = List.copyOf(sources);
        }
    }

    public record Candidate(ItemKey variant, long amount, int physicalViews, boolean existingInTarget) {
        public String itemId() { return BuiltInRegistries.ITEM.getKey(variant.item()).toString(); }
    }

    public record Report(boolean available, int healthScore, String grade, int physicalInventories, int totalViews,
                         int nonEmptyViews, int digitalViews, int duplicateDigitalEndpoints, int targetEndpointCount,
                         int activeScanners, int failingScanners, int averageScanIntervalTicks, int estimatedFreedViews,
                         List<Candidate> candidates, TopologyToken topology, List<InventoryEndpoint.Reference> sourceEndpoints) {
        public Report {
            candidates = List.copyOf(candidates);
            sourceEndpoints = List.copyOf(sourceEndpoints);
        }

        public static Report unavailable() {
            return new Report(false, 0, "-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), null, List.of());
        }

        public String topCandidateId() { return candidates.isEmpty() ? "" : candidates.get(0).itemId(); }
    }

    private static final class MutableCandidate {
        private final ItemKey variant;
        private long amount;
        private int views;

        private MutableCandidate(ItemKey variant) { this.variant = variant; }

        private void add(long added) {
            long threshold = VolumeLedger.MAX_AMOUNT_PER_VARIANT + 1;
            amount = added >= threshold - amount ? threshold : amount + added;
            views++;
        }
    }
}
