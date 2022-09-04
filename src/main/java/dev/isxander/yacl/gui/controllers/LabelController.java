package dev.isxander.yacl.gui.controllers;

import dev.isxander.yacl.api.Controller;
import dev.isxander.yacl.api.Option;
import dev.isxander.yacl.api.utils.Dimension;
import dev.isxander.yacl.gui.AbstractWidget;
import dev.isxander.yacl.gui.YACLScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import org.jetbrains.annotations.ApiStatus;

/**
 * Simply renders some text as a label.
 */
public class LabelController implements Controller<Text> {
    private final Option<Text> option;
    /**
     * Constructs a label controller
     *
     * @param option bound option
     */
    public LabelController(Option<Text> option) {
        this.option = option;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Option<Text> option() {
        return option;
    }

    @Override
    public Text formatValue() {
        return option().pendingValue();
    }

    @Override
    public AbstractWidget provideWidget(YACLScreen screen, Dimension<Integer> widgetDimension) {
        return new LabelControllerElement(widgetDimension);
    }

    @ApiStatus.Internal
    public class LabelControllerElement extends AbstractWidget {

        public LabelControllerElement(Dimension<Integer> dim) {
            super(dim);
        }

        @Override
        public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
            textRenderer.drawWithShadow(matrices, formatValue(), dim.x(), dim.centerY() - textRenderer.fontHeight / 2f, -1);
        }
    }
}