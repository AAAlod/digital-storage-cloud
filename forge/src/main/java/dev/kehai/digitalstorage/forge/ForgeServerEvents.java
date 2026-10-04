package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
import dev.kehai.digitalstorage.forge.tom.ForgeScannerTelemetry;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkHooks;

public final class ForgeServerEvents {
    private ForgeServerEvents() { }

    @SubscribeEvent public static void starting(ServerStartingEvent event) {
        DigitalStorageState.onServerStarting(event.getServer());
        ForgeTransferSessions.starting(event.getServer());
        ForgeMigrationManager.starting(event.getServer());
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            ForgeMigrationManager.tick(event.getServer());
            if (event.getServer().getTickCount() % 200 == 0) {
                ForgeScannerTelemetry.get(event.getServer()).prune(event.getServer().getTickCount());
            }
            DigitalStorageState.onServerTick(event.getServer());
        }
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        ForgeMigrationManager.stopping(event.getServer());
        ForgeTransferSessions.stopping(event.getServer());
        DigitalStorageState.onServerStopping(event.getServer());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        ForgeMigrationManager.stopped(event.getServer());
        ForgeScannerTelemetry.stopped(event.getServer());
        ForgeTransferSessions.stopped(event.getServer());
        DigitalStorageState.onServerStopped(event.getServer());
        DigitalStorageMountTracker.clear();
    }
    @SubscribeEvent public static void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) ForgeMigrationManager.logout(player);
    }
    @SubscribeEvent public static void reload(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) DigitalStorageTierRegistry.INSTANCE::reload);
    }
    @SubscribeEvent public static void use(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer player
                && event.getLevel().getBlockEntity(event.getPos()) instanceof DigitalStorageAccessorBlockEntity accessor) {
            NetworkHooks.openScreen(player, accessor, buf -> {
                buf.writeBlockPos(accessor.getBlockPos());
                DigitalStorageScreenState.capture(player, accessor, Component.empty()).write(buf);
            });
            event.setCancellationResult(InteractionResult.CONSUME);
            event.setCanceled(true);
        }
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(dev.kehai.digitalstorage.command.ManagementCommands.root("digitalstorage")
                .then(Commands.literal("benchmark").requires(source -> source.hasPermission(2)).executes(context -> {
                    try {
                        String result = ForgePerformanceBenchmark.run();
                        context.getSource().sendSuccess(() -> Component.literal(result), false);
                        return 1;
                    } catch (RuntimeException failure) {
                        DigitalStorage.LOGGER.error("Forge benchmark failed", failure);
                        context.getSource().sendFailure(Component.literal("Forge benchmark failed: " + failure));
                        return 0;
                    }
                }))
                .then(Commands.literal("probe").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                .executes(context -> probe(context.getSource(),
                                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(context, "pos")))))
                .then(Commands.literal("selftest").requires(source -> source.hasPermission(2)).executes(context -> {
                    try {
                        ForgeSharedSelfTest.run(context.getSource().getServer());
                        context.getSource().sendSuccess(() -> Component.literal("Forge shared self-test passed"), false);
                        return 1;
                    } catch (RuntimeException failure) {
                        DigitalStorage.LOGGER.error("Forge shared self-test failed", failure);
                        context.getSource().sendFailure(Component.literal("Forge shared self-test failed: " + failure));
                        return 0;
                    }
                }))
                .then(Commands.literal("diagnostics").requires(source -> source.hasPermission(2)).executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("Forge bootstrap/capability ACTIVE; Tom dedup/analysis, migration and scanner telemetry ACTIVE"), false);
                    context.getSource().sendSuccess(() -> Component.literal(ForgeTransferSessions.get(context.getSource().getServer()).diagnostics()), false);
                    return 1;
                }))
                .then(Commands.literal("flush").requires(source -> source.hasPermission(2)).executes(context -> {
                    DigitalStorageState.get(context.getSource().getServer().overworld()).flushNow();
                    if (!ForgeTransferSessions.get(context.getSource().getServer()).flush()) {
                        context.getSource().sendFailure(Component.literal("Forge transfer records could not be flushed; ownership retained in memory"));
                        return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Digital Storage files flushed successfully"), false);
                    return 1;
                })));
        ForgeRecoveryCommands.register(event.getDispatcher());
        ForgeHopperCommands.register(event.getDispatcher());
        event.getDispatcher().register(Commands.literal("dsc")
                .redirect(event.getDispatcher().getRoot().getChild("digitalstorage")));
    }
    private static int probe(net.minecraft.commands.CommandSourceStack source, net.minecraft.core.BlockPos pos) {
        try {
            var entity = source.getLevel().getBlockEntity(pos);
            var handler = entity == null ? null : entity.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                    .orElse(null);
            if (handler == null) {
                source.sendFailure(Component.literal("No unsided Forge item handler found at " + pos.toShortString())); return 0;
            }
            int slots = handler.getSlots(), scanned = Math.min(slots, 65536), nonEmpty = 0;
            if (slots < 0) throw new IllegalStateException("Negative inventory slot count");
            for (int slot = 0; slot < scanned; slot++) if (!handler.getStackInSlot(slot).isEmpty()) nonEmpty++;
            final int populated = nonEmpty;
            source.sendSuccess(() -> Component.literal("Forge unsided item handler at " + pos.toShortString()
                    + ": slots=" + slots + ", scanned=" + scanned + ", nonEmpty=" + populated + ", truncated=" + (scanned < slots)), false);
            return 1;
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("Forge inventory probe failed: " + failure.getClass().getSimpleName())); return 0;
        }
    }
}
