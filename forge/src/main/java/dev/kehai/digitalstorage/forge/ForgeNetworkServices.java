package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.NetworkServices;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Forge Tom discovery/analysis. Migration remains gated until its transfer guarantees are installed. */
public final class ForgeNetworkServices implements NetworkServices.Backend {
    public NetworkAnalysis.Report analyze(BlockEntity entity) {
        if (!(entity instanceof ForgeAccessorBlockEntity accessor) || accessor.getLevel() == null
                || accessor.getLevel().isClientSide) return NetworkAnalysis.Report.unavailable();
        for (var direction : net.minecraft.core.Direction.values()) {
            var neighborPosition = accessor.getBlockPos().relative(direction);
            if (!accessor.getLevel().hasChunkAt(neighborPosition)) continue;
            var neighbor = accessor.getLevel().getBlockEntity(neighborPosition);
            if (!(neighbor instanceof com.tom.storagemod.tile.InventoryConnectorBlockEntity connector)) continue;
            var reference = new java.lang.ref.WeakReference<>(connector);
            var world = accessor.getLevel();
            var position = connector.getBlockPos();
            try {
                var report = dev.kehai.digitalstorage.forge.tom.ForgeTomTopology.analyze(accessor.getRecord(), () -> {
                    var current = reference.get();
                    return current == null || current.isRemoved() || !world.hasChunkAt(position)
                            || world.getBlockEntity(position) != current ? null : current.getInventory().orElse(null);
                });
                if (report.available()) return report;
            } catch (RuntimeException failure) {
                dev.kehai.digitalstorage.DigitalStorage.LOGGER.debug("Forge Tom analysis unavailable at {}: {}",
                        position, failure.toString());
            }
        }
        return NetworkAnalysis.Report.unavailable();
    }
    public NetworkServices.StartResult start(ServerPlayer player, BlockEntity accessor, NetworkAnalysis.Report report) {
        return NetworkServices.StartResult.NO_NETWORK;
    }
    public boolean cancel(ServerPlayer player, UUID volumeId) { return false; }
    public MigrationTask.Status status(UUID volumeId) { return MigrationTask.Status.idle(); }
}
