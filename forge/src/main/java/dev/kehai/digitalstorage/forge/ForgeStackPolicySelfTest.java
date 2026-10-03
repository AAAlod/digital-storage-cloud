package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import dev.kehai.digitalstorage.storage.ItemKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.extensions.IForgeItem;

/** Isolated NBT-sensitive Forge hook fixture; never registers a test item. */
public final class ForgeStackPolicySelfTest {
    private ForgeStackPolicySelfTest() { }

    public static void run() {
        IForgeItem dynamic = new IForgeItem() {
            @Override public boolean isRepairable(ItemStack stack) { return false; }
            @Override public int getMaxStackSize(ItemStack stack) {
                return stack.hasTag() && stack.getTag().getBoolean("Unstackable") ? 1 : 64;
            }
        };
        var platform = new ForgeItemKeys();
        var config = DigitalStorageConfig.get();
        boolean previousAllow = config.allowUnstackableItems;
        // Use a registered item for actual Forge NBT reconstruction and supply
        // an isolated stack-sensitive hook to test the common policy dispatch.
        // Runtime registries are frozen; never construct/register a test Item.
        ItemKey.installStackDataAdapter(new ItemKey.StackDataAdapter() {
            public CompoundTag capture(ItemStack stack) { return platform.capture(stack); }
            public ItemStack create(ItemKey key, int count) {
                return platform.create(key, count);
            }
            public int maximumStackSize(ItemKey key) {
                return key.item() == Items.PAPER ? dynamic.getMaxStackSize(key.toStack(1))
                        : platform.maximumStackSize(key);
            }
        });
        try {
            var tag = new CompoundTag();
            tag.putBoolean("Unstackable", true);
            ItemKey single = ItemKey.of(Items.PAPER, tag);
            tag.putBoolean("Unstackable", false);
            ItemKey normal = ItemKey.of(Items.PAPER, tag);
            expect(Items.PAPER.getMaxStackSize() == 64 && single.maximumStackSize() == 1
                            && normal.maximumStackSize() == 64,
                    "Forge stack-aware maximum ignored immutable item NBT");
            config.allowUnstackableItems = true;
            ItemSecurityPolicy.reload();
            expect(!ItemSecurityPolicy.canInsert(single, false) && ItemSecurityPolicy.canInsert(single, true)
                            && ItemSecurityPolicy.canInsert(normal, false),
                    "Per-volume policy ignored Forge dynamic unstackable state");
            config.allowUnstackableItems = false;
            ItemSecurityPolicy.reload();
            expect(!ItemSecurityPolicy.canInsert(single, true) && ItemSecurityPolicy.canInsert(normal, false),
                    "Global policy ignored Forge dynamic unstackable state");
        } finally {
            ItemKey.installStackDataAdapter(platform);
            config.allowUnstackableItems = previousAllow;
            ItemSecurityPolicy.reload();
        }
        expect(ItemKey.of(Items.PAPER).maximumStackSize() == 64
                        && ItemKey.of(Items.DIAMOND_PICKAXE).maximumStackSize() == 1,
                "Production Forge maximumStackSize adapter was not restored");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
