package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.CreateVolume;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.ManageVolume;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;

public final class FabricScreenNetworking {
    private FabricScreenNetworking() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(dev.kehai.digitalstorage.screen.BatchScreenProtocol.REQUEST,
                (server, player, handler, buf, response) -> {
                    var request = dev.kehai.digitalstorage.screen.BatchScreenProtocol.Request.read(buf);
                    server.execute(() -> DigitalStorageScreenHandler.handleRequest(player, request));
                });
        DigitalStorageScreenHandler.setBatchSender((player, reply) -> {
            FriendlyByteBuf buf = PacketByteBufs.create(); reply.write(buf);
            ServerPlayNetworking.send(player, dev.kehai.digitalstorage.screen.BatchScreenProtocol.REPLY, buf);
        });
        ServerPlayNetworking.registerGlobalReceiver(DigitalStorageScreenProtocol.VOLUME_ICON_PACKET_ID,
                (server, player, networkHandler, buf, responseSender) -> {
                    var request = DigitalStorageScreenProtocol.VolumeIcon.read(buf);
                    server.execute(() -> DigitalStorageScreenHandler.handleRequest(player, request));
                });
        DigitalStorageScreenHandler.setStateSender((player, update) -> {
            FriendlyByteBuf buf = PacketByteBufs.create();
            update.write(buf);
            ServerPlayNetworking.send(player, DigitalStorageScreenProtocol.STATE_PACKET_ID, buf);
        });
        ServerPlayNetworking.registerGlobalReceiver(
                DigitalStorageScreenProtocol.CREATE_VOLUME_PACKET_ID,
                (server, player, networkHandler, buf, responseSender) -> {
                    var request = DigitalStorageScreenProtocol.CreateVolume.read(buf);
                    server.execute(() -> DigitalStorageScreenHandler.handleRequest(player, request));
                });
        ServerPlayNetworking.registerGlobalReceiver(
                DigitalStorageScreenProtocol.MANAGE_VOLUME_PACKET_ID,
                (server, player, networkHandler, buf, responseSender) -> {
                    var request = DigitalStorageScreenProtocol.ManageVolume.read(buf);
                    server.execute(() -> DigitalStorageScreenHandler.handleRequest(player, request));
                });
    }
}
