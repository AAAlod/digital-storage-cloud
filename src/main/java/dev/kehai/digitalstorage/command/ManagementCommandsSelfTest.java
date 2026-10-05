package dev.kehai.digitalstorage.command;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.DigitalStorageContent;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Actual dispatcher and ownership checks; mutating fixtures require a declared private audit-world. */
public final class ManagementCommandsSelfTest {
    private ManagementCommandsSelfTest() { }
    public static void run(MinecraftServer server) {
        var dispatcher = server.getCommands().getDispatcher();
        var root = dispatcher.getRoot().getChild("digitalstorage");
        var low = server.createCommandSourceStack().withPermission(0);
        var admin = server.createCommandSourceStack().withPermission(2);
        expect(root != null && dispatcher.getRoot().getChild("dsc") != null, "Both command roots registered");
        expect(root.getChild("volume").getChild("create").canUse(low)
                && root.getChild("accessor").getChild("bind").canUse(low), "Player management available");
        for (var name : new String[]{"reloadconfig", "stats"}) {
            expect(!root.getChild(name).canUse(low) && root.getChild(name).canUse(admin), "Admin permission: " + name);
        }
        expect(!root.getChild("accessor").getChild("forceclear").canUse(low)
                && root.getChild("accessor").getChild("forceclear").canUse(admin), "Force-clear permission");
        String configured = System.getProperty("digitalstorage.integrationTestRoot");
        if (configured == null) return;
        Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        if (!worldRoot.equals(Path.of(configured).toAbsolutePath().normalize())
                || !worldRoot.toString().replace('\\', '/').contains("/maintenance/work/fabric-decoupling/")
                || !worldRoot.getFileName().toString().equals("audit-world")) {
            throw new IllegalStateException("Management fixture requires a declared task audit-world");
        }
        var world = server.overworld();
        var pos = world.getSharedSpawnPos().above(24);
        world.getChunkAt(pos);
        expect(world.getBlockState(pos).isAir() && world.getBlockEntity(pos) == null, "Untouched fixture location");
        var state = DigitalStorageState.get(world);
        int accounts = state.accountCount(), volumes = state.volumeCount();
        UUID owner = UUID.randomUUID(), foreign = UUID.randomUUID();
        var player = new ServerPlayer(server, world, new com.mojang.authlib.GameProfile(owner, "DSCCommandA"));
        var other = new ServerPlayer(server, world, new com.mojang.authlib.GameProfile(foreign, "DSCCommandB"));
        var ownSource = low.withEntity(player).withLevel(world);
        var foreignSource = low.withEntity(other).withLevel(world);
        String location = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        try {
            expect(dispatcher.execute("digitalstorage volume create Private alpha", ownSource) == 1, "Player create");
            expect(dispatcher.execute("dsc volume create Private beta", foreignSource) == 1, "Alias create");
            var volume = state.volumes(owner).get(0);
            String id = volume.id().toString();
            expect(dispatcher.execute("dsc volume list", ownSource) == 1, "Alias owner listing");
            expect(dispatcher.execute("digitalstorage volume rename " + id + " foreign", foreignSource) == 0
                    && dispatcher.execute("digitalstorage volume delete " + id, foreignSource) == 0,
                    "Foreign rename/delete rejected");
            world.setBlockAndUpdate(pos, ((BlockItem) DigitalStorageContent.accessorItem()).getBlock().defaultBlockState());
            var accessor = (DigitalStorageAccessorBlockEntity) world.getBlockEntity(pos);
            dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler.runResponseSelfTest(player, accessor);
            dev.kehai.digitalstorage.optimization.BatchTransfers.runWorldSelfTest(player, accessor);
            expect(dispatcher.execute("digitalstorage accessor bind " + location + " " + id, foreignSource) == 0
                    && accessor.boundVolumeId().isEmpty(), "Foreign binding rejected");
            expect(dispatcher.execute("dsc accessor bind " + location + " " + id, ownSource) == 1
                    && accessor.boundVolumeId().filter(volume.id()::equals).isPresent(), "Owner binding");
            expect(dispatcher.execute("digitalstorage accessor clear " + location, foreignSource) == 0
                    && accessor.boundVolumeId().isPresent(), "Foreign clearing rejected");
            expect(dispatcher.execute("digitalstorage volume delete " + id, ownSource) == 0, "Mounted deletion rejected");
            expect(dispatcher.execute("dsc accessor inspect " + location, ownSource) == 1, "Accessor inspect");
            expect(dispatcher.execute("digitalstorage accessor forceclear " + location, admin) == 1
                    && accessor.boundVolumeId().isEmpty(), "Administrator force-clear");
            expect(dispatcher.execute("digitalstorage accessor bind " + location + " " + id, ownSource) == 1
                    && dispatcher.execute("dsc accessor clear " + location, ownSource) == 1, "Owner rebinding/clearing");
            var ledger = volume.record().storage();
            try (var transaction = LedgerTransaction.open()) {
                ledger.insert(ItemKey.of(Items.STONE), 5, transaction); transaction.commit();
            }
            expect(dispatcher.execute("digitalstorage volume delete " + id, ownSource) == 0, "Nonempty deletion rejected");
            try (var transaction = LedgerTransaction.open()) {
                ledger.extract(ItemKey.of(Items.STONE), 5, transaction); transaction.commit();
            }
            expect(dispatcher.execute("dsc volume rename " + id + " Renamed alpha", ownSource) == 1
                    && volume.name().equals("Renamed alpha"), "Greedy name rename");
            expect(dispatcher.execute("digitalstorage volume delete " + id, ownSource) == 1, "Empty unmounted deletion");
            expect(dispatcher.execute("dsc reloadconfig", admin) == 1, "Alias configuration reload");
            dispatcher.execute("digitalstorage stats", admin);
            dispatcher.execute("dsc stats deep", admin);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalStateException("Management dispatcher fixture failed", failure);
        } finally {
            world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            for (UUID actor : new UUID[]{owner, foreign}) {
                for (var volume : state.volumes(actor)) {
                    try (var transaction = LedgerTransaction.open()) {
                        volume.record().storage().extract(ItemKey.of(Items.STONE), Long.MAX_VALUE, transaction);
                        transaction.commit();
                    }
                    expect(state.deleteEmptyVolume(actor, volume.id()), "Fixture volume cleanup");
                }
            }
            state.flushNow();
        }
        expect(state.accountCount() == accounts && state.volumeCount() == volumes, "No fixture records left");
        DigitalStorage.LOGGER.info("Shared management command fixture passed: actual dispatcher, aliases, owner/foreign access, mount/nonempty deletion guards, admin clear, reload and stats; synthetic server players outside PlayerList");
    }
    private static void expect(boolean value, String detail) {
        if (!value) throw new IllegalStateException("Management commands: " + detail);
    }
}
