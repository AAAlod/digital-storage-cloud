package dev.kehai.digitalstorage.forge.tom;

import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;

/** Server-thread observations. Recording looks up associated root handles, never scans slots or unwraps a graph. */
public final class ForgeScannerTelemetry {
    private static final Map<MinecraftServer, ForgeScannerTelemetry> SERVERS = new IdentityHashMap<>();
    private final Map<IItemHandler, List<Association>> roots = new WeakHashMap<>();
    private final Map<VolumeLedger, Map<BlockEntity, Entry>> targets = new WeakHashMap<>();
    public static ForgeScannerTelemetry get(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, ignored -> new ForgeScannerTelemetry());
    }
    public static void stopped(MinecraftServer server) { SERVERS.remove(server); }
    public void associate(VolumeLedger target, IItemHandler root, BooleanSupplier valid) {
        var associations = roots.computeIfAbsent(root, ignored -> new ArrayList<>());
        associations.removeIf(association -> association.target.get() == null || association.target.get() == target);
        associations.add(new Association(new WeakReference<>(target), valid));
    }
    public void forget(VolumeLedger target) {
        roots.values().forEach(list -> list.removeIf(association -> association.target.get() == target));
        targets.remove(target);
    }
    public void record(BlockEntity hopper, IItemHandler source, IItemHandler destination, long tick, int failures) {
        if (roots.isEmpty()) return;
        var seen = Collections.newSetFromMap(new IdentityHashMap<VolumeLedger, Boolean>());
        recordRoot(hopper, source, tick, failures, seen);
        recordRoot(hopper, destination, tick, failures, seen);
    }
    private void recordRoot(BlockEntity hopper, IItemHandler root, long tick, int failures, Set<VolumeLedger> seen) {
        var associations = roots.get(root);
        if (associations == null) return;
        for (var association : associations) {
            var target = association.target.get();
            if (target == null || !association.isValid() || !seen.add(target)) continue;
            var entry = targets.computeIfAbsent(target, ignored -> new WeakHashMap<>()).computeIfAbsent(hopper, ignored -> new Entry());
            if (entry.lastTick >= 0 && tick > entry.lastTick) {
                entry.intervalTotal += tick - entry.lastTick;
                entry.intervalSamples++;
            }
            entry.lastTick = tick;
            entry.failures = failures;
        }
    }
    public Snapshot snapshot(VolumeLedger target, long tick) {
        var entries = targets.get(target);
        if (entries == null) return new Snapshot(0, 0, 0);
        int active = 0;
        int failing = 0;
        long total = 0;
        long samples = 0;
        for (var item : entries.entrySet()) {
            var entry = item.getValue();
            if (item.getKey().isRemoved() || tick - entry.lastTick > 1200) continue;
            active++;
            if (entry.failures >= 3) failing++;
            total += entry.intervalTotal;
            samples += entry.intervalSamples;
        }
        return new Snapshot(active, failing, samples == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(1, total / samples)));
    }
    public void prune(long tick) {
        roots.values().forEach(list -> list.removeIf(association -> association.target.get() == null || !association.isValid()));
        roots.values().removeIf(List::isEmpty);
        targets.values().forEach(entries -> entries.entrySet().removeIf(entry -> entry.getKey().isRemoved() || tick - entry.getValue().lastTick > 1200));
        targets.values().removeIf(Map::isEmpty);
    }
    private record Association(WeakReference<VolumeLedger> target, BooleanSupplier valid) {
        boolean isValid() {
            try { return valid.getAsBoolean(); }
            catch (RuntimeException unavailable) { return false; }
        }
    }
    private static final class Entry {
        long lastTick = -1;
        long intervalTotal;
        long intervalSamples;
        int failures;
    }
    public record Snapshot(int active, int failing, int interval) { }
}
