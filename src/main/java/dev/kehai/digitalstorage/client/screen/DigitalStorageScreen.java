package dev.kehai.digitalstorage.client.screen;

import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.tier.UpgradeIngredient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import java.util.ArrayList;
import java.util.List;

public final class DigitalStorageScreen extends HandledScreen<DigitalStorageScreenHandler> {
    private static final int SCREEN_WIDTH = 360;
    private static final int SCREEN_HEIGHT = 270;
    private static final int CONTENT_MARGIN = 12;
    private static final int HEADER_HEIGHT = 22;
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
    private static final int FIRST_SECTION_Y = 29;
    private static final int SECTION_DIVIDER_Y = 140;
    private static final int SECOND_SECTION_Y = 148;
    private static final int LABEL_ROW_HEIGHT = 13;
    private static final int COLUMN_DIVIDER_X = 178;
    private static final int RIGHT_COLUMN_X = 188;
    private static final int STATUS_Y = 221;
    private static final int ACTION_BUTTON_Y = 240;
    private static final int VOLUMES_PER_PAGE = 4;
    private static final int PANEL_BACKGROUND = 0xFF171B22;
    private static final int PANEL_BORDER = 0xFF596575;
    private static final int PRIMARY_TEXT = 0xFFE8EDF2;
    private static final int SECONDARY_TEXT = 0xFFB4C0CC;
    private static final int SUCCESS_TEXT = 0xFF73D673;
    private static final int ERROR_TEXT = 0xFFFF7777;

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
            context.drawHorizontalLine(
                    x + CONTENT_MARGIN - 1,
                    x + backgroundWidth - CONTENT_MARGIN,
                    y + SECTION_DIVIDER_Y,
                    PANEL_BORDER
            );
            context.drawVerticalLine(x + COLUMN_DIVIDER_X, y + HEADER_HEIGHT + 2, y + 214, PANEL_BORDER);
        }
    }

    @Override
    protected void drawForeground(DrawContext context, int mouseX, int mouseY) {
        DigitalStorageScreenState state = handler.state();
        context.drawText(textRenderer, title, 12, 7, PRIMARY_TEXT, false);

        if (!state.accessorBound()) {
            context.drawText(
                    textRenderer,
                    Text.translatable("screen.digitalstorage.unbound"),
                    12,
                    30,
                    ERROR_TEXT,
                    false
            );
            context.drawTextWrapped(
                    textRenderer,
                    Text.translatable("screen.digitalstorage.choose_volume"),
                    12,
                    45,
                    backgroundWidth - 24,
                    SECONDARY_TEXT
            );
            context.drawText(
                    textRenderer,
                    Text.translatable("screen.digitalstorage.volume_name"),
                    12,
                    63,
                    SECONDARY_TEXT,
                    false
            );
            if (state.ownedVolumes().isEmpty()) {
                context.drawTextWrapped(
                        textRenderer,
                        Text.translatable("screen.digitalstorage.no_volumes"),
                        12,
                        108,
                        backgroundWidth - 24,
                        ERROR_TEXT
                );
            }
            if (pageCount() > 1) {
                Text page = Text.translatable("screen.digitalstorage.page", volumePage + 1, pageCount());
                context.drawCenteredTextWithShadow(textRenderer, page, backgroundWidth / 2, 206, SECONDARY_TEXT);
            }
            drawStatus(context, state);
            return;
        }

        drawLabeled(context, "screen.digitalstorage.volume", Text.literal(state.volumeName()),
                CONTENT_MARGIN, FIRST_SECTION_Y);
        drawLabeled(context, "screen.digitalstorage.controller", Text.literal(state.controller()),
                CONTENT_MARGIN, FIRST_SECTION_Y + LABEL_ROW_HEIGHT);
        drawLabeled(context, "screen.digitalstorage.current_tier", tierName(state.tierId()),
                CONTENT_MARGIN, FIRST_SECTION_Y + LABEL_ROW_HEIGHT * 2);
        drawLabeled(context, "screen.digitalstorage.variants", Text.literal(
                state.usedVariants() + " / " + state.variantCapacity()
        ), CONTENT_MARGIN, FIRST_SECTION_Y + LABEL_ROW_HEIGHT * 3);
        drawLabeled(context, "screen.digitalstorage.remaining", Text.literal(
                Integer.toString(state.remainingVariants())
        ), CONTENT_MARGIN, FIRST_SECTION_Y + LABEL_ROW_HEIGHT * 4);
        drawLabeled(context, "screen.digitalstorage.total_items", Text.literal(state.totalItems()),
                CONTENT_MARGIN, FIRST_SECTION_Y + LABEL_ROW_HEIGHT * 5);
        drawLabeled(context, "screen.digitalstorage.single_variant_max", Text.literal(
                Long.toString(dev.kehai.digitalstorage.storage.DigitalItemStorage.MAX_AMOUNT_PER_VARIANT)
        ), CONTENT_MARGIN, FIRST_SECTION_Y + LABEL_ROW_HEIGHT * 6);

        if (state.hasNextTier()) {
            drawLabeled(context, "screen.digitalstorage.next_tier", tierName(state.nextTierId()),
                    CONTENT_MARGIN, SECOND_SECTION_Y);
            drawLabeled(context, "screen.digitalstorage.next_capacity", Text.literal(
                    Integer.toString(state.nextVariantCapacity())
            ), CONTENT_MARGIN, SECOND_SECTION_Y + LABEL_ROW_HEIGHT);
            drawLabeled(context, "screen.digitalstorage.cost", costText(state),
                    CONTENT_MARGIN, SECOND_SECTION_Y + LABEL_ROW_HEIGHT * 2);

            Text affordability = Text.translatable(state.canAfford()
                    ? "screen.digitalstorage.affordable"
                    : "screen.digitalstorage.unaffordable");
            context.drawText(
                    textRenderer,
                    affordability,
                    CONTENT_MARGIN,
                    SECOND_SECTION_Y + LABEL_ROW_HEIGHT * 3 + 2,
                    state.canAfford() ? SUCCESS_TEXT : ERROR_TEXT,
                    false
            );
        } else {
            context.drawText(
                    textRenderer,
                    Text.translatable("screen.digitalstorage.maximum"),
                    CONTENT_MARGIN,
                    SECOND_SECTION_Y,
                    SUCCESS_TEXT,
                    false
            );
        }

        drawNetworkDiagnostic(context, state.networkDiagnostic());

        drawStatus(context, state);
    }

    private void drawNetworkDiagnostic(
            DrawContext context,
            DigitalStorageScreenState.NetworkDiagnostic diagnostic
    ) {
        int drawX = RIGHT_COLUMN_X;
        if (!diagnostic.available()) {
            context.drawText(textRenderer, Text.translatable("screen.digitalstorage.network.title"),
                    drawX, 29, PRIMARY_TEXT, false);
            context.drawTextWrapped(textRenderer, Text.translatable("screen.digitalstorage.network.unavailable"),
                    drawX, 45, backgroundWidth - drawX - 12, ERROR_TEXT);
            return;
        }
        context.drawText(
                textRenderer,
                Text.translatable(
                        "screen.digitalstorage.network.health",
                        diagnostic.healthScore(),
                        diagnostic.grade()
                ),
                drawX,
                29,
                diagnostic.healthScore() >= 75 ? SUCCESS_TEXT : diagnostic.healthScore() >= 40
                        ? 0xFFFFC45C : ERROR_TEXT,
                false
        );
        drawLabeled(context, "screen.digitalstorage.network.inventories",
                Text.literal(Integer.toString(diagnostic.physicalInventories())), drawX, 47);
        drawLabeled(context, "screen.digitalstorage.network.non_empty_views",
                Text.literal(Integer.toString(diagnostic.nonEmptyViews())), drawX, 60);
        drawLabeled(context, "screen.digitalstorage.network.total_views",
                Text.literal(Integer.toString(diagnostic.totalViews())), drawX, 73);
        drawLabeled(context, "screen.digitalstorage.network.digital_views",
                Text.literal(Integer.toString(diagnostic.digitalViews())), drawX, 86);
        drawLabeled(context, "screen.digitalstorage.network.scanners",
                Text.literal(diagnostic.activeScanners() + "/" + diagnostic.failingScanners()), drawX, 99);
        drawLabeled(context, "screen.digitalstorage.network.scan_interval",
                Text.literal(diagnostic.averageScanIntervalTicks() <= 0
                        ? "-" : Integer.toString(diagnostic.averageScanIntervalTicks())), drawX, 112);
        drawLabeled(context, "screen.digitalstorage.network.duplicate_endpoints",
                Text.translatable(
                        "screen.digitalstorage.network.duplicate_endpoint_value",
                        diagnostic.duplicateDigitalEndpoints(),
                        diagnostic.targetEndpointCount()
                ), drawX, 125);

        context.drawText(textRenderer, Text.translatable("screen.digitalstorage.network.recommendation"),
                drawX, 148, PRIMARY_TEXT, false);
        drawLabeled(context, "screen.digitalstorage.network.freed_views",
                Text.literal(Integer.toString(diagnostic.estimatedFreedViews())), drawX, 164);
        drawLabeled(context, "screen.digitalstorage.network.recommended_variants",
                Text.literal(Integer.toString(diagnostic.recommendedVariants())), drawX, 177);
        if (!diagnostic.topCandidateId().isEmpty()) {
            String top = textRenderer.trimToWidth(diagnostic.topCandidateId(), backgroundWidth - drawX - 14);
            drawLabeled(context, "screen.digitalstorage.network.top_candidate", Text.literal(top), drawX, 190);
        }
        if (!"IDLE".equals(diagnostic.migrationState())) {
            context.drawTextWrapped(
                    textRenderer,
                    Text.translatable(
                            "screen.digitalstorage.migration.progress",
                            diagnostic.movedItems(),
                            diagnostic.completedCandidates(),
                            diagnostic.totalCandidates(),
                            diagnostic.scannedViews()
                    ),
                    drawX,
                    198,
                    backgroundWidth - drawX - 12,
                    diagnostic.migrationActive() ? SUCCESS_TEXT : SECONDARY_TEXT
            );
        }
    }

    private void drawLabeled(DrawContext context, String labelKey, Text value, int drawX, int drawY) {
        MutableText text = Text.translatable(labelKey).formatted(Formatting.GRAY)
                .append(Text.literal(": ").formatted(Formatting.DARK_GRAY))
                .append(value.copy().formatted(Formatting.WHITE));
        context.drawText(textRenderer, text, drawX, drawY, SECONDARY_TEXT, false);
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
        networkAnalysisButton.visible = state.accessorBound();
        networkAnalysisButton.active = state.accessorBound() && !state.networkDiagnostic().migrationActive();
        clearBindingButton.visible = state.accessorBound() && state.accessorConfigurable();
        clearBindingButton.active = clearBindingButton.visible;
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
            context.drawTextWrapped(
                    textRenderer,
                    state.status(),
                    CONTENT_MARGIN,
                    STATUS_Y,
                    backgroundWidth - 24,
                    state.statusSuccessful() ? SUCCESS_TEXT : ERROR_TEXT
            );
        }
    }
}
