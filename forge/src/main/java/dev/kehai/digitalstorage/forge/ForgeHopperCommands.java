package dev.kehai.digitalstorage.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;

/** Physical hoppers have no reliable player owner: administrators explicitly select a target volume. */
public final class ForgeHopperCommands {
    private ForgeHopperCommands() { }
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("digitalstorage").then(Commands.literal("hopperrecovery")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list").executes(context -> {
                    var entries = ForgeTransferSessions.get(context.getSource().getServer()).hoppers().entries();
                    for (var entry : entries) context.getSource().sendSuccess(() -> Component.literal(
                            entry.id() + " " + entry.phase() + " " + entry.dimension() + " " + entry.position()
                                    + " 物品=" + entry.item() + " 原观察数量=" + observed(entry)
                                    + " 已确认=" + entry.confirmed() + " 已保存=" + entry.durable()), false);
                    return entries.size();
                }))
                .then(Commands.literal("inspect").then(Commands.argument("id", UuidArgument.uuid()).executes(context -> {
                    var id = UuidArgument.getUuid(context, "id");
                    var entry = ForgeTransferSessions.get(context.getSource().getServer()).hoppers().entries().stream()
                            .filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
                    if (entry == null) { context.getSource().sendFailure(Component.literal("漏斗托管条目不存在。")); return 0; }
                    context.getSource().sendSuccess(() -> Component.literal(entry.id() + " " + entry.phase()
                            + " " + entry.dimension() + " " + entry.position() + " 物品=" + entry.item()
                            + " 原观察数量=" + observed(entry) + " 已确认=" + entry.confirmed()
                            + " 冲突来源=" + entry.conflictsWith() + " 卷主=" + entry.owner() + " 卷=" + entry.volume()
                            + " 管理员=" + entry.administrator() + " " + entry.explanation() + " " + entry.failure()), false);
                    return 1;
                })))
                .then(Commands.literal("reconcile-empty").then(Commands.argument("id", UuidArgument.uuid())
                        .then(Commands.argument("explanation", StringArgumentType.greedyString()).executes(context -> {
                            var administrator = context.getSource().getPlayerOrException();
                            try {
                                ForgeTransferSessions.get(context.getSource().getServer()).hoppers().retire(
                                        UuidArgument.getUuid(context, "id"), administrator.getUUID(),
                                        StringArgumentType.getString(context, "explanation"));
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "外部核对为无可交付余量，永久退役收据已保存；原始证据保留，不产生恢复物品。"), false);
                                return 1;
                            } catch (RuntimeException failure) {
                                context.getSource().sendFailure(Component.literal("漏斗核对失败：" + failure.getMessage()));
                                return 0;
                            }
                        }))))
                .then(Commands.literal("handoff").then(Commands.argument("id", UuidArgument.uuid())
                        .then(Commands.argument("volume", UuidArgument.uuid())
                                .then(Commands.argument("explanation", StringArgumentType.greedyString()).executes(context -> {
                                    var administrator = context.getSource().getPlayerOrException();
                                    try {
                                        var state = DigitalStorageState.get(context.getSource().getLevel());
                                        var volume = state.volume(UuidArgument.getUuid(context, "volume"))
                                                .orElseThrow(() -> new IllegalArgumentException("目标卷不存在"));
                                        var session = ForgeTransferSessions.get(context.getSource().getServer());
                                        var id = session.hoppers().export(UuidArgument.getUuid(context, "id"), volume.ownerId(),
                                                volume.id(), administrator.getUUID(), StringArgumentType.getString(context, "explanation"),
                                                session.recovery());
                                        context.getSource().sendSuccess(() -> Component.literal(
                                                "漏斗交接收据已保存。恢复条目=" + id + "；由卷主执行 /digitalstorage recovery deliver " + id + "。"), false);
                                        return 1;
                                    } catch (RuntimeException failure) {
                                        context.getSource().sendFailure(Component.literal("漏斗交接失败：" + failure.getMessage()));
                                        return 0;
                                    }
                                })))))));
    }
    static String observed(ForgeHopperCustody.Summary entry) {
        return entry.observedKnown() ? Long.toString(entry.observedAmount()) : "未知";
    }
}
