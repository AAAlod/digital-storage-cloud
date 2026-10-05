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
    private BatchScreenProtocol.Reply result;
    private UUID refreshedTask = BatchScreenProtocol.EMPTY;
    private int autoRefresh;
    private UUID token = BatchScreenProtocol.EMPTY;
    private boolean exporting, tools, all, editing, ascending;
    private boolean pendingOpen, pendingExport, pendingTools;
    private boolean pendingResume;
    private int serial, outstanding, waiting, seen, ticks, searchDelay, busyTicks, focused = -1, page, pages, entries;
    private long total;
    private long storedTools;
    private UUID toolsVolume = BatchScreenProtocol.EMPTY;
    private String detail = "", query = "", location = "";
    private int x, y, width, height;
    private Font font;
    private EditBox search, quantity;
    private Button direction, filter, selectAll, clear, maximum, previous, next, start, refresh, sort;
    private final java.util.ArrayList<AbstractWidget> widgets = new java.util.ArrayList<>();
    BatchTransferPanel(DigitalStorageScreenHandler menu, DigitalStorageScreenProtocol.RequestSender sender) { this.menu = menu; this.sender = sender; }
    void init(Font font, int x, int y, int width, int height, Consumer<AbstractWidget> add) {
        this.font = font; this.x = x; this.y = y; this.width = width; this.height = height; widgets.clear();
        direction = button(add, "in", 96, 31, 20, () -> { open(!exporting, tools); all = false; });
        filter = button(add, "tools", 36, 161, 20, () -> { open(exporting, !tools); all = false; });
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
        sort = button(add, "quantity_desc", 12, 161, 20, () -> {
            ascending = !ascending; request(BatchScreenProtocol.PAGE, 0); update();
        });
        previous = button(add, "previous", 108, 161, 16, () -> request(BatchScreenProtocol.PAGE, page - 1));
        next = button(add, "next", 180, 161, 16, () -> request(BatchScreenProtocol.PAGE, page + 1));
        previous.setWidth(16); next.setWidth(16);
        refresh = button(add, "refresh", 204, height - 70, width - 216, () -> open(exporting, tools));
        start = button(add, "start_out", 204, height - 44, width - 216, () -> request(running() ? BatchScreenProtocol.STOP : BatchScreenProtocol.START, page));
        updateQuantity(); update();
    }
    private Button button(Consumer<AbstractWidget> add, String key, int dx, int dy, int size, Runnable action) {
        int icon = key.equals("quantity_desc") ? 1 : key.equals("in") ? 2 : key.equals("tools") ? 3 : 0;
        var button = new StableButton(x + dx, y + dy, Math.max(20, size), tr(key), ignored -> action.run(), icon);
        add.accept(button); widgets.add(button); return button;
    }
    /** Input locks immediately; only sustained work changes the button's appearance. */
    private final class StableButton extends Button {
        private boolean enabledAppearance;
        private final int icon;
        StableButton(int bx, int by, int bw, Component message, OnPress action, int icon) {
            super(bx, by, bw, 20, message, action, DEFAULT_NARRATION);
            this.icon = icon;
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            boolean logicalActive = active;
            Component message = getMessage();
            if (outstanding == 0 && !running()) enabledAppearance = active;
            else if (busyTicks < 6) active = enabledAppearance;
            if (icon != 0) setMessage(Component.empty());
            try {
                super.renderWidget(graphics, mouseX, mouseY, delta);
                if (icon == 1) {
                    int color = active ? 0xFFFFFFFF : 0xFFA0A0A0;
                    int ax = getX() + 5, ay = getY() + 6;
                    graphics.fill(ax + 2, ay, ax + 3, ay + 8, color);
                    for (int i = 0; i < 3; i++) {
                        int ry = ascending ? ay + i : ay + 7 - i;
                        graphics.fill(ax + 2 - i, ry, ax + 3 + i, ry + 1, color);
                    }
                    graphics.drawString(font, "1", getX() + 11, getY() + 6, color, true);
                } else if (icon == 2) {
                    int color = active ? 0xFFFFFFFF : 0xFFA0A0A0;
                    int ax = getX() + 5, ay = getY() + 9;
                    graphics.fill(ax, ay, ax + 10, ay + 2, color);
                    for (int i = 0; i < 4; i++) {
                        int rx = exporting ? ax + 9 - i : ax + i;
                        graphics.fill(rx, ay - i, rx + 1, ay + 2 + i, color);
                    }
                } else if (icon == 3) {
                    graphics.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD), getX() + 2, getY() + 2);
                    if (tools) graphics.renderOutline(getX() + 1, getY() + 1, 18, 18, 0xFF245A20);
                }
            } finally { active = logicalActive; setMessage(message); }
        }
    }
    void open(boolean exporting, boolean tools) {
        if (running()) return;
        if (outstanding != 0) { pendingOpen = true; pendingExport = exporting; pendingTools = tools; return; }
        pendingOpen = false; status = null; token = BatchScreenProtocol.EMPTY; detail = "";
        if (this.exporting != exporting) ascending = exporting;
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
            result = null;
            var selected = new java.util.ArrayList<BatchScreenProtocol.Pick>();
            if (all) selected.add(new BatchScreenProtocol.Pick(-1, 1));
            amounts.forEach((index, amount) -> { if (all || amount > 0) selected.add(new BatchScreenProtocol.Pick(index, amount)); });
            picks = selected;
        }
        var matches = query.isBlank() ? List.<net.minecraft.resources.ResourceLocation>of() : BuiltInRegistries.ITEM.stream()
                .filter(item -> new net.minecraft.world.item.ItemStack(item).getHoverName().getString().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
                .map(BuiltInRegistries.ITEM::getKey).limit(4096).toList();
        outstanding = ++serial; waiting = 0;
        sender.send(new BatchScreenProtocol.Request(menu.containerId, serial, action, token, exporting, tools, Math.max(0, page), query, matches, picks, ascending));
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
                    if (exporting != reply.exporting()) ascending = reply.exporting();
                    token = reply.token(); exporting = reply.exporting();
                }
                if ((reply.state().equals("COMPLETE") || reply.state().equals("STOPPED") || reply.state().equals("CANCELLED"))
                        && !reply.token().equals(refreshedTask)) {
                    result = reply; refreshedTask = reply.token(); autoRefresh = 10;
                }
            }
        }
        if (outstanding != 0 && ++waiting >= 200) { outstanding = 0; detail = "timeout"; request(BatchScreenProtocol.POLL, 0); }
        if (outstanding == 0 && pendingResume) { pendingResume = false; resume(); }
        if (outstanding == 0 && pendingOpen) open(pendingExport, pendingTools);
        if (autoRefresh > 0 && --autoRefresh == 0) {
            if (outstanding != 0 || running()) autoRefresh = 1;
            else { open(exporting, tools); all = false; }
        }
        if (visible && searchDelay > 0 && --searchDelay == 0) {
            if (outstanding != 0 || running()) searchDelay = 1;
            else { all = false; amounts.clear(); focused = -1; request(BatchScreenProtocol.PAGE, 0); }
        }
        if (outstanding == 0 && ticks % (running() ? 10 : 40) == 0 && menu.state().accessorBound()
                && (!visible || running())) request(BatchScreenProtocol.POLL, 0);
        busyTicks = outstanding != 0 || running() ? busyTicks + 1 : 0;
        for (var widget : widgets) widget.visible = visible;
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
        direction.active = filter.active = idle;
        direction.setMessage(tr(exporting ? "out" : "in"));
        filter.setMessage(tr(tools ? "filter_on" : "filter_off"));
        search.setEditable(idle); selectAll.active = clear.active = idle && entries > 0;
        quantity.setEditable(idle && focused >= 0); maximum.active = idle && focused >= 0;
        previous.active = idle && page > 0; next.active = idle && page + 1 < pages;
        sort.active = idle && status != null && status.state().equals("PREVIEW");
        sort.setMessage(tr(ascending ? "quantity_asc" : "quantity_desc"));
        refresh.active = idle;
        start.setMessage(tr(running() ? "stop" : exporting ? "start_out" : "start_in"));
        start.active = outstanding == 0 && (running() || status != null && status.state().equals("PREVIEW")
                && !token.equals(BatchScreenProtocol.EMPTY) && selectedAmount() > 0);
    }
    boolean running() { return status != null && status.volumeId().equals(menu.state().volumeId()) && status.state().equals("RUNNING"); }
    void show(boolean visible) { for (var widget : widgets) widget.visible = visible; if (visible) update(); }
    boolean focusedInput() { return search != null && search.visible && search.isFocused() || quantity != null && quantity.visible && quantity.isFocused(); }
    private boolean hasProgress() { return status != null && status.volumeId().equals(menu.state().volumeId()) && status.total() > 0 && !status.state().equals("PREVIEW") && !status.state().equals("ERROR"); }
    long toolsStored() { return toolsVolume.equals(menu.state().volumeId()) ? storedTools : 0; }
    Component progressText() { return status == null ? Component.empty() : tr("progress", status.moved(), status.total()); }
    boolean hasTaskResult() { return hasProgress() || result != null && result.volumeId().equals(menu.state().volumeId()); }
    Component homeProgress() {
        var last = hasProgress() ? status : result;
        return running() ? progressText() : last.state().equals("COMPLETE") ? tr("completed", last.moved())
                : tr("stopped_progress", last.moved(), last.total());
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
        String volumeName = menu.state().volumeName();
        String shortenedName = font.width(volumeName) <= 60 ? volumeName
                : font.plainSubstrByWidth(volumeName, Math.max(1, 60 - font.width("…"))) + "…";
        var volumeItem = BuiltInRegistries.ITEM.getOptional(menu.state().volumeIcon()).orElse(net.minecraft.world.item.Items.CHEST);
        graphics.renderItem(new net.minecraft.world.item.ItemStack(volumeItem), x + 12, y + 33);
        draw(graphics, Component.literal(shortenedName), 32, 37, 60, 0xFF404040);
        draw(graphics, tr("physical_target"), 122, 37, 78, 0xFF404040);
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
                graphics.pose().pushPose();
                graphics.pose().translate(0, 0, 200);
                graphics.drawString(font, count, gx + 20 - font.width(count), gy + 13, 0xFFFFFFFF, true);
                graphics.pose().popPose();
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
        {
            String fraction = (pages == 0 ? 0 : page + 1) + "/" + pages;
            draw(graphics, Component.literal(fraction), 152 - font.width(fraction) / 2, 167, 54, 0xFF545454);
        }
        if (running() && busyTicks >= 6) draw(graphics, progressText(), 12, height - 17, width - 24, 0xFF245A20);
        else if (outstanding != 0 && busyTicks >= 6) draw(graphics, tr("loading"), 12, height - 17, width - 24, 0xFF545454);
        else if (!detail.isEmpty()) draw(graphics, tr("reason." + detail), 12, height - 17, width - 24, 0xFFA32C2C);
        else if (status != null && status.state().equals("COMPLETE")) draw(graphics, tr("completed", status.moved()), 12, height - 17, width - 24, 0xFF245A20);
        else if (status != null && (status.state().equals("STOPPED") || status.state().equals("CANCELLED")))
            draw(graphics, tr("remaining", Math.max(0, status.total() - status.moved())), 12, height - 17, width - 24, 0xFF545454);
        else if (result != null && result.volumeId().equals(menu.state().volumeId()))
            draw(graphics, result.detail().isEmpty() ? tr("completed", result.moved()) : tr("reason." + result.detail()),
                    12, height - 17, width - 24, result.detail().isEmpty() ? 0xFF245A20 : 0xFFA32C2C);
        if (hovered != null) graphics.renderTooltip(font, hovered, mouseX, mouseY);
        else if (sort.isHovered()) graphics.renderTooltip(font, tr(ascending ? "quantity_asc" : "quantity_desc"), mouseX, mouseY);
        else if (direction.isHovered()) graphics.renderTooltip(font, tr("switch_direction"), mouseX, mouseY);
        else if (filter.isHovered()) graphics.renderTooltip(font, tr(tools ? "filter_on" : "filter_off"), mouseX, mouseY);
        else if (mouseX >= x + 12 && mouseX < x + 92 && mouseY >= y + 31 && mouseY < y + 51)
            graphics.renderTooltip(font, Component.literal(volumeName), mouseX, mouseY);
    }
    private void draw(GuiGraphics graphics, Component value, int dx, int dy, int max, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value.getString(), Math.max(1, max)), x + dx, y + dy, color, false);
    }
    private static Component tr(String key, Object... args) { return Component.translatable("screen.digitalstorage.batch." + key, args); }
}
