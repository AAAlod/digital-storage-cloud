package dev.kehai.digitalstorage.client.screen;

import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.tier.UpgradeIngredient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import java.util.ArrayList;
import java.util.List;

public final class DigitalStorageScreen extends HandledScreen<DigitalStorageScreenHandler> {
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
    private static final ItemStack ACCESSOR_ICON = new ItemStack(DigitalStorageMod.DIGITAL_STORAGE_ACCESSOR_ITEM);

    private ButtonWidget upgradeButton;
    private ButtonWidget clearBindingButton;
    private ButtonWidget migrationButton;
    private ButtonWidget networkAnalysisButton;
    private ButtonWidget createVolumeButton;
    private ButtonWidget previousPageButton;
    private ButtonWidget nextPageButton;
    private TextFieldWidget volumeNameField;
    private final List<ButtonWidget> volumeButtons = new ArrayList<>();
    private final List<ButtonWidget> renameVolumeButtons = new ArrayList<>();
    private final List<ButtonWidget> deleteVolumeButtons = new ArrayList<>();
    private int volumePage;
    private int lastKnownVolumeCount;
    private DigitalStorageScreenState creationRequestedFromState;
    private DigitalStorageScreenState managementRequestedFromState;

    public DigitalStorageScreen(
            DigitalStorageScreenHandler handler,
            PlayerInventory inventory,
            Text title
    ) {
        super(handler, inventory, title);
        backgroundWidth = SCREEN_WIDTH;
        backgroundHeight = SCREEN_HEIGHT;
        playerInventoryTitleY = 10000;
    }

    @Override
    protected void init() {
        super.init();
        volumeButtons.clear();
        renameVolumeButtons.clear();
        deleteVolumeButtons.clear();
        volumeNameField = addDrawableChild(new TextFieldWidget(
                textRenderer,
                x + CONTENT_MARGIN,
                y + FORM_Y,
                FORM_FIELD_WIDTH,
                20,
                Text.translatable("screen.digitalstorage.volume_name")
        ));
        volumeNameField.setMaxLength(dev.kehai.digitalstorage.storage.StorageVolume.MAX_NAME_LENGTH);

        createVolumeButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.digitalstorage.create_volume"),
                button -> requestVolumeCreation()
        ).dimensions(x + 192, y + FORM_Y, 72, 20).build());

        for (int slot = 0; slot < VOLUMES_PER_PAGE; slot++) {
            int selectedSlot = slot;
            ButtonWidget button = addDrawableChild(ButtonWidget.builder(
                    Text.empty(),
                    ignored -> bindVisibleVolume(selectedSlot)
            ).dimensions(
                    x + CONTENT_MARGIN,
                    y + VOLUME_LIST_Y + slot * VOLUME_ROW_HEIGHT,
                    VOLUME_BIND_BUTTON_WIDTH,
                    20
            ).build());
            volumeButtons.add(button);
            int manageButtonX = CONTENT_MARGIN + VOLUME_BIND_BUTTON_WIDTH + VOLUME_BUTTON_GAP;
            renameVolumeButtons.add(addDrawableChild(ButtonWidget.builder(
                    Text.translatable("screen.digitalstorage.rename_volume"),
                    ignored -> requestVolumeManagement(DigitalStorageScreenHandler.RENAME_VOLUME_ACTION, selectedSlot)
            ).dimensions(
                    x + manageButtonX,
                    y + VOLUME_LIST_Y + slot * VOLUME_ROW_HEIGHT,
                    VOLUME_MANAGE_BUTTON_WIDTH,
                    20
            ).build()));
            deleteVolumeButtons.add(addDrawableChild(ButtonWidget.builder(
                    Text.translatable("screen.digitalstorage.delete_volume"),
                    ignored -> requestVolumeManagement(DigitalStorageScreenHandler.DELETE_VOLUME_ACTION, selectedSlot)
            ).dimensions(
                    x + manageButtonX + VOLUME_MANAGE_BUTTON_WIDTH + VOLUME_BUTTON_GAP,
                    y + VOLUME_LIST_Y + slot * VOLUME_ROW_HEIGHT,
                    VOLUME_MANAGE_BUTTON_WIDTH,
                    20
            ).build()));
        }

        previousPageButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.digitalstorage.previous_page"),
                button -> {
                    volumePage = Math.max(0, volumePage - 1);
                    updateButton();
                }
        ).dimensions(x + CONTENT_MARGIN, y + PAGE_BUTTON_Y, 64, 20).build());
        nextPageButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.digitalstorage.next_page"),
                button -> {
                    volumePage = Math.min(pageCount() - 1, volumePage + 1);
                    updateButton();
                }
        ).dimensions(x + backgroundWidth - CONTENT_MARGIN - 64, y + PAGE_BUTTON_Y, 64, 20).build());

        int actionWidth = (backgroundWidth - 32) / 3;
        upgradeButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.digitalstorage.upgrade"),
                button -> {
                    if (client != null && client.interactionManager != null) {
                        client.interactionManager.clickButton(handler.syncId, DigitalStorageScreenHandler.UPGRADE_BUTTON_ID);
                    }
                }
        ).dimensions(x + CONTENT_MARGIN, y + ACTION_BUTTON_Y, actionWidth, 20).build());
        migrationButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.digitalstorage.migration.run"),
                button -> {
                    if (client != null && client.interactionManager != null) {
                        client.interactionManager.clickButton(
                                handler.syncId,
                                DigitalStorageScreenHandler.MIGRATION_BUTTON_ID
                        );
                    }
                }
        ).dimensions(x + 16 + actionWidth, y + ACTION_BUTTON_Y, actionWidth, 20).build());
        clearBindingButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.digitalstorage.clear_binding"),
                button -> {
                    if (client != null && client.interactionManager != null) {
                        client.interactionManager.clickButton(
                                handler.syncId,
                                DigitalStorageScreenHandler.CLEAR_BINDING_BUTTON_ID
                        );
                    }
                }
        ).dimensions(x + 20 + actionWidth * 2, y + ACTION_BUTTON_Y, actionWidth, 20).build());
        networkAnalysisButton = addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.digitalstorage.network.refresh"),
                button -> {
                    if (client != null && client.interactionManager != null) {
                        client.interactionManager.clickButton(
                                handler.syncId,
                                DigitalStorageScreenHandler.NETWORK_ANALYSIS_BUTTON_ID
                        );
                    }
                }
        ).dimensions(x + backgroundWidth - 72, y + 3, 60, 16).build());

        lastKnownVolumeCount = handler.state().ownedVolumes().size();
        updateButton();
    }

    @Override
    protected void handledScreenTick() {
        if (creationRequestedFromState != null && handler.state() != creationRequestedFromState) {
            creationRequestedFromState = null;
        }
        if (managementRequestedFromState != null && handler.state() != managementRequestedFromState) {
            managementRequestedFromState = null;
        }
        int currentVolumeCount = handler.state().ownedVolumes().size();
        if (currentVolumeCount > lastKnownVolumeCount && handler.state().statusSuccessful()) {
            volumeNameField.setText("");
            volumePage = Math.max(0, pageCount() - 1);
        }
        lastKnownVolumeCount = currentVolumeCount;
        updateButton();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        context.fill(x, y, x + backgroundWidth, y + backgroundHeight, PANEL_BACKGROUND);
        context.drawBorder(x, y, backgroundWidth, backgroundHeight, PANEL_BORDER);
        context.fill(x + 1, y + 1, x + backgroundWidth - 1, y + HEADER_HEIGHT, 0xFF252C36);
        if (handler.state().accessorBound()) {
            int rightX = x + CARD_MARGIN + CARD_WIDTH + CARD_GAP;
            drawCardBackground(context, x + CARD_MARGIN, y + TOP_CARD_Y, CARD_WIDTH, TOP_CARD_HEIGHT);
            drawCardBackground(context, rightX, y + TOP_CARD_Y, CARD_WIDTH, TOP_CARD_HEIGHT);
            drawCardBackground(context, x + CARD_MARGIN, y + BOTTOM_CARD_Y, CARD_WIDTH, BOTTOM_CARD_HEIGHT);
            drawCardBackground(context, rightX, y + BOTTOM_CARD_Y, CARD_WIDTH, BOTTOM_CARD_HEIGHT);
        } else {
            drawCardBackground(
                    context,
                    x + CARD_MARGIN,
                    y + TOP_CARD_Y,
                    backgroundWidth - CARD_MARGIN * 2,
                    194
            );
        }
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        DigitalStorageScreenState state = handler.state();
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

    private void drawCardBackground(DrawContext context, int drawX, int drawY, int width, int height) {
        context.fill(drawX, drawY, drawX + width, drawY + height, CARD_BACKGROUND);
        context.drawBorder(drawX, drawY, width, height, CARD_BORDER);
    }

    private void drawHeader(DrawContext context, DigitalStorageScreenState state) {
        context.drawItem(ACCESSOR_ICON, 5, 4);
        context.drawText(textRenderer, title, 26, 8, PRIMARY_TEXT, false);
        if (!state.accessorBound()) {
            return;
        }
        DigitalStorageScreenState.NetworkDiagnostic diagnostic = state.networkDiagnostic();
        Text badge = diagnostic.available()
                ? Text.literal(diagnostic.grade() + " · " + diagnostic.healthScore() + " / 100")
                : Text.translatable("screen.digitalstorage.network.not_connected_short");
        int color = diagnostic.available() ? healthColor(diagnostic.healthScore()) : SECONDARY_TEXT;
        int badgeRight = backgroundWidth - 80;
        context.drawText(
                textRenderer,
                badge,
                badgeRight - textRenderer.getWidth(badge),
                8,
                color,
                false
        );
    }

    private void drawUnboundState(DrawContext context, DigitalStorageScreenState state) {
        context.drawText(
                textRenderer,
                Text.translatable("screen.digitalstorage.unbound"),
                16,
                36,
                WARNING_TEXT,
                false
        );
        context.drawTextWrapped(
                textRenderer,
                Text.translatable("screen.digitalstorage.choose_volume"),
                16,
                49,
                backgroundWidth - 32,
                SECONDARY_TEXT
        );
        context.drawText(
                textRenderer,
                Text.translatable("screen.digitalstorage.volume_name"),
                16,
                64,
                SECONDARY_TEXT,
                false
        );
        if (state.ownedVolumes().isEmpty()) {
            context.drawTextWrapped(
                    textRenderer,
                    Text.translatable("screen.digitalstorage.no_volumes"),
                    16,
                    108,
                    backgroundWidth - 32,
                    WARNING_TEXT
            );
        }
        if (pageCount() > 1) {
            Text page = Text.translatable("screen.digitalstorage.page", volumePage + 1, pageCount());
            context.drawCenteredTextWithShadow(textRenderer, page, backgroundWidth / 2, 206, SECONDARY_TEXT);
        }
        drawStatus(context, state);
    }

    private void drawStorageOverview(
            DrawContext context,
            DigitalStorageScreenState state,
            int drawX,
            int drawY
    ) {
        drawSectionTitle(context, "screen.digitalstorage.section.storage", drawX, drawY);
        drawKeyValue(context, "screen.digitalstorage.volume", Text.literal(state.volumeName()),
                drawX + 8, drawY + 23, CARD_WIDTH - 16);
        drawKeyValue(context, "screen.digitalstorage.controller", Text.literal(state.controller()),
                drawX + 8, drawY + 36, CARD_WIDTH - 16);
        drawKeyValue(context, "screen.digitalstorage.current_tier", tierName(state.tierId()),
                drawX + 8, drawY + 49, CARD_WIDTH - 16);
        drawKeyValue(context, "screen.digitalstorage.variants", Text.literal(
                state.usedVariants() + " / " + state.variantCapacity()
        ), drawX + 8, drawY + 64, CARD_WIDTH - 16);
        drawProgressBar(
                context,
                drawX + 8,
                drawY + 77,
                CARD_WIDTH - 16,
                6,
                state.variantCapacity() <= 0 ? 0.0 : (double) state.usedVariants() / state.variantCapacity()
        );
        drawKeyValue(context, "screen.digitalstorage.total_items", Text.literal(state.totalItems()),
                drawX + 8, drawY + 89, CARD_WIDTH - 16);
    }

    private void drawNetworkSummary(
            DrawContext context,
            DigitalStorageScreenState.NetworkDiagnostic diagnostic,
            int drawX,
            int drawY
    ) {
        drawSectionTitle(context, "screen.digitalstorage.section.network", drawX, drawY);
        if (!diagnostic.available()) {
            drawWrappedText(context, Text.translatable("screen.digitalstorage.network.unavailable"),
                    drawX + 8, drawY + 25, CARD_WIDTH - 16, SECONDARY_TEXT);
            return;
        }
        int lineY = drawY + 25;
        lineY = drawWrappedText(
                context,
                Text.translatable(diagnostic.healthScore() >= 90
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
                    Text.translatable(
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
                    Text.translatable(
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
        if (diagnostic.recommendedVariants() > 0) {
            drawWrappedText(
                    context,
                    Text.translatable(
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
                    Text.translatable("screen.digitalstorage.network.no_action"),
                    drawX + 8,
                    lineY,
                    CARD_WIDTH - 16,
                    SECONDARY_TEXT
            );
        }
    }

    private void drawUpgradePanel(
            DrawContext context,
            DigitalStorageScreenState state,
            int drawX,
            int drawY
    ) {
        drawSectionTitle(context, "screen.digitalstorage.section.upgrade", drawX, drawY);
        if (!state.hasNextTier()) {
            context.drawText(
                    textRenderer,
                    Text.translatable("screen.digitalstorage.maximum"),
                    drawX + 8,
                    drawY + 28,
                    SUCCESS_TEXT,
                    false
            );
            return;
        }
        drawFittedText(
                context,
                Text.translatable(
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
                Text.translatable(
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
        context.drawText(
                textRenderer,
                Text.translatable(state.canAfford()
                        ? "screen.digitalstorage.affordable"
                        : "screen.digitalstorage.unaffordable"),
                drawX + 8,
                drawY + 66,
                state.canAfford() ? SUCCESS_TEXT : ERROR_TEXT,
                false
        );
    }

    private void drawOptimizationPanel(
            DrawContext context,
            DigitalStorageScreenState.NetworkDiagnostic diagnostic,
            int drawX,
            int drawY
    ) {
        drawSectionTitle(context, "screen.digitalstorage.section.optimization", drawX, drawY);
        if (!diagnostic.available()) {
            drawWrappedText(context, Text.translatable("screen.digitalstorage.network.not_connected"),
                    drawX + 8, drawY + 28, CARD_WIDTH - 16, SECONDARY_TEXT);
            return;
        }
        if (diagnostic.migrationActive()) {
            drawFittedText(context, Text.translatable("screen.digitalstorage.migration.running"),
                    drawX + 8, drawY + 23, CARD_WIDTH - 16, SUCCESS_TEXT, 0.85F);
            double progress = diagnostic.totalCandidates() <= 0 ? 0.0
                    : (double) diagnostic.completedCandidates() / diagnostic.totalCandidates();
            drawProgressBar(context, drawX + 8, drawY + 37, CARD_WIDTH - 16, 7, progress);
            drawFittedText(
                    context,
                    Text.translatable(
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
                    Text.translatable("screen.digitalstorage.migration.moved_short", diagnostic.movedItems()),
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
                    Text.translatable("screen.digitalstorage.network.migration_blocked"),
                    drawX + 8,
                    drawY + 27,
                    CARD_WIDTH - 16,
                    ERROR_TEXT
            );
            drawWrappedText(
                    context,
                    Text.translatable("screen.digitalstorage.network.keep_one_endpoint"),
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
                    Text.translatable(
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
                    Text.translatable(
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
        drawWrappedText(context, Text.translatable("screen.digitalstorage.network.no_optimization"),
                drawX + 8, drawY + 30, CARD_WIDTH - 16, SUCCESS_TEXT);
    }

    private void drawSectionTitle(DrawContext context, String key, int drawX, int drawY) {
        context.drawText(
                textRenderer,
                Text.translatable(key),
                drawX + 8,
                drawY + 7,
                PRIMARY_TEXT,
                false
        );
        context.drawHorizontalLine(drawX + 8, drawX + CARD_WIDTH - 9, drawY + 19, CARD_BORDER);
    }

    private void drawKeyValue(
            DrawContext context,
            String labelKey,
            Text value,
            int drawX,
            int drawY,
            int width
    ) {
        Text label = Text.translatable(labelKey);
        context.drawText(textRenderer, label, drawX, drawY, SECONDARY_TEXT, false);
        int maxValueWidth = Math.max(20, width - textRenderer.getWidth(label) - 8);
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
            DrawContext context,
            Text text,
            int drawX,
            int drawY,
            int width,
            int color,
            float minScale
    ) {
        drawFittedText(context, text, drawX, drawY, width, color, minScale, false);
    }

    private void drawFittedTextRightAligned(
            DrawContext context,
            Text text,
            int drawX,
            int drawY,
            int width,
            int color,
            float minScale
    ) {
        drawFittedText(context, text, drawX, drawY, width, color, minScale, true);
    }

    private void drawFittedText(
            DrawContext context,
            Text text,
            int drawX,
            int drawY,
            int width,
            int color,
            float minScale,
            boolean rightAligned
    ) {
        int textWidth = textRenderer.getWidth(text);
        if (textWidth <= width) {
            int textX = rightAligned ? drawX + width - textWidth : drawX;
            context.drawText(textRenderer, text, textX, drawY, color, false);
            return;
        }

        float scale = Math.max(minScale, width / (float) textWidth);
        Text displayText = text;
        int displayWidth = textWidth;
        if (displayWidth * scale > width) {
            int unscaledWidth = Math.max(1, (int) Math.floor(width / scale));
            displayText = Text.literal(textRenderer.trimToWidth(text.getString(), unscaledWidth));
            displayWidth = textRenderer.getWidth(displayText);
        }

        context.getMatrices().push();
        context.getMatrices().scale(scale, scale, 1.0F);
        int scaledY = Math.round(drawY / scale);
        int scaledX = rightAligned
                ? Math.round((drawX + width) / scale) - displayWidth
                : Math.round(drawX / scale);
        context.drawText(textRenderer, displayText, scaledX, scaledY, color, false);
        context.getMatrices().pop();
    }

    private int drawWrappedText(
            DrawContext context,
            Text text,
            int drawX,
            int drawY,
            int width,
            int color
    ) {
        List<OrderedText> lines = textRenderer.wrapLines(text, width);
        int lineHeight = textRenderer.fontHeight + 1;
        for (int index = 0; index < lines.size(); index++) {
            context.drawText(textRenderer, lines.get(index), drawX, drawY + index * lineHeight, color, false);
        }
        return drawY + lines.size() * lineHeight;
    }

    private void drawProgressBar(
            DrawContext context,
            int drawX,
            int drawY,
            int width,
            int height,
            double progress
    ) {
        context.fill(drawX, drawY, drawX + width, drawY + height, PROGRESS_BACKGROUND);
        context.drawBorder(drawX, drawY, width, height, CARD_BORDER);
        double clamped = Math.max(0.0, Math.min(1.0, progress));
        int fillWidth = (int) Math.round((width - 2) * clamped);
        if (fillWidth > 0) {
            context.fill(drawX + 1, drawY + 1, drawX + 1 + fillWidth, drawY + height - 1, PROGRESS_FILL);
        }
    }

    private int healthColor(int score) {
        return score >= 75 ? SUCCESS_TEXT : score >= 40 ? WARNING_TEXT : ERROR_TEXT;
    }

    private Text tierName(net.minecraft.util.Identifier id) {
        String translationKey = "tier.digitalstorage." + id.getNamespace() + "." + id.getPath().replace('/', '.');
        return Text.translatableWithFallback(translationKey, id.toString());
    }

    private Text costText(DigitalStorageScreenState state) {
        MutableText result = Text.empty();
        boolean hasPrevious = false;
        for (UpgradeIngredient ingredient : state.upgradeCost()) {
            if (hasPrevious) {
                result.append(Text.literal(", "));
            }
            Text name = ingredient.kind() == UpgradeIngredient.Kind.ITEM
                    ? Registries.ITEM.getOrEmpty(ingredient.id())
                            .<Text>map(item -> item.getName())
                            .orElse(Text.literal(ingredient.id().toString()))
                    : Text.literal("#" + ingredient.id());
            result.append(Text.translatable("screen.digitalstorage.cost_entry", name, ingredient.count()));
            hasPrevious = true;
        }
        if (state.experienceLevels() > 0) {
            if (hasPrevious) {
                result.append(Text.literal(", "));
            }
            result.append(Text.translatable("screen.digitalstorage.cost_xp", state.experienceLevels()));
            hasPrevious = true;
        }
        return hasPrevious ? result : Text.translatable("screen.digitalstorage.cost_free");
    }

    private void updateButton() {
        if (upgradeButton == null || migrationButton == null || networkAnalysisButton == null
                || clearBindingButton == null || volumeNameField == null) {
            return;
        }
        DigitalStorageScreenState state = handler.state();
        boolean canConfigureUnbound = !state.accessorBound() && state.accessorConfigurable();
        volumeNameField.visible = canConfigureUnbound;
        volumeNameField.setEditable(canConfigureUnbound);
        createVolumeButton.visible = canConfigureUnbound;
        createVolumeButton.active = canConfigureUnbound
                && creationRequestedFromState == null
                && !volumeNameField.getText().isBlank();

        int pageCount = pageCount();
        volumePage = Math.max(0, Math.min(volumePage, pageCount - 1));
        previousPageButton.visible = canConfigureUnbound && pageCount > 1;
        previousPageButton.active = previousPageButton.visible && volumePage > 0;
        nextPageButton.visible = canConfigureUnbound && pageCount > 1;
        nextPageButton.active = nextPageButton.visible && volumePage + 1 < pageCount;

        upgradeButton.visible = state.accessorBound();
        upgradeButton.active = state.accessorBound() && state.accessorConfigurable() && state.hasNextTier();
        upgradeButton.setMessage(Text.translatable(
                state.hasNextTier() ? "screen.digitalstorage.upgrade" : "screen.digitalstorage.maximum_button"
        ));
        migrationButton.visible = state.accessorBound();
        migrationButton.active = state.accessorBound()
                && state.accessorConfigurable()
                && (state.networkDiagnostic().migrationActive()
                || (state.networkDiagnostic().available()
                && !state.networkDiagnostic().hasDuplicateTargetEndpoints()
                && state.networkDiagnostic().recommendedVariants() > 0));
        migrationButton.setMessage(Text.translatable(state.networkDiagnostic().migrationActive()
                ? "screen.digitalstorage.migration.cancel"
                : "screen.digitalstorage.migration.run"));
        migrationButton.setTooltip(state.networkDiagnostic().migrationActive()
                ? null
                : Tooltip.of(Text.translatable("screen.digitalstorage.network.migration_hint")));
        networkAnalysisButton.visible = state.accessorBound();
        networkAnalysisButton.active = state.accessorBound() && !state.networkDiagnostic().migrationActive();
        clearBindingButton.visible = state.accessorBound();
        clearBindingButton.active = clearBindingButton.visible && state.accessorConfigurable();
        List<DigitalStorageScreenState.VolumeChoice> choices = state.ownedVolumes();
        for (int slot = 0; slot < volumeButtons.size(); slot++) {
            ButtonWidget button = volumeButtons.get(slot);
            ButtonWidget renameButton = renameVolumeButtons.get(slot);
            ButtonWidget deleteButton = deleteVolumeButtons.get(slot);
            int volumeIndex = volumePage * VOLUMES_PER_PAGE + slot;
            boolean hasChoice = volumeIndex < choices.size();
            button.visible = canConfigureUnbound && hasChoice;
            button.active = button.visible && managementRequestedFromState == null;
            renameButton.visible = button.visible;
            deleteButton.visible = button.visible;
            if (hasChoice) {
                DigitalStorageScreenState.VolumeChoice choice = choices.get(volumeIndex);
                Text usage = Text.translatable(
                        choice.usedVariants() == 0
                                ? "screen.digitalstorage.volume_empty"
                                : "screen.digitalstorage.volume_in_use",
                        choice.usedVariants(),
                        choice.variantCapacity()
                );
                button.setMessage(Text.translatable(
                        "screen.digitalstorage.bind_volume",
                        choice.name(),
                        tierName(choice.tierId()),
                        usage
                ));
                renameButton.active = managementRequestedFromState == null
                        && !volumeNameField.getText().isBlank();
                deleteButton.active = managementRequestedFromState == null && choice.usedVariants() == 0;
            }
        }
    }

    private void requestVolumeCreation() {
        if (!createVolumeButton.active) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(handler.syncId);
        buf.writeString(
                volumeNameField.getText().strip(),
                dev.kehai.digitalstorage.storage.StorageVolume.MAX_NAME_LENGTH
        );
        creationRequestedFromState = handler.state();
        createVolumeButton.active = false;
        ClientPlayNetworking.send(DigitalStorageScreenHandler.CREATE_VOLUME_PACKET_ID, buf);
    }

    private void bindVisibleVolume(int slot) {
        int volumeIndex = volumePage * VOLUMES_PER_PAGE + slot;
        if (client != null
                && client.interactionManager != null
                && volumeIndex < handler.state().ownedVolumes().size()) {
            client.interactionManager.clickButton(
                    handler.syncId,
                    DigitalStorageScreenHandler.BIND_VOLUME_BUTTON_BASE + volumeIndex
            );
        }
    }

    private void requestVolumeManagement(int action, int slot) {
        int volumeIndex = volumePage * VOLUMES_PER_PAGE + slot;
        List<DigitalStorageScreenState.VolumeChoice> choices = handler.state().ownedVolumes();
        if (managementRequestedFromState != null || volumeIndex < 0 || volumeIndex >= choices.size()) {
            return;
        }
        String requestedName = volumeNameField.getText().strip();
        if (action == DigitalStorageScreenHandler.RENAME_VOLUME_ACTION && requestedName.isEmpty()) {
            return;
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeVarInt(handler.syncId);
        buf.writeVarInt(action);
        buf.writeUuid(choices.get(volumeIndex).id());
        buf.writeString(requestedName, dev.kehai.digitalstorage.storage.StorageVolume.MAX_NAME_LENGTH);
        managementRequestedFromState = handler.state();
        ClientPlayNetworking.send(DigitalStorageScreenHandler.MANAGE_VOLUME_PACKET_ID, buf);
        updateButton();
    }

    private int pageCount() {
        return Math.max(1, (handler.state().ownedVolumes().size() + VOLUMES_PER_PAGE - 1) / VOLUMES_PER_PAGE);
    }

    private void drawStatus(DrawContext context, DigitalStorageScreenState state) {
        if (!state.status().getString().isEmpty()) {
            drawFittedText(
                    context,
                    state.status(),
                    CONTENT_MARGIN,
                    STATUS_Y,
                    backgroundWidth - 24,
                    state.statusSuccessful() ? SUCCESS_TEXT : ERROR_TEXT,
                    0.8F
            );
        }
    }
}
