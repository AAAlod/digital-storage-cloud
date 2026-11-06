package dev.kehai.digitalstorage.optimization;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Restricts optional Sophisticated Storage integration to audited block states. */
public final class SophisticatedEndpointGuard {
    private SophisticatedEndpointGuard() { }
    public static boolean isStackUpgrade(net.minecraft.world.item.Item item) {
        var id = BuiltInRegistries.ITEM.getKey(item);
        return id != null && id.getNamespace().equals("sophisticatedstorage")
                && java.util.Set.of("stack_upgrade_tier_1", "stack_upgrade_tier_1_plus", "stack_upgrade_tier_2",
                        "stack_upgrade_tier_3", "stack_upgrade_tier_4", "stack_upgrade_tier_5", "stack_upgrade_omega_tier")
                .contains(id.getPath())
                && item.getClass().getName().equals("net.p3pp3rf1y.sophisticatedcore.upgrades.stack.StackUpgradeItem");
    }
    public static boolean isSupportedBlock(BlockEntity block) {
        if (block == null || block.isRemoved()) return false;
        var id = BuiltInRegistries.BLOCK.getKey(block.getBlockState().getBlock());
        return id != null && id.getNamespace().equals("sophisticatedstorage")
                && (id.getPath().equals("chest") || id.getPath().equals("barrel") || id.getPath().equals("limited_barrel_1"));
    }
    public static Object upgradeInventory(BlockEntity block, boolean requireRemoteLinkCheck) {
        if (!isSupportedBlock(block)) return null;
        try {
            var type = block.getClass();
            if (Boolean.TRUE.equals(type.getMethod("isLinked").invoke(block))) return null;
            if (!(type.getMethod("getControllerPos").invoke(block) instanceof Optional<?> position) || position.isPresent()) return null;
            try {
                if (!Boolean.FALSE.equals(type.getMethod("isLinkedStorage").invoke(block))) return null;
            } catch (NoSuchMethodException absent) {
                if (requireRemoteLinkCheck) return null;
            }
            Object wrapper = type.getMethod("getStorageWrapper").invoke(block);
            return wrapper.getClass().getMethod("getUpgradeHandler").invoke(wrapper);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }
}
