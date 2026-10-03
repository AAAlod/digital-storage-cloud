package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

public final class FabricTierReloadListener implements SimpleSynchronousResourceReloadListener {
    @Override
    public Identifier getFabricId() {
        return DigitalStorage.id("tier_registry");
    }

    @Override
    public void reload(ResourceManager manager) {
        DigitalStorageTierRegistry.INSTANCE.reload(manager);
    }
}
