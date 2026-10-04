package dev.kehai.digitalstorage.screen;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.storage.StorageVolume;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Loader-independent messages. 1.2.1 state replies include an operation response revision. */
public final class DigitalStorageScreenProtocol {
    public static final ResourceLocation STATE_PACKET_ID = DigitalStorage.id("screen_state");
    public static final ResourceLocation CREATE_VOLUME_PACKET_ID = DigitalStorage.id("create_volume");
    public static final ResourceLocation MANAGE_VOLUME_PACKET_ID = DigitalStorage.id("manage_volume");
    public static final ResourceLocation VOLUME_ICON_PACKET_ID = DigitalStorage.id("volume_icon");

    private DigitalStorageScreenProtocol() {
    }

    public interface RequestSender {
        void send(CreateVolume request);

        void send(ManageVolume request);
        void send(VolumeIcon request);
    }

    @FunctionalInterface
    public interface StateSender {
        void send(ServerPlayer player, StateUpdate update);
    }

    public record CreateVolume(int syncId, String name) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(syncId);
            buf.writeUtf(name, StorageVolume.MAX_NAME_LENGTH);
        }

        public static CreateVolume read(FriendlyByteBuf buf) {
            return new CreateVolume(buf.readVarInt(), buf.readUtf(StorageVolume.MAX_NAME_LENGTH));
        }
    }

    public record ManageVolume(int syncId, int action, UUID volumeId, String name) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(syncId);
            buf.writeVarInt(action);
            buf.writeUUID(volumeId);
            buf.writeUtf(name, StorageVolume.MAX_NAME_LENGTH);
        }

        public static ManageVolume read(FriendlyByteBuf buf) {
            return new ManageVolume(buf.readVarInt(), buf.readVarInt(), buf.readUUID(),
                    buf.readUtf(StorageVolume.MAX_NAME_LENGTH));
        }
    }

    public record VolumeIcon(int syncId, UUID volumeId, ResourceLocation itemId) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(syncId);
            buf.writeUUID(volumeId);
            buf.writeResourceLocation(itemId);
        }
        public static VolumeIcon read(FriendlyByteBuf buf) {
            return new VolumeIcon(buf.readVarInt(), buf.readUUID(), buf.readResourceLocation());
        }
    }

    public record StateUpdate(int syncId, DigitalStorageScreenState state) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(syncId);
            state.write(buf);
        }

        public static StateUpdate read(FriendlyByteBuf buf) {
            return new StateUpdate(buf.readVarInt(), DigitalStorageScreenState.read(buf));
        }
    }

    public static void runCompatibilitySelfTest() {
        // Legacy wire fixtures: syncId 300, UTF-8 Chinese name, and volume UUID ending in 1.
        String createFixture = "ac020ce4b8bbe8a681e4bb93e5ba93";
        String manageFixture = "ac0200000000000000000000000000000000010ce4b8bbe8a681e4bb93e5ba93";
        CreateVolume create = new CreateVolume(300, "主要仓库");
        ManageVolume manage = new ManageVolume(300, DigitalStorageScreenHandler.RENAME_VOLUME_ACTION,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "主要仓库");
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            create.write(buf);
            if (!createFixture.equals(io.netty.buffer.ByteBufUtil.hexDump(buf))) {
                throw new IllegalStateException("Create volume protocol changed its legacy wire format");
            }
            buf.clear().writeBytes(java.util.HexFormat.of().parseHex(createFixture));
            if (!create.equals(CreateVolume.read(buf)) || buf.isReadable()) {
                throw new IllegalStateException("Legacy create volume message was not decoded exactly");
            }
            buf.clear();
            manage.write(buf);
            if (!manageFixture.equals(io.netty.buffer.ByteBufUtil.hexDump(buf))) {
                throw new IllegalStateException("Manage volume protocol changed its legacy wire format");
            }
            buf.clear().writeBytes(java.util.HexFormat.of().parseHex(manageFixture));
            if (!manage.equals(ManageVolume.read(buf)) || buf.isReadable()) {
                throw new IllegalStateException("Legacy manage volume message was not decoded exactly");
            }
            buf.clear();
            VolumeIcon icon = new VolumeIcon(300, manage.volumeId(), new ResourceLocation("minecraft", "diamond"));
            icon.write(buf);
            if (!icon.equals(VolumeIcon.read(buf)) || buf.isReadable()) {
                throw new IllegalStateException("Volume icon request did not round-trip exactly");
            }
            buf.clear();
            buf.writeVarInt(300);
            buf.writeUtf("x".repeat(StorageVolume.MAX_NAME_LENGTH + 1));
            boolean rejected = false;
            try {
                CreateVolume.read(buf);
            } catch (io.netty.handler.codec.DecoderException expected) {
                rejected = true;
            }
            if (!rejected) {
                throw new IllegalStateException("Oversized volume request name was accepted");
            }
        } finally {
            buf.release();
        }
    }
}
