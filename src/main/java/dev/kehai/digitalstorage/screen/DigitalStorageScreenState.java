package dev.kehai.digitalstorage.screen;

import com.mojang.authlib.GameProfile;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.NetworkAnalysis;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.StorageVolume;
import dev.kehai.digitalstorage.tier.DigitalStorageTier;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import dev.kehai.digitalstorage.tier.UpgradeIngredient;
import dev.kehai.digitalstorage.upgrade.DigitalStorageUpgradeService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public record DigitalStorageScreenState(
        boolean accessorBound,
        boolean accessorConfigurable,
        String controller,
        String volumeName,
        List<VolumeChoice> ownedVolumes,
        ResourceLocation tierId,
        int usedVariants,
        int variantCapacity,
        String totalItems,
        boolean acceptsUnstackableItems,
        boolean unstackableItemsAllowedByServer,
        boolean unstackableItemsConfigurable,
        boolean hasNextTier,
        ResourceLocation nextTierId,
        int nextVariantCapacity,
        List<UpgradeIngredient> upgradeCost,
        int experienceLevels,
        boolean canAfford,
        NetworkDiagnostic networkDiagnostic,
        boolean statusSuccessful,
        Component status,
        long responseRevision
) {
    private static final int MAX_SYNCED_INGREDIENTS = 256;
    private static final int MAX_SYNCED_VOLUMES = 64;
    private static final int MAX_TEXT_LENGTH = 128;

    public DigitalStorageScreenState {
        ownedVolumes = List.copyOf(ownedVolumes);
        upgradeCost = List.copyOf(upgradeCost);
        networkDiagnostic = networkDiagnostic == null ? NetworkDiagnostic.unavailable() : networkDiagnostic;
        status = status.copy();
    }

    public static DigitalStorageScreenState capture(
            ServerPlayer player,
            DigitalStorageAccessorBlockEntity accessor,
            Component status
    ) {
        return capture(player, accessor, status, NetworkDiagnostic.unavailable());
    }

    public static DigitalStorageScreenState capture(
            ServerPlayer player,
            DigitalStorageAccessorBlockEntity accessor,
            Component status,
            NetworkDiagnostic networkDiagnostic
    ) {
        DigitalStorageState cloud = DigitalStorageState.get(player.serverLevel());
        List<VolumeChoice> choices = cloud.volumes(player.getUUID()).stream()
                .map(VolumeChoice::from)
                .toList();
        StorageVolume volume = accessor.getVolume();
        boolean bound = accessor.isBound();
        boolean configurable = accessor.controllerId().isEmpty()
                || accessor.controllerId().orElseThrow().equals(player.getUUID());
        String controller = controllerDisplayName(player, accessor.controllerId());

        if (volume == null) {
            DigitalStorageTier first = DigitalStorageTierRegistry.INSTANCE.first();
            return new DigitalStorageScreenState(
                    bound,
                    configurable,
                    controller,
                    bound ? "<missing>" : "",
                    choices,
                    first.id(),
                    0,
                    first.variantCapacity(),
                    "0",
                    false,
                    DigitalStorageConfig.get().allowUnstackableItems,
                    false,
                    false,
                    first.id(),
                    first.variantCapacity(),
                    List.of(),
                    0,
                    false,
                    networkDiagnostic,
                    false,
                    status,
                    0
            );
        }

        DigitalStorageRecord record = volume.record();
        DigitalStorageTier currentTier = record.tier();
        Optional<DigitalStorageTier> nextTier = DigitalStorageTierRegistry.INSTANCE.next(currentTier.id());
        DigitalStorageTier next = nextTier.orElse(currentTier);

        return new DigitalStorageScreenState(
                true,
                configurable,
                controller,
                volume.name(),
                choices,
                currentTier.id(),
                record.storage().variantCount(),
                currentTier.variantCapacity(),
                Long.toString(record.storage().totalItemCount()),
                record.acceptsUnstackableItems(),
                DigitalStorageConfig.get().allowUnstackableItems,
                volume.ownerId().equals(player.getUUID()),
                nextTier.isPresent(),
                next.id(),
                next.variantCapacity(),
                nextTier.map(DigitalStorageTier::entryCost).orElse(List.of()),
                nextTier.map(DigitalStorageTier::experienceLevels).orElse(0),
                nextTier.isPresent() && DigitalStorageUpgradeService.canAfford(player, next),
                networkDiagnostic,
                false,
                status,
                0
        );
    }

    public int remainingVariants() {
        return Math.max(0, variantCapacity - usedVariants);
    }

    private static String controllerDisplayName(ServerPlayer viewer, Optional<UUID> controllerId) {
        if (controllerId.isEmpty()) {
            return "";
        }
        UUID id = controllerId.orElseThrow();
        var server = viewer.serverLevel().getServer();
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getProfileCache()
                .get(id)
                .map(GameProfile::getName)
                .filter(name -> !name.isBlank())
                .orElse(id.toString());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(accessorBound);
        buf.writeBoolean(accessorConfigurable);
        buf.writeUtf(controller, MAX_TEXT_LENGTH);
        buf.writeUtf(volumeName, MAX_TEXT_LENGTH);
        buf.writeVarInt(ownedVolumes.size());
        for (VolumeChoice choice : ownedVolumes) {
            choice.write(buf);
        }
        buf.writeResourceLocation(tierId);
        buf.writeVarInt(usedVariants);
        buf.writeVarInt(variantCapacity);
        buf.writeUtf(totalItems, MAX_TEXT_LENGTH);
        buf.writeBoolean(acceptsUnstackableItems);
        buf.writeBoolean(unstackableItemsAllowedByServer);
        buf.writeBoolean(unstackableItemsConfigurable);
        buf.writeBoolean(hasNextTier);
        buf.writeResourceLocation(nextTierId);
        buf.writeVarInt(nextVariantCapacity);
        buf.writeVarInt(upgradeCost.size());
        for (UpgradeIngredient ingredient : upgradeCost) {
            buf.writeEnum(ingredient.kind());
            buf.writeResourceLocation(ingredient.id());
            buf.writeVarInt(ingredient.count());
        }
        buf.writeVarInt(experienceLevels);
        buf.writeBoolean(canAfford);
        networkDiagnostic.write(buf);
        buf.writeBoolean(statusSuccessful);
        buf.writeComponent(status);
        buf.writeLong(responseRevision);
    }

    public static DigitalStorageScreenState read(FriendlyByteBuf buf) {
        boolean accessorBound = buf.readBoolean();
        boolean accessorConfigurable = buf.readBoolean();
        String controller = buf.readUtf(MAX_TEXT_LENGTH);
        String volumeName = buf.readUtf(MAX_TEXT_LENGTH);
        int volumeCount = checkedCount(buf.readVarInt(), MAX_SYNCED_VOLUMES, "volume");
        List<VolumeChoice> ownedVolumes = new ArrayList<>(volumeCount);
        for (int index = 0; index < volumeCount; index++) {
            ownedVolumes.add(VolumeChoice.read(buf));
        }
        ResourceLocation tierId = buf.readResourceLocation();
        int usedVariants = buf.readVarInt();
        int variantCapacity = buf.readVarInt();
        String totalItems = buf.readUtf(MAX_TEXT_LENGTH);
        boolean acceptsUnstackableItems = buf.readBoolean();
        boolean unstackableItemsAllowedByServer = buf.readBoolean();
        boolean unstackableItemsConfigurable = buf.readBoolean();
        boolean hasNextTier = buf.readBoolean();
        ResourceLocation nextTierId = buf.readResourceLocation();
        int nextVariantCapacity = buf.readVarInt();
        int ingredientCount = checkedCount(buf.readVarInt(), MAX_SYNCED_INGREDIENTS, "upgrade ingredient");
        List<UpgradeIngredient> upgradeCost = new ArrayList<>(ingredientCount);
        for (int index = 0; index < ingredientCount; index++) {
            UpgradeIngredient.Kind kind = buf.readEnum(UpgradeIngredient.Kind.class);
            ResourceLocation id = buf.readResourceLocation();
            int count = buf.readVarInt();
            upgradeCost.add(new UpgradeIngredient(kind, id, count));
        }
        return new DigitalStorageScreenState(
                accessorBound,
                accessorConfigurable,
                controller,
                volumeName,
                ownedVolumes,
                tierId,
                usedVariants,
                variantCapacity,
                totalItems,
                acceptsUnstackableItems,
                unstackableItemsAllowedByServer,
                unstackableItemsConfigurable,
                hasNextTier,
                nextTierId,
                nextVariantCapacity,
                upgradeCost,
                buf.readVarInt(),
                buf.readBoolean(),
                NetworkDiagnostic.read(buf),
                buf.readBoolean(),
                buf.readComponent(),
                buf.readLong()
        );
    }

    public DigitalStorageScreenState withStatus(Component message, boolean successful) {
        return withStatus(message, successful, responseRevision);
    }

    public DigitalStorageScreenState withStatus(Component message, boolean successful, long revision) {
        return new DigitalStorageScreenState(
                accessorBound,
                accessorConfigurable,
                controller,
                volumeName,
                ownedVolumes,
                tierId,
                usedVariants,
                variantCapacity,
                totalItems,
                acceptsUnstackableItems,
                unstackableItemsAllowedByServer,
                unstackableItemsConfigurable,
                hasNextTier,
                nextTierId,
                nextVariantCapacity,
                upgradeCost,
                experienceLevels,
                canAfford,
                networkDiagnostic,
                successful,
                message,
                revision
        );
    }

    public static void runCodecSelfTest() {
        DigitalStorageScreenState expected = new DigitalStorageScreenState(
                false,
                true,
                "none",
                "",
                List.of(new VolumeChoice(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        "Primary",
                        new ResourceLocation("digitalstorage", "basic"),
                        3,
                        64
                )),
                new ResourceLocation("digitalstorage", "basic"),
                3,
                64,
                "9223372036854775808",
                true,
                true,
                true,
                true,
                new ResourceLocation("digitalstorage", "advanced"),
                128,
                List.of(
                        UpgradeIngredient.item(new ResourceLocation("minecraft", "diamond"), 16),
                        UpgradeIngredient.tag(new ResourceLocation("c", "ingots"), 4)
                ),
                7,
                true,
                new NetworkDiagnostic(
                        true, 43, "D", 27, 6400, 5832, 1024, 2, 3, 6, 2, 20,
                        4217, 64, "minecraft:cobblestone", "RUNNING", "4096", 3, 12, 8192
                ),
                true,
                Component.literal("codec status"),
                42
        );
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            expected.write(buf);
            DigitalStorageScreenState actual = read(buf);
            if (!expected.equals(actual) || buf.isReadable()) {
                throw new IllegalStateException("Digital storage screen state codec round trip failed");
            }
            if (actual.withStatus(Component.literal("content update"), false).responseRevision() != 42
                    || actual.withStatus(actual.status(), true, 43).responseRevision() != 43) {
                throw new IllegalStateException("Screen response revision was lost or not advanced");
            }
        } finally {
            buf.release();
        }
    }

    private static int checkedCount(int value, int maximum, String description) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException("Invalid synced " + description + " count: " + value);
        }
        return value;
    }

    public record NetworkDiagnostic(
            boolean available,
            int healthScore,
            String grade,
            int physicalInventories,
            int totalViews,
            int nonEmptyViews,
            int digitalViews,
            int duplicateDigitalEndpoints,
            int targetEndpointCount,
            int activeScanners,
            int failingScanners,
            int averageScanIntervalTicks,
            int estimatedFreedViews,
            int recommendedVariants,
            String topCandidateId,
            String migrationState,
            String movedItems,
            int completedCandidates,
            int totalCandidates,
            long scannedViews
    ) {
        static NetworkDiagnostic unavailable() {
            return new NetworkDiagnostic(
                    false, 0, "-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    "", "IDLE", "0", 0, 0, 0
            );
        }

        public static NetworkDiagnostic from(
                NetworkAnalysis.Report report,
                MigrationTask.Status migration
        ) {
            if (!report.available()) {
                NetworkDiagnostic unavailable = unavailable();
                return new NetworkDiagnostic(
                        false, 0, "-", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "",
                        migration.state().name(), migration.movedItems(), migration.completedCandidates(),
                        migration.totalCandidates(), migration.scannedViews()
                );
            }
            return new NetworkDiagnostic(
                    true,
                    report.healthScore(),
                    report.grade(),
                    report.physicalInventories(),
                    report.totalViews(),
                    report.nonEmptyViews(),
                    report.digitalViews(),
                    report.duplicateDigitalEndpoints(),
                    report.targetEndpointCount(),
                    report.activeScanners(),
                    report.failingScanners(),
                    report.averageScanIntervalTicks(),
                    report.estimatedFreedViews(),
                    report.candidates().size(),
                    report.topCandidateId(),
                    migration.state().name(),
                    migration.movedItems(),
                    migration.completedCandidates(),
                    migration.totalCandidates(),
                    migration.scannedViews()
            );
        }

        void write(FriendlyByteBuf buf) {
            buf.writeBoolean(available);
            buf.writeVarInt(healthScore);
            buf.writeUtf(grade, 8);
            buf.writeVarInt(physicalInventories);
            buf.writeVarInt(totalViews);
            buf.writeVarInt(nonEmptyViews);
            buf.writeVarInt(digitalViews);
            buf.writeVarInt(duplicateDigitalEndpoints);
            buf.writeVarInt(targetEndpointCount);
            buf.writeVarInt(activeScanners);
            buf.writeVarInt(failingScanners);
            buf.writeVarInt(averageScanIntervalTicks);
            buf.writeVarInt(estimatedFreedViews);
            buf.writeVarInt(recommendedVariants);
            buf.writeUtf(topCandidateId, MAX_TEXT_LENGTH);
            buf.writeUtf(migrationState, 32);
            buf.writeUtf(movedItems, MAX_TEXT_LENGTH);
            buf.writeVarInt(completedCandidates);
            buf.writeVarInt(totalCandidates);
            buf.writeVarLong(scannedViews);
        }

        static NetworkDiagnostic read(FriendlyByteBuf buf) {
            return new NetworkDiagnostic(
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readUtf(8),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(MAX_TEXT_LENGTH),
                    buf.readUtf(32),
                    buf.readUtf(MAX_TEXT_LENGTH),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarLong()
            );
        }

        public boolean migrationActive() {
            return "RUNNING".equals(migrationState);
        }

        public boolean hasDuplicateTargetEndpoints() {
            return targetEndpointCount > 1;
        }
    }

    public record VolumeChoice(UUID id, String name, ResourceLocation tierId, int usedVariants, int variantCapacity) {
        static VolumeChoice from(StorageVolume volume) {
            DigitalStorageRecord record = volume.record();
            return new VolumeChoice(
                    volume.id(),
                    volume.name(),
                    record.tierId(),
                    record.storage().variantCount(),
                    record.variantCapacity()
            );
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name, MAX_TEXT_LENGTH);
            buf.writeResourceLocation(tierId);
            buf.writeVarInt(usedVariants);
            buf.writeVarInt(variantCapacity);
        }

        static VolumeChoice read(FriendlyByteBuf buf) {
            return new VolumeChoice(
                    buf.readUUID(),
                    buf.readUtf(MAX_TEXT_LENGTH),
                    buf.readResourceLocation(),
                    buf.readVarInt(),
                    buf.readVarInt()
            );
        }
    }
}
