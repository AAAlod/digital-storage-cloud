package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.tier.DigitalStorageTierRegistry;
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
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) DigitalStorageState.onServerTick(event.getServer());
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) {
        ForgeTransferSessions.stopping(event.getServer());
        DigitalStorageState.onServerStopping(event.getServer());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        ForgeTransferSessions.stopped(event.getServer());
        DigitalStorageState.onServerStopped(event.getServer());
        DigitalStorageMountTracker.clear();
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
        event.getDispatcher().register(Commands.literal("digitalstorage")
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
                    context.getSource().sendSuccess(() -> Component.literal("Forge bootstrap/capability ACTIVE; Tom dedup/analysis ACTIVE; scanner telemetry/migration pending"), false);
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
    }
}
