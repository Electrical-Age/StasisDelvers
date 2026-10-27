package org.erisia.stasis.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import org.erisia.stasis.BeltMenu;

/** The battery belt's pockets (BeltMenu): a plain panel, four slots, the inventory. */
public class BeltScreen extends AbstractContainerScreen<BeltMenu> {
    static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");

    public BeltScreen(BeltMenu menu, Inventory inv, Component title) {
        super(menu, inv, title, 176, 133);
        inventoryLabelY = BeltMenu.INV_Y - 11;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        super.extractBackground(g, mouseX, mouseY, a);
        int x = leftPos, y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xFF555555);
        g.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xFFC6C6C6);
        for (var s : menu.slots) g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, x + s.x - 1, y + s.y - 1, 18, 18);
    }
}
