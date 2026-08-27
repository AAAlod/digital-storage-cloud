package dev.kehai.digitalstorage.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kehai.digitalstorage.DigitalStorageMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

public final class DigitalStorageConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int[] DEFAULT_FAILURE_COOLDOWNS = {10, 20, 40, 100, 200};
    private static final DateTimeFormatter BROKEN_FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    public static final int HARD_MAX_VARIANT_NBT_BYTES = 1_048_576;
    public static final int HARD_MAX_VOLUME_VARIANT_NBT_BYTES = 1_073_741_824;
    public static final int HARD_MAX_VOLUME_NBT_WARNING_BYTES = 1_073_741_824;
    private static volatile DigitalStorageConfig instance = defaults();

    public boolean optimizeTomsHopper = true;
    public int normalHopperBatchSize = 16;
    public int advancedHopperBatchSize = 64;
    public int hopperSuccessCooldown = 10;
    public int[] hopperFailureCooldowns = DEFAULT_FAILURE_COOLDOWNS.clone();
    public boolean staggerConnectorScans = true;
    public int maxVariantNbtBytes = 65_536;
    /** Hard aggregate limit for serialized item variants held by one volume. */
    public int maxVolumeVariantNbtBytes = 67_108_864;
    /** Whether volume owners may opt in to storing max-stack-size-one items. */
    public boolean allowUnstackableItems = true;
    public String itemFilterMode = "blacklist";
    public List<String> itemFilterItems = new ArrayList<>();
    public List<String> itemFilterTags = new ArrayList<>();
    public boolean logRejectedVariants = false;
    /** Maximum physical StorageViews inspected by one migration job in one server tick. */
    public int migrationViewsScannedPerTick = 256;
    /** Digital views per one health point; calibrate with /digitalstorage benchmark. */
    public int digitalViewScoreDivisor = 1_024;
    /** Zero means unlimited. */
    public int defaultVolumesPerPlayer = 3;
    public int persistenceFlushIntervalTicks = 200;
    /** Maximum account/volume snapshots captured on the server thread per persistence tick. */
    public int persistenceSnapshotsPerTick = 1;
    /** Maximum item variants copied into persistence snapshots on one server tick. */
    public int persistenceVariantsPerTick = 128;
    /** Zero disables the soft warning. This does not reject or delete data. */
    public int volumeNbtWarningBytes = 16_777_216;

    private DigitalStorageConfig() {
    }

    public static DigitalStorageConfig get() {
        return instance;
    }

    public static void load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("digitalstorage.json");
        DigitalStorageConfig loaded = defaults();
        JsonObject sourceObject = null;
        boolean invalidConfig = false;

        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    throw new IllegalArgumentException("Configuration root must be a JSON object");
                }
                sourceObject = root.getAsJsonObject();
                DigitalStorageConfig parsed = GSON.fromJson(sourceObject, DigitalStorageConfig.class);
                if (parsed != null) {
                    loaded = parsed;
                }
            } catch (IOException | RuntimeException exception) {
                DigitalStorageMod.LOGGER.warn("Could not read {}, using defaults", path, exception);
                invalidConfig = true;
            }
        }

        if (sourceObject != null
                && !sourceObject.has("allowUnstackableItems")
                && sourceObject.has("rejectUnstackableItems")) {
            loaded.allowUnstackableItems = migratedAllowUnstackableItems(sourceObject, loaded.allowUnstackableItems);
            DigitalStorageMod.LOGGER.info(
                    "Migrated legacy rejectUnstackableItems to allowUnstackableItems={}",
                    loaded.allowUnstackableItems
            );
        }

        loaded.normalize();
        instance = loaded;

        if (invalidConfig && !backupBrokenConfig(path)) {
            DigitalStorageMod.LOGGER.error(
                    "Refusing to overwrite invalid config {} because it could not be backed up",
                    path
            );
            logLoadedConfig(loaded);
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(loaded, writer);
            }
        } catch (IOException exception) {
            DigitalStorageMod.LOGGER.warn("Could not write {}", path, exception);
        }

        logLoadedConfig(loaded);
    }

    private static boolean backupBrokenConfig(Path path) {
        String timestamp = BROKEN_FILE_TIMESTAMP.format(LocalDateTime.now());
        Path backup = path.resolveSibling(path.getFileName() + ".broken-" + timestamp);
        int suffix = 1;
        while (Files.exists(backup)) {
            backup = path.resolveSibling(path.getFileName() + ".broken-" + timestamp + "-" + suffix++);
        }

        try {
            Files.move(path, backup, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailed) {
            try {
                Files.move(path, backup);
            } catch (IOException moveFailed) {
                moveFailed.addSuppressed(atomicMoveFailed);
                DigitalStorageMod.LOGGER.error("Could not back up invalid config {}", path, moveFailed);
                return false;
            }
        }
        DigitalStorageMod.LOGGER.warn("Backed up invalid Digital Storage config to {}", backup);
        return true;
    }

    private static void logLoadedConfig(DigitalStorageConfig loaded) {
        DigitalStorageMod.LOGGER.info(
                "Digital storage config: hopper enabled={}, normal batch={}, advanced batch={}, success cooldown={}, failure cooldowns={}, "
                        + "stagger scans={}, allow unstackables={}, filter={}, "
                        + "filter items={}, filter tags={}, max variant NBT={} bytes, volume variant NBT cap={} bytes, "
                        + "volumes per player={}, migration views/tick={}, digital view divisor={}, persistence flush={} ticks, snapshots/tick={}, variants/tick={}, "
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

    private void normalize() {
        normalHopperBatchSize = clamp(normalHopperBatchSize, 1, 64);
        advancedHopperBatchSize = clamp(advancedHopperBatchSize, 1, 1_024);
        hopperSuccessCooldown = clamp(hopperSuccessCooldown, 0, 1200);
        maxVariantNbtBytes = clamp(maxVariantNbtBytes, 1_024, HARD_MAX_VARIANT_NBT_BYTES);
        maxVolumeVariantNbtBytes = clamp(
                maxVolumeVariantNbtBytes,
                1_048_576,
                HARD_MAX_VOLUME_VARIANT_NBT_BYTES
        );
        defaultVolumesPerPlayer = clamp(defaultVolumesPerPlayer, 0, 64);
        migrationViewsScannedPerTick = clamp(migrationViewsScannedPerTick, 1, 65_536);
        digitalViewScoreDivisor = clamp(digitalViewScoreDivisor, 256, 8_192);
        persistenceFlushIntervalTicks = clamp(persistenceFlushIntervalTicks, 20, 12_000);
        persistenceSnapshotsPerTick = clamp(persistenceSnapshotsPerTick, 1, 64);
        persistenceVariantsPerTick = clamp(persistenceVariantsPerTick, 1, 65_536);
        if (volumeNbtWarningBytes != 0) {
            volumeNbtWarningBytes = clamp(
                    volumeNbtWarningBytes,
                    1_048_576,
                    HARD_MAX_VOLUME_NBT_WARNING_BYTES
            );
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

    static boolean migratedAllowUnstackableItems(JsonObject source, boolean fallback) {
        JsonElement legacy = source.get("rejectUnstackableItems");
        if (legacy == null || !legacy.isJsonPrimitive() || !legacy.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return !legacy.getAsBoolean();
    }

    public static void runSelfTest() {
        JsonObject legacyReject = new JsonObject();
        legacyReject.addProperty("rejectUnstackableItems", true);
        expect(!migratedAllowUnstackableItems(legacyReject, true),
                "legacy reject=true did not migrate to allow=false");

        JsonObject legacyAllow = new JsonObject();
        legacyAllow.addProperty("rejectUnstackableItems", false);
        expect(migratedAllowUnstackableItems(legacyAllow, false),
                "legacy reject=false did not migrate to allow=true");

        JsonObject missingLegacy = new JsonObject();
        expect(migratedAllowUnstackableItems(missingLegacy, true),
                "missing legacy setting did not preserve the new default");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static DigitalStorageConfig defaults() {
        return new DigitalStorageConfig();
    }
}
