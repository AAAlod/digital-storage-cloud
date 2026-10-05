package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.CreateVolume;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.ManageVolume;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.StateUpdate;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ForgeScreenNetworking {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(DigitalStorage.id("screen"),
            () -> "4", "4"::equals, "4"::equals);

    private ForgeScreenNetworking() { }

    public static void register() {
        CHANNEL.messageBuilder(dev.kehai.digitalstorage.screen.BatchScreenProtocol.Request.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .encoder(dev.kehai.digitalstorage.screen.BatchScreenProtocol.Request::write)
                .decoder(dev.kehai.digitalstorage.screen.BatchScreenProtocol.Request::read)
                .consumerMainThread((request, context) -> {
                    var player = context.get().getSender();
                    if (player != null) DigitalStorageScreenHandler.handleRequest(player, request);
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(dev.kehai.digitalstorage.screen.BatchScreenProtocol.Reply.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(dev.kehai.digitalstorage.screen.BatchScreenProtocol.Reply::write)
                .decoder(dev.kehai.digitalstorage.screen.BatchScreenProtocol.Reply::read)
                .consumerMainThread((reply, context) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ForgeClient.applyBatch(reply));
                    context.get().setPacketHandled(true);
                }).add();
        DigitalStorageScreenHandler.setBatchSender((player, reply) -> CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), reply));
        CHANNEL.messageBuilder(dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.VolumeIcon.class, 3, NetworkDirection.PLAY_TO_SERVER)
                .encoder(dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.VolumeIcon::write)
                .decoder(dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol.VolumeIcon::read)
                .consumerMainThread((request, context) -> {
                    var player = context.get().getSender();
                    if (player != null) DigitalStorageScreenHandler.handleRequest(player, request);
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(CreateVolume.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CreateVolume::write).decoder(CreateVolume::read)
                .consumerMainThread((request, context) -> {
                    var player = context.get().getSender();
                    if (player != null) DigitalStorageScreenHandler.handleRequest(player, request);
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(ManageVolume.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ManageVolume::write).decoder(ManageVolume::read)
                .consumerMainThread((request, context) -> {
                    var player = context.get().getSender();
                    if (player != null) DigitalStorageScreenHandler.handleRequest(player, request);
                    context.get().setPacketHandled(true);
                }).add();
        CHANNEL.messageBuilder(StateUpdate.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(StateUpdate::write).decoder(StateUpdate::read)
                .consumerMainThread((update, context) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ForgeClient.apply(update));
                    context.get().setPacketHandled(true);
                }).add();
        DigitalStorageScreenHandler.setStateSender((player, update) ->
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), update));
    }
}
