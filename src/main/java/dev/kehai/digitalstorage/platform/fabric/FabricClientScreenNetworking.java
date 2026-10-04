package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.StateUpdate;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.FriendlyByteBuf;

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
                                && client.player.containerMenu instanceof DigitalStorageScreenHandler handler
                                && handler.containerId == update.syncId()) {
                            handler.applySyncedState(update.state());
                        }
                    });
                });
    }

    @Override
    public void send(DigitalStorageScreenProtocol.CreateVolume request) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        request.write(buf);
        ClientPlayNetworking.send(DigitalStorageScreenProtocol.CREATE_VOLUME_PACKET_ID, buf);
    }

    @Override
    public void send(DigitalStorageScreenProtocol.ManageVolume request) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        request.write(buf);
        ClientPlayNetworking.send(DigitalStorageScreenProtocol.MANAGE_VOLUME_PACKET_ID, buf);
    }

    @Override
    public void send(DigitalStorageScreenProtocol.VolumeIcon request) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        request.write(buf);
        ClientPlayNetworking.send(DigitalStorageScreenProtocol.VOLUME_ICON_PACKET_ID, buf);
    }
}
