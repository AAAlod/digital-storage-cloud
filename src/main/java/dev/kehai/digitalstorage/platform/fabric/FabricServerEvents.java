package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.command.DigitalStorageCommands;
import dev.kehai.digitalstorage.platform.fabric.tom.TomMigrationManager;
import dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkCache;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Loader event wiring; storage services expose ordinary lifecycle methods. */
public final class FabricServerEvents {
    private static boolean registered;

    private FabricServerEvents() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            DigitalStorageMod.registerAdvancedHopperBlockEntitySupport();
            DigitalStorageState.onServerStarting(server);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DigitalStorageState.onServerTick(server);
            TomMigrationManager.tick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(DigitalStorageState::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            DigitalStorageState.onServerStopped(server);
            DigitalStorageMountTracker.clear();
            TomMigrationManager.clear();
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(DigitalStorageMountTracker::onBlockEntityLoad);
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, world) -> {
            DigitalStorageMountTracker.onBlockEntityUnload(blockEntity, world);
            TomNetworkCache.onBlockEntityUnload(blockEntity, world);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                DigitalStorageCommands.register(dispatcher));
    }
}
