package dev.kehai.digitalstorage.integration;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime confirmation that every required Tom integration hook is active. */
public final class TomIntegrationStatus {
    private static final String MIXIN_PREFIX = "dev.kehai.digitalstorage.mixin.";
    private static final Set<String> APPLIED_MIXINS = ConcurrentHashMap.newKeySet();
    private static volatile boolean advancedHopperSupportActive;

    private TomIntegrationStatus() {
    }

    public static void markMixinApplied(String mixinClassName) {
        APPLIED_MIXINS.add(mixinClassName);
    }

    public static void markAdvancedHopperSupportActive() {
        advancedHopperSupportActive = true;
    }

    public static Snapshot snapshot() {
        return new Snapshot(
                applied("BasicInventoryHopperBlockEntityMixin"),
                applied("AbstractInventoryHopperBlockEntityMixin"),
                applied("InventoryConnectorBlockEntityMixin"),
                applied("MergedStorageMixin"),
                applied("BlockEntityTypeAccessor"),
                advancedHopperSupportActive
        );
    }

    private static boolean applied(String simpleName) {
        return APPLIED_MIXINS.contains(MIXIN_PREFIX + simpleName);
    }

    public record Snapshot(
            boolean hopperOptimizationMixinApplied,
            boolean connectorStaggerMixinApplied,
            boolean topologyTrackingMixinApplied,
            boolean volumeDedupMixinApplied,
            boolean blockEntityTypeExtensionMixinApplied,
            boolean advancedHopperSupportActive
    ) {
        public boolean allActive() {
            return hopperOptimizationMixinApplied
                    && connectorStaggerMixinApplied
                    && topologyTrackingMixinApplied
                    && volumeDedupMixinApplied
                    && blockEntityTypeExtensionMixinApplied
                    && advancedHopperSupportActive;
        }
    }
}
