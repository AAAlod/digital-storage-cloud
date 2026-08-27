package dev.kehai.digitalstorage.screen;

import com.mojang.authlib.GameProfile;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.optimization.TomMigrationManager;
import dev.kehai.digitalstorage.optimization.TomNetworkAnalysis;
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
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public record DigitalStorageScreenState(
        boolean accessorBound,
        boolean accessorConfigurable,
        String controller,
        String volumeName,
        List<VolumeChoice> ownedVolumes,
        Identifier tierId,
        int usedVariants,
        int variantCapacity,
        String totalItems,
        boolean acceptsUnstackableItems,
        boolean unstackableItemsAllowedByServer,
        boolean unstackableItemsConfigurable,
        boolean hasNextTier,
        Identifier nextTierId,
        int nextVariantCapacity,
        List<UpgradeIngredient> upgradeCost,
        int experienceLevels,
        boolean canAfford,
        NetworkDiagnostic networkDiagnostic,
        boolean statusSuccessful,
        Text status
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
            ServerPlayerEntity player,
            DigitalStorageAccessorBlockEntity accessor,
            Text status
    ) {
        return capture(player, accessor, status, NetworkDiagnostic.unavailable());
    }

    public static DigitalStorageScreenState capture(
            ServerPlayerEntity player,
            DigitalStorageAccessorBlockEntity accessor,
            Text status,
            NetworkDiagnostic networkDiagnostic
    ) {
        DigitalStorageState cloud = DigitalStorageState.get(player.getServerWorld());
        List<VolumeChoice> choices = cloud.volumes(player.getUuid()).stream()
                .map(VolumeChoice::from)
                .toList();
        StorageVolume volume = accessor.getVolume();
        boolean bound = accessor.isBound();
        boolean configurable = accessor.controllerId().isEmpty()
                || accessor.controllerId().orElseThrow().equals(player.getUuid());
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
                    status
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
                volume.ownerId().equals(player.getUuid()),
                nextTier.isPresent(),
                next.id(),
                next.variantCapacity(),
                nextTier.map(DigitalStorageTier::entryCost).orElse(List.of()),
                nextTier.map(DigitalStorageTier::experienceLevels).orElse(0),
                nextTier.isPresent() && DigitalStorageUpgradeService.canAfford(player, next),
                networkDiagnostic,
                false,
                status
        );
    }

    public int remainingVariants() {
        return Math.max(0, variantCapacity - usedVariants);
    }

    private static String controllerDisplayName(ServerPlayerEntity viewer, Optional<UUID> controllerId) {
        if (controllerId.isEmpty()) {
            return "";
        }
        UUID id = controllerId.orElseThrow();
        var server = viewer.getServerWorld().getServer();
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(id);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        return server.getUserCache()
                .getByUuid(id)
                .map(GameProfile::getName)
                .filter(name -> !name.isBlank())
                .orElse(id.toString());
    }

    public void write(PacketByteBuf buf) {
        buf.writeBoolean(accessorBound);
        buf.writeBoolean(accessorConfigurable);
        buf.writeString(controller, MAX_TEXT_LENGTH);
        buf.writeString(volumeName, MAX_TEXT_LENGTH);
        buf.writeVarInt(ownedVolumes.size());
        for (VolumeChoice choice : ownedVolumes) {
            choice.write(buf);
        }
        buf.writeIdentifier(tierId);
        buf.writeVarInt(usedVariants);
        buf.writeVarInt(variantCapacity);
        buf.writeString(totalItems, MAX_TEXT_LENGTH);
        buf.writeBoolean(acceptsUnstackableItems);
        buf.writeBoolean(unstackableItemsAllowedByServer);
        buf.writeBoolean(unstackableItemsConfigurable);
        buf.writeBoolean(hasNextTier);
        buf.writeIdentifier(nextTierId);
        buf.writeVarInt(nextVariantCapacity);
        buf.writeVarInt(upgradeCost.size());
        for (UpgradeIngredient ingredient : upgradeCost) {
            buf.writeEnumConstant(ingredient.kind());
            buf.writeIdentifier(ingredient.id());
            buf.writeVarInt(ingredient.count());
        }
        buf.writeVarInt(experienceLevels);
        buf.writeBoolean(canAfford);
        networkDiagnostic.write(buf);
        buf.writeBoolean(statusSuccessful);
        buf.writeText(status);
    }

    public static DigitalStorageScreenState read(PacketByteBuf buf) {
        boolean accessorBound = buf.readBoolean();
        boolean accessorConfigurable = buf.readBoolean();
        String controller = buf.readString(MAX_TEXT_LENGTH);
        String volumeName = buf.readString(MAX_TEXT_LENGTH);
        int volumeCount = checkedCount(buf.readVarInt(), MAX_SYNCED_VOLUMES, "volume");
        List<VolumeChoice> ownedVolumes = new ArrayList<>(volumeCount);
        for (int index = 0; index < volumeCount; index++) {
            ownedVolumes.add(VolumeChoice.read(buf));
        }
        Identifier tierId = buf.readIdentifier();
        int usedVariants = buf.readVarInt();
        int variantCapacity = buf.readVarInt();
        String totalItems = buf.readString(MAX_TEXT_LENGTH);
        boolean acceptsUnstackableItems = buf.readBoolean();
        boolean unstackableItemsAllowedByServer = buf.readBoolean();
        boolean unstackableItemsConfigurable = buf.readBoolean();
        boolean hasNextTier = buf.readBoolean();
        Identifier nextTierId = buf.readIdentifier();
        int nextVariantCapacity = buf.readVarInt();
        int ingredientCount = checkedCount(buf.readVarInt(), MAX_SYNCED_INGREDIENTS, "upgrade ingredient");
        List<UpgradeIngredient> upgradeCost = new ArrayList<>(ingredientCount);
        for (int index = 0; index < ingredientCount; index++) {
            UpgradeIngredient.Kind kind = buf.readEnumConstant(UpgradeIngredient.Kind.class);
            Identifier id = buf.readIdentifier();
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
                buf.readText()
        );
    }

    public DigitalStorageScreenState withStatus(Text message, boolean successful) {
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
                message
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
                        new Identifier("digitalstorage", "basic"),
                        3,
                        64
                )),
                new Identifier("digitalstorage", "basic"),
                3,
                64,
                "9223372036854775808",
                true,
                true,
                true,
                true,
                new Identifier("digitalstorage", "advanced"),
                128,
                List.of(
                        UpgradeIngredient.item(new Identifier("minecraft", "diamond"), 16),
                        UpgradeIngredient.tag(new Identifier("c", "ingots"), 4)
                ),
                7,
                true,
                new NetworkDiagnostic(
                        true, 43, "D", 27, 6400, 5832, 1024, 2, 3, 6, 2, 20,
                        4217, 64, "minecraft:cobblestone", "RUNNING", "4096", 3, 12, 8192
                ),
                true,
                Text.literal("codec status")
        );
        PacketByteBuf buf = net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create();
        try {
            expected.write(buf);
            DigitalStorageScreenState actual = read(buf);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Digital storage screen state codec round trip failed");
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
                TomNetworkAnalysis.Report report,
                TomMigrationManager.Status migration
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

        void write(PacketByteBuf buf) {
            buf.writeBoolean(available);
            buf.writeVarInt(healthScore);
            buf.writeString(grade, 8);
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
            buf.writeString(topCandidateId, MAX_TEXT_LENGTH);
            buf.writeString(migrationState, 32);
            buf.writeString(movedItems, MAX_TEXT_LENGTH);
            buf.writeVarInt(completedCandidates);
            buf.writeVarInt(totalCandidates);
            buf.writeVarLong(scannedViews);
        }

        static NetworkDiagnostic read(PacketByteBuf buf) {
            return new NetworkDiagnostic(
                    buf.readBoolean(),
                    buf.readVarInt(),
                    buf.readString(8),
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
                    buf.readString(MAX_TEXT_LENGTH),
                    buf.readString(32),
                    buf.readString(MAX_TEXT_LENGTH),
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

    public record VolumeChoice(UUID id, String name, Identifier tierId, int usedVariants, int variantCapacity) {
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

        void write(PacketByteBuf buf) {
            buf.writeUuid(id);
            buf.writeString(name, MAX_TEXT_LENGTH);
            buf.writeIdentifier(tierId);
            buf.writeVarInt(usedVariants);
            buf.writeVarInt(variantCapacity);
        }

        static VolumeChoice read(PacketByteBuf buf) {
            return new VolumeChoice(
                    buf.readUuid(),
                    buf.readString(MAX_TEXT_LENGTH),
                    buf.readIdentifier(),
                    buf.readVarInt(),
                    buf.readVarInt()
            );
        }
    }
}
