package dev.kehai.digitalstorage;

import com.tom.storagemod.Content;
import dev.kehai.digitalstorage.block.AdvancedInventoryHopperBlock;
import dev.kehai.digitalstorage.block.DigitalStorageAccessorBlock;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import dev.kehai.digitalstorage.platform.fabric.mixin.BlockEntityTypeAccessor;
import dev.kehai.digitalstorage.integration.TomIntegrationStatus;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import dev.kehai.digitalstorage.platform.fabric.FabricServerEvents;
import dev.kehai.digitalstorage.platform.fabric.FabricScreenNetworking;
import dev.kehai.digitalstorage.platform.fabric.FabricTierReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import java.util.HashSet;
import java.util.Set;
import static dev.kehai.digitalstorage.DigitalStorage.id;

public final class DigitalStorageMod implements ModInitializer {
    public static final Block DIGITAL_STORAGE_ACCESSOR = Registry.register(
            BuiltInRegistries.BLOCK,
            id("digital_storage_accessor"),
            new DigitalStorageAccessorBlock(FabricBlockSettings.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5F, 6.0F)
                    .requiresCorrectToolForDrops())
    );

    public static final Item DIGITAL_STORAGE_ACCESSOR_ITEM = Registry.register(
            BuiltInRegistries.ITEM,
            id("digital_storage_accessor"),
            new BlockItem(DIGITAL_STORAGE_ACCESSOR, new Item.Properties())
    );

    public static final Block ADVANCED_INVENTORY_HOPPER = Registry.register(
            BuiltInRegistries.BLOCK,
            id("advanced_inventory_hopper"),
            new AdvancedInventoryHopperBlock()
    );

    public static final Item ADVANCED_INVENTORY_HOPPER_ITEM = Registry.register(
            BuiltInRegistries.ITEM,
            id("advanced_inventory_hopper"),
            new BlockItem(ADVANCED_INVENTORY_HOPPER, new Item.Properties())
    );

    public static final CreativeModeTab DIGITAL_STORAGE_ITEM_GROUP = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            id("digital_storage_cloud"),
            FabricItemGroup.builder()
                    .title(Component.translatable("itemGroup.digitalstorage.digital_storage_cloud"))
                    .icon(() -> new ItemStack(DIGITAL_STORAGE_ACCESSOR_ITEM))
                    .displayItems((displayContext, entries) -> {
                        entries.accept(DIGITAL_STORAGE_ACCESSOR_ITEM);
                        entries.accept(ADVANCED_INVENTORY_HOPPER_ITEM);
                    })
                    .build()
    );

    public static final BlockEntityType<DigitalStorageAccessorBlockEntity> DIGITAL_STORAGE_ACCESSOR_BLOCK_ENTITY =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    id("digital_storage_accessor"),
                    BlockEntityType.Builder.<DigitalStorageAccessorBlockEntity>of(
                            dev.kehai.digitalstorage.platform.fabric.FabricAccessorBlockEntity::new,
                            DIGITAL_STORAGE_ACCESSOR
                    ).build(null)
            );

    public static final MenuType<DigitalStorageScreenHandler> DIGITAL_STORAGE_SCREEN_HANDLER =
            Registry.register(
                    BuiltInRegistries.MENU,
                    id("digital_storage_accessor"),
                    new ExtendedScreenHandlerType<>(DigitalStorageScreenHandler::new)
            );

    @Override
    public void onInitialize() {
        DigitalStorageContent.install(() -> DIGITAL_STORAGE_ACCESSOR_BLOCK_ENTITY,
                () -> DIGITAL_STORAGE_SCREEN_HANDLER, () -> DIGITAL_STORAGE_ACCESSOR_ITEM,
                dev.kehai.digitalstorage.platform.fabric.FabricAccessorBlockEntity::new);
        DigitalStorageConfig.load(FabricLoader.getInstance().getConfigDir());
        dev.kehai.digitalstorage.optimization.NetworkServices.install(
                new dev.kehai.digitalstorage.platform.fabric.FabricNetworkServices());
        FabricServerEvents.register();
        FabricScreenNetworking.register();
        ItemSecurityPolicy.reload();
        ResourceManagerHelper.get(PackType.SERVER_DATA)
                .registerReloadListener(new FabricTierReloadListener());

        ItemStorage.SIDED.registerForBlockEntity(
                (blockEntity, direction) -> dev.kehai.digitalstorage.platform.fabric.FabricAccessorBlockEntity.canonicalStorage(blockEntity),
                DIGITAL_STORAGE_ACCESSOR_BLOCK_ENTITY
        );

        DigitalStorage.LOGGER.info("Digital Storage Cloud initialized with Account -> Volume -> Accessor architecture");
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
