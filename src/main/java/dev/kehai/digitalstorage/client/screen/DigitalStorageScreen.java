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
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Shared device UI. Navigation is local; every mutation remains server validated. */
public final class DigitalStorageScreen extends AbstractContainerScreen<DigitalStorageScreenHandler> {
    private static final int MARGIN = 12;
    private static final int ROW_HEIGHT = 40;
    private static final int TEXT = 0xFF404040;
    private static final int MUTED = 0xFF545454;
    private static final int LINE = 0xFF8B8B8B;
    private static final int GREEN = 0xFF245A20;
    private static final int YELLOW = 0xFF825500;
    private static final int RED = 0xFFA32C2C;
    private static final ItemStack ICON = new ItemStack(DigitalStorageContent.accessorItem());
    private enum View { HOME, UPGRADE, NETWORK, VOLUME, CREATE, RENAME, DELETE, CLEAR, ICONS }
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
    private Button upgrade, clearBinding, network, toggle, create, back, confirm, secondary;
    private Button volumeGlyph;
    private final List<Button> iconChoices = new ArrayList<>();
    private final List<Button> presetChoices = new ArrayList<>();
    private final Inventory inventory;
    private View iconReturn = View.HOME;
    private boolean togglePending;
    private int pendingButtonId = -1;
    private DigitalStorageScreenState requestedFrom;
    private int pendingTicks;
    private boolean responseTimedOut;
    private Component localStatus = Component.empty();

    public DigitalStorageScreen(DigitalStorageScreenHandler handler, Inventory inventory, Component title,
                                DigitalStorageScreenProtocol.RequestSender requestSender) {
        super(handler, inventory, title);
        this.requestSender = java.util.Objects.requireNonNull(requestSender, "requestSender");
        this.inventory = inventory;
        inventoryLabelY = 10000;
    }

    @Override
    protected void init() {
        String draft = nameField == null ? "" : nameField.getValue();
        imageWidth = Math.min(desiredWidth(), width - 16);
        imageHeight = Math.min(desiredHeight(), height - 16);
        super.init();
        homeButtons.clear();
        bindButtons.clear();
        manageButtons.clear();
        lastBound = menu.state().accessorBound();
        button("gui.close", imageWidth - 26, 4, 20, ignored -> onClose());
        upgrade = button("upgrade", MARGIN, 113, 98, ignored -> open(View.UPGRADE));
        clearBinding = button("clear_binding", imageWidth - 102, 113, 90, ignored -> open(View.CLEAR));
        network = button("gui.details", imageWidth - 90, 149, 78, ignored -> open(View.NETWORK));
        toggle = addRenderableWidget(new SwitchButton(leftPos + imageWidth - 76, topPos + 187, ignored -> requestToggle()));
        homeButtons.addAll(List.of(upgrade, clearBinding, network, toggle));
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
        nameField.setBordered(true);
        nameField.setHint(tr("gui.name_placeholder"));
        nameField.setValue(draft);
        iconChoices.clear();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack sample = inventory.getItem(slot < 27 ? slot + 9 : slot - 27);
            ItemStack display = sample.isEmpty() ? ItemStack.EMPTY : new ItemStack(sample.getItem());
            iconChoices.add(addRenderableWidget(new ItemIconButton(leftPos + (imageWidth - 198) / 2 + slot % 9 * 22,
                    topPos + 65 + slot / 9 * 22 + (slot >= 27 ? 4 : 0), display, ignored -> setIcon(display), true)));
        }
        presetChoices.clear();
        for (int i = 0; i < StorageVolume.PRESET_ICONS.size(); i++) {
            ItemStack display = iconStack(StorageVolume.PRESET_ICONS.get(i));
            presetChoices.add(addRenderableWidget(new ItemIconButton(leftPos + imageWidth - MARGIN - 120 + i * 20,
                    topPos + imageHeight - 44, display, ignored -> setIcon(display), true)));
        }
        volumeGlyph = addRenderableWidget(new ItemIconButton(leftPos + MARGIN, topPos + 32,
                ItemStack.EMPTY, ignored -> chooseIcon()));
        back = button("gui.back", MARGIN, imageHeight - 44, 92, ignored -> goBack());
        confirm = button("gui.confirm", imageWidth - 118, imageHeight - 44, 106, ignored -> confirm());
        secondary = button("gui.refresh", imageWidth - 118, imageHeight - 70, 106,
                ignored -> secondaryAction());
        if (view == View.NETWORK || view == View.VOLUME) {
            int actionWidth = (imageWidth - 32) / 3;
            back.setWidth(actionWidth);
            secondary.setX(leftPos + MARGIN + actionWidth + 4);
            secondary.setY(topPos + imageHeight - 44);
            secondary.setWidth(actionWidth);
            confirm.setX(leftPos + MARGIN + 2 * (actionWidth + 4));
            confirm.setWidth(actionWidth);
        }
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
        open(view == View.ICONS ? iconReturn : view == View.RENAME || view == View.DELETE ? View.VOLUME
                : View.HOME);
    }

    @Override
    protected void containerTick() {
        if (requestedFrom != null) {
            if (menu.state().responseRevision() > requestedFrom.responseRevision()) {
                boolean successful = menu.state().statusSuccessful();
                requestedFrom = null;
                if (successful && (view == View.CREATE || view == View.RENAME || view == View.DELETE
                        || view == View.CLEAR)) open(View.HOME);
                else if (successful && view == View.ICONS) open(iconReturn);
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
            case HOME -> 234;
            case NETWORK -> 224;
            case UPGRADE -> menu.state().hasNextTier() ? 250 : 174;
            case CREATE, RENAME -> 160;
            case CLEAR, DELETE -> 190;
            case VOLUME -> 180;
            case ICONS -> 224;
        };
    }

    private int desiredWidth() {
        return switch (view) {
            case CREATE, RENAME, VOLUME -> 280;
            case CLEAR, DELETE -> 300;
            default -> 320;
        };
    }

    private boolean pending() { return requestedFrom != null; }
    private boolean canRequest() { return !pending() && !responseTimedOut; }

    private void beginRequest() {
        togglePending = false;
        pendingButtonId = -1;
        requestedFrom = menu.state();
        pendingTicks = 0;
        localStatus = Component.empty();
        updateWidgets();
    }

    private void sendButton(int id) {
        if (!canRequest() || minecraft == null || minecraft.gameMode == null) return;
        beginRequest();
        pendingButtonId = id;
        togglePending = id == DigitalStorageScreenHandler.SET_UNSTACKABLE_ACCEPT_BUTTON_ID
                || id == DigitalStorageScreenHandler.SET_UNSTACKABLE_REJECT_BUTTON_ID;
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

    private UUID iconTarget() { return iconReturn == View.HOME ? menu.state().volumeId() : selectedVolume; }

    private void chooseIcon() {
        if (!canRequest()) return;
        iconReturn = view;
        if (!menu.getCarried().isEmpty()) setIcon(menu.getCarried());
        else open(View.ICONS);
    }

    private void setIcon(ItemStack stack) {
        if (!canRequest() || stack.isEmpty() || iconTarget() == null) return;
        beginRequest();
        requestSender.send(new DigitalStorageScreenProtocol.VolumeIcon(menu.containerId, iconTarget(),
                BuiltInRegistries.ITEM.getKey(stack.getItem())));
    }

    private static ItemStack iconStack(ResourceLocation id) {
        return BuiltInRegistries.ITEM.getOptional(id).filter(item -> item != Items.AIR)
                .map(ItemStack::new).orElseGet(() -> new ItemStack(Items.CHEST));
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
            case ICONS -> setIcon(new ItemStack(Items.CHEST));
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
            widget.active = !responseTimedOut;
        }
        upgrade.active &= state.accessorConfigurable() && state.hasNextTier();
        upgrade.setMessage(tr(state.hasNextTier() ? "gui.upgrade" : "maximum_button"));
        clearBinding.active &= state.accessorBound() && state.accessorConfigurable();
        network.active = true;
        boolean accept = state.unstackableItemsAllowedByServer() && state.acceptsUnstackableItems();
        toggle.setMessage(tr(accept ? "gui.on" : "gui.off"));
        toggle.active = canRequest();
        toggle.active &= state.unstackableItemsConfigurable() && state.unstackableItemsAllowedByServer()
                && !diagnostic.migrationActive();
        String policyTip = !state.unstackableItemsAllowedByServer() ? "server_disabled"
                : diagnostic.migrationActive() ? "migration_active"
                : !state.unstackableItemsConfigurable() ? "not_owner" : accept ? "accept" : "reject";
        boolean policyUnavailable = !state.unstackableItemsAllowedByServer()
                || diagnostic.migrationActive() || !state.unstackableItemsConfigurable();
        toggle.setTooltip(policyUnavailable ? Tooltip.create(tr("unstackables.tooltip." + policyTip)) : null);
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
        confirm.visible = !home && view != View.ICONS;
        confirm.active = canRequest();
        confirm.setTooltip(null);
        secondary.visible = view == View.NETWORK || view == View.VOLUME;
        secondary.active = canRequest();
        secondary.setTooltip(null);
        var selected = selectedChoice();
        boolean boundIcon = view == View.HOME && state.accessorBound();
        volumeGlyph.visible = boundIcon || view == View.VOLUME && selected != null;
        volumeGlyph.setX(leftPos + MARGIN + (boundIcon ? 0 : 7));
        volumeGlyph.setY(topPos + (boundIcon ? 32 - homeScroll : 61));
        if (boundIcon) volumeGlyph.visible &= 32 - homeScroll >= 30;
        volumeGlyph.active = canRequest() && state.accessorConfigurable()
                && (boundIcon ? state.unstackableItemsConfigurable() : selected != null);
        ((ItemIconButton) volumeGlyph).item = iconStack(boundIcon ? state.volumeIcon()
                : selected == null ? StorageVolume.DEFAULT_ICON : selected.icon());
        volumeGlyph.setTooltip(volumeGlyph.active ? Tooltip.create(tr("gui.choose_icon")) : null);
        for (Button choice : iconChoices) {
            choice.visible = view == View.ICONS;
            choice.active = canRequest() && !((ItemIconButton) choice).item.isEmpty();
        }
        for (Button choice : presetChoices) {
            choice.visible = view == View.ICONS;
            choice.active = canRequest();
        }
        switch (view) {
            case ICONS -> confirm.setMessage(tr("gui.reset_icon"));
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
            case CLEAR -> {
                confirm.setMessage(tr("gui.confirm_clear"));
                confirm.active &= state.accessorBound() && state.accessorConfigurable();
            }
            case VOLUME -> {
                confirm.setMessage(tr("rename_volume"));
                secondary.setMessage(tr("delete_volume"));
                confirm.active &= state.accessorConfigurable() && !state.accessorBound() && selected != null;
                secondary.active = confirm.active && selected.usedVariants() == 0;
                if (selected != null && selected.usedVariants() > 0)
                    secondary.setTooltip(Tooltip.create(tr("delete_volume.tooltip.non_empty")));
            }
            case UPGRADE -> {
                confirm.setMessage(tr(state.hasNextTier() ? "gui.confirm_upgrade" : "maximum_button"));
                confirm.active &= state.accessorBound() && state.accessorConfigurable()
                        && state.hasNextTier() && state.canAfford();
                if (state.hasNextTier() && (!state.accessorConfigurable() || !state.canAfford()))
                    confirm.setTooltip(Tooltip.create(tr(!state.accessorConfigurable() ? "gui.read_only" : "unaffordable")));
            }
            case NETWORK -> {
                // Refreshing the report must not restyle an unrelated migration control.
                confirm.active = !responseTimedOut;
                confirm.setMessage(tr(diagnostic.migrationActive() ? "migration.cancel" : "gui.optimize"));
                confirm.active &= state.accessorBound() && state.accessorConfigurable()
                        && (diagnostic.migrationActive() || diagnostic.available()
                        && !diagnostic.hasDuplicateTargetEndpoints() && diagnostic.recommendedVariants() > 0);
                secondary.setMessage(tr("network.refresh"));
                secondary.active &= state.accessorBound() && !diagnostic.migrationActive();
            }
            default -> { }
        }
        if (pending() && confirm.visible && !(view == View.NETWORK
                && pendingButtonId == DigitalStorageScreenHandler.NETWORK_ANALYSIS_BUTTON_ID))
            confirm.setMessage(tr("gui.pending"));
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
        if (button == 1 && volumeGlyph.visible && volumeGlyph.active && volumeGlyph.isMouseOver(mouseX, mouseY)) {
            iconReturn = view;
            setIcon(new ItemStack(Items.CHEST));
            return true;
        }
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
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF555555);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xFFC6C6C6);
        graphics.fill(leftPos + 2, topPos + 2, leftPos + imageWidth - 2, topPos + 4, 0xFFFFFFFF);
        graphics.fill(leftPos + 2, topPos + 4, leftPos + 4, topPos + imageHeight - 2, 0xFFFFFFFF);
        graphics.fill(leftPos + 4, topPos + imageHeight - 4, leftPos + imageWidth - 2, topPos + imageHeight - 2, 0xFF8B8B8B);
        graphics.fill(leftPos + imageWidth - 4, topPos + 4, leftPos + imageWidth - 2, topPos + imageHeight - 4, 0xFF8B8B8B);
        graphics.pose().pushPose();
        graphics.pose().translate(leftPos, topPos, 0);
        // Container labels run after widgets. Draw surfaces first so they cannot cover the EditBox.
        drawContent(graphics);
        graphics.pose().popPose();
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) { }

    private void drawContent(GuiGraphics graphics) {
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
        panel(graphics, MARGIN - 3, 31, imageWidth - 18, 77);
        text(graphics, Component.literal(state.volumeName()), MARGIN + 23, 35, imageWidth - 147, TEXT);
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
            graphics.renderItem(iconStack(choice.icon()), MARGIN, y + 2);
            text(graphics, Component.literal(choice.name()), MARGIN + 22, y, w - 22, TEXT);
            text(graphics, tr("gui.volume_usage", tierName(choice.tierId()), choice.usedVariants(), choice.variantCapacity()),
                    MARGIN + 22, y + 13, w - 22, MUTED);
            progress(graphics, MARGIN, y + 26, w, 4,
                    choice.variantCapacity() <= 0 ? 0 : (double) choice.usedVariants() / choice.variantCapacity());
            graphics.hLine(MARGIN, imageWidth - 18, y + 36, LINE);
        }
    }

    private void drawDetail(GuiGraphics graphics) {
        String heading = switch (view) {
            case UPGRADE -> "gui.upgrade_title";
            case NETWORK -> "gui.network_title";
            case CREATE -> "gui.create";
            case RENAME -> "rename_volume";
            case DELETE -> "gui.delete_title";
            case CLEAR -> "gui.clear_title";
            case ICONS -> "gui.choose_icon";
            default -> "gui.manage_volume";
        };
        text(graphics, tr(heading), MARGIN, 35, imageWidth - 24, TEXT);
        if (view == View.UPGRADE) {
            drawUpgrade(graphics);
            return;
        }
        detailLines = 0;
        if (view == View.ICONS) {
            text(graphics, tr("gui.icon_inventory"), MARGIN, 51, imageWidth - 24, MUTED);
            text(graphics, tr("gui.icon_presets"), imageWidth - MARGIN - 120, imageHeight - 58, 120, MUTED);
            return;
        }
        if (view == View.NETWORK) {
            drawNetwork(graphics);
            return;
        }
        if (view == View.CREATE || view == View.RENAME) {
            text(graphics, view == View.RENAME && selectedChoice() != null
                    ? tr("gui.target", selectedChoice().name()) : tr("gui.name"), MARGIN, 62, imageWidth - 24, TEXT);
            return;
        }
        drawManagement(graphics);
    }

    private void drawNetwork(GuiGraphics graphics) {
        var d = menu.state().networkDiagnostic();
        int color = !d.available() ? MUTED : d.hasDuplicateTargetEndpoints() ? RED
                : d.failingScanners() > 0 ? YELLOW : GREEN;
        String health = !d.available() ? "gui.network_disconnected"
                : d.hasDuplicateTargetEndpoints() ? "gui.network_duplicates"
                : d.failingScanners() > 0 ? "gui.network_scanner_fault" : "gui.network_healthy";
        panel(graphics, MARGIN, 52, imageWidth - 24, 43);
        graphics.renderItem(new ItemStack(Items.COMPARATOR), MARGIN + 6, 60);
        text(graphics, d.failingScanners() > 0 && !d.hasDuplicateTargetEndpoints() && d.available()
                ? tr("gui.network_stalled", d.failingScanners()) : tr(health),
                MARGIN + 28, 60, imageWidth - 65, color);
        String nextStep = !d.available() ? "gui.network_connect"
                : d.hasDuplicateTargetEndpoints() ? "gui.network_remove_duplicate"
                : d.failingScanners() > 0 ? "gui.network_check_hoppers" : "gui.network_no_action";
        text(graphics, tr(nextStep), MARGIN + 28, 78, imageWidth - 65, MUTED);
        int columnWidth = (imageWidth - 32) / 2;
        metric(graphics, MARGIN, 103, columnWidth, "gui.metric_inventories", d.available() ? "" + d.physicalInventories() : "—", TEXT);
        metric(graphics, MARGIN + columnWidth + 8, 103, columnWidth, "gui.metric_slots",
                d.available() ? d.nonEmptyViews() + "/" + d.totalViews() : "—", TEXT);
        int y = 122;
        panel(graphics, MARGIN, y, imageWidth - 24, 53);
        if (d.migrationActive()) {
            graphics.renderItem(new ItemStack(Items.HOPPER), MARGIN + 6, y + 8);
            text(graphics, tr("migration.progress_short", d.completedCandidates(), d.totalCandidates()),
                    MARGIN + 28, y + 5, imageWidth - 65, GREEN);
            progress(graphics, MARGIN + 28, y + 21, imageWidth - 65, 5,
                    d.totalCandidates() <= 0 ? 0 : (double) d.completedCandidates() / d.totalCandidates(), GREEN);
            text(graphics, tr("gui.moved_items", d.movedItems()), MARGIN + 28, y + 36, imageWidth - 65, MUTED);
        } else {
            boolean hasCandidate = d.available() && d.recommendedVariants() > 0;
            text(graphics, hasCandidate ? tr("gui.migration_benefit", d.recommendedVariants(), d.estimatedFreedViews())
                    : tr("gui.no_opportunity"), MARGIN + 28, y + 5, imageWidth - 65, hasCandidate ? GREEN : MUTED);
            var candidateId = hasCandidate ? ResourceLocation.tryParse(d.topCandidateId()) : null;
            var candidateItem = candidateId == null ? Items.HOPPER : BuiltInRegistries.ITEM.getOptional(candidateId).orElse(Items.HOPPER);
            graphics.renderItem(new ItemStack(candidateItem), MARGIN + 6, y + 8);
            Component candidate = candidateId == null ? tr("gui.no_opportunity")
                    : BuiltInRegistries.ITEM.getOptional(candidateId).<Component>map(item -> item.getDescription())
                            .orElse(Component.literal(d.topCandidateId()));
            if (hasCandidate) text(graphics, candidate, MARGIN + 28, y + 21, imageWidth - 65, TEXT);
            text(graphics, tr("gui.migration_target", menu.state().volumeName()), MARGIN + 28, y + 36, imageWidth - 65, MUTED);
        }
    }

    private void metric(GuiGraphics graphics, int x, int y, int w, String key, String value, int color) {
        int valueWidth = Math.min(w / 2, font.width(value));
        text(graphics, tr(key), x + 5, y, Math.max(1, w - valueWidth - 15), MUTED);
        text(graphics, Component.literal(value), x + w - valueWidth - 5, y, valueWidth, color);
    }

    private void drawManagement(GuiGraphics graphics) {
        var state = menu.state();
        var choice = selectedChoice();
        boolean selected = view == View.VOLUME || view == View.DELETE;
        if (selected && choice == null) {
            notice(graphics, 59, "gui.target_missing", RED);
            return;
        }
        String name = selected ? choice.name() : state.volumeName();
        ResourceLocation tier = selected ? choice.tierId() : state.tierId();
        int used = selected ? choice.usedVariants() : state.usedVariants();
        int capacity = selected ? choice.variantCapacity() : state.variantCapacity();
        panel(graphics, MARGIN, 53, imageWidth - 24, 58);
        if (view != View.VOLUME) graphics.renderItem(iconStack(selected ? choice.icon() : state.volumeIcon()), MARGIN + 7, 62);
        text(graphics, Component.literal(name), MARGIN + 31, 62, imageWidth - 62, TEXT);
        text(graphics, tr("gui.volume_usage", tierName(tier), used, capacity), MARGIN + 31, 78, imageWidth - 62, MUTED);
        progress(graphics, MARGIN + 7, 98, imageWidth - 38, 5, capacity <= 0 ? 0 : (double) used / capacity);
        switch (view) {
            case CLEAR -> notice(graphics, 117, "gui.clear_effect", YELLOW);
            case DELETE -> notice(graphics, 117, "gui.delete_effect", RED);
            case VOLUME -> { }
            default -> { }
        }
    }

    private void notice(GuiGraphics graphics, int y, String key, int color) {
        panel(graphics, MARGIN, y, imageWidth - 24, 23);
        graphics.fill(MARGIN, y, MARGIN + 2, y + 23, color);
        text(graphics, tr(key), MARGIN + 8, y + 7, imageWidth - 42, color);
    }

    private void panel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.hLine(x, x + w - 1, y + h - 1, 0xFFAAAAAA);
    }

    private final class SwitchButton extends Button {
        SwitchButton(int x, int y, OnPress action) { super(x, y, 64, 20, Component.empty(), action, DEFAULT_NARRATION); }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            boolean selected = menu.state().acceptsUnstackableItems() && menu.state().unstackableItemsAllowedByServer();
            if (pending() && togglePending) selected = !selected;
            int x = getX() + 32, y = getY() + 4;
            graphics.drawString(font, tr(selected ? "gui.on" : "gui.off"), getX() + 2, getY() + 6, active ? TEXT : MUTED, false);
            graphics.fill(x, y, x + 30, y + 12, selected ? 0xFF579E40 : 0xFF808080);
            graphics.renderOutline(x, y, 30, 12, isHoveredOrFocused() ? TEXT : LINE);
            int knob = x + (selected ? 19 : 2);
            graphics.fill(knob, y + 2, knob + 9, y + 10, 0xFFFFFFFF);
        }
    }

    private static final class ItemIconButton extends Button {
        private ItemStack item;
        private final boolean cell;
        ItemIconButton(int x, int y, ItemStack item, OnPress action) {
            this(x, y, item, action, false);
        }
        ItemIconButton(int x, int y, ItemStack item, OnPress action, boolean cell) {
            super(x, y, 20, 20, Component.translatable("screen.digitalstorage.gui.choose_icon"), action, DEFAULT_NARRATION);
            this.item = item;
            this.cell = cell;
            if (!item.isEmpty()) setTooltip(Tooltip.create(item.getHoverName()));
        }
        @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            if (cell) {
                graphics.fill(getX(), getY(), getX() + 20, getY() + 20, 0xFFB0B0B0);
                graphics.renderOutline(getX(), getY(), 20, 20, LINE);
            }
            if (active && isHoveredOrFocused()) graphics.renderOutline(getX(), getY(), 20, 20, TEXT);
            graphics.renderItem(item, getX() + 2, getY() + 2);
        }
    }

    private void drawUpgrade(GuiGraphics graphics) {
        var state = menu.state();
        if (imageHeight >= 200 || !state.hasNextTier())
            text(graphics, tr("gui.target", state.volumeName()), MARGIN, 53, imageWidth - 24, MUTED);
        else hints.add(new Hint(tr("gui.target", state.volumeName()), MARGIN, 35, imageWidth - 24, 12));
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
        int cardY = imageHeight < 200 ? 60 : imageHeight < 230 ? 68 : 72;
        tierPanel(graphics, MARGIN, cardY, cardWidth, tr("gui.current_tier"), state.tierId(), state.variantCapacity(), MUTED);
        tierPanel(graphics, imageWidth - MARGIN - cardWidth, cardY, cardWidth,
                tr("gui.next_tier"), state.nextTierId(), state.nextVariantCapacity(), GREEN);
        text(graphics, Component.literal("→"), imageWidth / 2 - 4, cardY + 19, 12, YELLOW);
        if (imageHeight >= 200) text(graphics, tr("cost"), MARGIN, upgradeCostTop() - 12, imageWidth - 24, TEXT);
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
        int costTop = upgradeCostTop();
        panel(graphics, MARGIN, costTop, imageWidth - 24, upgradeCostViewport());
        graphics.enableScissor(leftPos + MARGIN, topPos + costTop, leftPos + imageWidth - 14,
                topPos + costTop + upgradeCostViewport());
        for (int i = detailScroll; i < Math.min(costs.size(), detailScroll + upgradeCostViewport() / 20); i++) {
            int y = costTop + 2 + (i - detailScroll) * 20;
            graphics.renderItem(icons.get(i), MARGIN + 5, y);
            text(graphics, costs.get(i), MARGIN + 27, y + 4, imageWidth - 56, TEXT);
        }
        graphics.disableScissor();
        if (imageHeight >= 230) text(graphics, tr(!state.accessorConfigurable() ? "gui.read_only"
                : state.canAfford() ? "affordable" : "unaffordable"), MARGIN, imageHeight - 59,
                imageWidth - 24, !state.accessorConfigurable() ? YELLOW : state.canAfford() ? GREEN : RED);
    }

    private void tierPanel(GuiGraphics graphics, int x, int y, int w, Component label,
                           ResourceLocation tier, int capacity, int color) {
        boolean compact = imageHeight < 200;
        panel(graphics, x, y, w, compact ? 35 : 47);
        if (!compact) text(graphics, label, x + 7, y + 5, w - 14, MUTED);
        text(graphics, tierName(tier), x + 7, y + (compact ? 6 : 18), w - 14, color);
        text(graphics, tr("gui.type_capacity", capacity), x + 7, y + (compact ? 21 : 33), w - 14, TEXT);
        if (compact) hints.add(new Hint(label, x, y, w, 35));
    }

    private int upgradeCostTop() { return imageHeight < 200 ? 106 : imageHeight < 230 ? 128 : 140; }
    private int upgradeCostViewport() {
        int bottom = imageHeight - (imageHeight >= 230 ? 65 : 50);
        return Math.max(20, (bottom - upgradeCostTop()) / 20 * 20);
    }

    private int maxHomeScroll() { return Math.max(0, 231 - imageHeight); }
    private int maxDetailScroll() {
        if (view == View.UPGRADE) return menu.state().hasNextTier()
                ? Math.max(0, detailLines - Math.max(1, upgradeCostViewport() / 20)) : 0;
        return 0;
    }
    private int scrollTop() { return view == View.HOME ? menu.state().accessorBound() ? 30 : 62
            : upgradeCostTop(); }
    private int scrollHeight() { return view == View.HOME ? imageHeight - 24 - scrollTop()
            : upgradeCostViewport(); }
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
        progress(graphics, x, y, w, h, fraction, fraction >= 0.9 ? YELLOW : GREEN);
    }

    private void progress(GuiGraphics graphics, int x, int y, int w, int h, double fraction, int color) {
        graphics.fill(x, y, x + w, y + h, 0xFF151515);
        graphics.renderOutline(x, y, w, h, LINE);
        int filled = (int) Math.round((w - 2) * Math.max(0, Math.min(1, fraction)));
        int fillColor = color == GREEN ? 0xFF579E40 : color == YELLOW ? 0xFFD2AA44 : color;
        if (filled > 0) graphics.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, fillColor);
    }

    private Component tierName(ResourceLocation id) {
        return dev.kehai.digitalstorage.tier.DigitalStorageTier.displayName(id);
    }
}
