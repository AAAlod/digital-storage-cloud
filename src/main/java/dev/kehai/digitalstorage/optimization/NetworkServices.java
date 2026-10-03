package dev.kehai.digitalstorage.optimization;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Screen-facing network operations. The loader installs its Tom implementation during startup. */
public final class NetworkServices {
    private static Backend backend;

    private NetworkServices() {
    }

    public static void install(Backend implementation) { backend = Objects.requireNonNull(implementation); }
    public static Backend get() { return Objects.requireNonNull(backend, "Network backend has not been installed"); }

    public interface Backend {
        NetworkAnalysis.Report analyze(BlockEntity accessor);
        StartResult start(ServerPlayer player, BlockEntity accessor, NetworkAnalysis.Report report);
        boolean cancel(ServerPlayer player, UUID volumeId);
        MigrationTask.Status status(UUID volumeId);
    }

    public enum StartResult {
        STARTED, NOT_OWNER, NO_NETWORK, NOTHING_TO_MOVE, DUPLICATE_TARGET_ENDPOINTS, NETWORK_CHANGED, ALREADY_RUNNING, RECOVERY_REQUIRED
    }
}
