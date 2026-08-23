package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.hopper.HopperTransferOptimizer;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;

/** Manual, bounded microbenchmark for calibrating Tom view-cost heuristics. */
public final class TomPerformanceBenchmark {
    private static final int VARIANTS = 512;
    private static final int WARMUP_PASSES = 40;
    private static final int MEASURED_PASSES = 160;
    private static final int MIGRATION_VIEW_BUDGET = 256;
    private static final int LATENCY_WARMUP_PASSES = 12;
    private static final int LATENCY_MEASURED_PASSES = 128;
    private static final int[] HOPPER_COUNTS = {1, 10, 50, 100};
    private static final int[] HOPPER_VARIANTS = {64, 256, 1_024};
    private static final double ROUND_ROBIN_P99_THRESHOLD_MS = 2.0;
    private static final double ROUND_ROBIN_MAX_THRESHOLD_MS = 5.0;
    private static volatile long blackhole;

    private TomPerformanceBenchmark() {
    }

    public static Result run() {
        try {
            Class<?> mergedClass = Class.forName("com.tom.storagemod.util.MergedStorage");
            Method add = mergedClass.getMethod("add", Storage.class);
            Storage<ItemVariant> physicalNetwork = newMerged(mergedClass);
            Storage<ItemVariant> digitalNetwork = newMerged(mergedClass);
            DigitalItemStorage digital = new DigitalItemStorage(() -> { }, VARIANTS);

            try (Transaction transaction = Transaction.openOuter()) {
                for (int index = 0; index < VARIANTS; index++) {
                    ItemVariant variant = variant(index);
                    digital.insert(variant, 1, transaction);
                    DigitalItemStorage single = new DigitalItemStorage(() -> { }, 1);
                    single.insert(variant, 1, transaction);
                    add.invoke(physicalNetwork, new OpaqueStorage(single));
                }
                add.invoke(digitalNetwork, digital);
                transaction.commit();
            }

            consume(physicalNetwork, WARMUP_PASSES);
            consume(digitalNetwork, WARMUP_PASSES);
            long physicalNanos = measure(physicalNetwork);
            long digitalNanos = measure(digitalNetwork);
            double physicalNanosPerView = (double) physicalNanos / (VARIANTS * MEASURED_PASSES);
            double digitalNanosPerView = (double) digitalNanos / (VARIANTS * MEASURED_PASSES);
            double relativeCost = digitalNanosPerView / Math.max(1.0, physicalNanosPerView);
            int recommendedDigitalDivisor = clamp(
                    (int) Math.round(256 / Math.max(0.03125, relativeCost)),
                    256,
                    8_192
            );
            int recommendedScanBudget = clamp(
                    (int) Math.round(250_000 / Math.max(1.0, physicalNanosPerView)),
                    64,
                    4_096
            );
            Latency migrationLatency = benchmarkMigrationBatch();
            List<HopperCase> hopperCases = benchmarkHoppers(mergedClass, add);
            HopperCase worstHopper = hopperCases.get(hopperCases.size() - 1);
            boolean additionalRoundRobinRecommended = worstHopper.latency().p99Millis() >= ROUND_ROBIN_P99_THRESHOLD_MS
                    || worstHopper.latency().maxMillis() >= ROUND_ROBIN_MAX_THRESHOLD_MS;
            return new Result(
                    VARIANTS,
                    physicalNanosPerView,
                    digitalNanosPerView,
                    relativeCost,
                    recommendedDigitalDivisor,
                    recommendedScanBudget,
                    migrationLatency,
                    hopperCases,
                    additionalRoundRobinRecommended
            );
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not run Tom performance benchmark", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Storage<ItemVariant> newMerged(Class<?> mergedClass) throws ReflectiveOperationException {
        return (Storage<ItemVariant>) mergedClass.getConstructor().newInstance();
    }

    private static ItemVariant variant(int index) {
        NbtCompound nbt = new NbtCompound();
        nbt.putInt("DigitalStorageBenchmark", index);
        return ItemVariant.of(Items.PAPER, nbt);
    }

    private static long measure(Storage<ItemVariant> storage) {
        long start = System.nanoTime();
        consume(storage, MEASURED_PASSES);
        return System.nanoTime() - start;
    }

    private static void consume(Storage<ItemVariant> storage, int passes) {
        long total = 0;
        for (int pass = 0; pass < passes; pass++) {
            for (StorageView<ItemVariant> view : storage) {
                total += view.getAmount();
            }
        }
        blackhole = total;
    }

    private static Latency benchmarkMigrationBatch() {
        for (int pass = 0; pass < LATENCY_WARMUP_PASSES; pass++) {
            blackhole ^= prepareMigrationBatch().run();
        }
        long[] samples = new long[LATENCY_MEASURED_PASSES];
        for (int pass = 0; pass < samples.length; pass++) {
            MigrationFixture fixture = prepareMigrationBatch();
            long start = System.nanoTime();
            long moved = fixture.run();
            samples[pass] = System.nanoTime() - start;
            blackhole ^= moved;
        }
        return Latency.of(samples);
    }

    private static MigrationFixture prepareMigrationBatch() {
        DigitalItemStorage target = new DigitalItemStorage(() -> { }, MIGRATION_VIEW_BUDGET);
        List<StorageView<ItemVariant>> views = new ArrayList<>(MIGRATION_VIEW_BUDGET);
        for (int index = 0; index < MIGRATION_VIEW_BUDGET; index++) {
            DigitalItemStorage source = new DigitalItemStorage(() -> { }, 1);
            source.load(variant(index), 64);
            views.add(source.iterator().next());
        }
        return new MigrationFixture(List.copyOf(views), target);
    }

    private static List<HopperCase> benchmarkHoppers(Class<?> mergedClass, Method add)
            throws ReflectiveOperationException {
        List<HopperCase> cases = new ArrayList<>(HOPPER_COUNTS.length * HOPPER_VARIANTS.length);
        for (int variants : HOPPER_VARIANTS) {
            for (int hoppers : HOPPER_COUNTS) {
                cases.add(benchmarkHopperCase(mergedClass, add, hoppers, variants));
            }
        }
        return List.copyOf(cases);
    }

    private static HopperCase benchmarkHopperCase(
            Class<?> mergedClass,
            Method add,
            int hoppers,
            int variants
    ) throws ReflectiveOperationException {
        Storage<ItemVariant> source = newMerged(mergedClass);
        DigitalItemStorage destination = new DigitalItemStorage(() -> { }, 1);
        ItemVariant match = variant(variants - 1);
        for (int index = 0; index < variants; index++) {
            DigitalItemStorage single = new DigitalItemStorage(() -> { }, 1);
            single.load(variant(index), index == variants - 1 ? (long) hoppers * 64 : 64);
            add.invoke(source, new OpaqueStorage(single));
        }
        Predicate<ItemVariant> lastVariantOnly = match::equals;
        for (int pass = 0; pass < LATENCY_WARMUP_PASSES; pass++) {
            blackhole ^= runHopperBatch(source, destination, lastVariantOnly, hoppers);
        }
        long[] samples = new long[LATENCY_MEASURED_PASSES];
        for (int pass = 0; pass < samples.length; pass++) {
            long start = System.nanoTime();
            long moved = runHopperBatch(source, destination, lastVariantOnly, hoppers);
            samples[pass] = System.nanoTime() - start;
            blackhole ^= moved;
        }
        return new HopperCase(hoppers, variants, Latency.of(samples));
    }

    private static long runHopperBatch(
            Storage<ItemVariant> source,
            Storage<ItemVariant> destination,
            Predicate<ItemVariant> predicate,
            int hoppers
    ) {
        long moved = 0;
        try (Transaction transaction = Transaction.openOuter()) {
            for (int hopper = 0; hopper < hoppers; hopper++) {
                moved += HopperTransferOptimizer.moveFiltered(
                        source, destination, predicate, 64, transaction
                );
            }
        }
        return moved;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Result(
            int variants,
            double physicalNanosPerView,
            double digitalNanosPerView,
            double digitalRelativeCost,
            int recommendedDigitalViewDivisor,
            int recommendedMigrationScanBudget,
            Latency migrationLatency,
            List<HopperCase> hopperCases,
            boolean additionalRoundRobinRecommended
    ) {
        public String summary() {
            StringBuilder summary = new StringBuilder(String.format(
                    java.util.Locale.ROOT,
                    "Tom benchmark (%d views): physical %.1f ns/view, digital %.1f ns/view, digital cost %.3fx; suggested divisor %d, scan budget %d; migration256 %s; hopper p99/max ms [",
                    variants,
                    physicalNanosPerView,
                    digitalNanosPerView,
                    digitalRelativeCost,
                    recommendedDigitalViewDivisor,
                    recommendedMigrationScanBudget,
                    migrationLatency.compact()
            ));
            for (int index = 0; index < hopperCases.size(); index++) {
                if (index > 0) summary.append(", ");
                HopperCase hopperCase = hopperCases.get(index);
                summary.append(hopperCase.hoppers()).append('x').append(hopperCase.variants())
                        .append('=').append(String.format(
                                java.util.Locale.ROOT, "%.3f/%.3f",
                                hopperCase.latency().p99Millis(), hopperCase.latency().maxMillis()
                        ));
            }
            return summary.append("]; additional-round-robin=")
                    .append(additionalRoundRobinRecommended ? "recommended" : "not needed")
                    .toString();
        }
    }

    public record HopperCase(int hoppers, int variants, Latency latency) {
    }

    private record MigrationFixture(
            List<StorageView<ItemVariant>> views,
            DigitalItemStorage target
    ) {
        private long run() {
            long moved = 0;
            int scanned = 0;
            Iterator<StorageView<ItemVariant>> iterator = views.iterator();
            while (scanned < MIGRATION_VIEW_BUDGET && iterator.hasNext()) {
                StorageView<ItemVariant> view = iterator.next();
                scanned++;
                ItemVariant resource = view.getResource();
                moved += TomMigrationManager.benchmarkMoveView(view, target, resource);
            }
            return moved;
        }
    }

    public record Latency(double p50Millis, double p95Millis, double p99Millis, double maxMillis) {
        private static Latency of(long[] nanos) {
            Arrays.sort(nanos);
            return new Latency(
                    millis(nanos[percentileIndex(nanos.length, 0.50)]),
                    millis(nanos[percentileIndex(nanos.length, 0.95)]),
                    millis(nanos[percentileIndex(nanos.length, 0.99)]),
                    millis(nanos[nanos.length - 1])
            );
        }

        private String compact() {
            return String.format(
                    java.util.Locale.ROOT,
                    "p50/p95/p99/max %.3f/%.3f/%.3f/%.3f ms",
                    p50Millis, p95Millis, p99Millis, maxMillis
            );
        }

        private static int percentileIndex(int length, double percentile) {
            return Math.min(length - 1, Math.max(0, (int) Math.ceil(length * percentile) - 1));
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }

    private static final class OpaqueStorage implements Storage<ItemVariant> {
        private final Storage<ItemVariant> delegate;

        private OpaqueStorage(Storage<ItemVariant> delegate) {
            this.delegate = delegate;
        }

        @Override
        public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            return delegate.insert(resource, maxAmount, transaction);
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            return delegate.extract(resource, maxAmount, transaction);
        }

        @Override
        public Iterator<StorageView<ItemVariant>> iterator() {
            return delegate.iterator();
        }
    }
}
