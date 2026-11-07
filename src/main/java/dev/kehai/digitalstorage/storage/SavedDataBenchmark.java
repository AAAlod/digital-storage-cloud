package dev.kehai.digitalstorage.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;

/** Explicitly gated, isolated SavedData microbenchmark; never uses a player world. */
public final class SavedDataBenchmark {
    private SavedDataBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("digitalstorage.benchmark")) throw new IllegalStateException("Set digitalstorage.benchmark=true");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Runtime runtime = Runtime.getRuntime();
        System.out.printf("DSC_BENCH environment java=%s vm=%s max_heap_bytes=%d%n",
                System.getProperty("java.version"), System.getProperty("java.vm.name"), runtime.maxMemory());
        runScenario("normal", 200, 64, 64);
        runScenario("large_nbt", 20, 1024, 1024);
    }

    private static void runScenario(String label, int volumes, int variants, int payloadBytes) throws Exception {
        Path root = Files.createTempDirectory("dsc-saveddata-benchmark-");
        try {
            Files.createDirectories(root.resolve("data"));
            Runtime runtime = Runtime.getRuntime();
            long heapBefore = runtime.totalMemory() - runtime.freeMemory();
            long start = System.nanoTime();
            DigitalStorageData data = new DigitalStorageData();
            String[] payloads = new String[variants];
            var random = new java.util.Random(0x445343L);
            for (int j = 0; j < variants; j++) {
                byte[] bytes = new byte[payloadBytes * 3 / 4]; random.nextBytes(bytes);
                payloads[j] = java.util.Base64.getEncoder().encodeToString(bytes);
            }
            UUID owner = UUID.randomUUID();
            for (int i = 0; i < volumes; i++) {
                var volume = data.createVolume(owner, "benchmark-" + i, 0).orElseThrow();
                var tier = dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry.INSTANCE.tierAtLeastCapacity(variants);
                volume.record().setTier(tier.id());
                if (volume.record().variantCapacity() < variants) throw new IllegalStateException("benchmark tier capacity too small");
                for (int j = 0; j < variants; j++) {
                    var tag = new CompoundTag(); tag.putInt("variant", j); tag.putString("payload", payloads[j]);
                    volume.record().storage().load(ItemKey.of(Items.PAPER, tag), 1);
                }
                if (volume.record().storage().variantCount() != variants) throw new IllegalStateException("benchmark variant count mismatch");
            }
            long build = System.nanoTime() - start;
            long heapAfterBuild = runtime.totalMemory() - runtime.freeMemory();
            var file = root.resolve("data/digitalstorage.dat"); data.setDirty();
            start = System.nanoTime(); data.save(file.toFile()); long save = System.nanoTime() - start;
            if (data.isDirty() || !Files.isRegularFile(file)) throw new IllegalStateException("benchmark save failed");
            long fileBytes = Files.size(file);
            start = System.nanoTime(); var state = DigitalStorageState.openForTest(root); long load = System.nanoTime() - start;
            if (state.volumeCount() != volumes || state.contentStats(false).variantCount() != (long) volumes * variants)
                throw new IllegalStateException("benchmark reopen mismatch");
            start = System.nanoTime(); state.flushNow(); long flush = System.nanoTime() - start;
            start = System.nanoTime(); state.closeForTest(); long stop = System.nanoTime() - start;
            long heapAfter = runtime.totalMemory() - runtime.freeMemory();
            System.out.printf("DSC_BENCH scenario=%s volumes=%d variants_per_volume=%d payload_chars=%d build_ms=%.3f save_ms=%.3f load_ms=%.3f flush_ms=%.3f stop_save_ms=%.3f file_bytes=%d heap_before=%d heap_after_build=%d heap_after=%d%n",
                    label, volumes, variants, payloadBytes, build / 1e6, save / 1e6, load / 1e6, flush / 1e6, stop / 1e6,
                    fileBytes, heapBefore, heapAfterBuild, heapAfter);
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
