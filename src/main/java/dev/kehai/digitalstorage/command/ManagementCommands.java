package dev.kehai.digitalstorage.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.StorageVolume;
import dev.kehai.digitalstorage.storage.VolumeManagementService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** Loader-neutral player ownership, accessor administration and storage reporting commands. */
public final class ManagementCommands {
    private ManagementCommands() { }
    public static LiteralArgumentBuilder<CommandSourceStack> root(String rootName) {
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
                .then(literal("reloadconfig").requires(source -> source.hasPermission(2))
                        .executes(context -> reloadConfig(context.getSource())))
                .then(literal("stats").requires(source -> source.hasPermission(2))
                        .executes(context -> stats(context.getSource(), false))
                        .then(literal("deep").executes(context -> stats(context.getSource(), true))));
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
