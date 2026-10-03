package dev.kehai.digitalstorage;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.Item;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.math.BlockPos;

/** Loader-owned registration handles used by shared content without initializing a loader entry point. */
public final class DigitalStorageContent {
    private static Supplier<BlockEntityType<DigitalStorageAccessorBlockEntity>> accessorType;
    private static Supplier<ScreenHandlerType<DigitalStorageScreenHandler>> screenType;
    private static Supplier<Item> accessorItem;
    private static BiFunction<BlockPos, BlockState, DigitalStorageAccessorBlockEntity> accessorFactory;

    private DigitalStorageContent() {
    }

    public static void install(Supplier<BlockEntityType<DigitalStorageAccessorBlockEntity>> blockEntityType,
                               Supplier<ScreenHandlerType<DigitalStorageScreenHandler>> menuType,
                               Supplier<Item> item,
                               BiFunction<BlockPos, BlockState, DigitalStorageAccessorBlockEntity> factory) {
        accessorType = Objects.requireNonNull(blockEntityType);
        screenType = Objects.requireNonNull(menuType);
        accessorItem = Objects.requireNonNull(item);
        accessorFactory = Objects.requireNonNull(factory);
    }

    public static BlockEntityType<DigitalStorageAccessorBlockEntity> accessorType() {
        return Objects.requireNonNull(accessorType, "Content has not been installed").get();
    }

    public static ScreenHandlerType<DigitalStorageScreenHandler> screenType() {
        return Objects.requireNonNull(screenType, "Content has not been installed").get();
    }

    public static Item accessorItem() {
        return Objects.requireNonNull(accessorItem, "Content has not been installed").get();
    }

    public static DigitalStorageAccessorBlockEntity createAccessor(BlockPos pos, BlockState state) {
        return Objects.requireNonNull(accessorFactory, "Content has not been installed").apply(pos, state);
    }
}
