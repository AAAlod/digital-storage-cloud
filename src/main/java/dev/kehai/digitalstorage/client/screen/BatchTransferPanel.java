package dev.kehai.digitalstorage.client.screen;

import dev.kehai.digitalstorage.screen.BatchScreenProtocol;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;

/** Item selection and quantities stay local; immutable preview tokens authorize the eventual batch. */
final class BatchTransferPanel {
    private final DigitalStorageScreenHandler menu;
    private final DigitalStorageScreenProtocol.RequestSender sender;
    private final Map<Integer, Long> amounts = new HashMap<>(), available = new HashMap<>();
    private List<BatchScreenProtocol.Row> rows = List.of();
    private BatchScreenProtocol.Reply status;
    private UUID token = BatchScreenProtocol.EMPTY;
    private boolean exporting, tools, all, editing;
    private boolean pendingOpen, pendingExport, pendingTools;
    private boolean pendingResume;
    private int serial, outstanding, waiting, seen, ticks, searchDelay, focused = -1, page, pages, entries;
    private long total;
    private long storedTools;
    private UUID toolsVolume = BatchScreenProtocol.EMPTY;
    private String detail = "", query = "", location = "";
    private int x, y, width, height;
    private Font font;
    private EditBox search, quantity;
    private Button input, output, filter, selectAll, clear, maximum, previous, next, start, refresh;
    private final java.util.ArrayList<AbstractWidget> widgets = new java.util.ArrayList<>();
    BatchTransferPanel(DigitalStorageScreenHandler menu, DigitalStorageScreenProtocol.RequestSender sender) { this.menu = menu; this.sender = sender; }
    void init(Font font, int x, int y, int width, int height, Consumer<AbstractWidget> add) {
        this.font = font; this.x = x; this.y = y; this.width = width; this.height = height; widgets.clear();
        input = button(add, "in", 12, 31, 102, () -> open(false, false));
        output = button(add, "out", 118, 31, 102, () -> open(true, false));
        filter = button(add, "tools", 224, 31, width - 236, () -> open(exporting, !tools));
        search = new EditBox(font, x + 12, y + 65, 188, 16, tr("search"));
        search.setHint(tr("search")); search.setMaxLength(128); search.setValue(query);
        search.setResponder(value -> { query = value; searchDelay = 6; }); add.accept(search); widgets.add(search);
        selectAll = button(add, "all", 204, 63, 58, () -> { all = true; amounts.clear(); update(); });
        clear = button(add, "clear", 266, 63, width - 278, () -> { all = false; amounts.clear(); update(); });
        quantity = new EditBox(font, x + 204, y + 107, width - 216, 18, tr("amount"));
        quantity.setMaxLength(12); quantity.setFilter(value -> value.isEmpty() || value.matches("[0-9]+"));
        quantity.setResponder(value -> {
            if (editing || focused < 0) return;
            try { amounts.put(focused, Math.min(available.getOrDefault(focused, 0L), Long.parseLong(value))); }
            catch (NumberFormatException ignored) { amounts.put(focused, 0L); }
        }); add.accept(quantity); widgets.add(quantity);
        maximum = button(add, "maximum", 204, 129, width - 216, () -> {
            if (focused >= 0) { amounts.put(focused, available.getOrDefault(focused, 0L)); updateQuantity(); }
        });
        previous = button(add, "previous", 12, 161, 38, () -> request(BatchScreenProtocol.PAGE, page - 1));
        next = button(add, "next", 160, 161, 38, () -> request(BatchScreenProtocol.PAGE, page + 1));
        refresh = button(add, "refresh", 204, height - 70, width - 216, () -> open(exporting, tools));
        start = button(add, "start_out", 204, height - 44, width - 216, () -> request(running() ? BatchScreenProtocol.STOP : BatchScreenProtocol.START, page));
        updateQuantity(); update();
    }
    private Button button(Consumer<AbstractWidget> add, String key, int dx, int dy, int size, Runnable action) {
        var button = Button.builder(tr(key), ignored -> action.run()).bounds(x + dx, y + dy, Math.max(20, size), 20).build();
        add.accept(button); widgets.add(button); return button;
    }
    void open(boolean exporting, boolean tools) {
        if (running()) return;
        if (outstanding != 0) { pendingOpen = true; pendingExport = exporting; pendingTools = tools; return; }
        pendingOpen = false; status = null; token = BatchScreenProtocol.EMPTY; detail = "";
        this.exporting = exporting; this.tools = tools; all = exporting && tools;
        amounts.clear(); available.clear(); focused = -1; page = 0; query = ""; rows = List.of();
        if (search != null) { search.setValue(""); searchDelay = 0; }
        request(BatchScreenProtocol.PREVIEW, 0);
    }
    void resume() {
        if (outstanding != 0) { pendingResume = true; return; }
        if (status != null && status.state().equals("PREVIEW") && status.volumeId().equals(menu.state().volumeId())) return;
        if (!hasProgress()) open(false, false);
    }
    private void request(int action, int page) {
        if (outstanding != 0) return;
        if (action == BatchScreenProtocol.PAGE) { focused = -1; updateQuantity(); }
        List<BatchScreenProtocol.Pick> picks = List.of();
        if (action == BatchScreenProtocol.START) {
            var selected = new java.util.ArrayList<BatchScreenProtocol.Pick>();
            if (all) selected.add(new BatchScreenProtocol.Pick(-1, 1));
            amounts.forEach((index, amount) -> { if (all || amount > 0) selected.add(new BatchScreenProtocol.Pick(index, amount)); });
            picks = selected;
        }
        var matches = query.isBlank() ? List.<net.minecraft.resources.ResourceLocation>of() : BuiltInRegistries.ITEM.stream()
                .filter(item -> new net.minecraft.world.item.ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .map(BuiltInRegistries.ITEM::getKey).limit(4096).toList();
        outstanding = ++serial; waiting = 0;
        sender.send(new BatchScreenProtocol.Request(menu.containerId, serial, action, token, exporting, tools, Math.max(0, page), query, matches, picks));
    }
    void tick(boolean visible) {
        ticks++;
        var reply = menu.batchReply();
        if (reply != null && reply.serial() > seen) {
            seen = reply.serial();
            if (reply.serial() == outstanding) {
                outstanding = 0; storedTools = reply.toolsStored(); toolsVolume = reply.volumeId();
                if (!(reply.state().equals("IDLE") && status != null && status.state().equals("PREVIEW")
                        && status.volumeId().equals(reply.volumeId()))) { status = reply; detail = reply.detail(); }
                if (reply.state().equals("PREVIEW")) {
                    token = reply.token(); rows = reply.rows(); page = reply.page(); pages = reply.pages(); entries = reply.entryCount(); total = reply.available();
                    location = reply.location();
                    for (var row : rows) available.put(row.index(), row.amount());
                    updateQuantity();
                } else if (!reply.state().equals("ERROR") && !reply.state().equals("IDLE")) {
                    token = reply.token(); exporting = reply.exporting();
                }
            }
        }
        if (outstanding != 0 && ++waiting >= 200) { outstanding = 0; detail = "timeout"; request(BatchScreenProtocol.POLL, 0); }
        if (outstanding == 0 && pendingResume) { pendingResume = false; resume(); }
        if (outstanding == 0 && pendingOpen) open(pendingExport, pendingTools);
        if (visible && searchDelay > 0 && --searchDelay == 0) {
            if (outstanding != 0 || running()) searchDelay = 1;
            else { all = false; amounts.clear(); focused = -1; request(BatchScreenProtocol.PAGE, 0); }
        }
        if (outstanding == 0 && ticks % (running() ? 10 : 40) == 0 && menu.state().accessorBound()
                && (!visible || running())) request(BatchScreenProtocol.POLL, 0);
        for (var widget : widgets) widget.visible = visible;
        previous.visible = next.visible = visible && !hasProgress();
        if (visible) update();
    }
    private long chosen(int index) { return amounts.getOrDefault(index, all ? available.getOrDefault(index, 0L) : 0L); }
    private long selectedAmount() {
        if (!all) return amounts.values().stream().mapToLong(Long::longValue).sum();
        long selected = total;
        for (var e : amounts.entrySet()) selected += e.getValue() - available.getOrDefault(e.getKey(), 0L);
        return Math.max(0, selected);
    }
    private void updateQuantity() {
        if (quantity == null) return;
        editing = true; quantity.setValue(focused < 0 ? "" : Long.toString(chosen(focused))); editing = false;
    }
    private void update() {
        if (start == null) return;
        boolean idle = outstanding == 0 && !running();
        input.active = output.active = filter.active = idle;
        input.setMessage(tr(exporting ? "in" : "in_selected")); output.setMessage(tr(exporting ? "out_selected" : "out"));
        filter.setMessage(tr(tools ? "tools_selected" : "tools"));
        search.setEditable(idle); selectAll.active = clear.active = idle && entries > 0;
        quantity.setEditable(idle && focused >= 0); maximum.active = idle && focused >= 0;
        previous.active = idle && page > 0; next.active = idle && page + 1 < pages;
        refresh.active = idle;
        start.setMessage(tr(running() ? "stop" : exporting ? "start_out" : "start_in"));
        start.active = outstanding == 0 && (running() || status != null && status.state().equals("PREVIEW")
                && !token.equals(BatchScreenProtocol.EMPTY) && selectedAmount() > 0);
    }
    boolean running() { return status != null && status.volumeId().equals(menu.state().volumeId()) && status.state().equals("RUNNING"); }
    void show(boolean visible) { for (var widget : widgets) widget.visible = visible; if (previous != null) previous.visible = next.visible = visible && !hasProgress(); if (visible) update(); }
    boolean focusedInput() { return search != null && search.visible && search.isFocused() || quantity != null && quantity.visible && quantity.isFocused(); }
    private boolean hasProgress() { return status != null && status.volumeId().equals(menu.state().volumeId()) && status.total() > 0 && !status.state().equals("PREVIEW") && !status.state().equals("ERROR"); }
    long toolsStored() { return toolsVolume.equals(menu.state().volumeId()) ? storedTools : 0; }
    Component progressText() { return status == null ? Component.empty() : tr("progress", status.moved(), status.total()); }
    boolean hasTaskResult() { return hasProgress() && status.volumeId().equals(menu.state().volumeId()); }
    Component homeProgress() {
        return running() ? progressText() : status.state().equals("COMPLETE") ? tr("completed", status.moved())
                : tr("stopped_progress", status.moved(), status.total());
    }
    Component locationText() { return location.isEmpty() ? Component.empty() : tr("connector", location); }
    boolean click(double mouseX, double mouseY, int button) {
        if (outstanding != 0 || running() || mouseX < x + 12 || mouseY < y + 86) return false;
        int col = (int) (mouseX - x - 12) / 23, row = (int) (mouseY - y - 86) / 23;
        int index = row * 8 + col;
        if (col < 0 || col >= 8 || row < 0 || row >= 3 || index >= rows.size()) return false;
        var item = rows.get(index); focused = item.index();
        if (button == 0) amounts.put(focused, chosen(focused) > 0 ? 0 : item.amount());
        updateQuantity(); update(); return true;
    }
    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        draw(graphics, tr(exporting ? "route_out" : "route_in", font.plainSubstrByWidth(menu.state().volumeName(), 128)), 12, 54, width - 24, 0xFF545454);
        net.minecraft.world.item.ItemStack hovered = null;
        for (int i = 0; i < 24; i++) {
            int gx = x + 12 + i % 8 * 23, gy = y + 86 + i / 8 * 23;
            graphics.fill(gx, gy, gx + 21, gy + 21, 0xFF8B8B8B);
            graphics.fill(gx + 1, gy + 1, gx + 20, gy + 20, 0xFFB0B0B0);
            if (i >= rows.size()) continue;
            var row = rows.get(i);
            if (chosen(row.index()) > 0) graphics.fill(gx, gy, gx + 2, gy + 21, 0xFF245A20);
            if (focused == row.index()) { graphics.hLine(gx, gx + 20, gy, 0xFFFFFFFF); graphics.hLine(gx, gx + 20, gy + 20, 0xFFFFFFFF); }
            graphics.renderItem(row.icon(), gx + 2, gy + 2);
            if (row.amount() > 1) {
                String count = row.amount() >= 1_000_000_000L ? row.amount() / 1_000_000_000L + "B"
                        : row.amount() >= 1_000_000 ? row.amount() / 1_000_000 + "M"
                        : row.amount() >= 1000 ? row.amount() / 1000 + "k" : Long.toString(row.amount());
                graphics.drawString(font, count, gx + 20 - font.width(count), gy + 13, 0xFFFFFFFF, true);
            }
            if (mouseX >= gx && mouseX < gx + 21 && mouseY >= gy && mouseY < gy + 21)
                hovered = row.icon();
        }
        var focus = rows.stream().filter(row -> row.index() == focused).findFirst().orElse(null);
        draw(graphics, focus == null ? tr("choose_item") : focus.icon().getHoverName(), 204, 88, width - 216, 0xFF404040);
        if (focus != null) draw(graphics, tr("available", focus.amount()), 204, 98, width - 216, 0xFF545454);
        if (status != null && status.state().equals("PREVIEW") && rows.isEmpty())
            draw(graphics, tr("empty"), 18, 113, 172, 0xFF545454);
        draw(graphics, hasProgress() ? tr("remaining", Math.max(0, status.total() - status.moved())) : tr("selected", selectedAmount()), 204, 152, width - 216, 0xFF404040);
        if (!hasProgress()) draw(graphics, Component.literal((pages == 0 ? 0 : page + 1) + " / " + pages), 68, 167, 86, 0xFF545454);
        if (hasProgress())
            draw(graphics, progressText(), 12, height - 64, width - 142, 0xFF245A20);
        if (outstanding != 0 && !running()) draw(graphics, tr("loading"), 12, height - 17, width - 24, 0xFF545454);
        else if (!detail.isEmpty()) draw(graphics, tr("reason." + detail), 12, height - 17, width - 24, 0xFFA32C2C);
        else if (status != null && status.state().equals("COMPLETE")) draw(graphics, tr("completed", status.moved()), 12, height - 17, width - 24, 0xFF245A20);
        else if (status != null && (status.state().equals("STOPPED") || status.state().equals("CANCELLED")))
            draw(graphics, tr("remaining", Math.max(0, status.total() - status.moved())), 12, height - 17, width - 24, 0xFF545454);
        if (hovered != null) graphics.renderTooltip(font, hovered, mouseX, mouseY);
    }
    private void draw(GuiGraphics graphics, Component value, int dx, int dy, int max, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value.getString(), Math.max(1, max)), x + dx, y + dy, color, false);
    }
    private static Component tr(String key, Object... args) { return Component.translatable("screen.digitalstorage.batch." + key, args); }
}
