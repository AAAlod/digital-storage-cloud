package dev.kehai.digitalstorage.forge;

import com.tom.storagemod.tile.BasicInventoryHopperBlockEntity;
import com.tom.storagemod.util.MultiItemHandler;
import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.nio.file.Files;
import java.lang.management.ManagementFactory;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/** Bounded, explicit microbenchmark. Fixtures never attach to a level or user volume. */
public final class ForgePerformanceBenchmark {
    private static final int WARMUP = 128, SAMPLES = 64, VARIANTS = 512;
    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static volatile long blackhole;
    private ForgePerformanceBenchmark() { }

    public static String run() {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        var physical = physical(VARIANTS);
        var physicalNetwork = new MultiItemHandler(); physicalNetwork.add(LazyOptional.of(() -> physical)); physicalNetwork.refresh();
        var ledger = new VolumeLedger(() -> { }, VARIANTS);
        for (int slot = 0; slot < VARIANTS; slot++) ledger.load(ItemKey.of(variant(slot)), 64);
        var digital = new MultiItemHandler();
        digital.add(LazyOptional.of(() -> ForgeDigitalItemStorage.of(ledger))); digital.refresh();
        expect(scan(physicalNetwork) == VARIANTS * 64L && scan(digital) == VARIANTS * 64L, "Scan fixture differs");
        var physicalLatency = measure(() -> () -> blackhole = scan(physicalNetwork), deadline);
        var digitalLatency = measure(() -> () -> blackhole = scan(digital), deadline);
        report("physical scan", physicalNetwork.getSlots(), physicalLatency);
        report("digital scan", digital.getSlots(), digitalLatency);
        var migrationLatency = migration(deadline);
        int cases = 0;
        long worst = 0;
        for (int variants : new int[]{64, 256, 1024}) {
            var source = physical(variants);
            source.setStackInSlot(variants - 1, new ItemStack(Items.STONE, 64));
            var network = new MultiItemHandler(); network.add(LazyOptional.of(() -> source)); network.refresh();
            for (int count : new int[]{1, 10, 50, 100}) {
                var hoppers = new ArrayList<BenchHopper>();
                for (int i = 0; i < count; i++) hoppers.add(new BenchHopper(network));
                try {
                    var latency = measure(() -> () -> hoppers.forEach(BenchHopper::coldAttempt), deadline);
                    expect(source.getStackInSlot(variants - 1).getCount() == 64
                            && hoppers.stream().allMatch(h -> !((ForgeHopperState) (Object) h).digitalstorage$transferState().blocked()),
                            "Rejected benchmark attempt changed ownership");
                    report("cold filtered rejection hoppers=" + count + " slots=" + variants, variants * count, latency);
                    worst = Math.max(worst, latency.maximum);
                    cases++;
                } finally { hoppers.forEach(BenchHopper::setRemoved); }
            }
        }
        String summary = "Forge benchmark passed: warmupMin=" + WARMUP + ", warmupMinMillis=250, samples=" + SAMPLES
                + ", variants=" + VARIANTS + ", hopperCases=" + cases
                + String.format(Locale.ROOT, "; physical512 meanMs=%.4f, digital1024 meanMs=%.4f, migration256 p99/maxMs=%.4f, worstHopper p99/maxMs=%.4f",
                        physicalLatency.mean / 1e6, digitalLatency.mean / 1e6, migrationLatency.maximum / 1e6, worst / 1e6)
                + "; isolated Tom scans, real migration256, actual cold filtered rejection updates; microbenchmark, not TPS";
        DigitalStorage.LOGGER.info(summary); return summary;
    }
    private static ItemStack variant(int index) {
        var stack = new ItemStack(Items.PAPER, 64);
        stack.getOrCreateTag().putInt("DSCBenchmark", index); return stack;
    }
    private static ItemStackHandler physical(int slots) {
        var source = new ItemStackHandler(slots);
        for (int slot = 0; slot < slots; slot++) source.setStackInSlot(slot, variant(slot));
        return source;
    }
    private static long scan(IItemHandler source) {
        long total = 0;
        for (int slot = 0; slot < source.getSlots(); slot++) total += source.getStackInSlot(slot).getCount();
        return total;
    }
    private static Latency migration(long deadline) {
        java.nio.file.Path root = null;
        try {
            root = Files.createTempDirectory("digitalstorage-forge-benchmark-");
            var recovery = new ForgeTransferRecovery(root);
            UUID owner = UUID.randomUUID(), volume = UUID.randomUUID();
            var latency = measure(() -> {
                var source = physical(256);
                var target = new VolumeLedger(volume, () -> { }, 256);
                var executor = new ForgeInventoryTransferExecutor(owner, volume, recovery);
                return () -> {
                    for (int slot = 0; slot < 256; slot++) {
                        var key = ItemKey.of(source.getStackInSlot(slot));
                        var result = executor.move(new ForgeInventoryEndpoint.View(source, slot), target, key, 64);
                        expect(result.moved() == 64 && result.stopDetail().isEmpty(), "Migration benchmark did not settle");
                    }
                    expect(target.totalItemCount() == 16384 && recovery.available() && recovery.pendingCount() == 0,
                            "Migration benchmark lost ownership");
                    blackhole = target.totalItemCount();
                };
            }, deadline);
            report("migration256", 256, latency);
            return latency;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Benchmark fixture storage failed", failure); }
        finally {
            if (root != null) try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (java.io.IOException failure) { throw new IllegalStateException("Benchmark cleanup failed", failure); }
        }
    }
    private static Latency measure(java.util.function.Supplier<Runnable> prepare, long deadline) {
        long[] samples = new long[SAMPLES];
        long cpu = 0, allocated = 0;
        int warmed = 0;
        long warmUntil = System.nanoTime() + 250_000_000L;
        do {
            expect(System.nanoTime() < deadline, "Benchmark warmup exceeded its 30 second budget");
            prepare.get().run(); warmed++;
        } while (warmed < WARMUP || System.nanoTime() < warmUntil);
        for (int pass = 0; pass < SAMPLES; pass++) {
            expect(System.nanoTime() < deadline, "Benchmark exceeded its 30 second budget; partial cases are not a pass");
            Runnable operation = prepare.get();
            long bytesBefore = allocated(), cpuBefore = cpu(), start = System.nanoTime();
            operation.run();
            long elapsed = System.nanoTime() - start, cpuAfter = cpu(), bytesAfter = allocated();
            samples[pass] = elapsed;
            cpu = cpuBefore < 0 || cpuAfter < 0 || cpu < 0 ? -1 : cpu + cpuAfter - cpuBefore;
            allocated = bytesBefore < 0 || bytesAfter < 0 || allocated < 0 ? -1 : allocated + bytesAfter - bytesBefore;
        }
        Arrays.sort(samples);
        return new Latency(Arrays.stream(samples).average().orElseThrow(), samples[60], samples[63], cpu, allocated, warmed);
    }
    private static long cpu() {
        var bean = THREADS;
        return bean.isCurrentThreadCpuTimeSupported() && bean.isThreadCpuTimeEnabled() ? bean.getCurrentThreadCpuTime() : -1;
    }
    private static long allocated() {
        var bean = THREADS;
        return bean instanceof com.sun.management.ThreadMXBean allocations && allocations.isThreadAllocatedMemorySupported()
                && allocations.isThreadAllocatedMemoryEnabled() ? allocations.getThreadAllocatedBytes(Thread.currentThread().getId()) : -1;
    }
    private static void report(String name, int operations, Latency latency) {
        DigitalStorage.LOGGER.info(String.format(Locale.ROOT,
                "Forge benchmark case %s: operations=%d, meanMs=%.4f, p95Ms=%.4f, p99/maxMs=%.4f, cpuNanos=%d, allocatedBytes=%d, warmupPasses=%d",
                name, operations, latency.mean / 1e6, latency.p95 / 1e6, latency.maximum / 1e6, latency.cpu, latency.allocated, latency.warmed));
    }
    private record Latency(double mean, long p95, long maximum, long cpu, long allocated, int warmed) { }
    private static final class BenchHopper extends BasicInventoryHopperBlockEntity {
        private final ItemStack match = new ItemStack(Items.STONE);
        BenchHopper(IItemHandler source) {
            super(BlockPos.ZERO, com.tom.storagemod.Content.invHopperBasic.get().defaultBlockState());
            var destination = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            };
            top = LazyOptional.of(() -> source); bottom = LazyOptional.of(() -> destination); topNet = true;
        }
        @Override public void setChanged() { } // Unattached fixture must never dirty a user chunk.
        void coldAttempt() { setFilter(match); super.update(); }
    }
    private static void expect(boolean condition, String detail) { if (!condition) throw new IllegalStateException(detail); }
}
