package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;

public final class FabricClientScreenNetworking implements DigitalStorageScreenProtocol.RequestSender {
    public static final FabricClientScreenNetworking INSTANCE = new FabricClientScreenNetworking();

    private FabricClientScreenNetworking() {
    }

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(
                DigitalStorageScreenProtocol.STATE_PACKET_ID,
                (client, networkHandler, buf, responseSender) -> {
                    var update = DigitalStorageScreenProtocol.StateUpdate.read(buf);
                    client.execute(() -> {
                        if (client.player != null
                                && client.player.currentScreenHandler instanceof DigitalStorageScreenHandler handler
                                && handler.syncId == update.syncId()) {
                            handler.applySyncedState(update.state());
                        }
                    });
                });
    }

    @Override
    public void send(DigitalStorageScreenProtocol.CreateVolume request) {
        PacketByteBuf buf = PacketByteBufs.create();
        request.write(buf);
        ClientPlayNetworking.send(DigitalStorageScreenProtocol.CREATE_VOLUME_PACKET_ID, buf);
    }

    @Override
    public void send(DigitalStorageScreenProtocol.ManageVolume request) {
        PacketByteBuf buf = PacketByteBufs.create();
        request.write(buf);
        ClientPlayNetworking.send(DigitalStorageScreenProtocol.MANAGE_VOLUME_PACKET_ID, buf);
    }
}
