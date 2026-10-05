package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.NetworkServices;
import dev.kehai.digitalstorage.platform.fabric.tom.TomMigrationManager;
import dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkAnalysis;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Adapts shared screen operations to the Fabric Tom network and migration lifecycle. */
public final class FabricNetworkServices implements NetworkServices.Backend {
    public FabricNetworkServices() { dev.kehai.digitalstorage.optimization.BatchTransfers.install(new dev.kehai.digitalstorage.platform.fabric.tom.FabricBatchNetwork()); }
    @Override
    public NetworkAnalysis.Report analyze(BlockEntity accessor) {
        return accessor instanceof DigitalStorageAccessorBlockEntity digital ? TomNetworkAnalysis.analyze(digital)
                : NetworkAnalysis.Report.unavailable();
    }

    @Override
    public NetworkServices.StartResult start(ServerPlayer player, BlockEntity accessor, NetworkAnalysis.Report report) {
        return accessor instanceof DigitalStorageAccessorBlockEntity digital ? TomMigrationManager.start(player, digital, report)
                : NetworkServices.StartResult.NO_NETWORK;
    }

    @Override
    public boolean cancel(ServerPlayer player, UUID volumeId) { return TomMigrationManager.cancel(player, volumeId); }

    @Override
    public MigrationTask.Status status(UUID volumeId) { return TomMigrationManager.status(volumeId); }
}
