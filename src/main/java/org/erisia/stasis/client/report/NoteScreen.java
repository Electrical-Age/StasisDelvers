package org.erisia.stasis.client.report;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Shift + bug-report key: one line, "What went wrong?" (SPEC 5.2 player_note). Opened after the screenshot was taken;
 * the report is packaged when it closes (Done: the text; Cancel or Esc: no note). done gets called exactly once.
 */
final class NoteScreen extends Screen {
    static final int MAX = 500;
    private final Consumer<String> done;
    private EditBox box;
    private String result;
    private boolean called;

    NoteScreen(Consumer<String> done) {
        super(Component.literal("Bug report"));
        this.done = done;
    }

    @Override
    protected void init() {
        int w = Math.min(320, width - 20), x = (width - w) / 2, y = height / 2 - 20;
        String old = box == null ? "" : box.getValue();
        box = new EditBox(font, x, y, w, 20, Component.literal("What went wrong?"));
        box.setMaxLength(MAX);
        box.setValue(old);
        box.setHint(Component.literal("What went wrong? (optional)"));
        addRenderableWidget(box);
        addRenderableWidget(Button.builder(Component.literal("Save report"), b -> submit()).bounds(x, y + 28, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("No note"), b -> onClose()).bounds(x + w / 2 + 2, y + 28, w / 2 - 2, 20).build());
        setInitialFocus(box);
    }

    void submit() {
        String s = box == null ? "" : box.getValue().strip();
        result = s.isEmpty() ? null : s;
        onClose();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) {
            submit();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void removed() {
        super.removed();
        if (!called) {
            called = true;
            done.accept(result);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int w = Math.min(320, width - 20), x = (width - w) / 2, y = height / 2 - 20;
        g.fill(x - 8, y - 22, x + w + 8, y + 56, 0xD0101018);
        super.extractRenderState(g, mouseX, mouseY, a);
        g.text(font, "Bug report: what went wrong? (Enter saves)", x, y - 14, 0xFFE0E0E0);
    }
}
