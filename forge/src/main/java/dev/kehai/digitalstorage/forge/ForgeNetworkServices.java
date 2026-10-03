package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.NetworkServices;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Conservative unavailable result until Forge Tom endpoints are installed. */
public final class ForgeNetworkServices implements NetworkServices.Backend {
    public NetworkAnalysis.Report analyze(BlockEntity accessor) { return NetworkAnalysis.Report.unavailable(); }
    public NetworkServices.StartResult start(ServerPlayer player, BlockEntity accessor, NetworkAnalysis.Report report) {
        return NetworkServices.StartResult.NO_NETWORK;
    }
    public boolean cancel(ServerPlayer player, UUID volumeId) { return false; }
    public MigrationTask.Status status(UUID volumeId) { return MigrationTask.Status.idle(); }
}
