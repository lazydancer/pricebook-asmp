package com.asmp.pricebook.config;

import com.asmp.pricebook.Pricebook;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class PricebookConfigScreen extends Screen {
    private final Screen parent;
    private ButtonWidget enabledButton;

    public PricebookConfigScreen(Screen parent) {
        super(Text.literal("Pricebook ASMP Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int buttonWidth = 160;
        int buttonHeight = 20;
        int y = this.height / 3;

        this.enabledButton = ButtonWidget.builder(enabledLabel(), button -> toggleEnabled())
                .dimensions(centerX - (buttonWidth / 2), y, buttonWidth, buttonHeight)
                .build();
        addDrawableChild(this.enabledButton);

        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.done"), button -> close())
                .dimensions(centerX - (buttonWidth / 2), y + 32, buttonWidth, buttonHeight)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 40, 0xFFFFFF);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private void toggleEnabled() {
        ModConfig config = Pricebook.config();
        config.enabled = !config.enabled;
        config.save();
        Pricebook.onConfigUpdated();
        if (this.enabledButton != null) {
            this.enabledButton.setMessage(enabledLabel());
        }
    }

    private Text enabledLabel() {
        return Pricebook.config().enabled
                ? Text.literal("Enabled: ON")
                : Text.literal("Enabled: OFF");
    }
}
