package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.items.ItemStackHandler;

/** Actual command execution only in a declared private world; synthetic players are outside PlayerList. */
public final class ForgeHopperCommandsSelfTest {
    private ForgeHopperCommandsSelfTest() { }
    public static void run(MinecraftServer server) {
        var dispatcher = server.getCommands().getDispatcher();
        var low = server.createCommandSourceStack().withPermission(0);
        var operator = server.createCommandSourceStack().withPermission(2);
        var node = dispatcher.getRoot().getChild("digitalstorage").getChild("hopperrecovery");
        expect(node != null && !node.canUse(low) && node.canUse(operator), "Administrator command permission");
        String configured = System.getProperty("digitalstorage.integrationTestRoot");
        if (configured == null) return;
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!root.equals(Path.of(configured).toAbsolutePath().normalize())
                || !root.toString().replace('\\', '/').contains("/maintenance/work/fabric-decoupling/")
                || !root.getFileName().toString().equals("audit-world")) throw new IllegalStateException("Private audit-world required");
        var state = DigitalStorageState.get(server.overworld());
        int accounts = state.accountCount(), volumes = state.volumeCount();
        UUID owner = UUID.randomUUID(), admin = UUID.randomUUID(), foreign = UUID.randomUUID(), id = UUID.randomUUID();
        var volume = state.createVolume(owner, "Hopper recovery command fixture", 1).orElseThrow();
        var ledger = volume.record().storage();
        var ownerSource = low.withEntity(new ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(owner, "DSCHopperOwner")));
        var foreignSource = low.withEntity(new ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(foreign, "DSCHopperOther")));
        var adminSource = operator.withEntity(new ServerPlayer(server, server.overworld(), new com.mojang.authlib.GameProfile(admin, "DSCHopperAdmin")));
        var session = ForgeTransferSessions.get(server);
        int pending = session.hoppers().pendingCount(), recoveryPending = session.recovery().pendingCount();
        try {
            var source = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            };
            source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
            var target = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return simulate ? ItemStack.EMPTY : stack; }
            };
            var engine = new ForgeHopperTransfer(); engine.move(source, 0, target, 8);
            expect(session.hoppers().retain(id, "minecraft:overworld", net.minecraft.core.BlockPos.ZERO, engine, engine::saveState), "Fixture custody saved");
            String handoff = "digitalstorage hopperrecovery handoff " + id + " " + volume.id() + " Inspected returned fixture items";
            boolean forbidden = false;
            try { dispatcher.execute(handoff, ownerSource); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { forbidden = true; }
            expect(forbidden && engine.heldCount() == 8, "Player cannot invoke administrator handoff");
            expect(dispatcher.execute("dsc hopperrecovery inspect " + id, adminSource) == 1, "Alias inspect");
            expect(dispatcher.execute("digitalstorage hopperrecovery handoff " + id + " " + UUID.randomUUID() + " Missing target", adminSource) == 0
                    && session.recovery().entry(id) == null, "Missing target does not adopt items");
            expect(dispatcher.execute(handoff, adminSource) == 1 && engine.heldCount() == 0
                    && session.recovery().ownedEntry(id, owner).volume().equals(volume.id()), "Administrator selects actual target owner");
            expect(dispatcher.execute("digitalstorage recovery deliver " + id, foreignSource) == 0
                    && ledger.amountOf(ItemKey.of(Items.STONE)) == 0, "Foreign recovery delivery rejected");
            expect(dispatcher.execute("dsc recovery deliver " + id, ownerSource) == 1
                    && ledger.amountOf(ItemKey.of(Items.STONE)) == 8, "Owner alias delivers exactly eight");
            expect(dispatcher.execute(handoff, adminSource) == 1 && ledger.amountOf(ItemKey.of(Items.STONE)) == 8,
                    "Repeated handoff cannot recreate delivered recovery");
            expect(dispatcher.execute("dsc hopperrecovery reconcile-empty " + id + " Cannot retire adopted ownership", adminSource) == 0,
                    "Reconciliation cannot retire a handoff receipt");
            UUID unknownId = UUID.randomUUID();
            var opaque = net.minecraft.nbt.StringTag.valueOf("unknown device observation");
            session.hoppers().retain(unknownId, "minecraft:overworld", net.minecraft.core.BlockPos.ZERO, new Object(), () -> opaque);
            String retire = "dsc hopperrecovery reconcile-empty " + unknownId + " External inventories inspected; no deliverable source";
            forbidden = false;
            try { dispatcher.execute(retire, ownerSource); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException expected) { forbidden = true; }
            expect(forbidden && session.hoppers().pendingCount() == pending + 1, "Player cannot retire custody evidence");
            expect(dispatcher.execute(retire, adminSource) == 1 && dispatcher.execute(retire, adminSource) == 1
                    && session.hoppers().state(unknownId).equals(opaque) && session.recovery().entry(unknownId) == null,
                    "Administrator retirement is durable, repeatable and creates no items");
            expect(session.hoppers().pendingCount() == pending && session.recovery().pendingCount() == recoveryPending,
                    "Only settled audit receipts remain");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalStateException("Hopper command fixture failed", failure);
        } finally {
            try (var transaction = LedgerTransaction.open()) { ledger.extract(ItemKey.of(Items.STONE), Long.MAX_VALUE, transaction); transaction.commit(); }
            expect(state.deleteEmptyVolume(owner, volume.id()), "Fixture volume cleanup");
            state.flushNow();
        }
        expect(state.accountCount() == accounts && state.volumeCount() == volumes, "Fixture account cleanup");
        DigitalStorage.LOGGER.info("Forge hopper recovery command self-test passed: actual dispatcher administrator gate, aliases, missing target, selected volume owner, foreign rejection and exactly-once owner delivery; synthetic players outside PlayerList");
    }
    private static void expect(boolean condition, String detail) { if (!condition) throw new IllegalStateException("Hopper commands: " + detail); }
}
