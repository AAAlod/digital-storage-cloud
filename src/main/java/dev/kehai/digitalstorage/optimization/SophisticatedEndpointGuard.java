package dev.kehai.digitalstorage.optimization;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Restricts optional Sophisticated Storage integration to audited block states. */
public final class SophisticatedEndpointGuard {
    private SophisticatedEndpointGuard() { }
    public static Object upgradeInventory(BlockEntity block, boolean requireRemoteLinkCheck) {
        if (block == null || block.isRemoved()) return null;
        var id = BuiltInRegistries.BLOCK.getKey(block.getBlockState().getBlock());
        if (id == null || !id.getNamespace().equals("sophisticatedstorage")
                || !(id.getPath().equals("chest") || id.getPath().equals("barrel") || id.getPath().equals("limited_barrel_1"))) return null;
        try {
            var type = block.getClass();
            if (Boolean.TRUE.equals(type.getMethod("isLinked").invoke(block))) return null;
            if (!(type.getMethod("getControllerPos").invoke(block) instanceof Optional<?> position) || position.isPresent()) return null;
            if (requireRemoteLinkCheck && !Boolean.FALSE.equals(type.getMethod("isLinkedStorage").invoke(block))) return null;
            Object wrapper = type.getMethod("getStorageWrapper").invoke(block);
            return wrapper.getClass().getMethod("getUpgradeHandler").invoke(wrapper);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }
}
