package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.block.entity.BlockEntity;

/**
 * Associates scanners with a volume during network analysis. Hopper events then
 * use identity-map lookups only, and snapshots never walk a Storage graph.
 * All calls happen on the server thread.
 */
public final class TomScannerTelemetry {
    private static final long ACTIVE_WINDOW_TICKS = 1_200;
    private static final long PRUNE_INTERVAL_TICKS = 200;
    private static final Map<TomNetworkCache.NetworkIdentity, Set<DigitalItemStorage>> TARGETS_BY_NETWORK
            = new IdentityHashMap<>();
    private static final Map<DigitalItemStorage, List<TomNetworkCache.NetworkIdentity>> NETWORKS_BY_TARGET
            = new WeakHashMap<>();
    private static final Map<DigitalItemStorage, Map<BlockEntity, Entry>> ENTRIES_BY_TARGET
            = new IdentityHashMap<>();
    private static long lastPruneTick;

    private TomScannerTelemetry() {
    }

    static void associate(
            DigitalItemStorage target,
            TomNetworkCache.NetworkIdentity network
    ) {
        List<TomNetworkCache.NetworkIdentity> associated = NETWORKS_BY_TARGET.computeIfAbsent(
                target,
                ignored -> new ArrayList<>()
        );
        if (associated.contains(network)) {
            return;
        }
        Set<DigitalItemStorage> targets = TARGETS_BY_NETWORK.computeIfAbsent(
                network,
                ignored -> Collections.newSetFromMap(new IdentityHashMap<>())
        );
        targets.add(target);
        associated.add(network);
    }

    public static void record(
            BlockEntity hopper,
            Storage<ItemVariant> source,
            Storage<ItemVariant> destination,
            int consecutiveFailures
    ) {
        if (hopper.getWorld() == null || TARGETS_BY_NETWORK.isEmpty()) {
            return;
        }
        long tick = hopper.getWorld().getTime();
        Set<DigitalItemStorage> sourceTargets = targetsFor(source);
        Set<DigitalItemStorage> destinationTargets = targetsFor(destination);
        updateTargets(sourceTargets, null, hopper, tick, consecutiveFailures);
        updateTargets(destinationTargets, sourceTargets, hopper, tick, consecutiveFailures);
    }

    private static Set<DigitalItemStorage> targetsFor(Storage<ItemVariant> storage) {
        TomNetworkCache.NetworkIdentity network = TomNetworkCache.networkFor(storage);
        return network == null ? null : TARGETS_BY_NETWORK.get(network);
    }

    private static void updateTargets(
            Set<DigitalItemStorage> targets,
            Set<DigitalItemStorage> alreadyUpdated,
            BlockEntity hopper,
            long tick,
            int consecutiveFailures
    ) {
        if (targets == null) {
            return;
        }
        for (DigitalItemStorage target : targets) {
            if (alreadyUpdated != null && alreadyUpdated.contains(target)) {
                continue;
            }
            Map<BlockEntity, Entry> entries = ENTRIES_BY_TARGET.computeIfAbsent(
                    target,
                    ignored -> new IdentityHashMap<>()
            );
            Entry entry = entries.computeIfAbsent(hopper, ignored -> new Entry());
            if (entry.lastTick > 0 && tick > entry.lastTick) {
                entry.intervalTotal += tick - entry.lastTick;
                entry.intervalSamples++;
            }
            entry.lastTick = tick;
            entry.consecutiveFailures = consecutiveFailures;
        }
    }

    static Snapshot snapshot(DigitalItemStorage target, long currentTick) {
        Map<BlockEntity, Entry> entries = ENTRIES_BY_TARGET.get(target);
        if (entries == null || entries.isEmpty()) {
            return new Snapshot(0, 0, 0);
        }
        int active = 0;
        int failing = 0;
        long intervalTotal = 0;
        long intervalSamples = 0;
        for (Entry entry : entries.values()) {
            if (currentTick - entry.lastTick > ACTIVE_WINDOW_TICKS) {
                continue;
            }
            active++;
            if (entry.consecutiveFailures >= 3) {
                failing++;
            }
            intervalTotal += entry.intervalTotal;
            intervalSamples += entry.intervalSamples;
        }
        int averageInterval = intervalSamples == 0 ? 0 : (int) Math.max(1, intervalTotal / intervalSamples);
        return new Snapshot(active, failing, averageInterval);
    }

    static void tick(long currentTick) {
        if (currentTick - lastPruneTick < PRUNE_INTERVAL_TICKS) {
            return;
        }
        lastPruneTick = currentTick;
        TARGETS_BY_NETWORK.entrySet().removeIf(entry -> {
            BlockEntity connector = entry.getKey().connector().get();
            return connector == null || connector.isRemoved();
        });
        NETWORKS_BY_TARGET.values().forEach(networks -> networks.removeIf(
                identity -> !TARGETS_BY_NETWORK.containsKey(identity)
        ));
        NETWORKS_BY_TARGET.values().removeIf(List::isEmpty);
        ENTRIES_BY_TARGET.values().removeIf(entries -> {
            entries.values().removeIf(entry -> currentTick - entry.lastTick > ACTIVE_WINDOW_TICKS);
            return entries.isEmpty();
        });
    }

    static void clear() {
        TARGETS_BY_NETWORK.clear();
        NETWORKS_BY_TARGET.clear();
        ENTRIES_BY_TARGET.clear();
        lastPruneTick = 0;
    }

    record Snapshot(int activeScanners, int failingScanners, int averageIntervalTicks) {
    }

    private static final class Entry {
        private long lastTick;
        private long intervalTotal;
        private long intervalSamples;
        private int consecutiveFailures;
    }
}
