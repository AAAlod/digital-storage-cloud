package dev.kehai.digitalstorage.client.screen;

import dev.kehai.digitalstorage.DigitalStorageContent;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.storage.StorageVolume;
import dev.kehai.digitalstorage.tier.UpgradeIngredient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared device UI. Navigation is local; every mutation remains server validated. */
public final class DigitalStorageScreen extends AbstractContainerScreen<DigitalStorageScreenHandler> {
    private static final int MARGIN = 12;
    private static final int ROW_HEIGHT = 40;
    private static final int TEXT = 0xFFE8E8E8;
    private static final int MUTED = 0xFFB9B9B9;
    private static final int LINE = 0xFF626262;
    private static final int GREEN = 0xFF8ED389;
    private static final int YELLOW = 0xFFFFCC75;
    private static final int RED = 0xFFFF8E8E;
    private static final ItemStack ICON = new ItemStack(DigitalStorageContent.accessorItem());
    private enum View { HOME, UPGRADE, NETWORK, MORE, VOLUME, CREATE, RENAME, DELETE, CLEAR }
    private record Hint(Component text, int x, int y, int width, int height) {}

    private final DigitalStorageScreenProtocol.RequestSender requestSender;
    private final List<Button> homeButtons = new ArrayList<>();
    private final List<Button> bindButtons = new ArrayList<>();
    private final List<Button> manageButtons = new ArrayList<>();
    private final List<Hint> hints = new ArrayList<>();
    private View view = View.HOME;
    private UUID selectedVolume;
    private int firstVolume;
    private int homeScroll;
    private int detailScroll;
    private int detailLines;
    private boolean draggingScroll;
    private boolean lastBound;
    private boolean drawingHomeContent;
    private EditBox nameField;
    private Button upgrade, more, network, toggle, create, back, confirm, secondary;
    private DigitalStorageScreenState requestedFrom;
    private int pendingTicks;
    private boolean responseTimedOut;
    private Component localStatus = Component.empty();

    public DigitalStorageScreen(DigitalStorageScreenHandler handler, Inventory inventory, Component title,
                                DigitalStorageScreenProtocol.RequestSender requestSender) {
        super(handler, inventory, title);
        this.requestSender = java.util.Objects.requireNonNull(requestSender, "requestSender");
        inventoryLabelY = 10000;
    }

    @Override
    protected void init() {
        String draft = nameField == null ? "" : nameField.getValue();
        imageWidth = Math.min(360, width - 16);
        imageHeight = Math.min(desiredHeight(), height - 16);
        super.init();
        homeButtons.clear();
        bindButtons.clear();
        manageButtons.clear();
        lastBound = menu.state().accessorBound();
        button("gui.close", imageWidth - 26, 4, 20, ignored -> onClose());
        upgrade = button("upgrade", MARGIN, 113, 98, ignored -> open(View.UPGRADE));
        more = button("gui.more", imageWidth - 90, 113, 78, ignored -> open(View.MORE));
        network = button("gui.details", imageWidth - 90, 149, 78, ignored -> open(View.NETWORK));
        toggle = button("gui.off", imageWidth - 76, 187, 64, ignored -> requestToggle());
        homeButtons.addAll(List.of(upgrade, more, network, toggle));
        create = button("gui.create", MARGIN, 34, 122, ignored -> {
            nameField.setValue("");
            open(View.CREATE);
        });
        int rows = Math.max(1, (imageHeight - 92) / ROW_HEIGHT);
        for (int row = 0; row < rows; row++) {
            int slot = row;
            bindButtons.add(button("gui.bind", imageWidth - 110, 64 + row * ROW_HEIGHT, 62,
                    ignored -> bind(slot)));
            manageButtons.add(button("gui.more_short", imageWidth - 44, 64 + row * ROW_HEIGHT, 26,
                    ignored -> {
                        var choice = visibleChoice(slot);
                        if (choice != null) {
                            selectedVolume = choice.id();
                            open(View.VOLUME);
                        }
                    }));
        }
        nameField = addRenderableWidget(new EditBox(font, leftPos + MARGIN, topPos + 81,
                imageWidth - MARGIN * 2, 20, tr("gui.name")));
        nameField.setMaxLength(StorageVolume.MAX_NAME_LENGTH);
        nameField.setValue(draft);
        back = button("gui.back", MARGIN, imageHeight - 44, 92, ignored -> goBack());
        confirm = button("gui.confirm", imageWidth - 118, imageHeight - 44, 106, ignored -> confirm());
        secondary = button("gui.refresh", imageWidth - 118, imageHeight - 70, 106,
                ignored -> secondaryAction());
        updateWidgets();
        nameField.setResponder(ignored -> updateWidgets());
    }

    private Button button(String key, int x, int y, int size, Button.OnPress action) {
        return addRenderableWidget(Button.builder(tr(key), action)
                .bounds(leftPos + x, topPos + y, size, 20).build());
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("screen.digitalstorage." + key, args);
    }

    private void open(View next) {
        view = next;
        detailScroll = 0;
        detailLines = 0;
        draggingScroll = false;
        clearWidgets();
        init();
        setFocused(next == View.HOME ? null : next == View.CREATE || next == View.RENAME ? nameField : back);
        nameField.setFocused(next == View.CREATE || next == View.RENAME);
    }

    private void goBack() {
        open(view == View.RENAME || view == View.DELETE ? View.VOLUME
                : view == View.CLEAR ? View.MORE : View.HOME);
    }

    @Override
    protected void containerTick() {
        if (requestedFrom != null) {
            if (menu.state().responseRevision() > requestedFrom.responseRevision()) {
                boolean successful = menu.state().statusSuccessful();
                requestedFrom = null;
                if (successful && (view == View.CREATE || view == View.RENAME || view == View.DELETE
                        || view == View.CLEAR)) open(View.HOME);
            } else if (++pendingTicks >= 200) {
                requestedFrom = null;
                responseTimedOut = true;
                localStatus = tr("gui.timeout");
            }
        }
        if (lastBound != menu.state().accessorBound()) {
            lastBound = menu.state().accessorBound();
            open(View.HOME);
        }
        if (imageHeight != Math.min(desiredHeight(), height - 16)) {
            clearWidgets();
            init();
        }
        updateWidgets();
    }

    private int desiredHeight() {
        return switch (view) {
            case HOME, NETWORK -> 250;
            case UPGRADE -> menu.state().hasNextTier() ? 250 : 174;
            case CREATE, RENAME -> 160;
            case MORE, CLEAR, DELETE -> 190;
            case VOLUME -> 210;
        };
    }

    private boolean pending() { return requestedFrom != null; }
    private boolean canRequest() { return !pending() && !responseTimedOut; }

    private void beginRequest() {
        requestedFrom = menu.state();
        pendingTicks = 0;
        localStatus = Component.empty();
        updateWidgets();
    }

    private void sendButton(int id) {
        if (!canRequest() || minecraft == null || minecraft.gameMode == null) return;
        beginRequest();
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
    }

    private DigitalStorageScreenState.VolumeChoice visibleChoice(int row) {
        var choices = menu.state().ownedVolumes();
        int index = firstVolume + row;
        return index >= 0 && index < choices.size() ? choices.get(index) : null;
    }

    private DigitalStorageScreenState.VolumeChoice selectedChoice() {
        return menu.state().ownedVolumes().stream().filter(v -> v.id().equals(selectedVolume))
                .findFirst().orElse(null);
    }

    private void bind(int row) {
        if (visibleChoice(row) != null && bindButtons.get(row).active)
            sendButton(DigitalStorageScreenHandler.BIND_VOLUME_BUTTON_BASE + firstVolume + row);
    }

    private void requestToggle() {
        if (toggle.active) sendButton(menu.state().acceptsUnstackableItems()
                ? DigitalStorageScreenHandler.SET_UNSTACKABLE_REJECT_BUTTON_ID
                : DigitalStorageScreenHandler.SET_UNSTACKABLE_ACCEPT_BUTTON_ID);
    }

    private void confirm() {
        if (!confirm.active) return;
        switch (view) {
            case CREATE -> {
                beginRequest();
                requestSender.send(new DigitalStorageScreenProtocol.CreateVolume(menu.containerId,
                        nameField.getValue().strip()));
            }
            case RENAME, DELETE -> {
                var choice = selectedChoice();
                if (choice == null) return;
                int action = view == View.RENAME ? DigitalStorageScreenHandler.RENAME_VOLUME_ACTION
                        : DigitalStorageScreenHandler.DELETE_VOLUME_ACTION;
                beginRequest();
                requestSender.send(new DigitalStorageScreenProtocol.ManageVolume(menu.containerId,
                        action, choice.id(), nameField.getValue().strip()));
            }
            case CLEAR -> sendButton(DigitalStorageScreenHandler.CLEAR_BINDING_BUTTON_ID);
            case UPGRADE -> sendButton(DigitalStorageScreenHandler.UPGRADE_BUTTON_ID);
            case NETWORK -> sendButton(DigitalStorageScreenHandler.MIGRATION_BUTTON_ID);
            case MORE -> open(View.CLEAR);
            case VOLUME -> {
                nameField.setValue(selectedChoice().name());
                open(View.RENAME);
            }
            default -> { }
        }
    }

    private void secondaryAction() {
        if (!secondary.active) return;
        if (view == View.NETWORK) sendButton(DigitalStorageScreenHandler.NETWORK_ANALYSIS_BUTTON_ID);
        if (view == View.VOLUME) open(View.DELETE);
    }

    private void updateWidgets() {
        if (nameField == null) return;
        var state = menu.state();
        var diagnostic = state.networkDiagnostic();
        boolean home = view == View.HOME;
        boolean boundHome = home && state.accessorBound();
        boolean unboundHome = home && !state.accessorBound();
        homeScroll = Math.max(0, Math.min(homeScroll, maxHomeScroll()));
        int[] positions = {113, 113, 149, 187};
        for (int i = 0; i < homeButtons.size(); i++) {
            Button widget = homeButtons.get(i);
            int y = positions[i] - homeScroll;
            widget.setY(topPos + y);
            widget.visible = boundHome && y >= 30 && y + 20 <= imageHeight - 24;
            widget.active = canRequest();
        }
        upgrade.active &= state.accessorConfigurable() && state.hasNextTier();
        upgrade.setMessage(tr(state.hasNextTier() ? "gui.upgrade" : "maximum_button"));
        more.active = true;
        network.active = true;
        boolean accept = state.unstackableItemsAllowedByServer() && state.acceptsUnstackableItems();
        toggle.setMessage(tr(pending() ? "gui.pending_short" : accept ? "gui.on" : "gui.off"));
        toggle.active &= state.unstackableItemsConfigurable() && state.unstackableItemsAllowedByServer()
                && !diagnostic.migrationActive();
        String policyTip = !state.unstackableItemsAllowedByServer() ? "server_disabled"
                : diagnostic.migrationActive() ? "migration_active"
                : !state.unstackableItemsConfigurable() ? "not_owner" : accept ? "accept" : "reject";
        toggle.setTooltip(Tooltip.create(tr("unstackables.tooltip." + policyTip)));
        create.visible = unboundHome && state.accessorConfigurable();
        create.active = canRequest() && state.accessorConfigurable();
        firstVolume = Math.max(0, Math.min(firstVolume,
                Math.max(0, state.ownedVolumes().size() - bindButtons.size())));
        for (int row = 0; row < bindButtons.size(); row++) {
            boolean visible = unboundHome && state.accessorConfigurable() && visibleChoice(row) != null;
            bindButtons.get(row).visible = visible;
            manageButtons.get(row).visible = visible;
            bindButtons.get(row).active = canRequest() && state.accessorConfigurable();
            manageButtons.get(row).active = canRequest() && state.accessorConfigurable();
        }
        boolean editing = view == View.CREATE || view == View.RENAME;
        nameField.visible = editing;
        nameField.setEditable(editing && canRequest());
        back.visible = !home;
        back.active = true;
        confirm.visible = !home;
        confirm.active = canRequest();
        confirm.setTooltip(null);
        secondary.visible = view == View.NETWORK || view == View.VOLUME;
        secondary.active = canRequest();
        secondary.setTooltip(null);
        var selected = selectedChoice();
        switch (view) {
            case CREATE -> {
                confirm.setMessage(tr("create_volume"));
                confirm.active &= state.accessorConfigurable() && !state.accessorBound()
                        && !nameField.getValue().isBlank();
            }
            case RENAME -> {
                confirm.setMessage(tr("gui.save"));
                confirm.active &= state.accessorConfigurable() && !state.accessorBound() && selected != null
                        && !nameField.getValue().isBlank() && !nameField.getValue().strip().equals(selected.name());
            }
            case DELETE -> {
                confirm.setMessage(tr("gui.confirm_delete"));
                confirm.active &= state.accessorConfigurable() && !state.accessorBound()
                        && selected != null && selected.usedVariants() == 0;
            }
            case CLEAR, MORE -> {
                confirm.setMessage(tr(view == View.CLEAR ? "gui.confirm_clear" : "clear_binding"));
                confirm.active &= state.accessorBound() && state.accessorConfigurable();
            }
            case VOLUME -> {
                confirm.setMessage(tr("rename_volume"));
                secondary.setMessage(tr("delete_volume"));
                confirm.active &= state.accessorConfigurable() && !state.accessorBound() && selected != null;
                secondary.active = confirm.active && selected.usedVariants() == 0;
                secondary.setTooltip(Tooltip.create(tr(selected != null && selected.usedVariants() == 0
                        ? "delete_volume.tooltip.empty" : "delete_volume.tooltip.non_empty")));
            }
            case UPGRADE -> {
                confirm.setMessage(tr(state.hasNextTier() ? "gui.confirm_upgrade" : "maximum_button"));
                confirm.active &= state.accessorBound() && state.accessorConfigurable()
                        && state.hasNextTier() && state.canAfford();
            }
            case NETWORK -> {
                confirm.setMessage(tr(diagnostic.migrationActive() ? "migration.cancel" : "gui.optimize"));
                confirm.active &= state.accessorBound() && state.accessorConfigurable()
                        && (diagnostic.migrationActive() || diagnostic.available()
                        && !diagnostic.hasDuplicateTargetEndpoints() && diagnostic.recommendedVariants() > 0);
                confirm.setTooltip(Tooltip.create(tr("network.migration_hint")));
                secondary.setMessage(tr("network.refresh"));
                secondary.active &= state.accessorBound() && !diagnostic.migrationActive();
            }
            default -> { }
        }
        if (pending() && confirm.visible) confirm.setMessage(tr("gui.pending"));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && view != View.HOME) { goBack(); return true; }
        if (nameField.visible && nameField.isFocused()) {
            if (keyCode == 257 || keyCode == 335) { confirm(); return true; }
            if (minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        }
        if (!nameField.visible && hasScroll() && keyCode >= 264 && keyCode <= 269) {
            int direction = keyCode == 264 || keyCode == 267 ? 1 : -1;
            int amount = keyCode == 266 || keyCode == 267 ? 8 : 1;
            if (view == View.HOME && !menu.state().accessorBound()) {
                firstVolume = keyCode == 268 ? 0 : keyCode == 269 ? menu.state().ownedVolumes().size()
                        : firstVolume + direction * amount;
            } else if (view == View.HOME) {
                homeScroll = keyCode == 268 ? 0 : keyCode == 269 ? maxHomeScroll()
                        : homeScroll + direction * amount * 12;
            } else {
                detailScroll = keyCode == 268 ? 0 : keyCode == 269 ? maxDetailScroll()
                        : Math.max(0, Math.min(maxDetailScroll(), detailScroll + direction * amount));
            }
            updateWidgets();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        boolean handled = super.charTyped(character, modifiers);
        if (handled) updateWidgets();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (!inside(mouseX, mouseY, MARGIN, 30, imageWidth - 24, imageHeight - 54))
            return super.mouseScrolled(mouseX, mouseY, amount);
        int step = amount > 0 ? -1 : amount < 0 ? 1 : 0;
        if (view == View.HOME && !menu.state().accessorBound()) firstVolume += step;
        else if (view == View.HOME) homeScroll += step * 12;
        else detailScroll = Math.max(0, Math.min(maxDetailScroll(), detailScroll + step * 3));
        updateWidgets();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && hasScroll() && inside(mouseX, mouseY, imageWidth - 9, scrollTop(), 7, scrollHeight())) {
            draggingScroll = true;
            moveScroll(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (draggingScroll && button == 0) { moveScroll(mouseY); return true; }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingScroll = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void moveScroll(double mouseY) {
        double fraction = Math.max(0, Math.min(1, (mouseY - topPos - scrollTop()) / scrollHeight()));
        if (view == View.HOME && !menu.state().accessorBound())
            firstVolume = (int) Math.round(fraction * Math.max(0, menu.state().ownedVolumes().size() - bindButtons.size()));
        else if (view == View.HOME) homeScroll = (int) Math.round(fraction * maxHomeScroll());
        else detailScroll = (int) Math.round(fraction * maxDetailScroll());
        updateWidgets();
    }

    private boolean inside(double x, double y, int px, int py, int w, int h) {
        return x >= leftPos + px && x < leftPos + px + w && y >= topPos + py && y < topPos + py + h;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        hints.clear();
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, delta);
        renderTooltip(graphics, mouseX, mouseY);
        for (Hint hint : hints) {
            if (inside(mouseX, mouseY, hint.x(), hint.y(), hint.width(), hint.height())) {
                graphics.renderTooltip(font, font.split(hint.text(), Math.max(100, Math.min(300, width - 24))), mouseX, mouseY);
                break;
            }
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        graphics.fillGradient(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF303236, 0xFF202226);
        graphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, 0xFF858585);
        graphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + 26, 0xFF3B3B3B);
        graphics.fill(leftPos + 1, topPos + 26, leftPos + imageWidth - 1, topPos + 27, 0xFF997344);
        graphics.fill(leftPos + 1, topPos + imageHeight - 23, leftPos + imageWidth - 1,
                topPos + imageHeight - 1, 0xFF181A1D);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.renderItem(ICON, 6, 5);
        text(graphics, title, 26, 9, imageWidth - 58, TEXT);
        if (view == View.HOME) {
            if (menu.state().accessorBound()) drawHome(graphics);
            else drawVolumes(graphics);
        } else drawDetail(graphics);
        Component status = pending() ? tr("gui.pending") : !localStatus.getString().isEmpty()
                ? localStatus : menu.state().status();
        text(graphics, status, MARGIN, imageHeight - 17, imageWidth - 24,
                pending() ? MUTED : !localStatus.getString().isEmpty() || !menu.state().statusSuccessful() ? RED : GREEN);
        drawScrollbar(graphics);
    }

    private void drawHome(GuiGraphics graphics) {
        var state = menu.state();
        var d = state.networkDiagnostic();
        graphics.enableScissor(leftPos + 1, topPos + 30, leftPos + imageWidth - 10, topPos + imageHeight - 24);
        graphics.pose().pushPose();
        graphics.pose().translate(0, -homeScroll, 0);
        drawingHomeContent = true;
        text(graphics, Component.literal(state.volumeName()), MARGIN, 35, imageWidth - 124, TEXT);
        text(graphics, tierName(state.tierId()), imageWidth - 104, 35, 92, MUTED);
        progress(graphics, MARGIN, 54, imageWidth - 24, 7,
                state.variantCapacity() <= 0 ? 0 : (double) state.usedVariants() / state.variantCapacity());
        text(graphics, tr("gui.capacity", state.usedVariants(), state.variantCapacity(),
                percentage(state.usedVariants(), state.variantCapacity())), MARGIN, 68, imageWidth - 24, TEXT);
        text(graphics, tr("gui.totals", state.totalItems(), state.remainingVariants()), MARGIN, 83, imageWidth - 24, MUTED);
        text(graphics, tr("gui.controller", state.controller()), MARGIN, 98, imageWidth - 24, MUTED);
        graphics.hLine(MARGIN, imageWidth - MARGIN - 1, 140, LINE);
        Component summary = !d.available() ? tr("network.not_connected_short")
                : d.hasDuplicateTargetEndpoints() || d.failingScanners() > 0 ? tr("gui.network_attention") : tr("gui.network_ready");
        text(graphics, summary, MARGIN, 148, imageWidth - 114,
                !d.available() ? MUTED : d.hasDuplicateTargetEndpoints() ? RED : d.failingScanners() > 0 ? YELLOW : GREEN);
        Component opportunity = d.migrationActive() ? tr("migration.progress_short", d.completedCandidates(), d.totalCandidates())
                : d.available() && d.recommendedVariants() > 0 ? tr("gui.opportunity", d.recommendedVariants()) : tr("gui.no_opportunity");
        text(graphics, opportunity, MARGIN, 163, imageWidth - 114, MUTED);
        text(graphics, tr("gui.unstackables"), MARGIN, 193, imageWidth - 98, TEXT);
        drawingHomeContent = false;
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    private static String percentage(int used, int capacity) {
        return String.format(Locale.ROOT, "%.1f", capacity <= 0 ? 0 : 100.0 * used / capacity);
    }

    private void drawVolumes(GuiGraphics graphics) {
        if (!menu.state().accessorConfigurable()) {
            text(graphics, tr("gui.read_only"), MARGIN, 63, imageWidth - 24, YELLOW);
            return;
        }
        if (menu.state().ownedVolumes().isEmpty()) {
            text(graphics, tr("gui.empty"), MARGIN, 69, imageWidth - 24, MUTED);
            return;
        }
        for (int row = 0; row < bindButtons.size(); row++) {
            var choice = visibleChoice(row);
            if (choice == null) break;
            int y = 62 + row * ROW_HEIGHT;
            int w = imageWidth - 136;
            text(graphics, Component.literal(choice.name()), MARGIN, y, w, TEXT);
            text(graphics, tr("gui.volume_usage", tierName(choice.tierId()), choice.usedVariants(), choice.variantCapacity()),
                    MARGIN, y + 13, w, MUTED);
            progress(graphics, MARGIN, y + 26, w, 4,
                    choice.variantCapacity() <= 0 ? 0 : (double) choice.usedVariants() / choice.variantCapacity());
            graphics.hLine(MARGIN, imageWidth - 18, y + 36, LINE);
        }
    }

    private void drawDetail(GuiGraphics graphics) {
        String heading = switch (view) {
            case UPGRADE -> "gui.upgrade_title";
            case NETWORK -> "gui.network_title";
            case MORE -> "gui.manage_accessor";
            case CREATE -> "gui.create";
            case RENAME -> "rename_volume";
            case DELETE -> "gui.delete_title";
            case CLEAR -> "gui.clear_title";
            default -> "gui.manage_volume";
        };
        text(graphics, tr(heading), MARGIN, 35, imageWidth - 24, TEXT);
        if (upgradeLayout()) {
            drawUpgrade(graphics);
            return;
        }
        if (view == View.CREATE || view == View.RENAME) {
            text(graphics, view == View.RENAME && selectedChoice() != null
                    ? tr("gui.target", selectedChoice().name()) : tr("gui.name"), MARGIN, 62, imageWidth - 24, MUTED);
            detailLines = 0;
            return;
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component paragraph : detailText()) {
            lines.addAll(font.split(paragraph, imageWidth - 42));
            lines.add(FormattedCharSequence.EMPTY);
        }
        detailLines = lines.size();
        detailScroll = Math.max(0, Math.min(detailScroll, maxDetailScroll()));
        panel(graphics, MARGIN - 2, 53, imageWidth - MARGIN * 2 + 2, detailViewport() + 8);
        graphics.enableScissor(leftPos + MARGIN, topPos + 57, leftPos + imageWidth - 14, topPos + 57 + detailViewport());
        for (int i = detailScroll; i < lines.size(); i++) {
            int y = 57 + (i - detailScroll) * (font.lineHeight + 2);
            if (y >= 57 + detailViewport()) break;
            graphics.drawString(font, lines.get(i), MARGIN + 5, y, i == 0 ? TEXT : MUTED, false);
        }
        graphics.disableScissor();
    }

    private void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF25282C);
        graphics.hLine(x, x + w - 1, y, 0xFF55595E);
        graphics.hLine(x, x + w - 1, y + h - 1, 0xFF15171A);
    }

    private void drawUpgrade(GuiGraphics graphics) {
        var state = menu.state();
        text(graphics, tr("gui.target", state.volumeName()), MARGIN, 53, imageWidth - 24, MUTED);
        if (!state.hasNextTier()) {
            panel(graphics, MARGIN, 71, imageWidth - 24, 48);
            graphics.renderItem(new ItemStack(Items.NETHER_STAR), MARGIN + 9, 85);
            text(graphics, tr("maximum"), MARGIN + 34, 81, imageWidth - 68, GREEN);
            text(graphics, tr("gui.tier_capacity", tierName(state.tierId()), state.variantCapacity()),
                    MARGIN + 34, 99, imageWidth - 68, MUTED);
            detailLines = 0;
            return;
        }
        int cardWidth = (imageWidth - 48) / 2;
        tierPanel(graphics, MARGIN, 72, cardWidth, tr("gui.current_tier"), state.tierId(), state.variantCapacity(), MUTED);
        tierPanel(graphics, imageWidth - MARGIN - cardWidth, 72, cardWidth,
                tr("gui.next_tier"), state.nextTierId(), state.nextVariantCapacity(), GREEN);
        text(graphics, Component.literal("→"), imageWidth / 2 - 4, 91, 12, YELLOW);
        text(graphics, tr("cost"), MARGIN, 128, imageWidth - 24, TEXT);
        List<Component> costs = new ArrayList<>();
        List<ItemStack> icons = new ArrayList<>();
        for (UpgradeIngredient ingredient : state.upgradeCost()) {
            costs.add(ingredient.displayComponent());
            icons.add(ingredient.kind() == UpgradeIngredient.Kind.ITEM
                    ? BuiltInRegistries.ITEM.getOptional(ingredient.id()).map(ItemStack::new).orElse(ItemStack.EMPTY)
                    : new ItemStack(Items.PAPER));
        }
        if (state.experienceLevels() > 0) {
            costs.add(tr("cost_xp", state.experienceLevels()));
            icons.add(new ItemStack(Items.EXPERIENCE_BOTTLE));
        }
        if (costs.isEmpty()) { costs.add(tr("cost_free")); icons.add(ItemStack.EMPTY); }
        detailLines = costs.size();
        detailScroll = Math.max(0, Math.min(detailScroll, maxDetailScroll()));
        panel(graphics, MARGIN, 140, imageWidth - 24, upgradeCostViewport());
        graphics.enableScissor(leftPos + MARGIN, topPos + 140, leftPos + imageWidth - 14,
                topPos + 140 + upgradeCostViewport());
        for (int i = detailScroll; i < Math.min(costs.size(), detailScroll + upgradeCostViewport() / 20); i++) {
            int y = 142 + (i - detailScroll) * 20;
            if (y >= 140 + upgradeCostViewport()) break;
            graphics.renderItem(icons.get(i), MARGIN + 5, y);
            text(graphics, costs.get(i), MARGIN + 27, y + 4, imageWidth - 56, TEXT);
        }
        graphics.disableScissor();
        text(graphics, tr(!state.accessorConfigurable() ? "gui.read_only"
                : state.canAfford() ? "affordable" : "unaffordable"), MARGIN, imageHeight - 59,
                imageWidth - 24, !state.accessorConfigurable() ? YELLOW : state.canAfford() ? GREEN : RED);
    }

    private void tierPanel(GuiGraphics graphics, int x, int y, int w, Component label,
                           ResourceLocation tier, int capacity, int color) {
        panel(graphics, x, y, w, 47);
        text(graphics, label, x + 7, y + 5, w - 14, MUTED);
        text(graphics, tierName(tier), x + 7, y + 18, w - 14, color);
        text(graphics, tr("gui.type_capacity", capacity), x + 7, y + 33, w - 14, TEXT);
    }

    private boolean upgradeLayout() {
        return view == View.UPGRADE && imageHeight >= (menu.state().hasNextTier() ? 230 : 160);
    }

    private int upgradeCostViewport() { return Math.max(20, (imageHeight - 205) / 20 * 20); }

    private List<Component> detailText() {
        var state = menu.state();
        var d = state.networkDiagnostic();
        List<Component> result = new ArrayList<>();
        switch (view) {
            case UPGRADE -> {
                result.add(tr("gui.target", state.volumeName()));
                if (!state.hasNextTier()) { result.add(tr("maximum")); break; }
                result.add(tr("upgrade.transition", tierName(state.tierId()), tierName(state.nextTierId())));
                result.add(tr("upgrade.capacity_transition", state.variantCapacity(), state.nextVariantCapacity()));
                result.add(tr("cost"));
                for (UpgradeIngredient ingredient : state.upgradeCost()) {
                    Component name = ingredient.kind() == UpgradeIngredient.Kind.ITEM
                            ? BuiltInRegistries.ITEM.getOptional(ingredient.id()).<Component>map(item -> item.getDescription())
                                    .orElse(Component.literal(ingredient.id().toString()))
                            : Component.literal("#" + ingredient.id());
                    result.add(tr("cost_entry", name, ingredient.count()));
                }
                if (state.experienceLevels() > 0) result.add(tr("cost_xp", state.experienceLevels()));
                if (state.upgradeCost().isEmpty() && state.experienceLevels() == 0) result.add(tr("cost_free"));
                result.add(tr(state.canAfford() ? "affordable" : "unaffordable"));
                if (!state.accessorConfigurable()) result.add(tr("gui.read_only"));
            }
            case NETWORK -> {
                if (!d.available()) result.add(tr("network.unavailable"));
                else {
                    result.add(tr("network.health", d.healthScore(), d.grade()));
                    result.add(tr("gui.score_hint"));
                    if (d.hasDuplicateTargetEndpoints()) {
                        result.add(tr("network.duplicate_warning", d.targetEndpointCount()));
                        result.add(tr("network.keep_one_endpoint"));
                    }
                    if (d.failingScanners() > 0) result.add(tr("network.hopper_warning", d.failingScanners()));
                    if (!d.hasDuplicateTargetEndpoints() && d.failingScanners() == 0) result.add(tr("network.no_action"));
                    result.add(tr("gui.network_inventories", d.physicalInventories(), d.nonEmptyViews(), d.totalViews()));
                    result.add(tr("gui.network_scanners", d.activeScanners(), d.failingScanners(), d.averageScanIntervalTicks()));
                    result.add(tr("gui.opportunity", d.recommendedVariants()));
                    result.add(tr("network.freed_views_short", d.estimatedFreedViews()));
                    if (!d.topCandidateId().isBlank()) result.add(tr("gui.top_candidate", d.topCandidateId()));
                }
                if (d.migrationActive()) {
                    result.add(tr("migration.running"));
                    result.add(tr("migration.progress", d.movedItems(), d.completedCandidates(), d.totalCandidates(), d.scannedViews()));
                }
                result.add(tr("gui.migration_explanation"));
            }
            case MORE -> {
                result.add(tr("gui.target", state.volumeName()));
                result.add(tr("gui.controller", state.controller()));
                result.add(tr("gui.clear_explanation"));
            }
            case CLEAR -> {
                result.add(tr("gui.target", state.volumeName()));
                result.add(tr("gui.clear_confirmation"));
            }
            case VOLUME, DELETE -> {
                var choice = selectedChoice();
                if (choice == null) { result.add(tr("gui.target_missing")); break; }
                result.add(tr("gui.target", choice.name()));
                result.add(tr("gui.volume_usage", tierName(choice.tierId()), choice.usedVariants(), choice.variantCapacity()));
                result.add(tr(view == View.DELETE ? "gui.delete_confirmation" : "gui.volume_help"));
            }
            default -> { }
        }
        return result;
    }

    private int maxHomeScroll() { return Math.max(0, 231 - imageHeight); }
    private int detailViewport() { return Math.max(11, imageHeight - (secondary.visible ? 132 : 106)); }
    private int maxDetailScroll() {
        if (upgradeLayout()) return menu.state().hasNextTier()
                ? Math.max(0, detailLines - Math.max(1, upgradeCostViewport() / 20)) : 0;
        return Math.max(0, detailLines - Math.max(1, detailViewport() / (font.lineHeight + 2)));
    }
    private int scrollTop() { return view == View.HOME ? menu.state().accessorBound() ? 30 : 62
            : upgradeLayout() ? 140 : 57; }
    private int scrollHeight() { return view == View.HOME ? imageHeight - 24 - scrollTop()
            : upgradeLayout() ? upgradeCostViewport() : detailViewport(); }
    private boolean hasScroll() {
        return view == View.HOME ? menu.state().accessorBound() ? maxHomeScroll() > 0
                : menu.state().ownedVolumes().size() > bindButtons.size() : maxDetailScroll() > 0;
    }

    private void drawScrollbar(GuiGraphics graphics) {
        if (!hasScroll()) return;
        int max = view == View.HOME ? menu.state().accessorBound() ? maxHomeScroll()
                : menu.state().ownedVolumes().size() - bindButtons.size() : maxDetailScroll();
        int value = view == View.HOME ? menu.state().accessorBound() ? homeScroll : firstVolume : detailScroll;
        int h = scrollHeight();
        int thumb = Math.max(12, Math.min(h, h / 3));
        int y = scrollTop() + (max <= 0 ? 0 : (int) Math.round((h - thumb) * (double) value / max));
        graphics.fill(imageWidth - 8, scrollTop(), imageWidth - 4, scrollTop() + h, 0xFF171717);
        graphics.fill(imageWidth - 8, y, imageWidth - 4, y + thumb, 0xFFA0A0A0);
    }

    private void text(GuiGraphics graphics, Component content, int x, int y, int w, int color) {
        boolean clipped = font.width(content) > w;
        Component display = clipped ? Component.literal(font.plainSubstrByWidth(content.getString(),
                Math.max(0, w - font.width("…"))) + "…") : content;
        graphics.drawString(font, display, x, y, color, false);
        int hintY = drawingHomeContent ? y - homeScroll : y;
        if (clipped && (!drawingHomeContent || hintY >= 30 && hintY + font.lineHeight <= imageHeight - 24))
            hints.add(new Hint(content, x, hintY, w, font.lineHeight));
    }

    private void progress(GuiGraphics graphics, int x, int y, int w, int h, double fraction) {
        graphics.fill(x, y, x + w, y + h, 0xFF151515);
        graphics.renderOutline(x, y, w, h, LINE);
        int filled = (int) Math.round((w - 2) * Math.max(0, Math.min(1, fraction)));
        if (filled > 0) graphics.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, fraction >= 0.9 ? YELLOW : GREEN);
    }

    private Component tierName(ResourceLocation id) {
        return dev.kehai.digitalstorage.tier.DigitalStorageTier.displayName(id);
    }
}
