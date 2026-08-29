package dev.kehai.digitalstorage.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kehai.digitalstorage.DigitalStorageMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

final class DigitalStorageConfigLoader {
    private static final String TOML_NAME = "digitalstorage.toml";
    private static final String LEGACY_JSON_NAME = "digitalstorage.json";
    private static final Gson GSON = new GsonBuilder().create();
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DigitalStorageConfigLoader() {
    }

    static Result load(Path configDirectory, Mode mode) {
        Path tomlPath = configDirectory.resolve(TOML_NAME);
        Path legacyJsonPath = configDirectory.resolve(LEGACY_JSON_NAME);
        if (Files.isRegularFile(tomlPath)) {
            if (Files.isRegularFile(legacyJsonPath)) {
                DigitalStorageMod.LOGGER.warn("Both {} and {} exist; the TOML configuration takes precedence",
                        tomlPath, legacyJsonPath);
            }
            return loadToml(tomlPath, mode);
        }
        if (Files.isRegularFile(legacyJsonPath)) {
            return migrateJson(legacyJsonPath, tomlPath, mode);
        }

        DigitalStorageConfig defaults = DigitalStorageConfig.defaults();
        try {
            writeAtomic(tomlPath, DigitalStorageConfigSchema.createDocument(defaults));
            DigitalStorageMod.LOGGER.info("Created Digital Storage configuration at {}", tomlPath);
            return new Result(true, defaults, "Created default TOML configuration");
        } catch (IOException | RuntimeException exception) {
            DigitalStorageMod.LOGGER.error("Could not create {}", tomlPath, exception);
            return new Result(false, mode == Mode.STARTUP ? defaults : null,
                    "Could not create TOML configuration: " + conciseMessage(exception));
        }
    }

    private static Result loadToml(Path tomlPath, Mode mode) {
        CommentedConfig document;
        try {
            document = parseToml(tomlPath);
        } catch (IOException | RuntimeException exception) {
            DigitalStorageMod.LOGGER.error("Could not parse {}", tomlPath, exception);
            if (mode == Mode.RELOAD) {
                return new Result(false, null,
                        "TOML parse failed; the previous configuration is still active: " + conciseMessage(exception));
            }
            return recoverBrokenFile(tomlPath, exception);
        }

        DigitalStorageConfigSchema.ReadResult read;
        try {
            read = DigitalStorageConfigSchema.read(document);
        } catch (RuntimeException exception) {
            DigitalStorageMod.LOGGER.error("Could not interpret {}", tomlPath, exception);
            if (mode == Mode.RELOAD) {
                return new Result(false, null,
                        "TOML schema is invalid; the previous configuration is still active: " + conciseMessage(exception));
            }
            return recoverBrokenFile(tomlPath, exception);
        }
        read.warnings().forEach(warning -> DigitalStorageMod.LOGGER.warn("{} in {}", warning, tomlPath));
        if (read.futureVersion()) {
            DigitalStorageMod.LOGGER.warn(
                    "{} uses configVersion newer than {}; recognized values were loaded but the file was left unchanged",
                    tomlPath, DigitalStorageConfigSchema.CURRENT_VERSION);
            return new Result(true, read.config(), "Loaded newer TOML schema in read-only compatibility mode");
        }
        if (read.changed()) {
            try {
                writeAtomic(tomlPath, document);
                DigitalStorageMod.LOGGER.info("Updated missing or invalid values in {}", tomlPath);
            } catch (IOException | RuntimeException exception) {
                DigitalStorageMod.LOGGER.error("Could not safely update {}", tomlPath, exception);
                return new Result(false, mode == Mode.STARTUP ? read.config() : null,
                        "Could not safely update TOML configuration: " + conciseMessage(exception));
            }
        }
        return new Result(true, read.config(), read.changed()
                ? "Reloaded TOML configuration after repairing invalid values"
                : "Reloaded TOML configuration");
    }

    private static Result migrateJson(Path jsonPath, Path tomlPath, Mode mode) {
        DigitalStorageConfig migrated;
        try {
            migrated = parseLegacyJson(jsonPath);
        } catch (IOException | RuntimeException exception) {
            DigitalStorageMod.LOGGER.error("Could not parse legacy configuration {}", jsonPath, exception);
            if (mode == Mode.RELOAD) {
                return new Result(false, null,
                        "Legacy JSON parse failed; the previous configuration is still active: " + conciseMessage(exception));
            }
            Path backup = backupPath(jsonPath, "broken");
            if (!moveWithoutReplacement(jsonPath, backup)) {
                return new Result(false, DigitalStorageConfig.defaults(),
                        "Invalid legacy JSON could not be backed up; it was not overwritten");
            }
            DigitalStorageConfig defaults = DigitalStorageConfig.defaults();
            try {
                writeAtomic(tomlPath, DigitalStorageConfigSchema.createDocument(defaults));
                return new Result(true, defaults, "Recovered from invalid legacy JSON with default TOML configuration");
            } catch (IOException | RuntimeException writeFailure) {
                DigitalStorageMod.LOGGER.error("Could not create {} after backing up invalid JSON", tomlPath, writeFailure);
                return new Result(false, defaults,
                        "Invalid legacy JSON was backed up, but default TOML creation failed: " + conciseMessage(writeFailure));
            }
        }

        try {
            writeAtomic(tomlPath, DigitalStorageConfigSchema.createDocument(migrated));
        } catch (IOException | RuntimeException exception) {
            DigitalStorageMod.LOGGER.error("Could not migrate {} to {}", jsonPath, tomlPath, exception);
            return new Result(false, mode == Mode.STARTUP ? migrated : null,
                    "Could not safely write migrated TOML configuration: " + conciseMessage(exception));
        }

        Path archive = backupPath(jsonPath, "migrated");
        if (!moveWithoutReplacement(jsonPath, archive)) {
            DigitalStorageMod.LOGGER.warn(
                    "Migrated {} to {}, but could not archive the legacy JSON; TOML will take precedence",
                    jsonPath, tomlPath);
            return new Result(true, migrated, "Migrated JSON to TOML; legacy JSON archive failed");
        }
        DigitalStorageMod.LOGGER.info("Migrated {} to {}; archived the original as {}", jsonPath, tomlPath, archive);
        return new Result(true, migrated, "Migrated legacy JSON configuration to TOML");
    }

    private static Result recoverBrokenFile(Path tomlPath, Exception parseFailure) {
        Path backup = backupPath(tomlPath, "broken");
        if (!moveWithoutReplacement(tomlPath, backup)) {
            return new Result(false, DigitalStorageConfig.defaults(),
                    "Invalid TOML could not be backed up; it was not overwritten: " + conciseMessage(parseFailure));
        }
        DigitalStorageConfig defaults = DigitalStorageConfig.defaults();
        try {
            writeAtomic(tomlPath, DigitalStorageConfigSchema.createDocument(defaults));
            DigitalStorageMod.LOGGER.warn("Backed up invalid Digital Storage configuration to {} and restored defaults", backup);
            return new Result(true, defaults, "Backed up invalid TOML and restored defaults");
        } catch (IOException | RuntimeException exception) {
            DigitalStorageMod.LOGGER.error("Could not create default {} after backing up the invalid file", tomlPath, exception);
            return new Result(false, defaults,
                    "Invalid TOML was backed up, but default creation failed: " + conciseMessage(exception));
        }
    }

    private static DigitalStorageConfig parseLegacyJson(Path path) throws IOException {
        JsonObject source;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IllegalArgumentException("Configuration root must be a JSON object");
            }
            source = root.getAsJsonObject();
        }
        DigitalStorageConfig parsed = GSON.fromJson(source, DigitalStorageConfig.class);
        DigitalStorageConfig loaded = parsed == null ? DigitalStorageConfig.defaults() : parsed;
        if (!source.has("allowUnstackableItems") && source.has("rejectUnstackableItems")) {
            JsonElement legacy = source.get("rejectUnstackableItems");
            if (legacy.isJsonPrimitive() && legacy.getAsJsonPrimitive().isBoolean()) {
                loaded.allowUnstackableItems = !legacy.getAsBoolean();
            }
        }
        loaded.normalize();
        return loaded;
    }

    private static CommentedConfig parseToml(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            CommentedConfig document = TomlFormat.newConfig(LinkedHashMap::new);
            new TomlParser().parse(reader, document, ParsingMode.REPLACE);
            return document;
        }
    }

    private static void writeAtomic(Path target, CommentedConfig document) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                TomlWriter tomlWriter = new TomlWriter();
                tomlWriter.setIndent("    ");
                tomlWriter.write(document, writer);
            }
            parseToml(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Path backupPath(Path source, String reason) {
        String timestamp = BACKUP_TIMESTAMP.format(LocalDateTime.now());
        Path candidate = source.resolveSibling(source.getFileName() + "." + reason + "-" + timestamp);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = source.resolveSibling(source.getFileName() + "." + reason + "-" + timestamp + "-" + suffix++);
        }
        return candidate;
    }

    private static boolean moveWithoutReplacement(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException atomicMoveFailed) {
            try {
                Files.move(source, target);
                return true;
            } catch (IOException moveFailed) {
                moveFailed.addSuppressed(atomicMoveFailed);
                DigitalStorageMod.LOGGER.error("Could not move {} to {}", source, target, moveFailed);
                return false;
            }
        }
    }

    private static String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    static void runSelfTest() {
        Path root = null;
        try {
            root = Files.createTempDirectory("digitalstorage-config-selftest-");
            verifyInitialCreationAndNoOp(root.resolve("create"));
            verifyLegacyMigration(root.resolve("migration"));
            verifyTomlPrecedence(root.resolve("precedence"));
            verifySingleFieldFallback(root.resolve("fallback"));
            verifyMissingFieldRepair(root.resolve("missing"));
            verifyNormalization(root.resolve("normalization"));
            verifyBrokenStartupRecovery(root.resolve("broken-startup"));
            verifyBrokenReloadRejection(root.resolve("broken-reload"));
            verifyFutureSchemaIsUntouched(root.resolve("future"));
        } catch (IOException exception) {
            throw new IllegalStateException("Digital Storage configuration self-test failed", exception);
        } finally {
            deleteTemporaryTree(root);
        }
    }

    private static void verifyInitialCreationAndNoOp(Path directory) throws IOException {
        Result created = load(directory, Mode.STARTUP);
        expect(created.success(), "default TOML creation failed");
        expect(created.config().hopperSuccessCooldown == 20
                        && java.util.Arrays.equals(created.config().hopperFailureCooldowns, new int[]{20, 40, 100, 200, 400})
                        && created.config().migrationViewsScannedPerTick == 128
                        && created.config().persistenceFlushIntervalTicks == 400
                        && created.config().persistenceSnapshotsPerTick == 1
                        && created.config().persistenceVariantsPerTick == 64,
                "TPS-first canonical defaults drifted");
        Path toml = directory.resolve(TOML_NAME);
        String generated = Files.readString(toml);
        expect(generated.contains("[hopper]") && generated.contains("[storage.filter]")
                        && generated.contains("[persistence]") && generated.contains("#"),
                "generated TOML lacks expected sections or comments");
        Files.writeString(toml, generated + System.lineSeparator() + "# administrator note" + System.lineSeparator());
        byte[] before = Files.readAllBytes(toml);
        Result reloaded = load(directory, Mode.RELOAD);
        expect(reloaded.success(), "valid TOML reload failed");
        expect(java.util.Arrays.equals(before, Files.readAllBytes(toml)), "complete valid TOML was rewritten");
    }

    private static void verifyLegacyMigration(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(LEGACY_JSON_NAME), """
                {
                  "optimizeTomsHopper": false,
                  "normalHopperBatchSize": 23,
                  "advancedHopperBatchSize": 87,
                  "hopperSuccessCooldown": 11,
                  "hopperFailureCooldowns": [3, 7],
                  "staggerConnectorScans": false,
                  "maxVariantNbtBytes": 70000,
                  "maxVolumeVariantNbtBytes": 70000000,
                  "itemFilterMode": "whitelist",
                  "itemFilterItems": ["minecraft:diamond"],
                  "itemFilterTags": ["minecraft:boats"],
                  "logRejectedVariants": true,
                  "migrationViewsScannedPerTick": 300,
                  "digitalViewScoreDivisor": 2048,
                  "defaultVolumesPerPlayer": 5,
                  "persistenceFlushIntervalTicks": 400,
                  "persistenceSnapshotsPerTick": 2,
                  "persistenceVariantsPerTick": 256,
                  "volumeNbtWarningBytes": 20000000,
                  "rejectUnstackableItems": true
                }
                """);
        Result result = load(directory, Mode.STARTUP);
        DigitalStorageConfig config = result.config();
        expect(result.success() && !config.optimizeTomsHopper && config.normalHopperBatchSize == 23
                        && config.advancedHopperBatchSize == 87 && config.hopperSuccessCooldown == 11
                        && java.util.Arrays.equals(config.hopperFailureCooldowns, new int[]{3, 7})
                        && !config.staggerConnectorScans && config.maxVariantNbtBytes == 70000
                        && config.maxVolumeVariantNbtBytes == 70000000 && config.itemFilterMode.equals("whitelist")
                        && config.itemFilterItems.equals(java.util.List.of("minecraft:diamond"))
                        && config.itemFilterTags.equals(java.util.List.of("minecraft:boats"))
                        && config.logRejectedVariants && config.migrationViewsScannedPerTick == 300
                        && config.digitalViewScoreDivisor == 2048 && config.defaultVolumesPerPlayer == 5
                        && config.persistenceFlushIntervalTicks == 400 && config.persistenceSnapshotsPerTick == 2
                        && config.persistenceVariantsPerTick == 256 && config.volumeNbtWarningBytes == 20000000,
                "one or more legacy values were not migrated");
        expect(!config.allowUnstackableItems, "legacy rejectUnstackableItems=true inversion failed");
        expect(Files.isRegularFile(directory.resolve(TOML_NAME)), "migrated TOML is missing");
        expect(!Files.exists(directory.resolve(LEGACY_JSON_NAME)), "legacy JSON was not archived");
        try (Stream<Path> files = Files.list(directory)) {
            expect(files.anyMatch(path -> path.getFileName().toString().startsWith(LEGACY_JSON_NAME + ".migrated-")),
                    "legacy JSON archive is missing");
        }

        Path allowDirectory = directory.resolve("legacy-allow");
        Files.createDirectories(allowDirectory);
        Files.writeString(allowDirectory.resolve(LEGACY_JSON_NAME), "{\"rejectUnstackableItems\": false}");
        Result allowResult = load(allowDirectory, Mode.STARTUP);
        expect(allowResult.success() && allowResult.config().allowUnstackableItems,
                "legacy rejectUnstackableItems=false inversion failed");
    }

    private static void verifyTomlPrecedence(Path directory) throws IOException {
        Result created = load(directory, Mode.STARTUP);
        expect(created.success(), "precedence TOML creation failed");
        Path toml = directory.resolve(TOML_NAME);
        CommentedConfig document = parseToml(toml);
        document.set("hopper.normalBatchSize", 31);
        writeAtomic(toml, document);
        Files.writeString(directory.resolve(LEGACY_JSON_NAME), "{\"normalHopperBatchSize\": 47}");
        Result loaded = load(directory, Mode.RELOAD);
        expect(loaded.success() && loaded.config().normalHopperBatchSize == 31, "TOML did not take precedence over JSON");
    }

    private static void verifySingleFieldFallback(Path directory) throws IOException {
        load(directory, Mode.STARTUP);
        Path toml = directory.resolve(TOML_NAME);
        CommentedConfig document = parseToml(toml);
        document.set("hopper.normalBatchSize", "invalid");
        document.set("hopper.advancedBatchSize", 123);
        writeAtomic(toml, document);
        Result loaded = load(directory, Mode.RELOAD);
        expect(loaded.success(), "single-field fallback reload failed");
        expect(loaded.config().normalHopperBatchSize == 16, "invalid field did not fall back independently");
        expect(loaded.config().advancedHopperBatchSize == 123, "valid sibling field was not preserved");
        expect(Files.readString(toml).contains("normalBatchSize = 16"), "invalid field was not repaired on disk");
    }

    private static void verifyMissingFieldRepair(Path directory) throws IOException {
        load(directory, Mode.STARTUP);
        Path toml = directory.resolve(TOML_NAME);
        CommentedConfig document = parseToml(toml);
        document.remove("network.digitalViewScoreDivisor");
        document.set("serverSpecific.unknownSetting", 42);
        document.setComment("hopper.normalBatchSize", " custom server note");
        writeAtomic(toml, document);

        Result loaded = load(directory, Mode.RELOAD);
        expect(loaded.success() && loaded.config().digitalViewScoreDivisor == 1024,
                "missing field did not receive its canonical default");
        CommentedConfig repaired = parseToml(toml);
        expect(((Number) repaired.getRaw("serverSpecific.unknownSetting")).intValue() == 42,
                "unknown TOML key was removed during repair");
        expect(" custom server note".equals(repaired.getComment("hopper.normalBatchSize")),
                "custom TOML comment was removed during repair");
    }

    private static void verifyNormalization(Path directory) throws IOException {
        load(directory, Mode.STARTUP);
        Path toml = directory.resolve(TOML_NAME);
        CommentedConfig document = parseToml(toml);
        document.set("hopper.normalBatchSize", 0);
        document.set("hopper.failureCooldownTicks", java.util.List.of(0, 9999));
        document.set("storage.filter.items", java.util.List.of(" minecraft:diamond ", "minecraft:diamond", "", "minecraft:stone"));
        document.set("storage.filter.tags", java.util.List.of(" minecraft:boats ", "minecraft:boats", " "));
        writeAtomic(toml, document);
        Result loaded = load(directory, Mode.RELOAD);
        expect(loaded.success(), "normalization reload failed");
        expect(loaded.config().normalHopperBatchSize == 1, "integer normalization failed");
        expect(java.util.Arrays.equals(loaded.config().hopperFailureCooldowns, new int[]{1, 1200}),
                "integer-list normalization failed");
        expect(loaded.config().itemFilterItems.equals(java.util.List.of("minecraft:diamond", "minecraft:stone")),
                "item-list trim or deduplication failed");
        expect(loaded.config().itemFilterTags.equals(java.util.List.of("minecraft:boats")),
                "tag-list trim or deduplication failed");

        document = parseToml(toml);
        document.set("hopper.failureCooldownTicks", java.util.List.of());
        writeAtomic(toml, document);
        loaded = load(directory, Mode.RELOAD);
        expect(java.util.Arrays.equals(loaded.config().hopperFailureCooldowns, new int[]{20, 40, 100, 200, 400}),
                "empty failure-cooldown list did not restore defaults");

        document = parseToml(toml);
        document.set("hopper.failureCooldownTicks", java.util.stream.IntStream.rangeClosed(1, 17).boxed().toList());
        writeAtomic(toml, document);
        loaded = load(directory, Mode.RELOAD);
        expect(loaded.config().hopperFailureCooldowns.length == 16
                        && loaded.config().hopperFailureCooldowns[15] == 16,
                "failure-cooldown list was not capped at 16 entries");
    }

    private static void verifyBrokenStartupRecovery(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path toml = directory.resolve(TOML_NAME);
        Files.writeString(toml, "[storage\ninvalid =");
        Result loaded = load(directory, Mode.STARTUP);
        expect(loaded.success() && Files.isRegularFile(toml), "broken startup TOML was not recovered");
        parseToml(toml);
        try (Stream<Path> files = Files.list(directory)) {
            expect(files.anyMatch(path -> path.getFileName().toString().startsWith(TOML_NAME + ".broken-")),
                    "broken TOML backup is missing");
        }
    }

    private static void verifyBrokenReloadRejection(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(TOML_NAME), "configVersion = [");
        Result loaded = load(directory, Mode.RELOAD);
        expect(!loaded.success() && loaded.config() == null, "broken hot reload did not preserve last-known-good state");
    }

    private static void verifyFutureSchemaIsUntouched(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path toml = directory.resolve(TOML_NAME);
        String future = """
                configVersion = 999
                futureSetting = "preserve me"

                [hopper]
                normalBatchSize = 29
                """;
        Files.writeString(toml, future);
        byte[] before = Files.readAllBytes(toml);
        Result loaded = load(directory, Mode.RELOAD);
        expect(loaded.success() && loaded.config().normalHopperBatchSize == 29, "future schema safe value was not read");
        expect(java.util.Arrays.equals(before, Files.readAllBytes(toml)), "future schema was modified or downgraded");
    }

    private static void deleteTemporaryTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of self-test files only.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup of self-test files only.
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    enum Mode {
        STARTUP,
        RELOAD
    }

    record Result(boolean success, DigitalStorageConfig config, String message) {
    }
}
