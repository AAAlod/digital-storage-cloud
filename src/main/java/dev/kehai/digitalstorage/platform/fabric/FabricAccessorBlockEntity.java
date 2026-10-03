package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import java.util.Collections;
import java.util.Iterator;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Fabric storage and menu-opening adapter; binding and permissions stay in the shared base. */
public final class FabricAccessorBlockEntity extends DigitalStorageAccessorBlockEntity
        implements Storage<ItemVariant>, ExtendedScreenHandlerFactory {
    public FabricAccessorBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    public static FabricDigitalItemStorage canonicalStorage(DigitalStorageAccessorBlockEntity accessor) {
        var record = accessor.getRecord();
        return record == null ? null : FabricDigitalItemStorage.of(record.storage());
    }

    @Override
    public void writeScreenOpeningData(ServerPlayer player, FriendlyByteBuf buf) {
        buf.writeBlockPos(getBlockPos());
        DigitalStorageScreenState.capture(player, this, Component.empty()).write(buf);
    }

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        var storage = canonicalStorage(this);
        return storage == null ? 0 : storage.insert(resource, maxAmount, transaction);
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        var storage = canonicalStorage(this);
        return storage == null ? 0 : storage.extract(resource, maxAmount, transaction);
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
        var storage = canonicalStorage(this);
        return storage == null ? Collections.emptyIterator() : storage.iterator();
    }
}
