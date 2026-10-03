package dev.kehai.digitalstorage.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;

public final class ForgeRecoveryCommands {
    private ForgeRecoveryCommands() { }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("digitalstorage").then(Commands.literal("recovery")
                .then(Commands.literal("list").executes(context -> {
                    var player = context.getSource().getPlayerOrException();
                    var store = ForgeTransferSessions.get(player.getServer()).recovery();
                    var entries = store.entries(player.getUUID());
                    for (var entry : entries) context.getSource().sendSuccess(() -> Component.literal(
                            entry.id() + " " + entry.state() + " 数量=" + entry.amount() + " 卷=" + entry.volume()), false);
                    if (entries.isEmpty()) context.getSource().sendSuccess(() -> Component.literal("没有可显示的恢复条目。"), false);
                    if (store.unsavedCount(player.getUUID()) > 0) context.getSource().sendFailure(Component.literal(
                            "另有 " + store.unsavedCount(player.getUUID()) + " 个条目尚未完成保存，请管理员检查诊断。"));
                    return entries.size();
                }))
                .then(Commands.literal("deliver").then(Commands.argument("id", UuidArgument.uuid())
                        .executes(context -> deliver(context.getSource(), UuidArgument.getUuid(context, "id"), Long.MAX_VALUE))
                        .then(Commands.argument("amount", LongArgumentType.longArg(1))
                                .executes(context -> deliver(context.getSource(), UuidArgument.getUuid(context, "id"),
                                        LongArgumentType.getLong(context, "amount"))))))));
        dispatcher.register(Commands.literal("digitalstorage").then(Commands.literal("recoveryadmin").requires(source -> source.hasPermission(2))
                .then(Commands.literal("inspect").then(Commands.argument("id", UuidArgument.uuid()).executes(context -> {
                    var store = ForgeTransferSessions.get(context.getSource().getServer()).recovery();
                    var entry = store.entry(UuidArgument.getUuid(context, "id"));
                    if (entry == null) { context.getSource().sendFailure(Component.literal("恢复条目不存在。")); return 0; }
                    context.getSource().sendSuccess(() -> Component.literal("玩家=" + entry.owner() + " 卷=" + entry.volume()
                            + " 状态=" + entry.state() + " 条目数量=" + entry.amount() + " 本次交付=" + entry.delivering()
                            + " 尝试标识=" + entry.deliveryId()), false);
                    for (var receipt : entry.reconciliations()) context.getSource().sendSuccess(() -> Component.literal(
                            receipt.deliveryId() + " " + receipt.outcome() + " 数量=" + receipt.amount()
                                    + " 管理员=" + receipt.administrator() + " " + receipt.explanation()), false);
                    return 1;
                })))
                .then(Commands.literal("reconcile").then(Commands.argument("id", UuidArgument.uuid())
                        .then(Commands.argument("attempt", UuidArgument.uuid())
                                .then(reconciliationBranch("delivered", ForgeTransferRecovery.Outcome.CONFIRMED_DELIVERED))
                                .then(reconciliationBranch("not_delivered", ForgeTransferRecovery.Outcome.CONFIRMED_NOT_DELIVERED)))))));
        dispatcher.register(Commands.literal("digitalstorage").then(Commands.literal("transferincident").requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(context -> {
                    var entries = ForgeTransferSessions.get(context.getSource().getServer()).incidents().entries();
                    for (var entry : entries) if (entry.administrator() == null) context.getSource().sendSuccess(() -> Component.literal(
                            entry.id() + " 玩家=" + entry.incident().owner() + " 卷=" + entry.incident().volume()
                                    + " 原因=" + entry.incident().reason()), false);
                    return (int) entries.stream().filter(entry -> entry.administrator() == null).count();
                }))
                .then(Commands.literal("inspect").then(Commands.argument("id", UuidArgument.uuid()).executes(context -> {
                    var id = UuidArgument.getUuid(context, "id");
                    var entry = ForgeTransferSessions.get(context.getSource().getServer()).incidents().entries().stream()
                            .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
                    if (entry == null) { context.getSource().sendFailure(Component.literal("事件不存在。")); return 0; }
                    var incident = entry.incident();
                    var origin = incident.origin();
                    var observation = incident.observation();
                    context.getSource().sendSuccess(() -> Component.literal("玩家=" + incident.owner() + " 卷=" + incident.volume()
                            + " 接口=" + incident.handlerClass() + " 槽位=" + incident.slot() + " 已结算=" + incident.settled()
                            + " 原因=" + incident.reason()), false);
                    context.getSource().sendSuccess(() -> Component.literal(origin.known()
                            ? "网络入口：" + origin.dimension() + " 接入器=" + net.minecraft.core.BlockPos.of(origin.accessorPosition())
                                    + " 连接器=" + net.minecraft.core.BlockPos.of(origin.connectorPosition()) : "网络入口未知。"), false);
                    context.getSource().sendSuccess(() -> Component.literal(observation.known()
                            ? "调用观察：阶段=" + observation.stage() + " 预期物品=" + observation.expectedVariant().getString("item")
                                    + " 源观察数量=" + (observation.observedKnown() ? Long.toString(observation.observed()) : "未知") + " 请求=" + observation.requested()
                                    + " 预留=" + observation.reserved() + " 已调用实际提取=" + observation.actualStarted()
                            : "旧记录没有调用观察。"), false);
                    if (entry.administrator() != null) context.getSource().sendSuccess(() -> Component.literal(
                            "已核对：" + entry.administrator() + " " + entry.explanation()), false);
                    return 1;
                })))
                .then(Commands.literal("acknowledge").then(Commands.argument("id", UuidArgument.uuid())
                        .then(Commands.argument("explanation", StringArgumentType.greedyString()).executes(context -> {
                            var administrator = context.getSource().getPlayerOrException();
                            try {
                                ForgeTransferSessions.get(administrator.getServer()).incidents().acknowledge(
                                        UuidArgument.getUuid(context, "id"), administrator.getUUID(),
                                        StringArgumentType.getString(context, "explanation"));
                                context.getSource().sendSuccess(() -> Component.literal("核对说明已保存；此操作不会交付物品。"), false);
                                return 1;
                            } catch (RuntimeException failure) {
                                context.getSource().sendFailure(Component.literal("事件核对失败：" + failure.getMessage()));
                                return 0;
                            }
                        }))))));
    }
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> reconciliationBranch(
            String label, ForgeTransferRecovery.Outcome outcome) {
        return Commands.literal(label).then(Commands.argument("explanation", StringArgumentType.greedyString()).executes(context -> {
            var administrator = context.getSource().getPlayerOrException();
            try {
                var entry = ForgeTransferSessions.get(administrator.getServer()).recovery().reconcile(
                        UuidArgument.getUuid(context, "id"), UuidArgument.getUuid(context, "attempt"), administrator.getUUID(),
                        outcome, StringArgumentType.getString(context, "explanation"));
                context.getSource().sendSuccess(() -> Component.literal("交付核对及收据已保存，条目状态=" + entry.state() + "。"), false);
                return 1;
            } catch (RuntimeException failure) {
                context.getSource().sendFailure(Component.literal("交付核对失败：" + failure.getMessage()));
                return 0;
            }
        }));
    }
    private static int deliver(CommandSourceStack source, java.util.UUID id, long maximum) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        try {
            var result = ForgeRecoveryDelivery.deliver(DigitalStorageState.get(player.serverLevel()),
                    ForgeTransferSessions.get(player.getServer()).recovery(), player.getUUID(), id, maximum);
            if (result.state() == ForgeRecoveryDelivery.State.UNCERTAIN || result.state() == ForgeRecoveryDelivery.State.BLOCKED) {
                source.sendFailure(Component.literal("恢复未完成：" + result.detail()));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("已恢复 " + result.settled() + " 个物品，剩余 " + result.remaining() + "。"), false);
            return 1;
        } catch (RuntimeException failure) {
            source.sendFailure(Component.literal("恢复失败：" + failure.getMessage()));
            return 0;
        }
    }
}
