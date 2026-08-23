package dev.kehai.digitalstorage.security;

import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

public final class ItemSecurityPolicy {
    private static final AtomicLong FILTER_REJECTIONS = new AtomicLong();
    private static final AtomicLong NBT_REJECTIONS = new AtomicLong();
    private static final AtomicLong UNSTACKABLE_REJECTIONS = new AtomicLong();
    private static final AtomicLong LAST_LOG_MILLIS = new AtomicLong();
    private static volatile Snapshot snapshot = Snapshot.empty();

    private ItemSecurityPolicy() {
    }

    public static void reload() {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        Set<Identifier> items = parseIdentifiers(config.itemFilterItems, "item");
        Set<Identifier> tags = parseIdentifiers(config.itemFilterTags, "tag");
        snapshot = new Snapshot(
                "whitelist".equals(config.itemFilterMode),
                Set.copyOf(items),
                Set.copyOf(tags),
                config.maxVariantNbtBytes,
                config.rejectUnstackableItems,
                config.logRejectedVariants
        );
    }

    /**
     * Rules that must be evaluated for every insertion, including variants that
     * already exist in a volume. Extraction intentionally bypasses this check so
     * changing the configuration can never strand previously stored items.
     */
    public static boolean allowsInsert(ItemVariant variant) {
        Snapshot current = snapshot;
        if (!isInsertAllowed(variant, current)) {
            UNSTACKABLE_REJECTIONS.incrementAndGet();
            logRejected(current, Registries.ITEM.getId(variant.getItem()), "unstackable item");
            return false;
        }
        return true;
    }

    public static boolean canInsert(ItemVariant variant) {
        return isInsertAllowed(variant, snapshot);
    }

    public static boolean canCreateVariant(ItemVariant variant) {
        Snapshot current = snapshot;
        Identifier itemId = Registries.ITEM.getId(variant.getItem());
        if (!passesFilter(variant, current, itemId)) {
            return false;
        }
        return variantNbtBytes(variant) <= current.maxNbtBytes();
    }

    public static boolean allowsNewVariant(ItemVariant variant) {
        Snapshot current = snapshot;
        Identifier itemId = Registries.ITEM.getId(variant.getItem());
        if (!passesFilter(variant, current, itemId)) {
            FILTER_REJECTIONS.incrementAndGet();
            logRejected(current, itemId, "item filter");
            return false;
        }

        int nbtBytes = variantNbtBytes(variant);
        if (nbtBytes > current.maxNbtBytes()) {
            NBT_REJECTIONS.incrementAndGet();
            logRejected(current, itemId, "variant NBT size " + nbtBytes + " bytes");
            return false;
        }
        return true;
    }

    private static boolean isInsertAllowed(ItemVariant variant, Snapshot current) {
        return !current.rejectUnstackables() || variant.getItem().getMaxCount() > 1;
    }

    private static boolean passesFilter(ItemVariant variant, Snapshot current, Identifier itemId) {
        boolean listed = current.items().contains(itemId);
        if (!listed) {
            for (Identifier tagId : current.tags()) {
                if (variant.getItem().getRegistryEntry().isIn(TagKey.of(RegistryKeys.ITEM, tagId))) {
                    listed = true;
                    break;
                }
            }
        }
        return current.whitelist() ? listed : !listed;
    }

    public static int variantNbtBytes(ItemVariant variant) {
        return variant.getNbt() == null ? 0 : variant.getNbt().getSizeInBytes();
    }

    public static boolean isNbtWithinLimit(ItemVariant variant, int maximumBytes) {
        return variantNbtBytes(variant) <= maximumBytes;
    }

    public static long filterRejections() {
        return FILTER_REJECTIONS.get();
    }

    public static long nbtRejections() {
        return NBT_REJECTIONS.get();
    }

    public static long unstackableRejections() {
        return UNSTACKABLE_REJECTIONS.get();
    }

    public static void runSelfTest() {
        Snapshot original = snapshot;
        long originalFilterRejections = FILTER_REJECTIONS.get();
        long originalNbtRejections = NBT_REJECTIONS.get();
        long originalUnstackableRejections = UNSTACKABLE_REJECTIONS.get();
        try {
            Identifier diamondId = new Identifier("minecraft", "diamond");
            snapshot = new Snapshot(true, Set.of(diamondId), Set.of(), 65_536, false, false);
            expect(allowsNewVariant(ItemVariant.of(net.minecraft.item.Items.DIAMOND)),
                    "whitelist rejected its listed item");
            expect(!allowsNewVariant(ItemVariant.of(net.minecraft.item.Items.DIRT)),
                    "whitelist accepted an unlisted item");

            snapshot = new Snapshot(
                    false,
                    Set.of(),
                    Set.of(new Identifier("minecraft", "logs")),
                    65_536,
                    false,
                    false
            );
            expect(!allowsNewVariant(ItemVariant.of(net.minecraft.item.Items.OAK_LOG)),
                    "blacklist tag accepted a tagged item");
            expect(allowsNewVariant(ItemVariant.of(net.minecraft.item.Items.DIAMOND)),
                    "blacklist tag rejected an unrelated item");

            net.minecraft.nbt.NbtCompound oversizedNbt = new net.minecraft.nbt.NbtCompound();
            oversizedNbt.putString("Payload", "x".repeat(2_000));
            snapshot = new Snapshot(false, Set.of(), Set.of(), 1_024, false, false);
            expect(!allowsNewVariant(ItemVariant.of(net.minecraft.item.Items.PAPER, oversizedNbt)),
                    "NBT guard accepted an oversized new variant");

            snapshot = new Snapshot(false, Set.of(), Set.of(), 65_536, true, false);
            expect(!allowsInsert(ItemVariant.of(net.minecraft.item.Items.IRON_PICKAXE)),
                    "unstackable guard accepted a tool");
            expect(allowsInsert(ItemVariant.of(net.minecraft.item.Items.DIAMOND)),
                    "unstackable guard rejected a stackable item");
        } finally {
            snapshot = original;
            FILTER_REJECTIONS.set(originalFilterRejections);
            NBT_REJECTIONS.set(originalNbtRejections);
            UNSTACKABLE_REJECTIONS.set(originalUnstackableRejections);
        }
    }

    private static Set<Identifier> parseIdentifiers(Iterable<String> values, String type) {
        Set<Identifier> result = new HashSet<>();
        for (String value : values) {
            Identifier id = Identifier.tryParse(value);
            if (id == null) {
                DigitalStorageMod.LOGGER.warn("Ignoring invalid item filter {} identifier {}", type, value);
            } else {
                result.add(id);
            }
        }
        return result;
    }

    private static void logRejected(Snapshot current, Identifier itemId, String reason) {
        if (!current.logRejected()) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = LAST_LOG_MILLIS.get();
        if (now - previous >= 1000 && LAST_LOG_MILLIS.compareAndSet(previous, now)) {
            DigitalStorageMod.LOGGER.warn("Rejected new digital storage variant {}: {}", itemId, reason);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Snapshot(
            boolean whitelist,
            Set<Identifier> items,
            Set<Identifier> tags,
            int maxNbtBytes,
            boolean rejectUnstackables,
            boolean logRejected
    ) {
        private static Snapshot empty() {
            return new Snapshot(false, Set.of(), Set.of(), 65_536, true, false);
        }
    }
}
