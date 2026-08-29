package dev.kehai.digitalstorage.config;

import dev.kehai.digitalstorage.DigitalStorageMod;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class DigitalStorageConfig {
    private static final int[] DEFAULT_FAILURE_COOLDOWNS = {20, 40, 100, 200, 400};
    public static final int HARD_MAX_VARIANT_NBT_BYTES = 1_048_576;
    public static final int HARD_MAX_VOLUME_VARIANT_NBT_BYTES = 1_073_741_824;
    public static final int HARD_MAX_VOLUME_NBT_WARNING_BYTES = 1_073_741_824;
    private static volatile DigitalStorageConfig instance = defaults();

    public boolean optimizeTomsHopper = true;
    public int normalHopperBatchSize = 16;
    public int advancedHopperBatchSize = 64;
    public int hopperSuccessCooldown = 20;
    public int[] hopperFailureCooldowns = DEFAULT_FAILURE_COOLDOWNS.clone();
    public boolean staggerConnectorScans = true;
    public int maxVariantNbtBytes = 65_536;
    public int maxVolumeVariantNbtBytes = 67_108_864;
    public boolean allowUnstackableItems = true;
    public String itemFilterMode = "blacklist";
    public List<String> itemFilterItems = new ArrayList<>();
    public List<String> itemFilterTags = new ArrayList<>();
    public boolean logRejectedVariants = false;
    public int migrationViewsScannedPerTick = 128;
    public int digitalViewScoreDivisor = 1_024;
    public int defaultVolumesPerPlayer = 3;
    public int persistenceFlushIntervalTicks = 400;
    public int persistenceSnapshotsPerTick = 1;
    public int persistenceVariantsPerTick = 64;
    public int volumeNbtWarningBytes = 16_777_216;

    DigitalStorageConfig() {
    }

    public static DigitalStorageConfig get() {
        return instance;
    }

    public static LoadResult load() {
        return load(DigitalStorageConfigLoader.Mode.STARTUP);
    }

    public static LoadResult reload() {
        return load(DigitalStorageConfigLoader.Mode.RELOAD);
    }

    private static LoadResult load(DigitalStorageConfigLoader.Mode mode) {
        Path configDirectory = FabricLoader.getInstance().getConfigDir();
        DigitalStorageConfigLoader.Result result = DigitalStorageConfigLoader.load(configDirectory, mode);
        if (result.config() != null) {
            instance = result.config();
            logLoadedConfig(result.config());
        }
        return new LoadResult(result.success(), result.message());
    }

    private static void logLoadedConfig(DigitalStorageConfig loaded) {
        DigitalStorageMod.LOGGER.info(
                "Digital storage config: hopper enabled={}, normal batch={}, advanced batch={}, success cooldown={}, failure cooldowns={}, "
                        + "stagger scans={}, allow unstackables={}, filter={}, filter items={}, filter tags={}, "
                        + "max variant NBT={} bytes, volume variant NBT cap={} bytes, volumes per player={}, "
                        + "migration views/tick={}, digital view divisor={}, persistence flush={} ticks, snapshots/tick={}, variants/tick={}, "
                        + "volume NBT warning={} bytes",
                loaded.optimizeTomsHopper,
                loaded.normalHopperBatchSize,
                loaded.advancedHopperBatchSize,
                loaded.hopperSuccessCooldown,
                Arrays.toString(loaded.hopperFailureCooldowns),
                loaded.staggerConnectorScans,
                loaded.allowUnstackableItems,
                loaded.itemFilterMode,
                loaded.itemFilterItems.size(),
                loaded.itemFilterTags.size(),
                loaded.maxVariantNbtBytes,
                loaded.maxVolumeVariantNbtBytes,
                loaded.defaultVolumesPerPlayer == 0 ? "unlimited" : loaded.defaultVolumesPerPlayer,
                loaded.migrationViewsScannedPerTick,
                loaded.digitalViewScoreDivisor,
                loaded.persistenceFlushIntervalTicks,
                loaded.persistenceSnapshotsPerTick,
                loaded.persistenceVariantsPerTick,
                loaded.volumeNbtWarningBytes == 0 ? "disabled" : loaded.volumeNbtWarningBytes
        );
    }

    public int failureCooldown(int consecutiveFailures) {
        int index = Math.max(0, Math.min(consecutiveFailures - 1, hopperFailureCooldowns.length - 1));
        return hopperFailureCooldowns[index];
    }

    void normalize() {
        normalHopperBatchSize = clamp(normalHopperBatchSize, 1, 64);
        advancedHopperBatchSize = clamp(advancedHopperBatchSize, 1, 1_024);
        hopperSuccessCooldown = clamp(hopperSuccessCooldown, 0, 1200);
        maxVariantNbtBytes = clamp(maxVariantNbtBytes, 1_024, HARD_MAX_VARIANT_NBT_BYTES);
        maxVolumeVariantNbtBytes = clamp(maxVolumeVariantNbtBytes, 1_048_576, HARD_MAX_VOLUME_VARIANT_NBT_BYTES);
        defaultVolumesPerPlayer = clamp(defaultVolumesPerPlayer, 0, 64);
        migrationViewsScannedPerTick = clamp(migrationViewsScannedPerTick, 1, 65_536);
        digitalViewScoreDivisor = clamp(digitalViewScoreDivisor, 256, 8_192);
        persistenceFlushIntervalTicks = clamp(persistenceFlushIntervalTicks, 20, 12_000);
        persistenceSnapshotsPerTick = clamp(persistenceSnapshotsPerTick, 1, 64);
        persistenceVariantsPerTick = clamp(persistenceVariantsPerTick, 1, 65_536);
        if (volumeNbtWarningBytes != 0) {
            volumeNbtWarningBytes = clamp(volumeNbtWarningBytes, 1_048_576, HARD_MAX_VOLUME_NBT_WARNING_BYTES);
        }
        itemFilterMode = "whitelist".equalsIgnoreCase(itemFilterMode) ? "whitelist" : "blacklist";
        itemFilterItems = normalizedStrings(itemFilterItems, 4096);
        itemFilterTags = normalizedStrings(itemFilterTags, 4096);

        if (hopperFailureCooldowns == null || hopperFailureCooldowns.length == 0) {
            hopperFailureCooldowns = DEFAULT_FAILURE_COOLDOWNS.clone();
        } else {
            hopperFailureCooldowns = Arrays.copyOf(hopperFailureCooldowns, Math.min(16, hopperFailureCooldowns.length));
            for (int index = 0; index < hopperFailureCooldowns.length; index++) {
                hopperFailureCooldowns[index] = clamp(hopperFailureCooldowns[index], 1, 1200);
            }
        }
    }

    private static List<String> normalizedStrings(List<String> input, int maximumSize) {
        if (input == null || input.isEmpty()) {
            return new ArrayList<>();
        }
        return input.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(maximumSize)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static DigitalStorageConfig defaults() {
        return new DigitalStorageConfig();
    }

    public static void runSelfTest() {
        DigitalStorageConfigLoader.runSelfTest();
    }

    public record LoadResult(boolean success, String message) {
    }
}
