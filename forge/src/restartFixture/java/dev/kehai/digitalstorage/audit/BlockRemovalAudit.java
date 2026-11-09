package dev.kehai.digitalstorage.audit;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.forge.ForgeAccessorBlockEntity;
import dev.kehai.digitalstorage.forge.ForgeDigitalStorage;
import dev.kehai.digitalstorage.optimization.BatchTransfer;
import dev.kehai.digitalstorage.optimization.BatchTransfers;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

/** Opt-in audit-world only: preserve each real removal result over a server restart. */
final class BlockRemovalAudit {
    private record Case(String name, UUID owner, UUID volume, BlockPos accessor, BlockPos connector, BlockPos removed, BlockPos other) { }
    private static final List<Case> CASES = new ArrayList<>();
    private static final ItemKey STONE = ItemKey.of(Items.STONE);
    private BlockRemovalAudit() { }

    static void beforeStop(MinecraftServer server) {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("sophisticatedstorage")) throw new IllegalStateException("BLOCK-01 requires Sophisticated Storage for restart audit");
        var world = server.overworld();
        for (int i = 0; i < 5; i++) run(world, i);
        DigitalStorageState.get(world).flushNow();
        DigitalStorage.LOGGER.info("BLOCK-01 restart audit first lifecycle: five actual removal results retained for restart");
    }

    private static void run(ServerLevel world, int index) {
        var base = world.getSharedSpawnPos().above(40).east(index * 16);
        var connectorPos = base.east(); var removed = connectorPos.east();
        var left = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.LEFT);
        boolean doubled = index == 2 || index == 3, exporting = index == 1 || index == 3, soph = index == 4;
        BlockPos other = doubled ? removed.relative(ChestBlock.getConnectedDirection(left)) : null;
        for (var pos : other == null ? List.of(base, connectorPos, removed) : List.of(base, connectorPos, removed, other)) {
            world.getChunkAt(pos);
            expect(world.getBlockState(pos).isAir() && world.getBlockEntity(pos) == null, "BLOCK-01 requires unused air at " + pos);
        }
        var state = DigitalStorageState.get(world); UUID owner = UUID.randomUUID();
        var volume = state.createVolume(owner, "BLOCK-01 restart " + index, 1).orElseThrow();
        var player = new ServerPlayer(world.getServer(), world, new com.mojang.authlib.GameProfile(owner, "DSCBlockAudit"));
        world.setBlockAndUpdate(base, ForgeDigitalStorage.ACCESSOR.get().defaultBlockState());
        world.setBlockAndUpdate(connectorPos, com.tom.storagemod.Content.connector.get().defaultBlockState());
        if (soph) {
            var block = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation("sophisticatedstorage", "barrel"));
            expect(block != null, "BLOCK-01 Sophisticated barrel missing"); world.setBlockAndUpdate(removed, block.defaultBlockState());
        } else if (doubled) {
            world.setBlock(removed, left, 2);
            world.setBlock(other, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
        } else world.setBlockAndUpdate(removed, Blocks.CHEST.defaultBlockState());
        var accessor = (ForgeAccessorBlockEntity) world.getBlockEntity(base);
        var binding = new CompoundTag(); binding.putUUID("ControllerId", owner); binding.putUUID("BoundVolumeId", volume.id());
        ((net.minecraft.world.level.block.entity.BlockEntity) accessor).load(binding); accessor.onLoad();
        var connector = (com.tom.storagemod.tile.InventoryConnectorBlockEntity) world.getBlockEntity(connectorPos);
        if (exporting) volume.record().storage().load(STONE, 128);
        else if (soph) {
            var h = world.getBlockEntity(removed).getCapability(ForgeCapabilities.ITEM_HANDLER, net.minecraft.core.Direction.UP)
                    .orElseThrow(() -> new IllegalStateException("BLOCK-01 Sophisticated capability missing"));
            expect(h.insertItem(0, new ItemStack(Items.STONE, 64), false).isEmpty()
                    && h.insertItem(1, new ItemStack(Items.STONE, 64), false).isEmpty(), "BLOCK-01 Sophisticated seed failed");
        } else {
            var chest = (ChestBlockEntity) world.getBlockEntity(removed); chest.setItem(0, new ItemStack(Items.STONE, 64));
            if (doubled) ((ChestBlockEntity) world.getBlockEntity(other)).setItem(0, new ItemStack(Items.STONE, 64));
            else chest.setItem(1, new ItemStack(Items.STONE, 64));
        }
        scan(world, connector);
        var route = BatchTransfers.open(player, accessor);
        var task = new BatchTransfer(volume.record(), route, List.of(new BatchTransfer.Entry(STONE, 128)), exporting);
        task.tick(1); expect(task.active() && task.moved() > 0 && task.moved() < 128, "BLOCK-01 task did not begin");
        expect(world.destroyBlock(removed, true), "BLOCK-01 removal failed");
        boolean fast = route.fastCurrent().getAsBoolean(); task.tick(128);
        String name = soph ? "sophisticated-import" : (doubled ? "double-" : "single-") + (exporting ? "export" : "import");
        var fixture = new Case(name, owner, volume.id(), base, connectorPos, removed, other);
        CASES.add(fixture);
        stoneDrops(world, removed).forEach(entity -> { entity.setNoGravity(true); entity.setDeltaMovement(0, 0, 0); });
        long total = total(world, fixture, volume.record().storage().amountOf(STONE));
        DigitalStorage.LOGGER.info("BLOCK-01 restart first {}: fastCurrent={}, moved={}, total={}, expected=128, state={}", name, fast, task.moved(), total, task.state());
        expect(total == 128, "BLOCK-01 " + name + " before restart conservation failed: total=" + total + ", expected=128");
    }

    static void prepareReload(MinecraftServer server) {
        for (var fixture : CASES) server.overworld().getChunkAt(fixture.removed());
    }

    static void afterRestart(MinecraftServer server) {
        var world = server.overworld(); var state = DigitalStorageState.get(world);
        expect(CASES.size() == 5, "BLOCK-01 restart cases missing");
        for (var fixture : CASES) {
            world.getChunkAt(fixture.removed());
            var volume = state.volume(fixture.volume()).orElseThrow(() -> new IllegalStateException("BLOCK-01 volume missing after restart"));
            long total = total(world, fixture, volume.record().storage().amountOf(STONE));
            DigitalStorage.LOGGER.info("BLOCK-01 restart second {}: total={}, expected=128", fixture.name(), total);
            expect(total == 128, "BLOCK-01 " + fixture.name() + " after restart conservation failed: total=" + total + ", expected=128");
            stoneDrops(world, fixture.removed()).forEach(ItemEntity::discard);
            if (fixture.other() != null && world.getBlockEntity(fixture.other()) instanceof ChestBlockEntity other) other.clearContent();
            for (var pos : fixture.other() == null ? List.of(fixture.accessor(), fixture.connector(), fixture.removed())
                    : List.of(fixture.accessor(), fixture.connector(), fixture.removed(), fixture.other())) world.removeBlock(pos, false);
            try (var tx = LedgerTransaction.open()) { volume.record().storage().extract(STONE, Long.MAX_VALUE, tx); tx.commit(); }
            expect(state.deleteEmptyVolume(fixture.owner(), fixture.volume()), "BLOCK-01 cleanup failed");
        }
        state.flushNow();
        DigitalStorage.LOGGER.info("BLOCK-01 restart audit passed: five real removal cases conserved after normal stop and restart");
    }
    private static List<ItemEntity> stoneDrops(ServerLevel world, BlockPos pos) {
        return world.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4), e -> e.getItem().is(Items.STONE));
    }
    private static long total(ServerLevel world, Case fixture, long stored) {
        long drops = stoneDrops(world, fixture.removed()).stream().mapToLong(e -> e.getItem().getCount()).sum();
        long other = 0;
        if (fixture.other() != null && world.getBlockEntity(fixture.other()) instanceof ChestBlockEntity chest)
            for (int i = 0; i < chest.getContainerSize(); i++) if (chest.getItem(i).is(Items.STONE)) other += chest.getItem(i).getCount();
        return stored + drops + other;
    }
    private static void scan(ServerLevel world, com.tom.storagemod.tile.InventoryConnectorBlockEntity connector) {
        var data = world.getServer().getWorldData().overworldData(); long old = world.getGameTime();
        try { data.setGameTime(old - Math.floorMod(old, 20)); connector.updateServer(); }
        finally { data.setGameTime(old); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
