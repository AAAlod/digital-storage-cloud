package dev.kehai.digitalstorage.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

final class DigitalStorageConfigSchema {
    static final int CURRENT_VERSION = 1;

    private static final List<Option> OPTIONS = List.of(
            bool("hopper.optimizationEnabled", "Enable optimized transfers for Tom's inventory hoppers.",
                    config -> config.optimizeTomsHopper, (config, value) -> config.optimizeTomsHopper = (Boolean) value),
            integer("hopper.normalBatchSize", "Maximum items moved by a normal hopper operation.\n"
                            + "Higher values increase throughput and work per operation.\nRange: 1-64.",
                    config -> config.normalHopperBatchSize, (config, value) -> config.normalHopperBatchSize = (Integer) value),
            integer("hopper.advancedBatchSize", "Maximum items moved by an advanced hopper operation.\n"
                            + "Higher values increase throughput and work per operation.\nRange: 1-1024.",
                    config -> config.advancedHopperBatchSize, (config, value) -> config.advancedHopperBatchSize = (Integer) value),
            integer("hopper.successCooldownTicks", "Cooldown after a successful transfer, in game ticks.\n"
                            + "Higher values reduce scan frequency but delay the next transfer.\n"
                            + "20 ticks is approximately one second at normal server TPS. Range: 0-1200.",
                    config -> config.hopperSuccessCooldown, (config, value) -> config.hopperSuccessCooldown = (Integer) value),
            integerList("hopper.failureCooldownTicks", "Backoff schedule after consecutive failed transfers, in game ticks.\n"
                            + "Later failures use later entries; the final entry repeats to protect TPS.\n"
                            + "Up to 16 entries; each value range: 1-1200.",
                    config -> config.hopperFailureCooldowns,
                    (config, value) -> config.hopperFailureCooldowns = (int[]) value),
            bool("hopper.staggerConnectorScans", "Spread connector scans across ticks to reduce synchronized spikes.",
                    config -> config.staggerConnectorScans, (config, value) -> config.staggerConnectorScans = (Boolean) value),
            bool("storage.allowUnstackableItems", "Allow volume owners to opt in to storing max-stack-size-one items.",
                    config -> config.allowUnstackableItems, (config, value) -> config.allowUnstackableItems = (Boolean) value),
            integer("storage.maxVariantNbtBytes", "Maximum serialized NBT accepted for one new item variant.\n"
                            + "Unit: bytes. Range: 1024-1048576.",
                    config -> config.maxVariantNbtBytes, (config, value) -> config.maxVariantNbtBytes = (Integer) value),
            integer("storage.maxVolumeVariantNbtBytes", "Hard aggregate serialized variant-NBT limit for one volume.\n"
                            + "Unit: bytes. Range: 1048576-1073741824.",
                    config -> config.maxVolumeVariantNbtBytes,
                    (config, value) -> config.maxVolumeVariantNbtBytes = (Integer) value),
            integer("storage.defaultVolumesPerPlayer", "Default maximum volumes per player.\n"
                            + "0 means unlimited. Range: 0-64.",
                    config -> config.defaultVolumesPerPlayer,
                    (config, value) -> config.defaultVolumesPerPlayer = (Integer) value),
            integer("storage.volumeNbtWarningBytes", "Soft per-volume NBT warning threshold; it does not reject data.\n"
                            + "Unit: bytes. 0 disables it; otherwise range: 1048576-1073741824.",
                    config -> config.volumeNbtWarningBytes,
                    (config, value) -> config.volumeNbtWarningBytes = (Integer) value),
            string("storage.filter.mode", "How the item and tag lists are interpreted.\n"
                            + "Allowed: blacklist (listed entries rejected), whitelist (only listed entries accepted).",
                    config -> config.itemFilterMode, (config, value) -> config.itemFilterMode = (String) value),
            stringList("storage.filter.items", "Item identifiers used by the filter.\n"
                            + "Example: [\"minecraft:shulker_box\", \"minecraft:bundle\"]. Maximum entries: 4096.",
                    config -> config.itemFilterItems,
                    (config, value) -> config.itemFilterItems = castStringList(value)),
            stringList("storage.filter.tags", "Item tag identifiers used by the filter, without a leading #.\n"
                            + "Example: [\"minecraft:boats\"]. Maximum entries: 4096.",
                    config -> config.itemFilterTags,
                    (config, value) -> config.itemFilterTags = castStringList(value)),
            bool("storage.filter.logRejectedVariants", "Log variants rejected by the storage safety policy.\n"
                            + "Useful for troubleshooting, but may add output on busy servers.",
                    config -> config.logRejectedVariants,
                    (config, value) -> config.logRejectedVariants = (Boolean) value),
            integer("network.migrationViewsScannedPerTick", "Maximum physical storage views inspected by one migration job per tick.\n"
                            + "Lower values protect TPS; higher values finish scans faster. Range: 1-65536.",
                    config -> config.migrationViewsScannedPerTick,
                    (config, value) -> config.migrationViewsScannedPerTick = (Integer) value),
            integer("network.digitalViewScoreDivisor", "Digital views per one network-health score point.\n"
                            + "Advanced tuning: lower values penalize large networks sooner. Range: 256-8192.",
                    config -> config.digitalViewScoreDivisor,
                    (config, value) -> config.digitalViewScoreDivisor = (Integer) value),
            integer("persistence.flushIntervalTicks", "Interval between scheduled persistence flushes, in game ticks.\n"
                            + "Higher values reduce write activity but extend the dirty-data window. Range: 20-12000.",
                    config -> config.persistenceFlushIntervalTicks,
                    (config, value) -> config.persistenceFlushIntervalTicks = (Integer) value),
            integer("persistence.snapshotsPerTick", "Maximum account or volume snapshots captured on the server thread per tick.\n"
                            + "Advanced tuning: higher values drain backlogs faster but add per-tick work. Range: 1-64.",
                    config -> config.persistenceSnapshotsPerTick,
                    (config, value) -> config.persistenceSnapshotsPerTick = (Integer) value),
            integer("persistence.variantsPerTick", "Maximum item variants copied into persistence snapshots per tick.\n"
                            + "Lower values spread work across ticks; higher values finish snapshots faster. Range: 1-65536.",
                    config -> config.persistenceVariantsPerTick,
                    (config, value) -> config.persistenceVariantsPerTick = (Integer) value)
    );

    private DigitalStorageConfigSchema() {
    }

    static CommentedConfig createDocument(DigitalStorageConfig config) {
        CommentedConfig document = TomlFormat.newConfig(LinkedHashMap::new);
        document.set("configVersion", CURRENT_VERSION);
        document.setComment("configVersion", commentText(
                "Digital Storage Cloud server configuration.\n"
                        + "Defaults favor stable server TPS over burst throughput.\n"
                        + "Values outside the documented range are normalized to a safe value.\n"
                        + "Configuration schema version; newer schemas are read without downgrading the file."
        ));
        for (Option option : OPTIONS) {
            Object value = option.getter().get(config);
            document.set(option.path(), serializableValue(value));
            document.setComment(option.path(), optionComment(option, value));
        }
        return document;
    }

    static ReadResult read(CommentedConfig document) {
        DigitalStorageConfig loaded = DigitalStorageConfig.defaults();
        List<String> warnings = new ArrayList<>();
        Object rawVersion = document.getRaw("configVersion");
        int version = rawVersion instanceof Number number ? number.intValue() : CURRENT_VERSION;
        boolean futureVersion = version > CURRENT_VERSION;
        boolean changed = false;

        if (!(rawVersion instanceof Number) || ((Number) rawVersion).longValue() != CURRENT_VERSION) {
            if (!futureVersion) {
                document.set("configVersion", CURRENT_VERSION);
                document.setComment("configVersion", commentText(
                        "Digital Storage Cloud server configuration.\n"
                                + "Defaults favor stable server TPS over burst throughput.\n"
                                + "Values outside the documented range are normalized to a safe value.\n"
                                + "Configuration schema version; newer schemas are read without downgrading the file."
                ));
                changed = true;
            }
        }

        for (Option option : OPTIONS) {
            Object raw = document.getRaw(option.path());
            Object parsed = option.type().parse(raw);
            if (parsed != Invalid.VALUE) {
                option.setter().set(loaded, parsed);
            } else if (!futureVersion || document.contains(option.path())) {
                warnings.add("Invalid or missing " + option.path() + "; using its default");
            }
        }

        loaded.normalize();
        DigitalStorageConfig defaults = DigitalStorageConfig.defaults();
        for (Option option : OPTIONS) {
            Object raw = document.getRaw(option.path());
            Object parsed = option.type().parse(raw);
            Object normalized = option.getter().get(loaded);
            boolean matches = option.type().matches(raw, normalized);
            if (parsed != Invalid.VALUE && !matches) {
                warnings.add("Normalized " + option.path() + " from " + displayValue(parsed)
                        + " to " + displayValue(normalized));
            }
            if (!futureVersion && !matches) {
                    document.set(option.path(), serializableValue(normalized));
                    if (!document.containsComment(option.path())) {
                        Object defaultValue = option.getter().get(defaults);
                        document.setComment(option.path(), optionComment(option, defaultValue));
                    }
                    changed = true;
            }
        }
        return new ReadResult(loaded, changed, futureVersion, List.copyOf(warnings));
    }

    private static Object serializableValue(Object value) {
        if (value instanceof int[] integers) {
            return Arrays.stream(integers).boxed().toList();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return value;
    }

    private static String displayValue(Object value) {
        if (value instanceof int[] integers) {
            return Arrays.toString(integers);
        }
        return value instanceof String string ? "\"" + string + "\"" : String.valueOf(value);
    }

    private static String optionComment(Option option, Object defaultValue) {
        return commentText(option.comment() + "\nDefault: " + displayValue(defaultValue));
    }

    private static String commentText(String comment) {
        return " " + comment.replace("\n", "\n ");
    }

    @SuppressWarnings("unchecked")
    private static List<String> castStringList(Object value) {
        return new ArrayList<>((List<String>) value);
    }

    private static Option bool(String path, String comment, Getter getter, Setter setter) {
        return new Option(path, comment, Type.BOOLEAN, getter, setter);
    }

    private static Option integer(String path, String comment, Getter getter, Setter setter) {
        return new Option(path, comment, Type.INTEGER, getter, setter);
    }

    private static Option string(String path, String comment, Getter getter, Setter setter) {
        return new Option(path, comment, Type.STRING, getter, setter);
    }

    private static Option integerList(String path, String comment, Getter getter, Setter setter) {
        return new Option(path, comment, Type.INTEGER_LIST, getter, setter);
    }

    private static Option stringList(String path, String comment, Getter getter, Setter setter) {
        return new Option(path, comment, Type.STRING_LIST, getter, setter);
    }

    record ReadResult(DigitalStorageConfig config, boolean changed, boolean futureVersion, List<String> warnings) {
    }

    private record Option(String path, String comment, Type type, Getter getter, Setter setter) {
    }

    @FunctionalInterface
    private interface Getter {
        Object get(DigitalStorageConfig config);
    }

    @FunctionalInterface
    private interface Setter {
        void set(DigitalStorageConfig config, Object value);
    }

    private enum Invalid {
        VALUE
    }

    private enum Type {
        BOOLEAN {
            @Override
            Object parse(Object raw) {
                return raw instanceof Boolean ? raw : Invalid.VALUE;
            }
        },
        INTEGER {
            @Override
            Object parse(Object raw) {
                if (!isIntegerNumber(raw)) {
                    return Invalid.VALUE;
                }
                Number number = (Number) raw;
                long value = number.longValue();
                return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE ? (int) value : Invalid.VALUE;
            }
        },
        STRING {
            @Override
            Object parse(Object raw) {
                return raw instanceof String ? raw : Invalid.VALUE;
            }
        },
        INTEGER_LIST {
            @Override
            Object parse(Object raw) {
                if (!(raw instanceof List<?> list)) {
                    return Invalid.VALUE;
                }
                int[] values = new int[list.size()];
                for (int index = 0; index < list.size(); index++) {
                    Object element = list.get(index);
                    if (!isIntegerNumber(element)) {
                        return Invalid.VALUE;
                    }
                    Number number = (Number) element;
                    if (number.longValue() < Integer.MIN_VALUE || number.longValue() > Integer.MAX_VALUE) {
                        return Invalid.VALUE;
                    }
                    values[index] = number.intValue();
                }
                return values;
            }
        },
        STRING_LIST {
            @Override
            Object parse(Object raw) {
                if (!(raw instanceof List<?> list) || list.stream().anyMatch(value -> !(value instanceof String))) {
                    return Invalid.VALUE;
                }
                return new ArrayList<>(list.stream().map(String.class::cast).toList());
            }
        };

        abstract Object parse(Object raw);

        private static boolean isIntegerNumber(Object value) {
            return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof java.math.BigInteger;
        }

        boolean matches(Object raw, Object expected) {
            Object parsed = parse(raw);
            if (parsed == Invalid.VALUE) {
                return false;
            }
            if (parsed instanceof int[] actual && expected instanceof int[] wanted) {
                return Arrays.equals(actual, wanted);
            }
            return Objects.equals(parsed, expected);
        }
    }
}
