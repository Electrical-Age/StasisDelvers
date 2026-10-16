package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.erisia.stasis.StasisNet;

/**
 * The tower console (CONSOLE-PLAN.md C; Baughn: part of the bottom core block's look later, no separate block): the
 * field's state, charge, power, the radii, and the controls. The target radius goes from the tower's own size to
 * +25% of the preferred range, ruinously expensive above it. OVERRIDE turns the automatic safety off (no shrinking
 * below 60% charge): arm it, then confirm within 5 s.
 */
public final class ConsoleScreen extends Screen {
    final BlockPos pos;
    StasisNet.ConsoleState st;
    int ticks;
    long armedUntil;
    Button overrideButton;

    ConsoleScreen(BlockPos pos) {
        super(Component.literal("Stasis tower console"));
        this.pos = pos;
    }

    static void open(BlockPos pos) {
        Minecraft.getInstance().gui.setScreen(new ConsoleScreen(pos));
        ClientPacketDistributor.sendToServer(new StasisNet.ConsoleQuery(pos));
    }

    static void receive(StasisNet.ConsoleState s) {
        if (Minecraft.getInstance().gui.screen() instanceof ConsoleScreen c && c.pos.equals(s.pos())) c.st = s;
    }

    /** Client init: right-clicks on a core and the server's readouts come here. */
    public static void hook() {
        StasisNet.consoleOpener = ConsoleScreen::open;
        StasisNet.consoleSink = ConsoleScreen::receive;
    }

    double v(int i) {
        return st == null ? 0 : st.v().get(i);
    }

    void set(double target, boolean override) {
        ClientPacketDistributor.sendToServer(new StasisNet.ConsoleSet(pos, target, override));
    }

    double target() {
        return v(9);
    }

    @Override
    protected void init() {
        int x = width / 2 - 150, y = height / 2 + 30;
        addRenderableWidget(Button.builder(Component.literal("-5"), b -> set(target() - 5, v(12) > 0)).bounds(x, y, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("-1"), b -> set(target() - 1, v(12) > 0)).bounds(x + 34, y, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+1"), b -> set(target() + 1, v(12) > 0)).bounds(x + 68, y, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("+5"), b -> set(target() + 5, v(12) > 0)).bounds(x + 102, y, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Preferred"), b -> set(Double.NaN, v(12) > 0)).bounds(x + 136, y, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Minimum"), b -> set(v(7), v(12) > 0)).bounds(x + 210, y, 90, 20).build());
        overrideButton = addRenderableWidget(Button.builder(Component.literal("OVERRIDE"), b -> pressOverride())
            .bounds(x, y + 28, 300, 20).build());
    }

    void pressOverride() {
        long now = System.currentTimeMillis();
        if (v(12) > 0) {   // on: turning it off needs no confirmation
            set(target(), false);
            return;
        }
        if (now < armedUntil) {
            set(target(), true);
            armedUntil = 0;
        } else armedUntil = now + 5000;
    }

    @Override
    public void tick() {
        if (++ticks % 10 == 0) ClientPacketDistributor.sendToServer(new StasisNet.ConsoleQuery(pos));
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 8 * 8) onClose();
        if (overrideButton != null) {
            boolean on = v(12) > 0, armed = System.currentTimeMillis() < armedUntil;
            overrideButton.setMessage(Component.literal(on ? "OVERRIDE IS ON: the safety will not shrink the field (press to restore)"
                : armed ? "CONFIRM OVERRIDE: the field will not save itself and collapses when the store runs dry"
                : "OVERRIDE (disables the automatic safety)"));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        int x = width / 2 - 150, y = height / 2 - 92;
        // the panel first, then the buttons on it (drawn after, it greyed them out: AdventurAgent's stills)
        g.fill(x - 8, y - 8, x + 308, height / 2 + 86, 0xD0101018);
        super.extractRenderState(g, mouseX, mouseY, a);
        g.text(font, "STASIS TOWER", x, y, 0xFFB8A8E8);
        if (st == null) {
            g.text(font, "...", x, y + 14, 0xFFA0A0A0);
            return;
        }
        String[] states = {"OFF (needs half a store to start)", "FIELD UP", "COLLAPSING"};
        int s = (int) v(0);
        g.text(font, states[Math.max(0, Math.min(2, s))], x + 90, y, s == 2 ? 0xFFFF5050 : s == 1 ? 0xFF70E070 : 0xFFA0A0A0);
        // charge bar, with the safety's 60% mark
        double share = v(1) / Math.max(1, v(2));
        int by = y + 16;
        g.fill(x, by, x + 300, by + 10, 0xFF202028);
        g.fill(x, by, x + (int) (300 * Math.max(0, Math.min(1, share))), by + 10, share < 0.25 ? 0xFFD05030 : share < 0.6 ? 0xFFD0A030 : 0xFF50A0D0);
        g.fill(x + 180, by - 2, x + 181, by + 12, 0xFFFFFFFF);
        g.text(font, String.format("Charge %.0f%%  (%.0f of %.0f kJ)", 100 * share, v(1) / 1000, v(2) / 1000), x, by + 14, 0xFFE0E0E0);
        double in = v(3), draw = s == 1 ? v(4) : 0, net = in - draw;
        g.text(font, String.format("Power in %.0f W, draw %.0f W%s, net %+.0f W", in, draw, v(5) > 1.001 ? String.format(" (x%.2f)", v(5)) : "", net),
            x, by + 26, net < 0 ? 0xFFE0A060 : 0xFFE0E0E0);
        if (net < 0 && s == 1) g.text(font, String.format("Empty in %s", time(v(1) / -net)), x + 220, by + 26, 0xFFE0A060);
        g.text(font, String.format("Radius %.0f now, goal %.0f, target %.0f", v(10), v(11), v(9)), x, by + 42, 0xFFE0E0E0);
        g.text(font, String.format("Preferred %.0f, range %.0f to %.0f (above %.0f: ruinous draw)", v(6), v(7), v(8), v(6)), x, by + 54, 0xFFA0A0B0);
        if (v(13) > 0) g.text(font, "Target set by signal", x + 200, by + 42, 0xFF80C0FF);
        boolean on = v(12) > 0;
        g.text(font, on ? "SAFETY OFF (OVERRIDE)" : share < 0.6 ? "Safety: shrinking to save power" : "Safety: on (shrinks below 60%)",
            x, by + 68, on ? 0xFFFF4040 : share < 0.6 ? 0xFFE0C060 : 0xFF70C070);
        g.text(font, st.text().length() > 60 ? st.text().substring(0, 60) + "..." : st.text(), x, by + 80, 0xFF909098);
    }

    static String time(double s) {
        if (s > 86400) return ">1 day";
        if (s > 3600) return String.format("%.1f h", s / 3600);
        return String.format("%d:%02d", (int) s / 60, (int) s % 60);
    }
}
