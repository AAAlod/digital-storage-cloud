package dev.kehai.digitalstorage.audit;

import dev.kehai.digitalstorage.forge.ForgeAccessorBlockEntity;
import dev.kehai.digitalstorage.forge.ForgeDigitalStorage;
import dev.kehai.digitalstorage.screen.BatchScreenSession;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

/** Prepares a large network outside the tick under measurement. */
final class RecommendationTickAudit {
    private static BlockPos accessorPos, connectorPos;
    private static UUID owner, volumeId;
    private static ForgeAccessorBlockEntity accessor;
    private static ServerPlayer player;
    private static com.tom.storagemod.util.MultiItemHandler network;
    private static final ArrayList<LazyOptional<IItemHandler>> handlers = new ArrayList<>();
    static void prepare(MinecraftServer server) {
        var world = server.overworld(); accessorPos = world.getSharedSpawnPos().above(40).east(112); connectorPos = accessorPos.east();
        world.getChunkAt(accessorPos); world.getChunkAt(connectorPos);
        if (!world.getBlockState(accessorPos).isAir() || !world.getBlockState(connectorPos).isAir()) throw new IllegalStateException("Timing fixture requires empty air");
        var state = DigitalStorageState.get(world); owner = UUID.randomUUID();
        var volume = state.createVolume(owner, "recommendation timing", 1).orElseThrow(); volumeId = volume.id();
        player = new ServerPlayer(server, world, new com.mojang.authlib.GameProfile(owner, "DSCTiming"));
        world.setBlockAndUpdate(accessorPos, ForgeDigitalStorage.ACCESSOR.get().defaultBlockState());
        world.setBlockAndUpdate(connectorPos, com.tom.storagemod.Content.connector.get().defaultBlockState());
        accessor = (ForgeAccessorBlockEntity) world.getBlockEntity(accessorPos);
        var binding = new CompoundTag(); binding.putUUID("ControllerId", owner); binding.putUUID("BoundVolumeId", volumeId);
        ((net.minecraft.world.level.block.entity.BlockEntity) accessor).load(binding); accessor.onLoad();
        var connector = (com.tom.storagemod.tile.InventoryConnectorBlockEntity) world.getBlockEntity(connectorPos);
        var data = server.getWorldData().overworldData(); long old = world.getGameTime();
        try { data.setGameTime(old - Math.floorMod(old, 20)); connector.updateServer(); } finally { data.setGameTime(old); }
        network = (com.tom.storagemod.util.MultiItemHandler) connector.getInventory().orElseThrow(() -> new IllegalStateException("Missing timing network"));
        for (int i = 0; i < 128; i++) {
            var h = new net.minecraftforge.items.ItemStackHandler(27); h.setStackInSlot(0, new ItemStack(Items.STONE, 1));
            var optional = LazyOptional.<IItemHandler>of(() -> h); handlers.add(optional); network.add(optional);
        }
        network.refresh();
    }
    static void measure() { for (var handler : handlers) if (!network.getHandlers().contains(handler)) network.add(handler); network.refresh(); BatchScreenSession.runSingleRecommendationTimingSelfTest(player, accessor); }
    static void cleanup(MinecraftServer server) {
        var world = server.overworld(); world.removeBlock(accessorPos, false); world.removeBlock(connectorPos, false);
        if (!DigitalStorageState.get(world).deleteEmptyVolume(owner, volumeId)) throw new IllegalStateException("Timing fixture volume cleanup failed");
        handlers.clear();
    }
}
