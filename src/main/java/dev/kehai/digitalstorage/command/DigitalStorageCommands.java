package dev.kehai.digitalstorage.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.integration.TomIntegrationStatus;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import dev.kehai.digitalstorage.platform.fabric.tom.TomPerformanceBenchmark;
import dev.kehai.digitalstorage.storage.DigitalItemStorageSelfTest;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.StorageVolume;
import dev.kehai.digitalstorage.storage.VolumeManagementService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class DigitalStorageCommands {
    private DigitalStorageCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("digitalstorage"));
        dispatcher.register(root("dsc"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String rootName) {
        return literal(rootName)
                .then(literal("volume")
                        .then(literal("create")
                                .then(argument("name", StringArgumentType.greedyString())
                                        .executes(context -> createVolume(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name")
                                        ))))
                        .then(literal("list").executes(context -> listVolumes(context.getSource())))
                        .then(literal("rename")
                                .then(argument("volume", UuidArgument.uuid())
                                        .then(argument("name", StringArgumentType.greedyString())
                                                .executes(context -> renameVolume(
                                                        context.getSource(),
                                                        UuidArgument.getUuid(context, "volume"),
                                                        StringArgumentType.getString(context, "name")
                                                )))))
                        .then(literal("delete")
                                .then(argument("volume", UuidArgument.uuid())
                                        .executes(context -> deleteVolume(
                                                context.getSource(),
                                                UuidArgument.getUuid(context, "volume")
                                        )))))
                .then(literal("accessor")
                        .then(literal("bind")
                                .then(argument("pos", BlockPosArgument.blockPos())
                                        .then(argument("volume", UuidArgument.uuid())
                                                .executes(context -> bindAccessor(
                                                        context.getSource(),
                                                        BlockPosArgument.getLoadedBlockPos(context, "pos"),
                                                        UuidArgument.getUuid(context, "volume")
                                                )))))
                        .then(literal("clear")
                                .then(argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> clearAccessor(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos")
                                        ))))
                        .then(literal("forceclear")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> forceClearAccessor(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos")
                                        ))))
                        .then(literal("inspect")
                                .then(argument("pos", BlockPosArgument.blockPos())
                                        .executes(context -> inspectAccessor(
                                                context.getSource(),
                                                BlockPosArgument.getLoadedBlockPos(context, "pos")
                                        )))))
                .then(literal("selftest")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> selfTest(context.getSource())))
                .then(literal("benchmark")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> benchmark(context.getSource())))
                .then(literal("reloadconfig")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> reloadConfig(context.getSource())))
                .then(literal("flush")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> flush(context.getSource())))
                .then(literal("probe")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("pos", BlockPosArgument.blockPos())
                                .executes(context -> probe(
                                        context.getSource(),
                                        BlockPosArgument.getLoadedBlockPos(context, "pos")
                                ))))
                .then(literal("stats")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> stats(context.getSource(), false))
                        .then(literal("deep")
                                .executes(context -> stats(context.getSource(), true))))
                .then(literal("diagnostics")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> diagnostics(context.getSource())));
    }

    private static int createVolume(CommandSourceStack source, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DigitalStorageState state = DigitalStorageState.get(player.serverLevel());
        int limit = DigitalStorageConfig.get().defaultVolumesPerPlayer;
        StorageVolume volume = state.createVolume(player.getUUID(), name, limit).orElse(null);
        if (volume == null) {
            source.sendFailure(Component.translatable("command.digitalstorage.volume.limit", limit));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "command.digitalstorage.volume.created",
                volume.name(),
                volume.id().toString()
        ), false);
        return 1;
    }

    private static int listVolumes(CommandSourceStack source)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<StorageVolume> volumes = DigitalStorageState.get(player.serverLevel()).volumes(player.getUUID());
        source.sendSuccess(() -> Component.translatable("command.digitalstorage.volume.count", volumes.size()), false);
        for (StorageVolume volume : volumes) {
            source.sendSuccess(() -> Component.literal(
                    volume.id() + "  " + volume.name()
                            + "  " + volume.record().tierId()
                            + "  " + volume.record().storage().variantCount()
                            + "/" + volume.record().variantCapacity()
            ), false);
        }
        return volumes.size();
    }

    private static int renameVolume(CommandSourceStack source, UUID volumeId, String name)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        boolean renamed = DigitalStorageState.get(player.serverLevel())
                .renameVolume(player.getUUID(), volumeId, name);
        if (!renamed) {
            source.sendFailure(Component.translatable("command.digitalstorage.volume.not_owned"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.digitalstorage.volume.renamed"), false);
        return 1;
    }

    private static int deleteVolume(CommandSourceStack source, UUID volumeId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        VolumeManagementService.DeleteResult result = VolumeManagementService.delete(
                DigitalStorageState.get(player.serverLevel()),
                player.getUUID(),
                volumeId
        );
        if (result != VolumeManagementService.DeleteResult.SUCCESS) {
            source.sendFailure(Component.translatable("command.digitalstorage.volume.delete_failed"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.digitalstorage.volume.deleted"), false);
        return 1;
    }

    private static int bindAccessor(CommandSourceStack source, BlockPos pos, UUID volumeId)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DigitalStorageAccessorBlockEntity accessor = accessor(source, pos);
        if (accessor == null) {
            return 0;
        }
        DigitalStorageAccessorBlockEntity.BindResult result = accessor.bind(player, volumeId);
        if (result != DigitalStorageAccessorBlockEntity.BindResult.SUCCESS) {
            source.sendFailure(Component.translatable("command.digitalstorage.accessor.bind_failed", result.name()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.digitalstorage.accessor.bound", volumeId.toString()), false);
        return 1;
    }

    private static int clearAccessor(CommandSourceStack source, BlockPos pos)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        DigitalStorageAccessorBlockEntity accessor = accessor(source, pos);
        if (accessor == null) {
            return 0;
        }
        DigitalStorageAccessorBlockEntity.BindResult result = accessor.clearBinding(player);
        if (result != DigitalStorageAccessorBlockEntity.BindResult.SUCCESS) {
            source.sendFailure(Component.translatable("command.digitalstorage.accessor.clear_failed", result.name()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.digitalstorage.accessor.cleared"), false);
        return 1;
    }

    private static int inspectAccessor(CommandSourceStack source, BlockPos pos) {
        DigitalStorageAccessorBlockEntity accessor = accessor(source, pos);
        if (accessor == null) {
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Accessor " + pos.toShortString()
                        + ": controller=" + accessor.controllerId().map(UUID::toString).orElse("none")
                        + ", volume=" + accessor.boundVolumeId().map(UUID::toString).orElse("none")
        ), false);
        return 1;
    }

    private static int forceClearAccessor(CommandSourceStack source, BlockPos pos) {
        DigitalStorageAccessorBlockEntity accessor = accessor(source, pos);
        if (accessor == null) {
            return 0;
        }
        DigitalStorageAccessorBlockEntity.BindResult result = accessor.forceClearBinding();
        if (result != DigitalStorageAccessorBlockEntity.BindResult.SUCCESS) {
            source.sendFailure(Component.translatable("command.digitalstorage.accessor.force_clear_failed", result.name()));
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("command.digitalstorage.accessor.force_cleared", pos.toShortString()),
                true
        );
        return 1;
    }

    private static int selfTest(CommandSourceStack source) {
        try {
            String result = DigitalItemStorageSelfTest.run();
            source.sendSuccess(() -> Component.literal(result), false);
            return 1;
        } catch (RuntimeException exception) {
            DigitalStorage.LOGGER.error("Digital Storage self-test failed", exception);
            source.sendFailure(Component.literal("Digital Storage self-test failed: " + exception));
            return 0;
        }
    }

    private static int benchmark(CommandSourceStack source) {
        try {
            TomPerformanceBenchmark.Result result = TomPerformanceBenchmark.run();
            source.sendSuccess(() -> Component.literal(result.summary()), false);
            DigitalStorage.LOGGER.info(result.summary());
            return 1;
        } catch (RuntimeException exception) {
            DigitalStorage.LOGGER.error("Digital Storage benchmark failed", exception);
            source.sendFailure(Component.literal("Digital Storage benchmark failed: " + exception));
            return 0;
        }
    }

    private static int reloadConfig(CommandSourceStack source) {
        DigitalStorageConfig.LoadResult result = DigitalStorageConfig.reload();
        if (!result.success()) {
            source.sendFailure(Component.literal("Digital Storage config reload failed: " + result.message()));
            return 0;
        }
        ItemSecurityPolicy.reload();
        source.sendSuccess(() -> Component.literal(result.message()), false);
        return 1;
    }

    private static int flush(CommandSourceStack source) {
        DigitalStorageState state = DigitalStorageState.get(source.getLevel());
        state.flushNow();
        source.sendSuccess(() -> Component.literal("Digital Storage files flushed successfully"), false);
        return 1;
    }

    private static int probe(CommandSourceStack source, BlockPos pos) {
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(source.getLevel(), pos, null);
        if (storage == null) {
            source.sendFailure(Component.literal("No bound Fabric item storage found at " + pos.toShortString()));
            return 0;
        }
        int views = 0;
        for (var ignored : storage) {
            views++;
        }
        int finalViews = views;
        source.sendSuccess(() -> Component.literal(
                "Fabric item storage found at " + pos.toShortString() + " with " + finalViews + " non-empty views"
        ), false);
        return 1;
    }

    private static int stats(CommandSourceStack source, boolean inspectColdVolumes) {
        DigitalStorageState state = DigitalStorageState.get(source.getLevel());
        DigitalStorageState.StorageSizeStats sizes = state.storageSizeStats();
        DigitalStorageState.ContentStats content = state.contentStats(inspectColdVolumes);
        source.sendSuccess(() -> Component.literal(
                "Digital Storage Cloud: accounts=" + state.accountCount()
                        + ", volumes=" + state.volumeCount()
                        + ", quarantined=" + state.quarantinedRecordCount()
                        + ", dirty accounts=" + state.dirtyAccountCount()
                        + ", dirty volumes=" + state.dirtyVolumeCount()
                        + ", pending write batches=" + state.pendingWriteBatches()
                        + ", snapshot restarts=" + state.snapshotRestartCount()
                        + ", forced snapshots=" + state.forcedSnapshotCount()
                        + ", oldest dirty ticks=" + state.oldestDirtyVolumeAgeTicks()
                        + ", variants=" + content.variantCount()
                        + ", items=" + content.totalItemCount()
                        + ", content volumes=" + content.inspectedVolumeCount() + "/" + state.volumeCount()
                        + ", cold uninspected=" + content.uninspectedVolumeCount()
                        + ", loaded accessors=" + DigitalStorageMountTracker.loadedAccessorCount()
                        + ", bound accessors=" + DigitalStorageMountTracker.boundAccessorCount()
                        + ", mounted volumes=" + DigitalStorageMountTracker.mountedVolumeCount()
                        + ", multiply linked volumes=" + DigitalStorageMountTracker.multiMountedVolumeCount()
                        + ", live DB=" + formatBytes(sizes.liveDiskBytes())
                        + " (accounts=" + formatBytes(sizes.accountDiskBytes())
                        + ", volumes=" + formatBytes(sizes.volumeDiskBytes()) + ")"
                        + ", estimated volume NBT total=" + formatBytes(sizes.totalEstimatedVolumeNbtBytes())
                        + ", largest=" + formatBytes(sizes.largestEstimatedVolumeNbtBytes())
                        + ", average=" + formatBytes(sizes.averageEstimatedVolumeNbtBytes())
                        + ", above warning=" + sizes.oversizedVolumeCount()
                        + ", filter rejects=" + ItemSecurityPolicy.filterRejections()
                        + ", NBT rejects=" + ItemSecurityPolicy.nbtRejections()
                        + ", unstackable rejects=" + ItemSecurityPolicy.unstackableRejections()
        ), false);
        if (!inspectColdVolumes && content.uninspectedVolumeCount() > 0) {
            source.sendSuccess(() -> Component.literal(
                    "Use /digitalstorage stats deep for exact totals; it will load "
                            + content.uninspectedVolumeCount() + " cold volume(s)."
            ), false);
        }
        return state.volumeCount();
    }

    private static int diagnostics(CommandSourceStack source) {
        TomIntegrationStatus.Snapshot status = TomIntegrationStatus.snapshot();
        source.sendSuccess(() -> Component.literal(
                "Tom integration: " + (status.allActive() ? "ACTIVE" : "INCOMPLETE")
        ), false);
        source.sendSuccess(() -> Component.literal(
                "hopper=" + state(status.hopperOptimizationMixinApplied())
                        + ", connector stagger=" + state(status.connectorStaggerMixinApplied())
                        + ", topology tracking=" + state(status.topologyTrackingMixinApplied())
                        + ", volume dedup=" + state(status.volumeDedupMixinApplied())
                        + ", block entity extension=" + state(status.blockEntityTypeExtensionMixinApplied())
                        + ", advanced hopper=" + state(status.advancedHopperSupportActive())
        ), false);
        return status.allActive() ? 1 : 0;
    }

    private static String state(boolean value) {
        return value ? "ACTIVE" : "INACTIVE";
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1_048_576) {
            return String.format(Locale.ROOT, "%.2f MiB", bytes / 1_048_576.0);
        }
        if (bytes >= 1_024) {
            return String.format(Locale.ROOT, "%.2f KiB", bytes / 1_024.0);
        }
        return bytes + " B";
    }

    private static DigitalStorageAccessorBlockEntity accessor(CommandSourceStack source, BlockPos pos) {
        if (source.getLevel().getBlockEntity(pos) instanceof DigitalStorageAccessorBlockEntity accessor) {
            return accessor;
        }
        source.sendFailure(Component.translatable("command.digitalstorage.accessor.missing", pos.toShortString()));
        return null;
    }
}
