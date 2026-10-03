package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.DigitalStorageContent;
import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

public final class FabricAccessorSelfTest {
    private FabricAccessorSelfTest() {
    }

    public static void run() {
        var state = DigitalStorageMod.DIGITAL_STORAGE_ACCESSOR.defaultBlockState();
        var created = DigitalStorageContent.createAccessor(BlockPos.ZERO, state);
        var loaded = DigitalStorageContent.accessorType().create(BlockPos.ZERO, state);
        if (!(created instanceof FabricAccessorBlockEntity accessor)
                || !(loaded instanceof FabricAccessorBlockEntity)
                || !(created instanceof ExtendedScreenHandlerFactory)
                || created.getType() != DigitalStorageContent.accessorType()
                || DigitalStorageContent.screenType() != DigitalStorageMod.DIGITAL_STORAGE_SCREEN_HANDLER
                || DigitalStorageContent.accessorItem() != DigitalStorageMod.DIGITAL_STORAGE_ACCESSOR_ITEM) {
            throw new IllegalStateException("Fabric accessor creation/loading/menu registration handles diverged");
        }
        try (Transaction transaction = Transaction.openOuter()) {
            ItemVariant stone = ItemVariant.of(Items.STONE);
            if (accessor.insert(stone, 64, transaction) != 0 || accessor.extract(stone, 64, transaction) != 0
                    || accessor.iterator().hasNext() || FabricAccessorBlockEntity.canonicalStorage(accessor) != null) {
                throw new IllegalStateException("Unbound Fabric accessor exposed storage");
            }
            transaction.commit();
        }
    }
}
