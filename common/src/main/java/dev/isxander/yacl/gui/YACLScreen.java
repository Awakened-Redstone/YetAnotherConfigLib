package dev.isxander.yacl.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.isxander.yacl.api.*;
import dev.isxander.yacl.api.utils.Dimension;
import dev.isxander.yacl.api.utils.MutableDimension;
import dev.isxander.yacl.api.utils.OptionUtils;
import dev.isxander.yacl.gui.utils.GuiUtils;
import dev.isxander.yacl.impl.utils.YACLConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.io.*;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class YACLScreen extends Screen {
    public final YetAnotherConfigLib config;

    private final Screen parent;

    public final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    public TabNavigationBar tabNavigationBar;
    public Tab[] tabs;
    public ScreenRectangle tabArea;

    public Component saveButtonMessage, saveButtonTooltipMessage;
    private int saveButtonMessageTime;

    public YACLScreen(YetAnotherConfigLib config, Screen parent) {
        super(config.title());
        this.config = config;
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (tabs != null) {
            closeTabs();
        }

        tabNavigationBar = TabNavigationBar.builder(tabManager, this.width)
                .addTabs(tabs = config.categories()
                        .stream()
                        .map(category -> {
                            if (category instanceof PlaceholderCategory placeholder)
                                return new PlaceholderTab(placeholder);
                            return new CategoryTab(category);
                        })
                        .toArray(Tab[]::new)
                )
                .build();
        tabNavigationBar.selectTab(0, false);
        tabNavigationBar.arrangeElements();
        ScreenRectangle navBarArea = tabNavigationBar.getRectangle();
        tabArea = new ScreenRectangle(0, navBarArea.height() - 1, this.width, this.height - navBarArea.height() + 1);
        tabManager.setTabArea(tabArea);
        addRenderableWidget(tabNavigationBar);

        config.initConsumer().accept(this);
    }

    @Override
    public void render(PoseStack matrices, int mouseX, int mouseY, float delta) {
        renderDirtBackground(matrices);

        super.render(matrices, mouseX, mouseY, delta);

        for (GuiEventListener child : children()) {
            if (child instanceof TooltipButtonWidget tooltipButtonWidget) {
                tooltipButtonWidget.renderHoveredTooltip(matrices);
            }
        }
    }

    protected void finishOrSave() {
        saveButtonMessage = null;

        if (pendingChanges()) {
            Set<OptionFlag> flags = new HashSet<>();
            OptionUtils.forEachOptions(config, option -> {
                if (option.applyValue()) {
                    flags.addAll(option.flags());
                }
            });
            OptionUtils.forEachOptions(config, option -> {
                if (option.changed()) {
                    // if still changed after applying, reset to the current value from binding
                    // as something has gone wrong.
                    option.forgetPendingValue();
                    YACLConstants.LOGGER.error("Option '{}' value mismatch after applying! Reset to binding's getter.", option.name().getString());
                }
            });
            config.saveFunction().run();

            flags.forEach(flag -> flag.accept(minecraft));
        } else onClose();
    }

    protected void cancelOrReset() {
        if (pendingChanges()) { // if pending changes, button acts as a cancel button
            OptionUtils.forEachOptions(config, Option::forgetPendingValue);
            onClose();
        } else { // if not, button acts as a reset button
            OptionUtils.forEachOptions(config, Option::requestSetDefault);
        }
    }

    protected void undo() {
        OptionUtils.forEachOptions(config, Option::forgetPendingValue);
    }

    @Override
    public void tick() {
        tabManager.tickCurrent();

        if (saveButtonMessage != null) {
            if (saveButtonMessageTime > 140) {
                saveButtonMessage = null;
                saveButtonTooltipMessage = null;
                saveButtonMessageTime = 0;
            } else {
                saveButtonMessageTime++;
                //finishedSaveButton.setMessage(saveButtonMessage);
                if (saveButtonTooltipMessage != null) {
                    //finishedSaveButton.setTooltip(saveButtonTooltipMessage);
                }
            }
        }
    }

    private void setSaveButtonMessage(Component message, Component tooltip) {
        saveButtonMessage = message;
        saveButtonTooltipMessage = tooltip;
        saveButtonMessageTime = 0;
    }

    private boolean pendingChanges() {
        AtomicBoolean pendingChanges = new AtomicBoolean(false);
        OptionUtils.consumeOptions(config, (option) -> {
            if (option.changed()) {
                pendingChanges.set(true);
                return true;
            }
            return false;
        });

        return pendingChanges.get();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        if (pendingChanges()) {
            setSaveButtonMessage(Component.translatable("yacl.gui.save_before_exit").withStyle(ChatFormatting.RED), Component.translatable("yacl.gui.save_before_exit.tooltip"));
            return false;
        }
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
        closeTabs();
    }

    private void closeTabs() {
        for (Tab tab : tabs) {
            if (tab instanceof Closeable closeable) {
                try {
                    closeable.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public static void renderMultilineTooltip(PoseStack matrices, Font font, MultiLineLabel text, int centerX, int yAbove, int yBelow, int screenWidth, int screenHeight) {
        if (text.getLineCount() > 0) {
            int maxWidth = text.getWidth();
            int lineHeight = font.lineHeight + 1;
            int height = text.getLineCount() * lineHeight - 1;

            int belowY = yBelow + 12;
            int aboveY = yAbove - height + 12;
            int maxBelow = screenHeight - (belowY + height);
            int minAbove = aboveY - height;
            int y = aboveY;
            if (minAbove < 8)
                y = maxBelow > minAbove ? belowY : aboveY;

            int x = Math.max(centerX - text.getWidth() / 2 - 12, -6);

            int drawX = x + 12;
            int drawY = y - 12;

            matrices.pushPose();
            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder bufferBuilder = tesselator.getBuilder();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            Matrix4f matrix4f = matrices.last().pose();
            TooltipRenderUtil.renderTooltipBackground(
                    GuiComponent::fillGradient,
                    matrix4f,
                    bufferBuilder,
                    drawX,
                    drawY,
                    maxWidth,
                    height,
                    400
            );
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            BufferUploader.drawWithShader(bufferBuilder.end());
            RenderSystem.disableBlend();
            matrices.translate(0.0, 0.0, 400.0);

            text.renderLeftAligned(matrices, drawX, drawY, lineHeight, -1);

            matrices.popPose();
        }
    }

    private class CategoryTab implements Tab, Closeable {
        private final ConfigCategory category;

        private final TabListWidget<OptionListWidget> optionList;
        private final Button saveFinishedButton;
        private final Button cancelResetButton;
        private final Button undoButton;
        private final SearchFieldWidget searchField;
        private OptionDescriptionWidget descriptionWidget;

        public CategoryTab(ConfigCategory category) {
            this.category = category;

            this.optionList = new TabListWidget<>(
                    () -> new ScreenRectangle(tabArea.position(), tabArea.width() / 3 * 2 + 1, tabArea.height()),
                    new OptionListWidget(YACLScreen.this, category, minecraft, 0, 0, width / 3 * 2 + 1, height, hoveredOption -> {
                        descriptionWidget.setOptionDescription(hoveredOption.description());
                    })
            );

            int columnWidth = width / 3;
            int padding = columnWidth / 20;
            columnWidth = Math.min(columnWidth, 400);
            int paddedWidth = columnWidth - padding * 2;
            MutableDimension<Integer> actionDim = Dimension.ofInt(width / 3 * 2 + width / 6, height - padding - 20, paddedWidth, 20);

            saveFinishedButton = Button.builder(Component.literal("Done"), btn -> finishOrSave())
                    .pos(actionDim.x() - actionDim.width() / 2, actionDim.y())
                    .size(actionDim.width(), actionDim.height())
                    .build();

            actionDim.expand(-actionDim.width() / 2 - 2, 0).move(-actionDim.width() / 2 - 2, -22);
            cancelResetButton = Button.builder(Component.literal("Cancel"), btn -> cancelOrReset())
                    .pos(actionDim.x() - actionDim.width() / 2, actionDim.y())
                    .size(actionDim.width(), actionDim.height())
                    .build();

            actionDim.move(actionDim.width() + 4, 0);
            undoButton = Button.builder(Component.translatable("yacl.gui.undo"), btn -> undo())
                    .pos(actionDim.x() - actionDim.width() / 2, actionDim.y())
                    .size(actionDim.width(), actionDim.height())
                    .tooltip(Tooltip.create(Component.translatable("yacl.gui.undo.tooltip")))
                    .build();

            searchField = new SearchFieldWidget(
                    YACLScreen.this,
                    font,
                    width / 3 * 2 + width / 6 - paddedWidth / 2 + 1,
                    undoButton.getY() - 22,
                    paddedWidth - 2, 18,
                    Component.translatable("gui.recipebook.search_hint"),
                    Component.translatable("gui.recipebook.search_hint"),
                    searchQuery -> optionList.getList().updateSearchQuery(searchQuery)
            );

            descriptionWidget = new OptionDescriptionWidget(
                    () -> new ScreenRectangle(
                            width / 3 * 2 + padding,
                            tabArea.top() + padding,
                            paddedWidth,
                            searchField.getY() - 1 - tabArea.top() - padding * 2
                    ),
                    null
            );

            updateButtons();
        }

        @Override
        public Component getTabTitle() {
            return category.name();
        }

        @Override
        public void visitChildren(Consumer<net.minecraft.client.gui.components.AbstractWidget> consumer) {
            consumer.accept(optionList);
            consumer.accept(saveFinishedButton);
            consumer.accept(cancelResetButton);
            consumer.accept(undoButton);
            consumer.accept(searchField);
            consumer.accept(descriptionWidget);
        }

        @Override
        public void doLayout(ScreenRectangle screenRectangle) {

        }

        @Override
        public void tick() {
            updateButtons();
            searchField.tick();
        }

        private void updateButtons() {
            boolean pendingChanges = pendingChanges();

            undoButton.active = pendingChanges;
            saveFinishedButton.setMessage(pendingChanges ? Component.translatable("yacl.gui.save") : GuiUtils.translatableFallback("yacl.gui.done", CommonComponents.GUI_DONE));
            saveFinishedButton.setTooltip(Tooltip.create(pendingChanges ? Component.translatable("yacl.gui.save.tooltip") : Component.translatable("yacl.gui.finished.tooltip")));
            cancelResetButton.setMessage(pendingChanges ? GuiUtils.translatableFallback("yacl.gui.cancel", CommonComponents.GUI_CANCEL) : Component.translatable("controls.reset"));
            cancelResetButton.setTooltip(Tooltip.create(pendingChanges ? Component.translatable("yacl.gui.cancel.tooltip") : Component.translatable("yacl.gui.reset.tooltip")));
        }

        @Override
        public void close() {
            descriptionWidget.close();
        }
    }

    private class PlaceholderTab implements Tab {
        private final PlaceholderCategory category;

        public PlaceholderTab(PlaceholderCategory category) {
            this.category = category;
        }

        @Override
        public Component getTabTitle() {
            return category.name();
        }

        @Override
        public void visitChildren(Consumer<AbstractWidget> consumer) {
        }

        @Override
        public void doLayout(ScreenRectangle screenRectangle) {
            minecraft.setScreen(category.screen().apply(minecraft, YACLScreen.this));
        }
    }
}
