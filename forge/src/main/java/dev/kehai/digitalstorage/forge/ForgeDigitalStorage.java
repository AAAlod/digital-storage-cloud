package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.DigitalStorageContent;
import dev.kehai.digitalstorage.block.DigitalStorageAccessorBlock;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.security.ItemSecurityPolicy;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

@Mod(DigitalStorage.MOD_ID)
public final class ForgeDigitalStorage {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, DigitalStorage.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, DigitalStorage.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DigitalStorage.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, DigitalStorage.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, DigitalStorage.MOD_ID);
    public static final RegistryObject<Block> ACCESSOR = BLOCKS.register("digital_storage_accessor", () ->
            new DigitalStorageAccessorBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                    .strength(3.5F, 6.0F).requiresCorrectToolForDrops()));
    public static final RegistryObject<Item> ACCESSOR_ITEM = ITEMS.register("digital_storage_accessor", () ->
            new BlockItem(ACCESSOR.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<DigitalStorageAccessorBlockEntity>> ACCESSOR_TYPE =
            ENTITIES.register("digital_storage_accessor", () -> BlockEntityType.Builder
                    .<DigitalStorageAccessorBlockEntity>of(ForgeAccessorBlockEntity::new, ACCESSOR.get()).build(null));
    public static final RegistryObject<MenuType<DigitalStorageScreenHandler>> MENU = MENUS.register("digital_storage_accessor", () ->
            IForgeMenuType.create(DigitalStorageScreenHandler::new));
    private static final RegistryObject<CreativeModeTab> TAB = TABS.register("digital_storage_cloud", () ->
            CreativeModeTab.builder().title(Component.translatable("itemGroup.digitalstorage.digital_storage_cloud"))
                    .icon(() -> new ItemStack(ACCESSOR_ITEM.get()))
                    .displayItems((parameters, output) -> output.accept(ACCESSOR_ITEM.get())).build());

    public ForgeDigitalStorage() {
        var bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        MENUS.register(bus);
        TABS.register(bus);
        DigitalStorageContent.install(ACCESSOR_TYPE, MENU, ACCESSOR_ITEM, ForgeAccessorBlockEntity::new);
        dev.kehai.digitalstorage.storage.ItemKey.installStackDataAdapter(new ForgeItemKeys());
        DigitalStorageConfig.load(FMLPaths.CONFIGDIR.get());
        dev.kehai.digitalstorage.optimization.NetworkServices.install(new ForgeNetworkServices());
        ItemSecurityPolicy.reload();
        ForgeScreenNetworking.register();
        MinecraftForge.EVENT_BUS.register(ForgeServerEvents.class);
        MinecraftForge.EVENT_BUS.register(ForgeItemKeySelfTest.class);
        DigitalStorage.LOGGER.info("Forge platform bootstrap initialized; Tom capability integration is under development");
    }
}
