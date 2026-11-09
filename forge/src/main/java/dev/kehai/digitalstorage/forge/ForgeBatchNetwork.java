package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.optimization.BatchTransfer;
import dev.kehai.digitalstorage.optimization.BatchTransfers;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.forge.tom.ForgeTomTopology;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.Direction;
import net.minecraftforge.items.IItemHandler;

public final class ForgeBatchNetwork implements BatchTransfers.Backend {
    public BatchTransfer.Route open(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        if (!(accessor instanceof ForgeAccessorBlockEntity)) return null;
        var volume = accessor.getVolume();
        var session = ForgeTransferSessions.get(player.getServer());
        if (!session.recoveryAvailable()) throw new IllegalStateException("recovery_required");
        for (var direction : Direction.values()) {
            var position = accessor.getBlockPos().relative(direction);
            if (!accessor.getLevel().hasChunkAt(position)) continue;
            var neighbor = accessor.getLevel().getBlockEntity(position);
            if (!(neighbor instanceof com.tom.storagemod.tile.InventoryConnectorBlockEntity connector)) continue;
            java.util.function.Supplier<IItemHandler> current = () -> connector.isRemoved()
                    || !connector.getLevel().hasChunkAt(position) || connector.getLevel().getBlockEntity(position) != connector
                    ? null : connector.getInventory().orElse(null);
            var root = current.get(); if (root == null) continue;
            if (!(root instanceof dev.kehai.digitalstorage.forge.tom.ForgeTomEndpoints.TopologyVersion versioned)) continue;
            var report = ForgeTomTopology.analyze(volume.record(), current);
            var origin = new ForgeInventoryTransferExecutor.Origin(true, player.serverLevel().dimension().location().toString(),
                    accessor.getBlockPos().asLong(), position.asLong());
            var executor = session.executorAllowingPending(player.getUUID(), volume.id(), origin);
            var ports = new ArrayList<BatchTransfer.Port>(ForgeTomTopology.physicalHandlers(root).stream()
                    .map(handler -> (BatchTransfer.Port) new Port(handler, player.getUUID(), volume.id(), session.recovery(),
                            () -> DigitalStorageState.get(player.serverLevel()).flushNow(), executor)).toList());
            var soph = safeDirectSoph(connector, root);
            if (soph != null) for (var handler : soph) ports.add(new Port(handler, player.getUUID(), volume.id(), session.recovery(),
                    () -> DigitalStorageState.get(player.serverLevel()).flushNow(), executor));
            // Capture after analysis/refresh: opening the route must not invalidate its own fast path.
            long version = versioned.digitalstorage$topologyVersion();
            // Covers accessor/binding/connector and Tom rebuilds, not direct third-party removal before Tom rebuilds.
            java.util.function.BooleanSupplier fastCurrent = () -> !accessor.isRemoved() && player.serverLevel() == accessor.getLevel()
                    && accessor.getLevel().hasChunkAt(accessor.getBlockPos())
                    && accessor.getLevel().getBlockEntity(accessor.getBlockPos()) == accessor
                    && accessor.getVolume() == volume && volume.ownerId().equals(player.getUUID())
                    && current.get() == root && versioned.digitalstorage$topologyVersion() == version;
            return new BatchTransfer.Route(ports, () -> fastCurrent.getAsBoolean() && report.topology().isCurrent()
                    && (soph == null || sameSoph(soph, safeDirectSoph(connector, current.get()))), fastCurrent, position.toShortString());
        }
        return null;
    }
    private static boolean sameSoph(java.util.List<IItemHandler> a, java.util.List<IItemHandler> b) {
        if (b == null || a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }
    static java.util.List<IItemHandler> safeDirectSoph(com.tom.storagemod.tile.InventoryConnectorBlockEntity connector, IItemHandler root) {
        if (root == null || connector.isRemoved()) return null;
        var parts = ForgeTomTopology.allPhysicalHandlers(root);
        var candidates = parts.stream().filter(part -> part.getClass().getName().equals(
                "net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler")).toList();
        if (candidates.isEmpty()) {
            var level = connector.getLevel();
            if (level != null) for (var direction : Direction.values()) {
                var pos = connector.getBlockPos().relative(direction);
                if (level.hasChunkAt(pos) && dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.isSupportedBlock(level.getBlockEntity(pos))) {
                    dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.report("HANDLER_UNSUPPORTED", "forge"); break;
                }
            }
            return null;
        }
        for (var part : parts) if (candidates.stream().noneMatch(candidate -> part == candidate) && !ForgeTomTopology.trustedPhysical(part)) return null;
        var level = connector.getLevel();
        if (level == null) return null;
        var matched = new java.util.IdentityHashMap<IItemHandler, net.minecraft.core.BlockPos>();
        var allowed = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<IItemHandler, Boolean>());
        var denied = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<IItemHandler, Boolean>());
        for (var block : dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.connectedContainers(connector, tomRange())) {
            var pos = block.getBlockPos();
            var mods = net.minecraftforge.fml.ModList.get();
            boolean verifiedLegacy = dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.knownLegacy(
                    mods.getModContainerById("sophisticatedstorage").map(c -> c.getModInfo().getVersion().toString()).orElse(""),
                    mods.getModContainerById("sophisticatedcore").map(c -> c.getModInfo().getVersion().toString()).orElse(""), false, block.getClass().getClassLoader());
            var upgrades = dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.upgradeInventory(block, !verifiedLegacy);
            boolean eligible = upgrades instanceof IItemHandler;
            if (upgrades instanceof IItemHandler upgradeHandler) for (int i = 0; i < upgradeHandler.getSlots(); i++) {
                var upgrade = upgradeHandler.getStackInSlot(i);
                if (!upgrade.isEmpty() && !dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.isStackUpgrade(upgrade.getItem())) { dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.report("UPGRADE_NOT_ALLOWED", block.getClass().getName()); eligible = false; break; }
            }
            if (block == null) continue;
            for (var side : Direction.values()) {
                var direct = block.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
                for (var candidate : candidates) if (direct == candidate) {
                    var prior = matched.putIfAbsent(candidate, pos);
                    if (prior != null && !prior.equals(pos)
                            && !dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.sameDoubleChest(level.getBlockEntity(prior), block)) return null;
                    if (!eligible) { denied.add(candidate); allowed.remove(candidate); }
                    else if (!denied.contains(candidate)) allowed.add(candidate);
                }
            }
        }
        if (matched.size() != candidates.size() || new java.util.HashSet<>(matched.values()).size() != candidates.size()) { dev.kehai.digitalstorage.optimization.SophisticatedEndpointGuard.report("HANDLER_MISMATCH", "forge"); return null; }
        var result = candidates.stream().filter(allowed::contains).toList();
        return result.isEmpty() ? null : result;
    }
    private static int tomRange() {
        return com.tom.storagemod.Config.get().invRange;
    }
    static final class Port implements BatchTransfer.Port {
        private final IItemHandler handler;
        private final java.util.UUID owner, volume;
        private final ForgeTransferRecovery recovery;
        private final Runnable flush;
        private final ForgeInventoryTransferExecutor importer;
        Port(IItemHandler handler, java.util.UUID owner, java.util.UUID volume, ForgeTransferRecovery recovery,
             Runnable flush, ForgeInventoryTransferExecutor importer) {
            this.handler = handler; this.owner = owner; this.volume = volume; this.recovery = recovery; this.flush = flush; this.importer = importer;
        }
        public Iterable<BatchTransfer.Entry> contents() {
            var result = new ArrayList<BatchTransfer.Entry>();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                var stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) result.add(new BatchTransfer.Entry(ItemKey.of(stack), stack.getCount()));
            }
            return result;
        }
        public BatchTransfer.Cursor cursor(boolean exporting) {
            return new BatchTransfer.Cursor() {
                int slot;
                public boolean hasNext() { return slot < handler.getSlots(); }
                public BatchTransfer.Result next(DigitalStorageRecord record, ItemKey key, long maximum) {
                    BatchTransfer.Result result;
                    if (exporting) result = ForgeBatchExport.move(record, key, maximum, handler, slot, owner, volume, recovery, flush);
                    else {
                        var moved = importer.move(new ForgeInventoryEndpoint.View(handler, slot), record.storage(), key, maximum);
                        result = new BatchTransfer.Result(moved.moved(), moved.stopDetail().isEmpty() ? "" : "recovery_required");
                    }
                    if (result.moved() == 0) slot++;
                    return result;
                }
            };
        }
    }
}
