package dev.kehai.digitalstorage.screen;

import dev.kehai.digitalstorage.DigitalStorageContent;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.optimization.NetworkServices;
import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.optimization.TopologyToken;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.StorageVolume;
import dev.kehai.digitalstorage.storage.VolumeManagementService;
import dev.kehai.digitalstorage.upgrade.DigitalStorageUpgradeService;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class DigitalStorageScreenHandler extends net.minecraft.world.inventory.AbstractContainerMenu {
    private static DigitalStorageScreenProtocol.StateSender stateSender;
    public static final int RENAME_VOLUME_ACTION = 0;
    public static final int DELETE_VOLUME_ACTION = 1;
    private static final int NETWORK_ANALYSIS_COOLDOWN_TICKS = 40;
    public static final int UPGRADE_BUTTON_ID = 0;
    public static final int CLEAR_BINDING_BUTTON_ID = 1;
    public static final int MIGRATION_BUTTON_ID = 2;
    public static final int NETWORK_ANALYSIS_BUTTON_ID = 3;
    public static final int SET_UNSTACKABLE_REJECT_BUTTON_ID = 4;
    public static final int SET_UNSTACKABLE_ACCEPT_BUTTON_ID = 5;
    public static final int BIND_VOLUME_BUTTON_BASE = 100;

    private final Inventory playerInventory;
    private final BlockPos blockPos;
    private DigitalStorageScreenState state;
    private Component status = Component.empty();
    private boolean statusSuccessful;
    private NetworkAnalysis.Report networkReport;
    private boolean migrationWasActive;
    private long lastContentVersion = Long.MIN_VALUE;
    private long lastPolicyVersion = Long.MIN_VALUE;
    private long lastMetadataVersion = Long.MIN_VALUE;
    private boolean lastServerAllowsUnstackableItems;
    private long nextStateSyncTick;
    private long nextNetworkAnalysisTick;
    private boolean initialStateSyncPending;
    // Advances only for an operation reply, never for inventory/topology broadcasts.
    private long responseRevision;

    /** Bound once by the loader during initialization, before any server menu opens. */
    public static void setStateSender(DigitalStorageScreenProtocol.StateSender sender) {
        stateSender = Objects.requireNonNull(sender, "sender");
    }

    /** The adapter must invoke requests on the server thread. */
    public static void handleRequest(ServerPlayer player, DigitalStorageScreenProtocol.CreateVolume request) {
        if (player.containerMenu instanceof DigitalStorageScreenHandler screenHandler
                && screenHandler.containerId == request.syncId()) {
            screenHandler.createVolume(player, request.name());
        }
    }

    public static void handleRequest(ServerPlayer player, DigitalStorageScreenProtocol.ManageVolume request) {
        if (player.containerMenu instanceof DigitalStorageScreenHandler screenHandler
                && screenHandler.containerId == request.syncId()) {
            screenHandler.manageVolume(player, request.action(), request.volumeId(), request.name());
        }
    }

    public static void handleRequest(ServerPlayer player, DigitalStorageScreenProtocol.VolumeIcon request) {
        if (player.containerMenu instanceof DigitalStorageScreenHandler handler
                && handler.containerId == request.syncId()) handler.setVolumeIcon(player, request);
    }

    private void setVolumeIcon(ServerPlayer player, DigitalStorageScreenProtocol.VolumeIcon request) {
        if (!stillValid(player)) return;
        var accessor = getServerBlockEntity();
        var volume = DigitalStorageState.get(player.serverLevel()).volume(request.volumeId()).orElse(null);
        boolean controls = accessor != null && accessor.controllerId().map(player.getUUID()::equals).orElse(true);
        var bound = accessor == null ? null : accessor.getVolume();
        boolean target = accessor != null && (!accessor.isBound() || bound != null && bound.id().equals(request.volumeId()));
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(request.itemId()).orElse(null);
        boolean possessed = request.itemId().equals(StorageVolume.DEFAULT_ICON);
        if (item != null && !possessed) {
            possessed = getCarried().is(item);
            for (int slot = 0; slot < player.getInventory().getContainerSize() && !possessed; slot++)
                possessed = player.getInventory().getItem(slot).is(item);
        }
        statusSuccessful = controls && target && volume != null && volume.ownerId().equals(player.getUUID())
                && item != null && item != net.minecraft.world.item.Items.AIR && possessed;
        if (statusSuccessful) volume.setIcon(request.itemId());
        status = Component.translatable(statusSuccessful ? "screen.digitalstorage.gui.icon_saved"
                : "screen.digitalstorage.gui.icon_rejected");
        sendServerState();
    }

    public DigitalStorageScreenHandler(int syncId, Inventory playerInventory, FriendlyByteBuf openingData) {
        super(DigitalStorageContent.screenType(), syncId);
        this.playerInventory = playerInventory;
        this.blockPos = openingData.readBlockPos();
        this.state = DigitalStorageScreenState.read(openingData);
    }

    public DigitalStorageScreenHandler(
            int syncId,
            Inventory playerInventory,
            DigitalStorageAccessorBlockEntity blockEntity
    ) {
        super(DigitalStorageContent.screenType(), syncId);
        this.playerInventory = playerInventory;
        this.blockPos = blockEntity.getBlockPos().immutable();
        this.state = captureServerState(Component.empty());
        rememberContentVersion(blockEntity);
        initialStateSyncPending = true;
    }

    public DigitalStorageScreenState state() {
        return state;
    }

    public void applySyncedState(DigitalStorageScreenState syncedState) {
        if (playerInventory.player.level().isClientSide) {
            state = syncedState;
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (!(player instanceof ServerPlayer serverPlayer) || !stillValid(player)) {
            return false;
        }

        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        if (blockEntity == null) {
            status = Component.translatable("screen.digitalstorage.error.unavailable");
            statusSuccessful = false;
            sendServerState();
            return true;
        }

        if (id == SET_UNSTACKABLE_REJECT_BUTTON_ID || id == SET_UNSTACKABLE_ACCEPT_BUTTON_ID) {
            StorageVolume volume = blockEntity.getVolume();
            if (volume == null || !volume.ownerId().equals(serverPlayer.getUUID())) {
                status = Component.translatable("screen.digitalstorage.error.not_owner");
                statusSuccessful = false;
                sendServerState();
                return true;
            }
            if (NetworkServices.get().status(volume.id()).active()) {
                status = Component.translatable("screen.digitalstorage.unstackables.error.migration_active");
                statusSuccessful = false;
                sendServerState();
                return true;
            }
            if (!DigitalStorageConfig.get().allowUnstackableItems) {
                status = Component.translatable("screen.digitalstorage.unstackables.error.server_disabled");
                statusSuccessful = false;
                sendServerState();
                return true;
            }

            boolean requestedValue = id == SET_UNSTACKABLE_ACCEPT_BUTTON_ID;
            volume.setAcceptUnstackableItems(requestedValue);
            networkReport = null;
            status = Component.translatable("screen.digitalstorage.unstackables.updated");
            statusSuccessful = true;
            sendServerState();
            return true;
        }

        if (id == CLEAR_BINDING_BUTTON_ID) {
            DigitalStorageAccessorBlockEntity.BindResult result = blockEntity.clearBinding(serverPlayer);
            statusSuccessful = result == DigitalStorageAccessorBlockEntity.BindResult.SUCCESS;
            status = Component.translatable(statusSuccessful
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
            if (volume == null || !volume.ownerId().equals(serverPlayer.getUUID())) {
                status = Component.translatable("screen.digitalstorage.error.not_owner");
                statusSuccessful = false;
                sendServerState();
                return true;
            }
            MigrationTask.Status migration = NetworkServices.get().status(volume.id());
            if (migration.active()) {
                statusSuccessful = NetworkServices.get().cancel(serverPlayer, volume.id());
                status = Component.translatable(statusSuccessful
                        ? "screen.digitalstorage.migration.cancelled"
                        : "screen.digitalstorage.migration.cancel_failed");
            } else {
                networkReport = NetworkServices.get().analyze(blockEntity);
                NetworkServices.StartResult result = NetworkServices.get().start(
                        serverPlayer, blockEntity, networkReport
                );
                statusSuccessful = result == NetworkServices.StartResult.STARTED;
                migrationWasActive = statusSuccessful;
                status = result == NetworkServices.StartResult.DUPLICATE_TARGET_ENDPOINTS
                        ? Component.translatable("screen.digitalstorage.migration.duplicate_target")
                        : result == NetworkServices.StartResult.RECOVERY_REQUIRED
                        ? Component.translatable("screen.digitalstorage.migration.recovery_required")
                        : Component.translatable(
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
            long tick = serverPlayer.serverLevel().getGameTime();
            if (tick < nextNetworkAnalysisTick) {
                status = Component.translatable(
                        "screen.digitalstorage.network.cooldown",
                        nextNetworkAnalysisTick - tick
                );
                statusSuccessful = false;
                sendServerState();
                return true;
            }
            nextNetworkAnalysisTick = tick + NETWORK_ANALYSIS_COOLDOWN_TICKS;
            networkReport = NetworkServices.get().analyze(blockEntity);
            status = Component.translatable("screen.digitalstorage.network.refreshed");
            statusSuccessful = true;
            sendServerState();
            return true;
        }

        if (id >= BIND_VOLUME_BUTTON_BASE) {
            int volumeIndex = id - BIND_VOLUME_BUTTON_BASE;
            java.util.List<StorageVolume> owned = dev.kehai.digitalstorage.storage.DigitalStorageState
                    .get(serverPlayer.serverLevel())
                    .volumes(serverPlayer.getUUID());
            if (volumeIndex < 0 || volumeIndex >= owned.size()) {
                return false;
            }
            StorageVolume selected = owned.get(volumeIndex);
            DigitalStorageAccessorBlockEntity.BindResult result = blockEntity.bind(serverPlayer, selected.id());
            statusSuccessful = result == DigitalStorageAccessorBlockEntity.BindResult.SUCCESS;
            status = Component.translatable(
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
            status = Component.translatable("screen.digitalstorage.error.unavailable");
            statusSuccessful = false;
            sendServerState();
            return true;
        }
        if (!volume.ownerId().equals(serverPlayer.getUUID())) {
            status = Component.translatable("screen.digitalstorage.error.not_owner");
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
    public void broadcastChanges() {
        super.broadcastChanges();
        if (!(playerInventory.player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        if (blockEntity == null) {
            return;
        }
        if (initialStateSyncPending) {
            initialStateSyncPending = false;
            nextStateSyncTick = serverPlayer.serverLevel().getGameTime() + 8;
            sendStatePacket(serverPlayer);
            return;
        }
        long tick = serverPlayer.serverLevel().getGameTime();
        StorageVolume volume = blockEntity.getVolume();
        long contentVersion = volume == null
                ? Long.MIN_VALUE
                : volume.record().storage().contentVersion();
        long policyVersion = volume == null
                ? Long.MIN_VALUE
                : volume.record().policyVersion();
        boolean serverAllowsUnstackableItems = DigitalStorageConfig.get().allowUnstackableItems;
        boolean migrationActive = volume != null && NetworkServices.get().status(volume.id()).active();
        boolean topologyInvalid = networkReport != null && networkReport.available()
                && !TopologyToken.isCurrent(networkReport.topology());
        boolean migrationFinished = migrationWasActive && !migrationActive;
        boolean contentChanged = contentVersion != lastContentVersion;
        boolean policyChanged = policyVersion != lastPolicyVersion;
        long metadataVersion = volume == null ? Long.MIN_VALUE : volume.metadataVersion();
        boolean metadataChanged = metadataVersion != lastMetadataVersion;
        boolean serverPolicyChanged = serverAllowsUnstackableItems != lastServerAllowsUnstackableItems;
        int interval = migrationActive ? 4 : 8;
        if (!topologyInvalid && !migrationFinished
                && (!(contentChanged || policyChanged || serverPolicyChanged || metadataChanged) || tick < nextStateSyncTick)
                && (!migrationActive || tick < nextStateSyncTick)) {
            return;
        }

        if (policyChanged || serverPolicyChanged) {
            networkReport = null;
        }
        DigitalStorageScreenState updated = captureServerState(status).withStatus(status, statusSuccessful, responseRevision);
        lastContentVersion = contentVersion;
        lastPolicyVersion = policyVersion;
        lastMetadataVersion = metadataVersion;
        lastServerAllowsUnstackableItems = serverAllowsUnstackableItems;
        nextStateSyncTick = tick + interval;
        if (!Objects.equals(updated, state)) {
            state = updated;
            sendStatePacket(serverPlayer);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) {
            return true;
        }
        if (!(player.level() instanceof ServerLevel) || player.distanceToSqr(
                blockPos.getX() + 0.5,
                blockPos.getY() + 0.5,
                blockPos.getZ() + 0.5
        ) > 64.0) {
            return false;
        }
        return getServerBlockEntity() != null;
    }

    private DigitalStorageScreenState captureServerState(Component message) {
        if (!(playerInventory.player instanceof ServerPlayer serverPlayer)) {
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
                captureNetworkDiagnostic(blockEntity, serverPlayer.serverLevel())
        );
    }

    private DigitalStorageScreenState.NetworkDiagnostic captureNetworkDiagnostic(
            DigitalStorageAccessorBlockEntity blockEntity,
            ServerLevel world
    ) {
        StorageVolume volume = blockEntity.getVolume();
        if (volume == null) {
            return DigitalStorageScreenState.NetworkDiagnostic.unavailable();
        }
        MigrationTask.Status migration = NetworkServices.get().status(volume.id());
        boolean migrationActive = migration.active();
        if (networkReport != null && networkReport.available()
                && !TopologyToken.isCurrent(networkReport.topology())) {
            networkReport = null;
        }
        if (networkReport == null || (migrationWasActive && !migrationActive)) {
            networkReport = NetworkServices.get().analyze(blockEntity);
        }
        migrationWasActive = migrationActive;
        return DigitalStorageScreenState.NetworkDiagnostic.from(networkReport, migration);
    }

    private void sendServerState() {
        if (playerInventory.player instanceof ServerPlayer serverPlayer) {
            responseRevision++;
            state = captureServerState(status).withStatus(status, statusSuccessful, responseRevision);
            DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
            if (blockEntity != null) {
                rememberContentVersion(blockEntity);
            }
            nextStateSyncTick = serverPlayer.serverLevel().getGameTime() + 8;
            sendStatePacket(serverPlayer);
        }
    }

    private void rememberContentVersion(DigitalStorageAccessorBlockEntity blockEntity) {
        StorageVolume volume = blockEntity.getVolume();
        lastContentVersion = volume == null
                ? Long.MIN_VALUE
                : volume.record().storage().contentVersion();
        lastPolicyVersion = volume == null
                ? Long.MIN_VALUE
                : volume.record().policyVersion();
        lastServerAllowsUnstackableItems = DigitalStorageConfig.get().allowUnstackableItems;
        lastMetadataVersion = volume == null ? Long.MIN_VALUE : volume.metadataVersion();
    }

    private void createVolume(ServerPlayer player, String requestedName) {
        if (!stillValid(player)) {
            return;
        }
        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        if (blockEntity == null || blockEntity.isBound() || !blockEntity.controllerId()
                .map(player.getUUID()::equals)
                .orElse(true)) {
            status = Component.translatable("screen.digitalstorage.error.unavailable");
            statusSuccessful = false;
            sendServerState();
            return;
        }

        String name = requestedName.strip();
        if (name.isEmpty()) {
            status = Component.translatable("screen.digitalstorage.volume_name_required");
            statusSuccessful = false;
            sendServerState();
            return;
        }

        int limit = DigitalStorageConfig.get().defaultVolumesPerPlayer;
        StorageVolume created = DigitalStorageState.get(player.serverLevel())
                .createVolume(player.getUUID(), name, limit)
                .orElse(null);
        if (created == null) {
            status = Component.translatable("command.digitalstorage.volume.limit", limit);
            statusSuccessful = false;
        } else {
            status = Component.translatable("screen.digitalstorage.volume_created", created.name());
            statusSuccessful = true;
        }
        sendServerState();
    }

    private void manageVolume(ServerPlayer player, int action, java.util.UUID volumeId, String requestedName) {
        if (!canConfigureUnbound(player)) {
            status = Component.translatable("screen.digitalstorage.error.unavailable");
            statusSuccessful = false;
            sendServerState();
            return;
        }

        DigitalStorageState cloud = DigitalStorageState.get(player.serverLevel());
        if (!cloud.ownsVolume(player.getUUID(), volumeId)) {
            status = Component.translatable("screen.digitalstorage.volume_manage_not_owned");
            statusSuccessful = false;
        } else if (action == RENAME_VOLUME_ACTION) {
            String name = requestedName.strip();
            statusSuccessful = !name.isEmpty() && cloud.renameVolume(player.getUUID(), volumeId, name);
            status = Component.translatable(statusSuccessful
                    ? "screen.digitalstorage.volume_renamed"
                    : "screen.digitalstorage.volume_rename_failed");
        } else if (action == DELETE_VOLUME_ACTION) {
            VolumeManagementService.DeleteResult result = VolumeManagementService.delete(
                    cloud,
                    player.getUUID(),
                    volumeId
            );
            statusSuccessful = result == VolumeManagementService.DeleteResult.SUCCESS;
            status = Component.translatable(switch (result) {
                case SUCCESS -> "screen.digitalstorage.volume_deleted";
                case MOUNTED -> "screen.digitalstorage.volume_delete_mounted";
                case NOT_OWNED_OR_MISSING -> "screen.digitalstorage.volume_manage_not_owned";
                case NOT_EMPTY -> "screen.digitalstorage.volume_delete_failed";
            });
        } else {
            statusSuccessful = false;
            status = Component.translatable("screen.digitalstorage.error.unavailable");
        }
        sendServerState();
    }

    private boolean canConfigureUnbound(ServerPlayer player) {
        if (!stillValid(player)) {
            return false;
        }
        DigitalStorageAccessorBlockEntity blockEntity = getServerBlockEntity();
        return blockEntity != null
                && !blockEntity.isBound()
                && blockEntity.controllerId().map(player.getUUID()::equals).orElse(true);
    }

    private void sendStatePacket(ServerPlayer player) {
        Objects.requireNonNull(stateSender, "Screen state sender was not initialized")
                .send(player, new DigitalStorageScreenProtocol.StateUpdate(containerId, state));
    }

    private DigitalStorageAccessorBlockEntity getServerBlockEntity() {
        if (!(playerInventory.player.level() instanceof ServerLevel serverWorld)
                || !(serverWorld.getBlockEntity(blockPos) instanceof DigitalStorageAccessorBlockEntity blockEntity)) {
            return null;
        }
        return blockEntity;
    }

    /** Called only by the declared private-world management fixture with a synthetic player. */
    public static void runResponseSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        var oldMenu = player.containerMenu;
        var oldSender = stateSender;
        var cloud = DigitalStorageState.get(player.serverLevel());
        var pos = accessor.getBlockPos();
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var replies = new java.util.ArrayList<DigitalStorageScreenState>();
        setStateSender((recipient, update) -> {
            if (recipient == player) replies.add(update.state());
            else oldSender.send(recipient, update);
        });
        StorageVolume created = null;
        try {
            var handler = new DigitalStorageScreenHandler(203, player.getInventory(), accessor);
            player.containerMenu = handler;
            handler.broadcastChanges();
            expectResponse(replies.size() == 1 && handler.state.responseRevision() == 0, "Initial sync is not a reply");
            handleRequest(player, new DigitalStorageScreenProtocol.CreateVolume(204, "GUI fixture"));
            expectResponse(replies.size() == 1, "Wrong menu request is ignored");
            handleRequest(player, new DigitalStorageScreenProtocol.CreateVolume(203, "GUI fixture"));
            expectResponse(handler.state.statusSuccessful() && handler.state.responseRevision() == 1, "Create reply");
            created = cloud.volumes(player.getUUID()).stream().filter(v -> v.name().equals("GUI fixture"))
                    .findFirst().orElseThrow();
            handleRequest(player, new DigitalStorageScreenProtocol.ManageVolume(203, RENAME_VOLUME_ACTION,
                    created.id(), "GUI renamed"));
            expectResponse(handler.state.statusSuccessful() && handler.state.responseRevision() == 2, "Rename reply");
            for (int revision = 3; revision <= 4; revision++) {
                handleRequest(player, new DigitalStorageScreenProtocol.ManageVolume(203, RENAME_VOLUME_ACTION,
                        created.id(), "GUI renamed"));
                expectResponse(!handler.state.statusSuccessful() && handler.state.responseRevision() == revision,
                        "Identical rejection still acknowledges a new operation");
            }
            int index = cloud.volumes(player.getUUID()).indexOf(created);
            handler.clickMenuButton(player, BIND_VOLUME_BUTTON_BASE + index);
            expectResponse(handler.state.accessorBound() && handler.state.responseRevision() == 5, "Binding reply");
            try (var transaction = dev.kehai.digitalstorage.storage.LedgerTransaction.open()) {
                created.record().storage().insert(dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE),
                        5, transaction);
                transaction.commit();
            }
            handler.nextStateSyncTick = 0;
            handler.broadcastChanges();
            expectResponse(handler.state.usedVariants() == 1 && handler.state.responseRevision() == 5,
                    "Content broadcast cannot acknowledge an operation");
            handler.clickMenuButton(player, CLEAR_BINDING_BUTTON_ID);
            expectResponse(!handler.state.accessorBound() && handler.state.responseRevision() == 6, "Clear reply");
            handleRequest(player, new DigitalStorageScreenProtocol.ManageVolume(203, DELETE_VOLUME_ACTION, created.id(), ""));
            expectResponse(!handler.state.statusSuccessful() && handler.state.responseRevision() == 7,
                    "Nonempty deletion rejected with a reply");
            try (var transaction = dev.kehai.digitalstorage.storage.LedgerTransaction.open()) {
                created.record().storage().extract(dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE),
                        5, transaction);
                transaction.commit();
            }
            handleRequest(player, new DigitalStorageScreenProtocol.ManageVolume(203, DELETE_VOLUME_ACTION, created.id(), ""));
            expectResponse(handler.state.statusSuccessful() && handler.state.responseRevision() == 8,
                    "Empty deletion reply");
            runIconSelfTest(player, accessor);
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Screen operation response fixture passed: menu identity, create, UUID rename, repeated rejection, binding, content broadcast, clearing and guarded deletion");
        } finally {
            stateSender = oldSender;
            player.containerMenu = oldMenu;
            if (created != null && cloud.ownsVolume(player.getUUID(), created.id())) {
                if (accessor.boundVolumeId().filter(created.id()::equals).isPresent()) accessor.clearBinding(player);
                try (var transaction = dev.kehai.digitalstorage.storage.LedgerTransaction.open()) {
                    created.record().storage().extract(dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE),
                            Long.MAX_VALUE, transaction);
                    transaction.commit();
                }
                expectResponse(cloud.deleteEmptyVolume(player.getUUID(), created.id()), "Fixture cleanup");
            }
        }
    }

    private static void runIconSelfTest(ServerPlayer player, DigitalStorageAccessorBlockEntity accessor) {
        var cloud = DigitalStorageState.get(player.serverLevel());
        var volume = cloud.createVolume(player.getUUID(), "Icon request fixture", Integer.MAX_VALUE).orElseThrow();
        var foreign = cloud.createVolume(java.util.UUID.randomUUID(), "Foreign icon fixture", 1).orElseThrow();
        var oldItem = player.getInventory().getItem(0);
        var diamond = new net.minecraft.resources.ResourceLocation("minecraft", "diamond");
        var item = new ItemStack(net.minecraft.world.item.Items.DIAMOND, 3);
        item.getOrCreateTag().putString("IconFixture", "preserve this tag");
        player.getInventory().setItem(0, item);
        try {
            var handler = new DigitalStorageScreenHandler(207, player.getInventory(), accessor);
            player.containerMenu = handler;
            handleRequest(player, new DigitalStorageScreenProtocol.VolumeIcon(208, volume.id(), diamond));
            expectResponse(volume.icon().equals(StorageVolume.DEFAULT_ICON) && handler.responseRevision == 0,
                    "Stale icon menu ignored");
            handleRequest(player, new DigitalStorageScreenProtocol.VolumeIcon(207, foreign.id(), diamond));
            expectResponse(!handler.state.statusSuccessful() && foreign.icon().equals(StorageVolume.DEFAULT_ICON),
                    "Foreign icon edit rejected");
            int index = cloud.volumes(player.getUUID()).indexOf(volume);
            handler.clickMenuButton(player, BIND_VOLUME_BUTTON_BASE + index);
            var alias = new DigitalStorageScreenHandler(208, player.getInventory(), accessor);
            alias.broadcastChanges();
            handleRequest(player, new DigitalStorageScreenProtocol.VolumeIcon(207, volume.id(), diamond));
            expectResponse(handler.state.statusSuccessful() && handler.state.volumeIcon().equals(diamond)
                    && handler.state.volumeId().equals(volume.id()), "Owned inventory icon accepted and synced");
            alias.nextStateSyncTick = 0;
            alias.broadcastChanges();
            expectResponse(alias.state.volumeIcon().equals(diamond) && alias.responseRevision == 0,
                    "Metadata broadcasts refresh other menus without acknowledging an operation");
            expectResponse(item.getCount() == 3 && item.getTag().getString("IconFixture").equals("preserve this tag")
                    && volume.record().storage().totalItemCount() == 0, "Icon selection preserves inventory and ledger");
            for (var denied : java.util.List.of(new net.minecraft.resources.ResourceLocation("minecraft", "air"),
                    new net.minecraft.resources.ResourceLocation("absent_mod", "missing_item"),
                    new net.minecraft.resources.ResourceLocation("minecraft", "nether_star"))) {
                handleRequest(player, new DigitalStorageScreenProtocol.VolumeIcon(207, volume.id(), denied));
                expectResponse(!handler.state.statusSuccessful() && volume.icon().equals(diamond),
                        "Invalid or unpossessed icon rejected: " + denied);
            }
            player.setPos(accessor.getBlockPos().getX() + 20, accessor.getBlockPos().getY(), accessor.getBlockPos().getZ());
            long revision = handler.responseRevision;
            handleRequest(player, new DigitalStorageScreenProtocol.VolumeIcon(207, volume.id(), StorageVolume.DEFAULT_ICON));
            expectResponse(handler.responseRevision == revision && volume.icon().equals(diamond), "Out-of-range icon ignored");
            player.setPos(accessor.getBlockPos().getX() + .5, accessor.getBlockPos().getY() + .5, accessor.getBlockPos().getZ() + .5);
            handleRequest(player, new DigitalStorageScreenProtocol.VolumeIcon(207, volume.id(), StorageVolume.DEFAULT_ICON));
            expectResponse(handler.state.statusSuccessful() && volume.icon().equals(StorageVolume.DEFAULT_ICON),
                    "Default icon reset requires no chest item");
            handler.setCarried(new ItemStack(net.minecraft.world.item.Items.PAPER));
            handleRequest(player, new DigitalStorageScreenProtocol.VolumeIcon(207, volume.id(),
                    new net.minecraft.resources.ResourceLocation("minecraft", "paper")));
            expectResponse(handler.state.statusSuccessful() && handler.getCarried().getCount() == 1,
                    "Carried item icon accepted without consumption");
            handler.setCarried(ItemStack.EMPTY);
            handler.clickMenuButton(player, CLEAR_BINDING_BUTTON_ID);
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Volume icon fixture passed: owner/menu/range checks, inventory and carried item preservation, invalid item rejection, default reset and metadata broadcast");
        } finally {
            player.getInventory().setItem(0, oldItem);
            player.setPos(accessor.getBlockPos().getX() + .5, accessor.getBlockPos().getY() + .5, accessor.getBlockPos().getZ() + .5);
            if (accessor.boundVolumeId().filter(volume.id()::equals).isPresent()) accessor.clearBinding(player);
            expectResponse(cloud.deleteEmptyVolume(volume.ownerId(), volume.id()), "Owned icon fixture cleanup");
            expectResponse(cloud.deleteEmptyVolume(foreign.ownerId(), foreign.id()), "Foreign icon fixture cleanup");
        }
    }

    private static void expectResponse(boolean value, String description) {
        if (!value) throw new IllegalStateException("Screen response fixture: " + description);
    }
}
