package dev.kehai.digitalstorage;

import dev.kehai.digitalstorage.client.screen.DigitalStorageScreen;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.platform.fabric.FabricClientScreenNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.gui.screens.MenuScreens;

public final class DigitalStorageClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                DigitalStorageMod.registerAdvancedHopperBlockEntitySupport());
        MenuScreens.<DigitalStorageScreenHandler, DigitalStorageScreen>register(
                DigitalStorageMod.DIGITAL_STORAGE_SCREEN_HANDLER, (handler, inventory, title) ->
                new DigitalStorageScreen(handler, inventory, title, FabricClientScreenNetworking.INSTANCE));
        FabricClientScreenNetworking.register();
    }
}
