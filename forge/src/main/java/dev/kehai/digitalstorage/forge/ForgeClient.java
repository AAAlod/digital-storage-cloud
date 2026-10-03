package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.client.screen.DigitalStorageScreen;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = DigitalStorage.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ForgeClient {
    private static final DigitalStorageScreenProtocol.RequestSender SENDER = new DigitalStorageScreenProtocol.RequestSender() {
        public void send(DigitalStorageScreenProtocol.CreateVolume request) { ForgeScreenNetworking.CHANNEL.sendToServer(request); }
        public void send(DigitalStorageScreenProtocol.ManageVolume request) { ForgeScreenNetworking.CHANNEL.sendToServer(request); }
    };

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.<DigitalStorageScreenHandler, DigitalStorageScreen>register(ForgeDigitalStorage.MENU.get(),
                (menu, inventory, title) -> new DigitalStorageScreen(menu, inventory, title, SENDER)));
    }

    public static void apply(DigitalStorageScreenProtocol.StateUpdate update) {
        var player = Minecraft.getInstance().player;
        if (player != null && player.containerMenu instanceof DigitalStorageScreenHandler handler
                && handler.containerId == update.syncId()) handler.applySyncedState(update.state());
    }
}
