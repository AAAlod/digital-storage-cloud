package dev.kehai.digitalstorage;

import dev.kehai.digitalstorage.client.screen.DigitalStorageScreen;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.gui.screen.ingame.HandledScreens;

public final class DigitalStorageClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                DigitalStorageMod.registerAdvancedHopperBlockEntitySupport());
        HandledScreens.register(DigitalStorageMod.DIGITAL_STORAGE_SCREEN_HANDLER, DigitalStorageScreen::new);
        ClientPlayNetworking.registerGlobalReceiver(
                DigitalStorageScreenHandler.STATE_PACKET_ID,
                (client, networkHandler, buf, responseSender) -> {
                    int syncId = buf.readVarInt();
                    DigitalStorageScreenState state = DigitalStorageScreenState.read(buf);
                    client.execute(() -> {
                        if (client.player != null
                                && client.player.currentScreenHandler instanceof DigitalStorageScreenHandler handler
                                && handler.syncId == syncId) {
                            handler.applySyncedState(state);
                        }
                    });
                }
        );

    }
}
