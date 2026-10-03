package dev.kehai.digitalstorage.security;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import dev.kehai.digitalstorage.storage.ItemKey;

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
        Set<ResourceLocation> items = parseIdentifiers(config.itemFilterItems, "item");
        Set<ResourceLocation> tags = parseIdentifiers(config.itemFilterTags, "tag");
        snapshot = new Snapshot(
                "whitelist".equals(config.itemFilterMode),
                Set.copyOf(items),
                Set.copyOf(tags),
                config.maxVariantNbtBytes,
                config.allowUnstackableItems,
                config.logRejectedVariants
        );
    }

    /**
     * Rules that must be evaluated for every insertion, including variants that
     * already exist in a volume. Extraction intentionally bypasses this check so
     * changing the configuration can never strand previously stored items.
     */
    public static boolean allowsInsert(ItemKey variant) {
        return allowsInsert(variant, true);
    }

    public static boolean allowsInsert(ItemKey variant, boolean volumeAcceptsUnstackables) {
        Snapshot current = snapshot;
        if (!isInsertAllowed(variant, current, volumeAcceptsUnstackables)) {
            UNSTACKABLE_REJECTIONS.incrementAndGet();
            logRejected(current, BuiltInRegistries.ITEM.getKey(variant.item()), "unstackable item");
            return false;
        }
        return true;
    }

    public static boolean canInsert(ItemKey variant) {
        return canInsert(variant, true);
    }

    public static boolean canInsert(ItemKey variant, boolean volumeAcceptsUnstackables) {
        return isInsertAllowed(variant, snapshot, volumeAcceptsUnstackables);
    }

    public static boolean canCreateVariant(ItemKey variant) {
        Snapshot current = snapshot;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(variant.item());
        if (!passesFilter(variant, current, itemId)) {
            return false;
        }
        return variantNbtBytes(variant) <= current.maxNbtBytes();
    }

    public static boolean allowsNewVariant(ItemKey variant) {
        Snapshot current = snapshot;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(variant.item());
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

    private static boolean isInsertAllowed(
            ItemKey variant,
            Snapshot current,
            boolean volumeAcceptsUnstackables
    ) {
        return variant.maximumStackSize() > 1
                || (current.allowUnstackables() && volumeAcceptsUnstackables);
    }

    private static boolean passesFilter(ItemKey variant, Snapshot current, ResourceLocation itemId) {
        boolean listed = current.items().contains(itemId);
        if (!listed) {
            for (ResourceLocation tagId : current.tags()) {
                if (variant.item().builtInRegistryHolder().is(TagKey.create(Registries.ITEM, tagId))) {
                    listed = true;
                    break;
                }
            }
        }
        return current.whitelist() ? listed : !listed;
    }

    public static int variantNbtBytes(ItemKey variant) {
        return variant.tagBytes();
    }

    public static boolean isNbtWithinLimit(ItemKey variant, int maximumBytes) {
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
            ResourceLocation diamondId = new ResourceLocation("minecraft", "diamond");
            snapshot = new Snapshot(true, Set.of(diamondId), Set.of(), 65_536, false, false);
            expect(allowsNewVariant(ItemKey.of(net.minecraft.world.item.Items.DIAMOND)),
                    "whitelist rejected its listed item");
            expect(!allowsNewVariant(ItemKey.of(net.minecraft.world.item.Items.DIRT)),
                    "whitelist accepted an unlisted item");

            snapshot = new Snapshot(
                    false,
                    Set.of(),
                    Set.of(new ResourceLocation("minecraft", "logs")),
                    65_536,
                    false,
                    false
            );
            expect(!allowsNewVariant(ItemKey.of(net.minecraft.world.item.Items.OAK_LOG)),
                    "blacklist tag accepted a tagged item");
            expect(allowsNewVariant(ItemKey.of(net.minecraft.world.item.Items.DIAMOND)),
                    "blacklist tag rejected an unrelated item");

            net.minecraft.nbt.CompoundTag oversizedNbt = new net.minecraft.nbt.CompoundTag();
            oversizedNbt.putString("Payload", "x".repeat(2_000));
            snapshot = new Snapshot(false, Set.of(), Set.of(), 1_024, false, false);
            expect(!allowsNewVariant(ItemKey.of(net.minecraft.world.item.Items.PAPER, oversizedNbt)),
                    "NBT guard accepted an oversized new variant");

            ItemKey pickaxe = ItemKey.of(net.minecraft.world.item.Items.IRON_PICKAXE);
            ItemKey diamond = ItemKey.of(net.minecraft.world.item.Items.DIAMOND);
            snapshot = new Snapshot(false, Set.of(), Set.of(), 65_536, false, false);
            expect(!canInsert(pickaxe, false), "server deny + volume reject accepted a tool");
            expect(!canInsert(pickaxe, true), "server deny + volume accept accepted a tool");
            expect(canInsert(diamond, false), "server deny rejected a stackable item");

            snapshot = new Snapshot(false, Set.of(), Set.of(), 65_536, true, false);
            expect(!canInsert(pickaxe, false), "volume reject accepted a tool");
            expect(canInsert(pickaxe, true), "server and volume allow rejected a tool");
            expect(allowsInsert(diamond, false), "volume reject rejected a stackable item");
        } finally {
            snapshot = original;
            FILTER_REJECTIONS.set(originalFilterRejections);
            NBT_REJECTIONS.set(originalNbtRejections);
            UNSTACKABLE_REJECTIONS.set(originalUnstackableRejections);
        }
    }

    private static Set<ResourceLocation> parseIdentifiers(Iterable<String> values, String type) {
        Set<ResourceLocation> result = new HashSet<>();
        for (String value : values) {
            ResourceLocation id = ResourceLocation.tryParse(value);
            if (id == null) {
                DigitalStorage.LOGGER.warn("Ignoring invalid item filter {} identifier {}", type, value);
            } else {
                result.add(id);
            }
        }
        return result;
    }

    private static void logRejected(Snapshot current, ResourceLocation itemId, String reason) {
        if (!current.logRejected()) {
            return;
        }
        long now = System.currentTimeMillis();
        long previous = LAST_LOG_MILLIS.get();
        if (now - previous >= 1000 && LAST_LOG_MILLIS.compareAndSet(previous, now)) {
            DigitalStorage.LOGGER.warn("Rejected new digital storage variant {}: {}", itemId, reason);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Snapshot(
            boolean whitelist,
            Set<ResourceLocation> items,
            Set<ResourceLocation> tags,
            int maxNbtBytes,
            boolean allowUnstackables,
            boolean logRejected
    ) {
        private static Snapshot empty() {
            return new Snapshot(false, Set.of(), Set.of(), 65_536, true, false);
        }
    }
}
