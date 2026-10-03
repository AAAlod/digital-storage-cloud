package dev.kehai.digitalstorage.hopper;

import dev.kehai.digitalstorage.config.DigitalStorageConfig;

/** Shared batch, backoff and scan timing policy; device detection belongs to each loader. */
public final class HopperPolicy {
    private HopperPolicy() {
    }

    public static long batchLimit(boolean advanced, DigitalStorageConfig config) {
        return advanced ? config.advancedHopperBatchSize : config.normalHopperBatchSize;
    }

    public static int failureCooldown(int failures, int[] cooldowns) {
        int index = Math.max(0, Math.min(failures - 1, cooldowns.length - 1));
        return cooldowns[index];
    }

    public static long staggeredScanTime(long tick, int positionHash) {
        return tick + Math.floorMod(positionHash, 20);
    }

    public static void runSelfTest() {
        int[] schedule = {20, 40, 100, 200, 400};
        if (failureCooldown(0, schedule) != 20 || failureCooldown(1, schedule) != 20
                || failureCooldown(3, schedule) != 100 || failureCooldown(100, schedule) != 400) {
            throw new IllegalStateException("Shared hopper backoff regression failed");
        }
        boolean[] phases = new boolean[20];
        for (int hash = -20; hash < 0; hash++) {
            long shifted = staggeredScanTime(100, hash);
            if (shifted < 100 || shifted >= 120) throw new IllegalStateException("Shared hopper scan phase outside period");
            phases[(int) shifted - 100] = true;
        }
        for (boolean phase : phases) if (!phase) throw new IllegalStateException("Shared hopper scan phases collapsed");
        DigitalStorageConfig config = DigitalStorageConfig.get();
        if (batchLimit(false, config) != config.normalHopperBatchSize || batchLimit(true, config) != config.advancedHopperBatchSize) {
            throw new IllegalStateException("Shared hopper device batch selection failed");
        }
    }
}
