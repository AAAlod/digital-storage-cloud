package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

public final class FabricTierReloadListener implements SimpleSynchronousResourceReloadListener {
    @Override
    public ResourceLocation getFabricId() {
        return DigitalStorage.id("tier_registry");
    }

    @Override
    public void onResourceManagerReload(ResourceManager manager) {
        DigitalStorageTierRegistry.INSTANCE.reload(manager);
    }
}
