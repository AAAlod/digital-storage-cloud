package dev.kehai.digitalstorage.audit;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.forge.*;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Main;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemStackHandler;

/** Separate, opt-in audit mod: two real dedicated server lifecycles in one JVM. */
@Mod("dsc_restart_fixture")
public final class RestartAudit {
    private static volatile int launches;
    private static volatile boolean finished;
    private static boolean failEncoding = true;
    private static ForgeTransferSessions.Session originalSession;
    private static ForgeHopperTransfer originalEngine;
    private static ItemStack originalReturned;
    private static UUID id;
    private static MinecraftServer first;
    private static final CountDownLatch complete = new CountDownLatch(1);

    public RestartAudit() {
        root();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, RestartAudit::started);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, RestartAudit::stopped);
    }
    private static Path root() {
        String configured = System.getProperty("digitalstorage.restartTestRoot");
        if (configured == null) throw new IllegalStateException("Restart fixture requires an explicit private audit root");
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!root.toString().replace('\\', '/').contains("/maintenance/work/fabric-decoupling/")
                || !root.getFileName().toString().equals("audit-world")) throw new IllegalStateException("Unsafe restart fixture root");
        return root;
    }
    public static boolean secondLaunch() { return launches == 1 && first != null && first.isStopped() && !finished; }
    public static boolean keepProcess() { return System.getProperty("digitalstorage.restartTestRoot") != null && !finished; }
    private static void started(ServerStartedEvent event) {
        var server = event.getServer();
        expect(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().equals(root()), "Wrong audit world");
        launches++;
        if (launches == 1) {
            first = server;
            originalSession = ForgeTransferSessions.get(server);
            var source = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack; }
            };
            source.setStackInSlot(0, new ItemStack(Items.STONE, 10));
            var target = new ItemStackHandler(1) {
                @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return simulate ? ItemStack.EMPTY : stack; }
            };
            originalEngine = new ForgeHopperTransfer(); originalEngine.move(source, 0, target, 8);
            originalReturned = returnedStack(originalEngine);
            id = UUID.randomUUID();
            expect(!originalSession.hoppers().retain(id, "minecraft:overworld", BlockPos.ZERO, originalEngine, () -> {
                if (failEncoding) throw new IllegalStateException("Injected restart audit encoding failure");
                return originalEngine.saveState();
            }) && originalEngine.heldCount() == 8 && originalSession.hasUnflushed(), "Initial live owner not retained");
            DigitalStorage.LOGGER.info("DSC restart audit first server stopping with retained eight");
            server.halt(false);
        } else if (launches == 2) {
            var session = ForgeTransferSessions.get(server);
            expect(server != first && session == originalSession && session.hasUnflushed(), "Restart did not reuse retained session");
            var binding = session.hoppers().bind(id, "minecraft:overworld", BlockPos.ZERO, originalEngine, originalEngine::saveState);
            expect(binding.id().equals(id) && binding.engine() == originalEngine && originalEngine.heldCount() == 8
                    && returnedStack(originalEngine) == originalReturned, "Restart lost original returned instance");
            failEncoding = false;
            expect(session.flush() && !session.hasUnflushed(), "Repaired writer did not persist retained owner");
            UUID owner = UUID.randomUUID();
            var state = DigitalStorageState.get(server.overworld());
            var volume = state.createVolume(owner, "Private restart audit", 1).orElseThrow();
            session.hoppers().export(id, owner, volume.id(), UUID.randomUUID(), "Audit retained original source across real restart", session.recovery());
            var ledger = volume.record().storage();
            ForgeRecoveryDelivery.deliver(state, session.recovery(), owner, id, 8);
            expect(ledger.amountOf(ItemKey.of(Items.STONE)) == 8 && originalEngine.heldCount() == 0
                    && session.available(), "Retained source did not settle exactly once");
            try (var transaction = LedgerTransaction.open()) {
                ledger.extract(ItemKey.of(Items.STONE), 8, transaction); transaction.commit();
            }
            expect(state.deleteEmptyVolume(owner, volume.id()), "Private audit volume cleanup");
            state.flushNow();
            DigitalStorage.LOGGER.info("DSC restart audit second server verified same session and original instance; delivered eight");
            try {
                var dispatcher = server.getCommands().getDispatcher();
                var commandSource = server.createCommandSourceStack();
                expect(dispatcher.execute("digitalstorage selftest", commandSource) == 1, "Second server selftest failed");
                dispatcher.execute("digitalstorage diagnostics", commandSource);
                expect(dispatcher.execute("digitalstorage flush", commandSource) == 1, "Second server final flush failed");
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
                throw new IllegalStateException("Second server command verification failed", failure);
            }
            server.halt(false);
        } else throw new IllegalStateException("Unexpected additional audit server");
    }
    private static void stopped(ServerStoppedEvent event) {
        var server = event.getServer();
        if (server == first) {
            expect(originalSession.hasUnflushed(), "Failed stop discarded original owner");
            boolean detached = false;
            try { ForgeTransferSessions.get(server); } catch (IllegalStateException expected) { detached = true; }
            expect(detached, "Stopped server still registered as active");
            Thread coordinator = new Thread(() -> {
                try {
                    server.getRunningThread().join(60000);
                    expect(!server.getRunningThread().isAlive(), "First server thread did not terminate");
                    Main.main(new String[]{"--nogui"});
                    long startupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() == null
                            && System.nanoTime() < startupDeadline) Thread.sleep(50);
                    expect(net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() != null, "Second Main did not construct a real server");
                    expect(complete.await(180, TimeUnit.SECONDS), "Second real server did not stop");
                    System.exit(0);
                } catch (Throwable failure) {
                    DigitalStorage.LOGGER.error("DSC restart audit failed", failure);
                    System.exit(1);
                }
            }, "DSC private restart coordinator");
            coordinator.setDaemon(false); coordinator.start();
        } else {
            expect(launches == 2 && originalSession.available() && !originalSession.hasUnflushed(), "Final stop left unresolved ownership");
            finished = true;
            DigitalStorage.LOGGER.info("DSC restart audit passed: two real server lifecycles, failed stop retention, same-process original instance, repair and exact delivery");
            complete.countDown();
        }
    }
    private static ItemStack returnedStack(ForgeHopperTransfer engine) {
        // Audit-only identity inspection; keep the player's transfer API unchanged.
        try {
            var accessor = ForgeHopperTransfer.class.getDeclaredMethod("heldStack");
            accessor.setAccessible(true);
            return (ItemStack) accessor.invoke(engine);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect retained stack identity", failure);
        }
    }
    private static void expect(boolean condition, String detail) { if (!condition) throw new IllegalStateException(detail); }
}
