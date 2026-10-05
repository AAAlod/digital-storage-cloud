package dev.kehai.digitalstorage.screen;

import dev.kehai.digitalstorage.DigitalStorage;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public final class BatchScreenProtocol {
    public static final ResourceLocation REQUEST = DigitalStorage.id("batch_request"), REPLY = DigitalStorage.id("batch_reply");
    public static final UUID EMPTY = new UUID(0, 0);
    public static final int PREVIEW = 0, PAGE = 1, START = 2, STOP = 3, POLL = 4;
    public record Pick(int index, long amount) { }
    public record Request(int syncId, int serial, int action, UUID token, boolean exporting, boolean tools,
                          int page, String query, List<ResourceLocation> matches, List<Pick> picks, boolean ascending) {
        public Request { matches = List.copyOf(matches); picks = List.copyOf(picks); }
        public Request(int syncId, int serial, int action, UUID token, boolean exporting, boolean tools,
                int page, String query, List<ResourceLocation> matches, List<Pick> picks) {
            this(syncId, serial, action, token, exporting, tools, page, query, matches, picks, exporting);
        }
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(syncId); buf.writeVarInt(serial); buf.writeVarInt(action); buf.writeUUID(token);
            buf.writeBoolean(exporting); buf.writeBoolean(tools); buf.writeVarInt(page); buf.writeUtf(query, 128);
            buf.writeVarInt(matches.size()); for (var id : matches) buf.writeResourceLocation(id);
            buf.writeVarInt(picks.size()); for (var pick : picks) { buf.writeVarInt(pick.index()); buf.writeVarLong(pick.amount()); }
            buf.writeBoolean(ascending);
        }
        public static Request read(FriendlyByteBuf buf) {
            int sync = buf.readVarInt(), serial = buf.readVarInt(), action = buf.readVarInt(); UUID token = buf.readUUID();
            boolean exporting = buf.readBoolean(), tools = buf.readBoolean(); int page = buf.readVarInt(); String query = buf.readUtf(128);
            int count = bounded(buf.readVarInt(), 4096); var matches = new java.util.ArrayList<ResourceLocation>(count);
            for (int i = 0; i < count; i++) matches.add(buf.readResourceLocation());
            count = bounded(buf.readVarInt(), 4097); var picks = new java.util.ArrayList<Pick>(count);
            for (int i = 0; i < count; i++) picks.add(new Pick(buf.readVarInt(), buf.readVarLong()));
            return new Request(sync, serial, action, token, exporting, tools, page, query, matches, picks, buf.readBoolean());
        }
    }
    public record Row(int index, ItemStack icon, long amount, boolean tools) { }
    public record Reply(int syncId, int serial, UUID token, boolean exporting, int page, int pages,
                        List<Row> rows, String state, long moved, long total, String detail, String location,
                        UUID volumeId, long toolsStored, int entryCount, long available) {
        public Reply { rows = List.copyOf(rows); }
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(syncId); buf.writeVarInt(serial); buf.writeUUID(token); buf.writeBoolean(exporting);
            buf.writeVarInt(page); buf.writeVarInt(pages); buf.writeVarInt(rows.size());
            for (var row : rows) { buf.writeVarInt(row.index()); buf.writeItem(row.icon()); buf.writeVarLong(row.amount()); buf.writeBoolean(row.tools()); }
            buf.writeUtf(state, 32); buf.writeVarLong(moved); buf.writeVarLong(total); buf.writeUtf(detail, 128); buf.writeUtf(location, 128);
            buf.writeUUID(volumeId); buf.writeVarLong(toolsStored);
            buf.writeVarInt(entryCount); buf.writeVarLong(available);
        }
        public static Reply read(FriendlyByteBuf buf) {
            int sync = buf.readVarInt(), serial = buf.readVarInt(); UUID token = buf.readUUID(); boolean exporting = buf.readBoolean();
            int page = buf.readVarInt(), pages = buf.readVarInt(), count = bounded(buf.readVarInt(), 24);
            var rows = new java.util.ArrayList<Row>(count);
            for (int i = 0; i < count; i++) rows.add(new Row(buf.readVarInt(), buf.readItem(), buf.readVarLong(), buf.readBoolean()));
            return new Reply(sync, serial, token, exporting, page, pages, rows, buf.readUtf(32), buf.readVarLong(), buf.readVarLong(), buf.readUtf(128), buf.readUtf(128), buf.readUUID(), buf.readVarLong(), buf.readVarInt(), buf.readVarLong());
        }
    }
    private static int bounded(int count, int maximum) {
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid batch packet count"); return count;
    }
    public static void runSelfTest() {
        var buffer = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            var token = UUID.randomUUID();
            var request = new Request(12, 9, START, token, true, true, 2, "钻石", List.of(new ResourceLocation("minecraft", "diamond_sword")), List.of(new Pick(-1, 1), new Pick(0, 7), new Pick(1, 0)), false);
            request.write(buffer);
            if (!request.equals(Request.read(buffer)) || buffer.isReadable()) throw new IllegalStateException("Batch selection codec failed");
            buffer.clear();
            var sword = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD); sword.setDamageValue(39);
            sword.setHoverName(net.minecraft.network.chat.Component.literal("Named sword"));
            var reply = new Reply(12, 9, token, true, 2, 3, List.of(new Row(48, sword, 7, true)), "PREVIEW", 0, 0, "", "1, 2, 3", token, 7, 50, 100);
            reply.write(buffer); var decoded = Reply.read(buffer);
            if (buffer.isReadable() || decoded.rows().get(0).amount() != 7 || decoded.rows().get(0).index() != 48
                    || !dev.kehai.digitalstorage.storage.ItemKey.of(decoded.rows().get(0).icon()).equals(dev.kehai.digitalstorage.storage.ItemKey.of(sword))
                    || decoded.entryCount() != 50 || decoded.available() != 100) throw new IllegalStateException("Batch item identity codec failed");
            buffer.clear(); new Request(12, 10, START, token, true, false, 0, "", List.of(), java.util.Collections.nCopies(4098, new Pick(0, 1))).write(buffer);
            boolean denied = false; try { Request.read(buffer); } catch (IllegalArgumentException expected) { denied = true; }
            if (!denied) throw new IllegalStateException("Oversized batch selection was accepted");
        } finally { buffer.release(); }
    }
}
