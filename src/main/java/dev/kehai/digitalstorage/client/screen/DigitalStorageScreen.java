package dev.kehai.digitalstorage.client.screen;

import dev.kehai.digitalstorage.DigitalStorageContent;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenProtocol;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.tier.UpgradeIngredient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class DigitalStorageScreen extends AbstractContainerScreen<DigitalStorageScreenHandler> {
    private static final int SCREEN_WIDTH = 360;
    private static final int SCREEN_HEIGHT = 270;
    private static final int CONTENT_MARGIN = 12;
    private static final int HEADER_HEIGHT = 24;
    private static final int FORM_Y = 74;
    private static final int FORM_FIELD_WIDTH = 174;
    private static final int VOLUME_LIST_Y = 104;
    private static final int VOLUME_ROW_HEIGHT = 24;
    private static final int VOLUME_MANAGE_BUTTON_WIDTH = 52;
    private static final int VOLUME_BUTTON_GAP = 4;
    private static final int VOLUME_BIND_BUTTON_WIDTH = SCREEN_WIDTH
            - CONTENT_MARGIN * 2
            - VOLUME_MANAGE_BUTTON_WIDTH * 2
            - VOLUME_BUTTON_GAP * 2;
    private static final int PAGE_BUTTON_Y = 200;
    private static final int CARD_MARGIN = 8;
    private static final int CARD_GAP = 8;
    private static final int CARD_WIDTH = (SCREEN_WIDTH - CARD_MARGIN * 2 - CARD_GAP) / 2;
    private static final int TOP_CARD_Y = 30;
    private static final int TOP_CARD_HEIGHT = 104;
    private static final int BOTTOM_CARD_Y = 140;
    private static final int BOTTOM_CARD_HEIGHT = 82;
    private static final int STATUS_Y = 225;
    private static final int ACTION_BUTTON_Y = 242;
    private static final int VOLUMES_PER_PAGE = 4;
    private static final int PANEL_BACKGROUND = 0xFF171B22;
    private static final int PANEL_BORDER = 0xFF596575;
    private static final int CARD_BACKGROUND = 0xFF20252D;
    private static final int CARD_BORDER = 0xFF3B4653;
    private static final int PROGRESS_BACKGROUND = 0xFF11151A;
    private static final int PROGRESS_FILL = 0xFF4FAD62;
    private static final int PRIMARY_TEXT = 0xFFE8EDF2;
    private static final int SECONDARY_TEXT = 0xFFB4C0CC;
    private static final int SUCCESS_TEXT = 0xFF73D673;
    private static final int WARNING_TEXT = 0xFFFFC45C;
    private static final int ERROR_TEXT = 0xFFFF7777;
    private static final ItemStack ACCESSOR_ICON = new ItemStack(DigitalStorageContent.accessorItem());

    private Button upgradeButton;
    private Button clearBindingButton;
    private Button migrationButton;
    private Button networkAnalysisButton;
    private Button unstackableButton;
    private Button createVolumeButton;
    private Button previousPageButton;
    private Button nextPageButton;
    private EditBox volumeNameField;
    private final List<Button> volumeButtons = new ArrayList<>();
    private final List<Button> renameVolumeButtons = new ArrayList<>();
    private final List<Button> deleteVolumeButtons = new ArrayList<>();
    private int volumePage;
    private int lastKnownVolumeCount;
    private DigitalStorageScreenState creationRequestedFromState;
    private DigitalStorageScreenState managementRequestedFromState;
    private DigitalStorageScreenState unstackableRequestedFromState;
    private final DigitalStorageScreenProtocol.RequestSender requestSender;

    public DigitalStorageScreen(
            DigitalStorageScreenHandler handler,
            Inventory inventory,
            Component title,
            DigitalStorageScreenProtocol.RequestSender requestSender
    ) {
        super(handler, inventory, title);
        this.requestSender = java.util.Objects.requireNonNull(requestSender, "requestSender");
        imageWidth = SCREEN_WIDTH;
        imageHeight = SCREEN_HEIGHT;
        inventoryLabelY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        volumeButtons.clear();
        renameVolumeButtons.clear();
        deleteVolumeButtons.clear();
        volumeNameField = addRenderableWidget(new EditBox(
                font,
                leftPos + CONTENT_MARGIN,
                topPos + FORM_Y,
                FORM_FIELD_WIDTH,
                20,
                Component.translatable("screen.digitalstorage.volume_name")
        ));
        volumeNameField.setMaxLength(dev.kehai.digitalstorage.storage.StorageVolume.MAX_NAME_LENGTH);

        createVolumeButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.digitalstorage.create_volume"),
                button -> requestVolumeCreation()
        ).bounds(leftPos + 192, topPos + FORM_Y, 72, 20).build());

        for (int slot = 0; slot < VOLUMES_PER_PAGE; slot++) {
            int selectedSlot = slot;
            Button button = addRenderableWidget(Button.builder(
                    Component.empty(),
                    ignored -> bindVisibleVolume(selectedSlot)
            ).bounds(
                    leftPos + CONTENT_MARGIN,
                    topPos + VOLUME_LIST_Y + slot * VOLUME_ROW_HEIGHT,
                    VOLUME_BIND_BUTTON_WIDTH,
                    20
            ).build());
            volumeButtons.add(button);
            int manageButtonX = CONTENT_MARGIN + VOLUME_BIND_BUTTON_WIDTH + VOLUME_BUTTON_GAP;
            renameVolumeButtons.add(addRenderableWidget(Button.builder(
                    Component.translatable("screen.digitalstorage.rename_volume"),
                    ignored -> requestVolumeManagement(DigitalStorageScreenHandler.RENAME_VOLUME_ACTION, selectedSlot)
            ).bounds(
                    leftPos + manageButtonX,
                    topPos + VOLUME_LIST_Y + slot * VOLUME_ROW_HEIGHT,
                    VOLUME_MANAGE_BUTTON_WIDTH,
                    20
            ).build()));
            deleteVolumeButtons.add(addRenderableWidget(Button.builder(
                    Component.translatable("screen.digitalstorage.delete_volume"),
                    ignored -> requestVolumeManagement(DigitalStorageScreenHandler.DELETE_VOLUME_ACTION, selectedSlot)
            ).bounds(
                    leftPos + manageButtonX + VOLUME_MANAGE_BUTTON_WIDTH + VOLUME_BUTTON_GAP,
                    topPos + VOLUME_LIST_Y + slot * VOLUME_ROW_HEIGHT,
                    VOLUME_MANAGE_BUTTON_WIDTH,
                    20
            ).build()));
        }

        previousPageButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.digitalstorage.previous_page"),
                button -> {
                    volumePage = Math.max(0, volumePage - 1);
                    updateButton();
                }
        ).bounds(leftPos + CONTENT_MARGIN, topPos + PAGE_BUTTON_Y, 64, 20).build());
        nextPageButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.digitalstorage.next_page"),
                button -> {
                    volumePage = Math.min(pageCount() - 1, volumePage + 1);
                    updateButton();
                }
        ).bounds(leftPos + imageWidth - CONTENT_MARGIN - 64, topPos + PAGE_BUTTON_Y, 64, 20).build());

        int actionWidth = (imageWidth - 32) / 3;
        upgradeButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.digitalstorage.upgrade"),
                button -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, DigitalStorageScreenHandler.UPGRADE_BUTTON_ID);
                    }
                }
        ).bounds(leftPos + CONTENT_MARGIN, topPos + ACTION_BUTTON_Y, actionWidth, 20).build());
        migrationButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.digitalstorage.migration.run"),
                button -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(
                                menu.containerId,
                                DigitalStorageScreenHandler.MIGRATION_BUTTON_ID
                        );
                    }
                }
        ).bounds(leftPos + 16 + actionWidth, topPos + ACTION_BUTTON_Y, actionWidth, 20).build());
        clearBindingButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.digitalstorage.clear_binding"),
                button -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(
                                menu.containerId,
                                DigitalStorageScreenHandler.CLEAR_BINDING_BUTTON_ID
                        );
                    }
                }
        ).bounds(leftPos + 20 + actionWidth * 2, topPos + ACTION_BUTTON_Y, actionWidth, 20).build());
        networkAnalysisButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.digitalstorage.network.refresh"),
                button -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(
                                menu.containerId,
                                DigitalStorageScreenHandler.NETWORK_ANALYSIS_BUTTON_ID
                        );
                    }
                }
        ).bounds(leftPos + imageWidth - 72, topPos + 3, 60, 16).build());
        unstackableButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.digitalstorage.unstackables.reject"),
                button -> requestUnstackablePolicy()
        ).bounds(
                leftPos + CARD_MARGIN + CARD_WIDTH - 62,
                topPos + TOP_CARD_Y + 88,
                54,
                14
        ).build());

        lastKnownVolumeCount = menu.state().ownedVolumes().size();
        updateButton();
    }

    @Override
    protected void containerTick() {
        if (creationRequestedFromState != null && menu.state() != creationRequestedFromState) {
            creationRequestedFromState = null;
        }
        if (managementRequestedFromState != null && menu.state() != managementRequestedFromState) {
            managementRequestedFromState = null;
        }
        if (unstackableRequestedFromState != null && menu.state() != unstackableRequestedFromState) {
            unstackableRequestedFromState = null;
        }
        int currentVolumeCount = menu.state().ownedVolumes().size();
        if (currentVolumeCount > lastKnownVolumeCount && menu.state().statusSuccessful()) {
            volumeNameField.setValue("");
            volumePage = Math.max(0, pageCount() - 1);
        }
        lastKnownVolumeCount = currentVolumeCount;
        updateButton();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (volumeNameField != null
                && volumeNameField.visible
                && volumeNameField.isFocused()
                && minecraft != null
                && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        renderTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics context, float delta, int mouseX, int mouseY) {
        context.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_BACKGROUND);
        context.renderOutline(leftPos, topPos, imageWidth, imageHeight, PANEL_BORDER);
        context.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + HEADER_HEIGHT, 0xFF252C36);
        if (menu.state().accessorBound()) {
            int rightX = leftPos + CARD_MARGIN + CARD_WIDTH + CARD_GAP;
            drawCardBackground(context, leftPos + CARD_MARGIN, topPos + TOP_CARD_Y, CARD_WIDTH, TOP_CARD_HEIGHT);
            drawCardBackground(context, rightX, topPos + TOP_CARD_Y, CARD_WIDTH, TOP_CARD_HEIGHT);
            drawCardBackground(context, leftPos + CARD_MARGIN, topPos + BOTTOM_CARD_Y, CARD_WIDTH, BOTTOM_CARD_HEIGHT);
            drawCardBackground(context, rightX, topPos + BOTTOM_CARD_Y, CARD_WIDTH, BOTTOM_CARD_HEIGHT);
        } else {
            drawCardBackground(
                    context,
                    leftPos + CARD_MARGIN,
                    topPos + TOP_CARD_Y,
                    imageWidth - CARD_MARGIN * 2,
                    194
            );
        }
    }

    @Override
    protected void renderLabels(GuiGraphics context, int mouseX, int mouseY) {
        DigitalStorageScreenState state = menu.state();
        drawHeader(context, state);

        if (!state.accessorBound()) {
            drawUnboundState(context, state);
            return;
        }

        int rightX = CARD_MARGIN + CARD_WIDTH + CARD_GAP;
        drawStorageOverview(context, state, CARD_MARGIN, TOP_CARD_Y);
        drawNetworkSummary(context, state.networkDiagnostic(), rightX, TOP_CARD_Y);
        drawUpgradePanel(context, state, CARD_MARGIN, BOTTOM_CARD_Y);
        drawOptimizationPanel(context, state.networkDiagnostic(), rightX, BOTTOM_CARD_Y);
        drawStatus(context, state);
    }

    private void drawCardBackground(GuiGraphics context, int drawX, int drawY, int width, int height) {
        context.fill(drawX, drawY, drawX + width, drawY + height, CARD_BACKGROUND);
        context.renderOutline(drawX, drawY, width, height, CARD_BORDER);
    }

    private void drawHeader(GuiGraphics context, DigitalStorageScreenState state) {
        context.renderItem(ACCESSOR_ICON, 5, 4);
        context.drawString(font, title, 26, 8, PRIMARY_TEXT, false);
        if (!state.accessorBound()) {
            return;
        }
        DigitalStorageScreenState.NetworkDiagnostic diagnostic = state.networkDiagnostic();
        Component badge = diagnostic.available()
                ? Component.literal(diagnostic.grade() + " · " + diagnostic.healthScore() + " / 100")
                : Component.translatable("screen.digitalstorage.network.not_connected_short");
        int color = diagnostic.available() ? healthColor(diagnostic.healthScore()) : SECONDARY_TEXT;
        int badgeRight = imageWidth - 80;
        context.drawString(
                font,
                badge,
                badgeRight - font.width(badge),
                8,
                color,
                false
        );
    }

    private void drawUnboundState(GuiGraphics context, DigitalStorageScreenState state) {
        context.drawString(
                font,
                Component.translatable("screen.digitalstorage.unbound"),
                16,
                36,
                WARNING_TEXT,
                false
        );
        context.drawWordWrap(
                font,
                Component.translatable("screen.digitalstorage.choose_volume"),
                16,
                49,
                imageWidth - 32,
                SECONDARY_TEXT
        );
        context.drawString(
                font,
                Component.translatable("screen.digitalstorage.volume_name"),
                16,
                64,
                SECONDARY_TEXT,
                false
        );
        if (state.ownedVolumes().isEmpty()) {
            context.drawWordWrap(
                    font,
                    Component.translatable("screen.digitalstorage.no_volumes"),
                    16,
                    108,
                    imageWidth - 32,
                    WARNING_TEXT
            );
        }
        if (pageCount() > 1) {
            Component page = Component.translatable("screen.digitalstorage.page", volumePage + 1, pageCount());
            context.drawCenteredString(font, page, imageWidth / 2, 206, SECONDARY_TEXT);
        }
        drawStatus(context, state);
    }

    private void drawStorageOverview(
            GuiGraphics context,
            DigitalStorageScreenState state,
            int drawX,
            int drawY
    ) {
        drawSectionTitle(context, "screen.digitalstorage.section.storage", drawX, drawY);
        drawKeyValue(context, "screen.digitalstorage.volume", Component.literal(state.volumeName()),
                drawX + 8, drawY + 23, CARD_WIDTH - 16);
        drawKeyValue(context, "screen.digitalstorage.controller", Component.literal(state.controller()),
                drawX + 8, drawY + 35, CARD_WIDTH - 16);
        drawKeyValue(context, "screen.digitalstorage.current_tier", tierName(state.tierId()),
                drawX + 8, drawY + 47, CARD_WIDTH - 16);
        drawKeyValue(context, "screen.digitalstorage.variants", Component.literal(
                state.usedVariants() + " / " + state.variantCapacity()
        ), drawX + 8, drawY + 59, CARD_WIDTH - 16);
        drawProgressBar(
                context,
                drawX + 8,
                drawY + 70,
                CARD_WIDTH - 16,
                5,
                state.variantCapacity() <= 0 ? 0.0 : (double) state.usedVariants() / state.variantCapacity()
        );
        drawKeyValue(context, "screen.digitalstorage.total_items", Component.literal(state.totalItems()),
                drawX + 8, drawY + 78, CARD_WIDTH - 16);
        context.drawString(
                font,
                Component.translatable("screen.digitalstorage.unstackables"),
                drawX + 8,
                drawY + 91,
                SECONDARY_TEXT,
                false
        );
    }

    private void drawNetworkSummary(
            GuiGraphics context,
            DigitalStorageScreenState.NetworkDiagnostic diagnostic,
            int drawX,
            int drawY
    ) {
        drawSectionTitle(context, "screen.digitalstorage.section.network", drawX, drawY);
        if (!diagnostic.available()) {
            drawWrappedText(context, Component.translatable("screen.digitalstorage.network.unavailable"),
                    drawX + 8, drawY + 25, CARD_WIDTH - 16, SECONDARY_TEXT);
            return;
        }
        int lineY = drawY + 25;
        lineY = drawWrappedText(
                context,
                Component.translatable(diagnostic.healthScore() >= 90
                        ? "screen.digitalstorage.network.good"
                        : "screen.digitalstorage.network.degraded"),
                drawX + 8,
                lineY,
                CARD_WIDTH - 16,
                healthColor(diagnostic.healthScore())
        );
        lineY += 2;
        if (diagnostic.hasDuplicateTargetEndpoints()) {
            lineY = drawWrappedText(
                    context,
                    Component.translatable(
                            "screen.digitalstorage.network.duplicate_warning",
                            diagnostic.targetEndpointCount()
                    ),
                    drawX + 8,
                    lineY,
                    CARD_WIDTH - 16,
                    ERROR_TEXT
            );
            lineY += 2;
        }
        if (diagnostic.failingScanners() > 0) {
            lineY = drawWrappedText(
                    context,
                    Component.translatable(
                            "screen.digitalstorage.network.hopper_warning",
                            diagnostic.failingScanners()
                    ),
                    drawX + 8,
                    lineY,
                    CARD_WIDTH - 16,
                    WARNING_TEXT
            );
            lineY += 2;
        }
        if (!diagnostic.hasDuplicateTargetEndpoints() && diagnostic.recommendedVariants() > 0) {
            drawWrappedText(
                    context,
                    Component.translatable(
                            "screen.digitalstorage.network.migration_available",
                            diagnostic.recommendedVariants()
                    ),
                    drawX + 8,
                    lineY,
                    CARD_WIDTH - 16,
                    SUCCESS_TEXT
            );
        } else if (!diagnostic.hasDuplicateTargetEndpoints() && diagnostic.failingScanners() == 0) {
            drawWrappedText(
                    context,
                    Component.translatable("screen.digitalstorage.network.no_action"),
                    drawX + 8,
                    lineY,
                    CARD_WIDTH - 16,
                    SECONDARY_TEXT
            );
        }
    }

    private void drawUpgradePanel(
            GuiGraphics context,
            DigitalStorageScreenState state,
            int drawX,
            int drawY
    ) {
        drawSectionTitle(context, "screen.digitalstorage.section.upgrade", drawX, drawY);
        if (!state.hasNextTier()) {
            context.drawString(
                    font,
                    Component.translatable("screen.digitalstorage.maximum"),
                    drawX + 8,
                    drawY + 28,
                    SUCCESS_TEXT,
                    false
            );
            return;
        }
        drawFittedText(
                context,
                Component.translatable(
                        "screen.digitalstorage.upgrade.transition",
                        tierName(state.tierId()),
                        tierName(state.nextTierId())
                ),
                drawX + 8,
                drawY + 23,
                CARD_WIDTH - 16,
                PRIMARY_TEXT,
                0.85F
        );
        drawFittedText(
                context,
                Component.translatable(
                        "screen.digitalstorage.upgrade.capacity_transition",
                        state.variantCapacity(),
                        state.nextVariantCapacity()
                ),
                drawX + 8,
                drawY + 37,
                CARD_WIDTH - 16,
                SECONDARY_TEXT,
                0.85F
        );
        drawKeyValue(context, "screen.digitalstorage.cost", costText(state),
                drawX + 8, drawY + 51, CARD_WIDTH - 16);
        context.drawString(
                font,
                Component.translatable(state.canAfford()
                        ? "screen.digitalstorage.affordable"
                        : "screen.digitalstorage.unaffordable"),
                drawX + 8,
                drawY + 66,
                state.canAfford() ? SUCCESS_TEXT : ERROR_TEXT,
                false
        );
    }

    private void drawOptimizationPanel(
            GuiGraphics context,
            DigitalStorageScreenState.NetworkDiagnostic diagnostic,
            int drawX,
            int drawY
    ) {
        drawSectionTitle(context, "screen.digitalstorage.section.optimization", drawX, drawY);
        if (!diagnostic.available()) {
            drawWrappedText(context, Component.translatable("screen.digitalstorage.network.not_connected"),
                    drawX + 8, drawY + 28, CARD_WIDTH - 16, SECONDARY_TEXT);
            return;
        }
        if (diagnostic.migrationActive()) {
            drawFittedText(context, Component.translatable("screen.digitalstorage.migration.running"),
                    drawX + 8, drawY + 23, CARD_WIDTH - 16, SUCCESS_TEXT, 0.85F);
            double progress = diagnostic.totalCandidates() <= 0 ? 0.0
                    : (double) diagnostic.completedCandidates() / diagnostic.totalCandidates();
            drawProgressBar(context, drawX + 8, drawY + 37, CARD_WIDTH - 16, 7, progress);
            drawFittedText(
                    context,
                    Component.translatable(
                            "screen.digitalstorage.migration.progress_short",
                            diagnostic.completedCandidates(),
                            diagnostic.totalCandidates()
                    ),
                    drawX + 8,
                    drawY + 49,
                    CARD_WIDTH - 16,
                    SECONDARY_TEXT,
                    0.85F
            );
            drawFittedText(
                    context,
                    Component.translatable("screen.digitalstorage.migration.moved_short", diagnostic.movedItems()),
                    drawX + 8,
                    drawY + 63,
                    CARD_WIDTH - 16,
                    PRIMARY_TEXT,
                    0.85F
            );
            return;
        }
        if (diagnostic.hasDuplicateTargetEndpoints()) {
            int lineY = drawWrappedText(
                    context,
                    Component.translatable("screen.digitalstorage.network.migration_blocked"),
                    drawX + 8,
                    drawY + 27,
                    CARD_WIDTH - 16,
                    ERROR_TEXT
            );
            drawWrappedText(
                    context,
                    Component.translatable("screen.digitalstorage.network.keep_one_endpoint"),
                    drawX + 8,
                    lineY + 2,
                    CARD_WIDTH - 16,
                    SECONDARY_TEXT
            );
            return;
        }
        if (diagnostic.recommendedVariants() > 0) {
            int lineY = drawWrappedText(
                    context,
                    Component.translatable(
                            "screen.digitalstorage.network.migration_available",
                            diagnostic.recommendedVariants()
                    ),
                    drawX + 8,
                    drawY + 24,
                    CARD_WIDTH - 16,
                    SUCCESS_TEXT
            );
            drawWrappedText(
                    context,
                    Component.translatable(
                            "screen.digitalstorage.network.freed_views_short",
                            diagnostic.estimatedFreedViews()
                    ),
                    drawX + 8,
                    lineY + 2,
                    CARD_WIDTH - 16,
                    SECONDARY_TEXT
            );
            return;
        }
        drawWrappedText(context, Component.translatable("screen.digitalstorage.network.no_optimization"),
                drawX + 8, drawY + 30, CARD_WIDTH - 16, SUCCESS_TEXT);
    }

    private void drawSectionTitle(GuiGraphics context, String key, int drawX, int drawY) {
        context.drawString(
                font,
                Component.translatable(key),
                drawX + 8,
                drawY + 7,
                PRIMARY_TEXT,
                false
        );
        context.hLine(drawX + 8, drawX + CARD_WIDTH - 9, drawY + 19, CARD_BORDER);
    }

    private void drawKeyValue(
            GuiGraphics context,
            String labelKey,
            Component value,
            int drawX,
            int drawY,
            int width
    ) {
        Component label = Component.translatable(labelKey);
        context.drawString(font, label, drawX, drawY, SECONDARY_TEXT, false);
        int maxValueWidth = Math.max(20, width - font.width(label) - 8);
        drawFittedTextRightAligned(
                context,
                value,
                drawX + width - maxValueWidth,
                drawY,
                maxValueWidth,
                PRIMARY_TEXT,
                0.8F
        );
    }

    private void drawFittedText(
            GuiGraphics context,
            Component text,
            int drawX,
            int drawY,
            int width,
            int color,
            float minScale
    ) {
        drawFittedText(context, text, drawX, drawY, width, color, minScale, false);
    }

    private void drawFittedTextRightAligned(
            GuiGraphics context,
            Component text,
            int drawX,
            int drawY,
            int width,
            int color,
            float minScale
    ) {
        drawFittedText(context, text, drawX, drawY, width, color, minScale, true);
    }

    private void drawFittedText(
            GuiGraphics context,
            Component text,
            int drawX,
            int drawY,
            int width,
            int color,
            float minScale,
            boolean rightAligned
    ) {
        int textWidth = font.width(text);
        if (textWidth <= width) {
            int textX = rightAligned ? drawX + width - textWidth : drawX;
            context.drawString(font, text, textX, drawY, color, false);
            return;
        }

        float scale = Math.max(minScale, width / (float) textWidth);
        Component displayText = text;
        int displayWidth = textWidth;
        if (displayWidth * scale > width) {
            int unscaledWidth = Math.max(1, (int) Math.floor(width / scale));
            displayText = Component.literal(font.plainSubstrByWidth(text.getString(), unscaledWidth));
            displayWidth = font.width(displayText);
        }

        context.pose().pushPose();
        context.pose().scale(scale, scale, 1.0F);
        int scaledY = Math.round(drawY / scale);
        int scaledX = rightAligned
                ? Math.round((drawX + width) / scale) - displayWidth
                : Math.round(drawX / scale);
        context.drawString(font, displayText, scaledX, scaledY, color, false);
        context.pose().popPose();
    }

    private int drawWrappedText(
            GuiGraphics context,
            Component text,
            int drawX,
            int drawY,
            int width,
            int color
    ) {
        List<FormattedCharSequence> lines = font.split(text, width);
        int lineHeight = font.lineHeight + 1;
        for (int index = 0; index < lines.size(); index++) {
            context.drawString(font, lines.get(index), drawX, drawY + index * lineHeight, color, false);
        }
        return drawY + lines.size() * lineHeight;
    }

    private void drawProgressBar(
            GuiGraphics context,
            int drawX,
            int drawY,
            int width,
            int height,
            double progress
    ) {
        context.fill(drawX, drawY, drawX + width, drawY + height, PROGRESS_BACKGROUND);
        context.renderOutline(drawX, drawY, width, height, CARD_BORDER);
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        int fillWidth = (int) Math.round((width - 2) * clamped);
        if (fillWidth > 0) {
            context.fill(drawX + 1, drawY + 1, drawX + 1 + fillWidth, drawY + height - 1, PROGRESS_FILL);
        }
    }

    private int healthColor(int score) {
        return score >= 75 ? SUCCESS_TEXT : score >= 40 ? WARNING_TEXT : ERROR_TEXT;
    }

    private Component tierName(net.minecraft.resources.ResourceLocation id) {
        String translationKey = "tier.digitalstorage." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        return Component.translatableWithFallback(translationKey, id.toString());
    }

    private Component costText(DigitalStorageScreenState state) {
        MutableComponent result = Component.empty();
        boolean hasPrevious = false;
        for (UpgradeIngredient ingredient : state.upgradeCost()) {
            if (hasPrevious) {
                result.append(Component.literal(", "));
            }
            Component name = ingredient.kind() == UpgradeIngredient.Kind.ITEM
                    ? BuiltInRegistries.ITEM.getOptional(ingredient.id())
                            .<Component>map(item -> item.getDescription())
                            .orElse(Component.literal(ingredient.id().toString()))
                    : Component.literal("#" + ingredient.id());
            result.append(Component.translatable("screen.digitalstorage.cost_entry", name, ingredient.count()));
            hasPrevious = true;
        }
        if (state.experienceLevels() > 0) {
            if (hasPrevious) {
                result.append(Component.literal(", "));
            }
            result.append(Component.translatable("screen.digitalstorage.cost_xp", state.experienceLevels()));
            hasPrevious = true;
        }
        return hasPrevious ? result : Component.translatable("screen.digitalstorage.cost_free");
    }

    private void updateButton() {
        if (upgradeButton == null || migrationButton == null || networkAnalysisButton == null
                || unstackableButton == null
                || clearBindingButton == null || volumeNameField == null) {
            return;
        }
        DigitalStorageScreenState state = menu.state();
        boolean canConfigureUnbound = !state.accessorBound() && state.accessorConfigurable();
        volumeNameField.visible = canConfigureUnbound;
        volumeNameField.setEditable(canConfigureUnbound);
        createVolumeButton.visible = canConfigureUnbound;
        createVolumeButton.active = canConfigureUnbound
                && creationRequestedFromState == null
                && !volumeNameField.getValue().isBlank();

        int pageCount = pageCount();
        volumePage = Math.max(0, Math.min(volumePage, pageCount - 1));
        previousPageButton.visible = canConfigureUnbound && pageCount > 1;
        previousPageButton.active = previousPageButton.visible && volumePage > 0;
        nextPageButton.visible = canConfigureUnbound && pageCount > 1;
        nextPageButton.active = nextPageButton.visible && volumePage + 1 < pageCount;

        upgradeButton.visible = state.accessorBound();
        upgradeButton.active = state.accessorBound() && state.accessorConfigurable() && state.hasNextTier();
        upgradeButton.setMessage(Component.translatable(
                state.hasNextTier() ? "screen.digitalstorage.upgrade" : "screen.digitalstorage.maximum_button"
        ));
        migrationButton.visible = state.accessorBound();
        migrationButton.active = state.accessorBound()
                && state.accessorConfigurable()
                && (state.networkDiagnostic().migrationActive()
                || (state.networkDiagnostic().available()
                && !state.networkDiagnostic().hasDuplicateTargetEndpoints()
                && state.networkDiagnostic().recommendedVariants() > 0));
        migrationButton.setMessage(Component.translatable(state.networkDiagnostic().migrationActive()
                ? "screen.digitalstorage.migration.cancel"
                : "screen.digitalstorage.migration.run"));
        migrationButton.setTooltip(state.networkDiagnostic().migrationActive()
                ? null
                : Tooltip.create(Component.translatable("screen.digitalstorage.network.migration_hint")));
        networkAnalysisButton.visible = state.accessorBound();
        networkAnalysisButton.active = state.accessorBound() && !state.networkDiagnostic().migrationActive();
        unstackableButton.visible = state.accessorBound();
        unstackableButton.active = state.accessorBound()
                && state.unstackableItemsConfigurable()
                && state.unstackableItemsAllowedByServer()
                && !state.networkDiagnostic().migrationActive()
                && unstackableRequestedFromState == null;
        boolean effectiveAccept = state.unstackableItemsAllowedByServer() && state.acceptsUnstackableItems();
        unstackableButton.setMessage(Component.translatable(effectiveAccept
                ? "screen.digitalstorage.unstackables.accept"
                : "screen.digitalstorage.unstackables.reject"));
        String unstackableTooltipKey;
        if (!state.unstackableItemsAllowedByServer()) {
            unstackableTooltipKey = "screen.digitalstorage.unstackables.tooltip.server_disabled";
        } else if (state.networkDiagnostic().migrationActive()) {
            unstackableTooltipKey = "screen.digitalstorage.unstackables.tooltip.migration_active";
        } else if (!state.unstackableItemsConfigurable()) {
            unstackableTooltipKey = "screen.digitalstorage.unstackables.tooltip.not_owner";
        } else {
            unstackableTooltipKey = effectiveAccept
                    ? "screen.digitalstorage.unstackables.tooltip.accept"
                    : "screen.digitalstorage.unstackables.tooltip.reject";
        }
        unstackableButton.setTooltip(Tooltip.create(Component.translatable(unstackableTooltipKey)));
        clearBindingButton.visible = state.accessorBound();
        clearBindingButton.active = clearBindingButton.visible && state.accessorConfigurable();
        List<DigitalStorageScreenState.VolumeChoice> choices = state.ownedVolumes();
        for (int slot = 0; slot < volumeButtons.size(); slot++) {
            Button button = volumeButtons.get(slot);
            Button renameButton = renameVolumeButtons.get(slot);
            Button deleteButton = deleteVolumeButtons.get(slot);
            int volumeIndex = volumePage * VOLUMES_PER_PAGE + slot;
            boolean hasChoice = volumeIndex < choices.size();
            button.visible = canConfigureUnbound && hasChoice;
            button.active = button.visible && managementRequestedFromState == null;
            renameButton.visible = button.visible;
            deleteButton.visible = button.visible;
            renameButton.active = false;
            deleteButton.active = false;
            deleteButton.setTooltip(null);
            if (hasChoice) {
                DigitalStorageScreenState.VolumeChoice choice = choices.get(volumeIndex);
                Component usage = Component.translatable(
                        choice.usedVariants() == 0
                                ? "screen.digitalstorage.volume_empty"
                                : "screen.digitalstorage.volume_in_use",
                        choice.usedVariants(),
                        choice.variantCapacity()
                );
                button.setMessage(Component.translatable(
                        "screen.digitalstorage.bind_volume",
                        choice.name(),
                        tierName(choice.tierId()),
                        usage
                ));
                renameButton.active = managementRequestedFromState == null
                        && !volumeNameField.getValue().isBlank();
                deleteButton.active = managementRequestedFromState == null && choice.usedVariants() == 0;
                deleteButton.setTooltip(Tooltip.create(Component.translatable(choice.usedVariants() == 0
                        ? "screen.digitalstorage.delete_volume.tooltip.empty"
                        : "screen.digitalstorage.delete_volume.tooltip.non_empty")));
            }
        }
    }

    private void requestVolumeCreation() {
        if (!createVolumeButton.active) {
            return;
        }
        creationRequestedFromState = menu.state();
        createVolumeButton.active = false;
        requestSender.send(new DigitalStorageScreenProtocol.CreateVolume(
                menu.containerId, volumeNameField.getValue().strip()));
    }

    private void requestUnstackablePolicy() {
        if (!unstackableButton.active || minecraft == null || minecraft.gameMode == null) {
            return;
        }
        DigitalStorageScreenState current = menu.state();
        int buttonId = current.acceptsUnstackableItems()
                ? DigitalStorageScreenHandler.SET_UNSTACKABLE_REJECT_BUTTON_ID
                : DigitalStorageScreenHandler.SET_UNSTACKABLE_ACCEPT_BUTTON_ID;
        unstackableRequestedFromState = current;
        unstackableButton.active = false;
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
    }

    private void bindVisibleVolume(int slot) {
        int volumeIndex = volumePage * VOLUMES_PER_PAGE + slot;
        if (minecraft != null
                && minecraft.gameMode != null
                && volumeIndex < menu.state().ownedVolumes().size()) {
            minecraft.gameMode.handleInventoryButtonClick(
                    menu.containerId,
                    DigitalStorageScreenHandler.BIND_VOLUME_BUTTON_BASE + volumeIndex
            );
        }
    }

    private void requestVolumeManagement(int action, int slot) {
        int volumeIndex = volumePage * VOLUMES_PER_PAGE + slot;
        List<DigitalStorageScreenState.VolumeChoice> choices = menu.state().ownedVolumes();
        if (managementRequestedFromState != null || volumeIndex < 0 || volumeIndex >= choices.size()) {
            return;
        }
        String requestedName = volumeNameField.getValue().strip();
        if (action == DigitalStorageScreenHandler.RENAME_VOLUME_ACTION && requestedName.isEmpty()) {
            return;
        }
        managementRequestedFromState = menu.state();
        requestSender.send(new DigitalStorageScreenProtocol.ManageVolume(
                menu.containerId, action, choices.get(volumeIndex).id(), requestedName));
        updateButton();
    }

    private int pageCount() {
        return Math.max(1, (menu.state().ownedVolumes().size() + VOLUMES_PER_PAGE - 1) / VOLUMES_PER_PAGE);
    }

    private void drawStatus(GuiGraphics context, DigitalStorageScreenState state) {
        if (!state.status().getString().isEmpty()) {
            drawFittedText(
                    context,
                    state.status(),
                    CONTENT_MARGIN,
                    STATUS_Y,
                    imageWidth - 24,
                    state.statusSuccessful() ? SUCCESS_TEXT : ERROR_TEXT,
                    0.8F
            );
        }
    }
}
