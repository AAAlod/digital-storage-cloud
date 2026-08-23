package dev.kehai.digitalstorage;

import com.tom.storagemod.Content;
import dev.kehai.digitalstorage.block.AdvancedInventoryHopperBlock;
import dev.kehai.digitalstorage.block.DigitalStorageAccessorBlock;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.command.DigitalStorageCommands;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import dev.kehai.digitalstorage.optimization.TomMigrationManager;
import dev.kehai.digitalstorage.mixin.BlockEntityTypeAccessor;
import dev.kehai.digitalstorage.integration.TomIntegrationStatus;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.ResourceType;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DigitalStorageMod implements ModInitializer {
    public static final String MOD_ID = "digitalstorage";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Block DIGITAL_STORAGE_ACCESSOR = Registry.register(
            Registries.BLOCK,
            id("digital_storage_accessor"),
            new DigitalStorageAccessorBlock(FabricBlockSettings.create()
                    .mapColor(MapColor.IRON_GRAY)
                    .strength(3.5F, 6.0F)
                    .requiresTool())
    );

    public static final Item DIGITAL_STORAGE_ACCESSOR_ITEM = Registry.register(
            Registries.ITEM,
            id("digital_storage_accessor"),
            new BlockItem(DIGITAL_STORAGE_ACCESSOR, new Item.Settings())
    );

    public static final Block ADVANCED_INVENTORY_HOPPER = Registry.register(
            Registries.BLOCK,
            id("advanced_inventory_hopper"),
            new AdvancedInventoryHopperBlock()
    );

    public static final Item ADVANCED_INVENTORY_HOPPER_ITEM = Registry.register(
            Registries.ITEM,
            id("advanced_inventory_hopper"),
            new BlockItem(ADVANCED_INVENTORY_HOPPER, new Item.Settings())
    );

    public static final BlockEntityType<DigitalStorageAccessorBlockEntity> DIGITAL_STORAGE_ACCESSOR_BLOCK_ENTITY =
            Registry.register(
                    Registries.BLOCK_ENTITY_TYPE,
                    id("digital_storage_accessor"),
                    BlockEntityType.Builder.create(
                            DigitalStorageAccessorBlockEntity::new,
                            DIGITAL_STORAGE_ACCESSOR
                    ).build(null)
            );

    public static final ScreenHandlerType<DigitalStorageScreenHandler> DIGITAL_STORAGE_SCREEN_HANDLER =
            Registry.register(
                    Registries.SCREEN_HANDLER,
                    id("digital_storage_accessor"),
                    new ExtendedScreenHandlerType<>(DigitalStorageScreenHandler::new)
            );

    @Override
    public void onInitialize() {
        DigitalStorageConfig.load();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> registerAdvancedHopperBlockEntitySupport());
        DigitalStorageState.registerLifecycle();
        DigitalStorageScreenHandler.registerNetworking();
        ItemSecurityPolicy.reload();
        DigitalStorageMountTracker.register();
        TomMigrationManager.register();
        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(DigitalStorageTierRegistry.INSTANCE);

        ItemStorage.SIDED.registerForBlockEntity(
                (blockEntity, direction) -> blockEntity.getCanonicalStorage(),
                DIGITAL_STORAGE_ACCESSOR_BLOCK_ENTITY
        );

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE)
                .register(entries -> {
                    entries.add(DIGITAL_STORAGE_ACCESSOR_ITEM);
                    entries.add(ADVANCED_INVENTORY_HOPPER_ITEM);
                });

        DigitalStorageCommands.register();
        LOGGER.info("Digital Storage Cloud initialized with Account -> Volume -> Accessor architecture");
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    public static void registerAdvancedHopperBlockEntitySupport() {
        BlockEntityType<?> hopperType = Content.invHopperBasicTile.get();
        if (hopperType == null) {
            throw new IllegalStateException("Tom's inventory hopper BlockEntityType is not initialized");
        }
        BlockEntityTypeAccessor accessor = (BlockEntityTypeAccessor) (Object) hopperType;
        Set<Block> supportedBlocks = new HashSet<>(accessor.digitalstorage$getBlocks());
        supportedBlocks.add(ADVANCED_INVENTORY_HOPPER);
        accessor.digitalstorage$setBlocks(Set.copyOf(supportedBlocks));
        TomIntegrationStatus.markAdvancedHopperSupportActive();
    }
}
