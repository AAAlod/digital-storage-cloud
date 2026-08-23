package dev.kehai.digitalstorage.screen;

import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.optimization.TomMigrationManager;
import dev.kehai.digitalstorage.optimization.TomNetworkCache;
import dev.kehai.digitalstorage.optimization.TomNetworkAnalysis;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.StorageVolume;
import dev.kehai.digitalstorage.storage.VolumeManagementService;
import dev.kehai.digitalstorage.upgrade.DigitalStorageUpgradeService;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class DigitalStorageScreenHandler extends net.minecraft.screen.ScreenHandler {
    public static final Identifier STATE_PACKET_ID = DigitalStorageMod.id("screen_state");
    public static final Identifier CREATE_VOLUME_PACKET_ID = DigitalStorageMod.id("create_volume");
    public static final Identifier MANAGE_VOLUME_PACKET_ID = DigitalStorageMod.id("manage_volume");
    public static final int RENAME_VOLUME_ACTION = 0;
    public static final int DELETE_VOLUME_ACTION = 1;
    private static final int NETWORK_ANALYSIS_COOLDOWN_TICKS = 40;
    public static final int UPGRADE_BUTTON_ID = 0;
    public static final int CLEAR_BINDING_BUTTON_ID = 1;
    public static final int MIGRATION_BUTTON_ID = 2;
    public static final int NETWORK_ANALYSIS_BUTTON_ID = 3;
    public static final int BIND_VOLUME_BUTTON_BASE = 100;

    private final PlayerInventory playerInventory;
    private final BlockPos blockPos;
    private DigitalStorageScreenState state;
    private Text status = Text.empty();
    private boolean statusSuccessful;
    private TomNetworkAnalysis.Report networkReport;
    private boolean migrationWasActive;
    private long lastContentVersion = Long.MIN_VALUE;
    private long nextStateSyncTick;
    private long nextNetworkAnalysisTick;

    public static void registerNetworking() {
        ServerPlayNetworking.registerGlobalReceiver(
                CREATE_VOLUME_PACKET_ID,
                (server, player, networkHandler, buf, responseSender) -> {
                    int requestedSyncId = buf.readVarInt();
                    String requestedName = buf.readString(StorageVolume.MAX_NAME_LENGTH);
                    server.execute(() -> {
                        if (player.currentScreenHandler instanceof DigitalStorageScreenHandler screenHandler
                                && screenHandler.syncId == requestedSyncId) {
                            screenHandler.createVolume(player, requestedName);
                        }
                    });
                }
        );
        ServerPlayNetworking.registerGlobalReceiver(
                MANAGE_VOLUME_PACKET_ID,
                (server, player, networkHandler, buf, responseSender) -> {
                    int requestedSyncId = buf.readVarInt();
                    int action = buf.readVarInt();
                    java.util.UUID volumeId = buf.readUuid();
                    String requestedName = buf.readString(StorageVolume.MAX_NAME_LENGTH);
                    server.execute(() -> {
                        if (player.currentScreenHandler instanceof DigitalStorageScreenHandler screenHandler
                                && screenHandler.syncId == requestedSyncId) {
                            screenHandler.manageVolume(player, action, volumeId, requestedName);
                        }
                    });
                }
        );
    }

    public DigitalStorageScreenHandler(int syncId, PlayerInventory playerInventory, PacketByteBuf openingData) {
        super(DigitalStorageMod.DIGITAL_STORAGE_SCREEN_HANDLER, syncId);
        this.playerInventory = playerInventory;
        this.blockPos = openingData.readBlockPos();
        this.state = DigitalStorageScreenState.read(openingData);
    }

    public DigitalStorageScreenHandler(
            int syncId,
            PlayerInventory playerInventory,
            DigitalStorageAccessorBlockEntity blockEntity
    ) {
        super(DigitalStorageMod.DIGITAL_STORAGE_SCREEN_HANDLER, syncId);
        this.playerInventory = playerInventory;
        this.blockPos = blockEntity.getPos().toImmutable();
        this.state = captureServerState(Text.empty());
        rememberContentVersion(blockEntity);
    }

    public DigitalStorageScreenState state() {
        return state;
    }

    public void applySyncedState(DigitalStorageScreenState syncedState) {
        if (playerInventory.player.getWorld().isClient) {
            state = syncedState;
        }
    }

    @Override
    public boolean onButtonClick(PlayerEntity player, int id) {
        if (!(player instanceof ServerPlayerEntity serverPlayer) || !canUse(player)) {
            return false;
        }

        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        if (blockEntity == null) {
            status = Text.translatable("screen.digitalstorage.error.unavailable");
            statusSuccessful = false;
            sendServerState();
            return true;
        }

        if (id == CLEAR_BINDING_BUTTON_ID) {
            DigitalStorageAccessorBlockEntity.BindResult result = blockEntity.clearBinding(serverPlayer);
            statusSuccessful = result == DigitalStorageAccessorBlockEntity.BindResult.SUCCESS;
            status = Text.translatable(statusSuccessful
                    ? "screen.digitalstorage.binding_cleared"
                    : "screen.digitalstorage.binding_failed", result.name());
            if (statusSuccessful) {
                networkReport = null;
            }
            sendServerState();
            return true;
        }

        if (id == MIGRATION_BUTTON_ID) {
            StorageVolume volume = blockEntity.getVolume();
            if (volume == null || !volume.ownerId().equals(serverPlayer.getUuid())) {
                status = Text.translatable("screen.digitalstorage.error.not_owner");
                statusSuccessful = false;
                sendServerState();
                return true;
            }
            TomMigrationManager.Status migration = TomMigrationManager.status(volume.id());
            if (migration.active()) {
                statusSuccessful = TomMigrationManager.cancel(serverPlayer, volume.id());
                status = Text.translatable(statusSuccessful
                        ? "screen.digitalstorage.migration.cancelled"
                        : "screen.digitalstorage.migration.cancel_failed");
            } else {
                networkReport = TomNetworkAnalysis.analyze(blockEntity);
                TomMigrationManager.StartResult result = TomMigrationManager.start(
                        serverPlayer, blockEntity, networkReport
                );
                statusSuccessful = result == TomMigrationManager.StartResult.STARTED;
                migrationWasActive = statusSuccessful;
                status = result == TomMigrationManager.StartResult.DUPLICATE_TARGET_ENDPOINTS
                        ? Text.translatable("screen.digitalstorage.migration.duplicate_target")
                        : Text.translatable(
                                statusSuccessful
                                        ? "screen.digitalstorage.migration.started"
                                        : "screen.digitalstorage.migration.start_failed",
                                result.name()
                        );
            }
            sendServerState();
            return true;
        }

        if (id == NETWORK_ANALYSIS_BUTTON_ID) {
            long tick = serverPlayer.getServerWorld().getTime();
            if (tick < nextNetworkAnalysisTick) {
                status = Text.translatable(
                        "screen.digitalstorage.network.cooldown",
                        nextNetworkAnalysisTick - tick
                );
                statusSuccessful = false;
                sendServerState();
                return true;
            }
            nextNetworkAnalysisTick = tick + NETWORK_ANALYSIS_COOLDOWN_TICKS;
            networkReport = TomNetworkAnalysis.analyze(blockEntity);
            status = Text.translatable("screen.digitalstorage.network.refreshed");
            statusSuccessful = true;
            sendServerState();
            return true;
        }

        if (id >= BIND_VOLUME_BUTTON_BASE) {
            int volumeIndex = id - BIND_VOLUME_BUTTON_BASE;
            java.util.List<StorageVolume> owned = dev.kehai.digitalstorage.storage.DigitalStorageState
                    .get(serverPlayer.getServerWorld())
                    .volumes(serverPlayer.getUuid());
            if (volumeIndex < 0 || volumeIndex >= owned.size()) {
                return false;
            }
            StorageVolume selected = owned.get(volumeIndex);
            DigitalStorageAccessorBlockEntity.BindResult result = blockEntity.bind(serverPlayer, selected.id());
            statusSuccessful = result == DigitalStorageAccessorBlockEntity.BindResult.SUCCESS;
            status = Text.translatable(
                    statusSuccessful ? "screen.digitalstorage.binding_bound" : "screen.digitalstorage.binding_failed",
                    statusSuccessful ? selected.name() : result.name()
            );
            if (statusSuccessful) {
                networkReport = null;
            }
            sendServerState();
            return true;
        }

        if (id != UPGRADE_BUTTON_ID) {
            return false;
        }

        StorageVolume volume = blockEntity.getVolume();
        if (volume == null) {
            status = Text.translatable("screen.digitalstorage.error.unavailable");
            statusSuccessful = false;
            sendServerState();
            return true;
        }
        if (!volume.ownerId().equals(serverPlayer.getUuid())) {
            status = Text.translatable("screen.digitalstorage.error.not_owner");
            statusSuccessful = false;
            sendServerState();
            return true;
        }
        DigitalStorageRecord record = volume.record();
        DigitalStorageUpgradeService.UpgradeResult result = DigitalStorageUpgradeService.tryUpgrade(
                serverPlayer,
                volume.id(),
                record
        );
        status = result.message();
        statusSuccessful = result.success();
        networkReport = null;
        sendServerState();
        return true;
    }

    @Override
    public void sendContentUpdates() {
        super.sendContentUpdates();
        if (!(playerInventory.player instanceof ServerPlayerEntity serverPlayer)) {
            return;
        }
        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        if (blockEntity == null) {
            return;
        }
        long tick = serverPlayer.getServerWorld().getTime();
        StorageVolume volume = blockEntity.getVolume();
        long contentVersion = volume == null
                ? Long.MIN_VALUE
                : volume.record().storage().contentVersion();
        boolean migrationActive = volume != null && TomMigrationManager.status(volume.id()).active();
        boolean topologyInvalid = networkReport != null && networkReport.available()
                && !TomNetworkCache.isCurrent(networkReport.topology());
        boolean migrationFinished = migrationWasActive && !migrationActive;
        boolean contentChanged = contentVersion != lastContentVersion;
        int interval = migrationActive ? 4 : 8;
        if (!topologyInvalid && !migrationFinished
                && (!contentChanged || tick < nextStateSyncTick)
                && (!migrationActive || tick < nextStateSyncTick)) {
            return;
        }

        DigitalStorageScreenState updated = captureServerState(status).withStatus(status, statusSuccessful);
        lastContentVersion = contentVersion;
        nextStateSyncTick = tick + interval;
        if (!Objects.equals(updated, state)) {
            state = updated;
            sendStatePacket(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        if (player.getWorld().isClient) {
            return true;
        }
        if (!(player.getWorld() instanceof ServerWorld) || player.squaredDistanceTo(
                blockPos.getX() + 0.5,
                blockPos.getY() + 0.5,
                blockPos.getZ() + 0.5
        ) > 64.0) {
            return false;
        }
        return getServerBlockEntity() != null;
    }

    private DigitalStorageScreenState captureServerState(Text message) {
        if (!(playerInventory.player instanceof ServerPlayerEntity serverPlayer)) {
            return state;
        }
        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        if (blockEntity == null) {
            return state;
        }
        return DigitalStorageScreenState.capture(
                serverPlayer,
                blockEntity,
                message,
                captureNetworkDiagnostic(blockEntity, serverPlayer.getServerWorld())
        );
    }

    private DigitalStorageScreenState.NetworkDiagnostic captureNetworkDiagnostic(
            DigitalStorageAccessorBlockEntity blockEntity,
            ServerWorld world
    ) {
        StorageVolume volume = blockEntity.getVolume();
        if (volume == null) {
            return DigitalStorageScreenState.NetworkDiagnostic.unavailable();
        }
        TomMigrationManager.Status migration = TomMigrationManager.status(volume.id());
        boolean migrationActive = migration.active();
        if (networkReport != null && networkReport.available()
                && !TomNetworkCache.isCurrent(networkReport.topology())) {
            networkReport = null;
        }
        if (networkReport == null || (migrationWasActive && !migrationActive)) {
            networkReport = TomNetworkAnalysis.analyze(blockEntity);
        }
        migrationWasActive = migrationActive;
        return DigitalStorageScreenState.NetworkDiagnostic.from(networkReport, migration);
    }

    private void sendServerState() {
        if (playerInventory.player instanceof ServerPlayerEntity serverPlayer) {
            state = captureServerState(status).withStatus(status, statusSuccessful);
            DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
            if (blockEntity != null) {
                rememberContentVersion(blockEntity);
            }
            nextStateSyncTick = serverPlayer.getServerWorld().getTime() + 8;
            sendStatePacket(serverPlayer);
        }
    }

    private void rememberContentVersion(DigitalStorageAccessorBlockEntity blockEntity) {
        StorageVolume volume = blockEntity.getVolume();
        lastContentVersion = volume == null
                ? Long.MIN_VALUE
                : volume.record().storage().contentVersion();
    }

    private void createVolume(ServerPlayerEntity player, String requestedName) {
        if (!canUse(player)) {
            return;
        }
        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        if (blockEntity == null || blockEntity.isBound() || !blockEntity.controllerId()
                .map(player.getUuid()::equals)
                .orElse(true)) {
            status = Text.translatable("screen.digitalstorage.error.unavailable");
            statusSuccessful = false;
            sendServerState();
            return;
        }

        String name = requestedName.strip();
        if (name.isEmpty()) {
            status = Text.translatable("screen.digitalstorage.volume_name_required");
            statusSuccessful = false;
            sendServerState();
            return;
        }

        int limit = DigitalStorageConfig.get().defaultVolumesPerPlayer;
        StorageVolume created = DigitalStorageState.get(player.getServerWorld())
                .createVolume(player.getUuid(), name, limit)
                .orElse(null);
        if (created == null) {
            status = Text.translatable("command.digitalstorage.volume.limit", limit);
            statusSuccessful = false;
        } else {
            status = Text.translatable("screen.digitalstorage.volume_created", created.name());
            statusSuccessful = true;
        }
        sendServerState();
    }

    private void manageVolume(ServerPlayerEntity player, int action, java.util.UUID volumeId, String requestedName) {
        if (!canConfigureUnbound(player)) {
            status = Text.translatable("screen.digitalstorage.error.unavailable");
            statusSuccessful = false;
            sendServerState();
            return;
        }

        DigitalStorageState cloud = DigitalStorageState.get(player.getServerWorld());
        if (!cloud.ownsVolume(player.getUuid(), volumeId)) {
            status = Text.translatable("screen.digitalstorage.volume_manage_not_owned");
            statusSuccessful = false;
        } else if (action == RENAME_VOLUME_ACTION) {
            String name = requestedName.strip();
            statusSuccessful = !name.isEmpty() && cloud.renameVolume(player.getUuid(), volumeId, name);
            status = Text.translatable(statusSuccessful
                    ? "screen.digitalstorage.volume_renamed"
                    : "screen.digitalstorage.volume_rename_failed");
        } else if (action == DELETE_VOLUME_ACTION) {
            VolumeManagementService.DeleteResult result = VolumeManagementService.delete(
                    cloud,
                    player.getUuid(),
                    volumeId
            );
            statusSuccessful = result == VolumeManagementService.DeleteResult.SUCCESS;
            status = Text.translatable(switch (result) {
                case SUCCESS -> "screen.digitalstorage.volume_deleted";
                case MOUNTED -> "screen.digitalstorage.volume_delete_mounted";
                case NOT_OWNED_OR_MISSING -> "screen.digitalstorage.volume_manage_not_owned";
                case NOT_EMPTY -> "screen.digitalstorage.volume_delete_failed";
            });
        } else {
            statusSuccessful = false;
            status = Text.translatable("screen.digitalstorage.error.unavailable");
        }
        sendServerState();
    }

    private boolean canConfigureUnbound(ServerPlayerEntity player) {
        if (!canUse(player)) {
            return false;
        }
        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        return blockEntity != null
                && !blockEntity.isBound()
                && blockEntity.controllerId().map(player.getUuid()::equals).orElse(true);
    }

    private void sendStatePacket(ServerPlayerEntity player) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(syncId);
        state.write(buf);
        ServerPlayNetworking.send(player, STATE_PACKET_ID, buf);
    }

    private DigitalStorageAccessorBlockEntity getServerBlockEntity() {
        if (!(playerInventory.player.getWorld() instanceof ServerWorld serverWorld)
                || !(serverWorld.getBlockEntity(blockPos) instanceof DigitalStorageAccessorBlockEntity blockEntity)) {
            return null;
        }
        return blockEntity;
    }
}
