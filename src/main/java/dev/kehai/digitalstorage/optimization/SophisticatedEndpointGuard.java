package dev.kehai.digitalstorage.optimization;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Restricts optional Sophisticated Storage integration to audited block states. */
public final class SophisticatedEndpointGuard {
    private SophisticatedEndpointGuard() { }
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static void report(String reason, String detail) {
        String key = reason + ":" + detail;
        if (REPORTED.add(key)) dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Sophisticated endpoint skipped: {} ({})", reason, detail);
    }
    public static boolean knownLegacy(String storageVersion, String coreVersion, boolean fabric, ClassLoader loader) {
        String storage = storageVersion.replaceFirst("^1\\.20\\.1-", "");
        String core = coreVersion.replaceFirst("^1\\.20\\.1-", "");
        boolean inRange = fabric
                ? between(storage, "1.3.5", "1.3.5.11.142") && between(core, "1.2.7", "1.2.7.15.166")
                : between(storage, "1.4.0", "1.4.86.2131") && between(core, "1.3.0", "1.3.84.2308");
        if (!inRange) return false;
        try {
            Class.forName("net.p3pp3rf1y.sophisticatedcore.linkedstorage.ILinkedStorageBlockEndpoint", false, loader);
            return false;
        } catch (ClassNotFoundException absent) {
            return true;
        } catch (LinkageError failure) {
            return false;
        }
    }
    private static boolean between(String value, String minimum, String maximum) {
        if (!value.matches("[0-9]+(\\.[0-9]+){2,4}")) return false;
        try { return compare(value, minimum) >= 0 && compare(value, maximum) <= 0; }
        catch (NumberFormatException failure) { return false; }
    }
    private static int compare(String left, String right) {
        var a = left.split("\\."); var b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
    public static void runSelfTest() {
        ClassLoader absent = new ClassLoader(null) { };
        if (!knownLegacy("1.4.47.1744", "1.3.38.1880", false, absent)
                || !knownLegacy("1.4.22.1461", "1.3.37.1868", false, absent)
                || !knownLegacy("1.4.86.2131", "1.3.84.2308", false, absent)
                || !knownLegacy("1.20.1-1.3.5.11.142", "1.20.1-1.2.7.15.166", true, absent)
                || knownLegacy("1.5.0.1", "1.3.38.1880", false, absent)
                || knownLegacy("unknown", "1.3.38.1880", false, absent)
                || knownLegacy("1.4.47.1744", "1.9.0", false, absent)) {
            throw new IllegalStateException("Sophisticated legacy version policy failed");
        }
    }
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
        boolean supported = id != null && id.getNamespace().equals("sophisticatedstorage")
                && (id.getPath().equals("chest") || id.getPath().equals("barrel") || id.getPath().equals("limited_barrel_1"));
        if (!supported && id != null && id.getNamespace().equals("sophisticatedstorage")) report("UNSUPPORTED_BLOCK", id.toString());
        return supported;
    }
    public static Object upgradeInventory(BlockEntity block, boolean requireRemoteLinkCheck) {
        if (!isSupportedBlock(block)) return null;
        try {
            var type = block.getClass();
            if (Boolean.TRUE.equals(type.getMethod("isLinked").invoke(block))) { report("CONTROLLER_LINKED", type.getName()); return null; }
            if (!(type.getMethod("getControllerPos").invoke(block) instanceof Optional<?> position) || position.isPresent()) { report("CONTROLLER_LINKED", type.getName()); return null; }
            try {
                if (!Boolean.FALSE.equals(type.getMethod("isLinkedStorage").invoke(block))) { report("REMOTE_LINKED", type.getName()); return null; }
            } catch (NoSuchMethodException absent) {
                if (requireRemoteLinkCheck) { report("REMOTE_LINK_UNKNOWN", type.getName()); return null; }
            }
            Object wrapper = type.getMethod("getStorageWrapper").invoke(block);
            return wrapper.getClass().getMethod("getUpgradeHandler").invoke(wrapper);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            report("REFLECTION_FAILED", block.getClass().getName() + "/" + failure.getClass().getSimpleName());
            return null;
        }
    }
}
