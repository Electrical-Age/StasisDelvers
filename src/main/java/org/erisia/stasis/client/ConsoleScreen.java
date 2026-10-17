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
    Button overrideButton, feedButton;
    final java.util.List<Button> targetButtons = new java.util.ArrayList<>();
    /** The last target we asked for, until the server's readout catches up (a quick second +5 was lost). */
    double pending = Double.NaN;
    long pendingUntil;

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
        if (!Double.isNaN(target)) target = Math.max(v(7), Math.min(v(8), target));
        pending = target;
        pendingUntil = System.currentTimeMillis() + 1500;
        ClientPacketDistributor.sendToServer(new StasisNet.ConsoleSet(pos, target, override));
    }

    double target() {
        return System.currentTimeMillis() < pendingUntil && !Double.isNaN(pending) ? pending : v(9);
    }

    @Override
    protected void init() {
        int x = width / 2 - 150, y = height / 2 + 46;   // (below the text: long help lines ran into the buttons)
        targetButtons.clear();
        targetButtons.add(addRenderableWidget(Button.builder(Component.literal("-5"), b -> set(target() - 5, v(12) > 0)).bounds(x, y, 30, 20).build()));
        targetButtons.add(addRenderableWidget(Button.builder(Component.literal("-1"), b -> set(target() - 1, v(12) > 0)).bounds(x + 34, y, 30, 20).build()));
        targetButtons.add(addRenderableWidget(Button.builder(Component.literal("+1"), b -> set(target() + 1, v(12) > 0)).bounds(x + 68, y, 30, 20).build()));
        targetButtons.add(addRenderableWidget(Button.builder(Component.literal("+5"), b -> set(target() + 5, v(12) > 0)).bounds(x + 102, y, 30, 20).build()));
        targetButtons.add(addRenderableWidget(Button.builder(Component.literal("Preferred"), b -> set(Double.NaN, v(12) > 0)).bounds(x + 136, y, 70, 20).build()));
        targetButtons.add(addRenderableWidget(Button.builder(Component.literal("Minimum"), b -> set(v(7), v(12) > 0)).bounds(x + 210, y, 90, 20).build()));
        overrideButton = addRenderableWidget(Button.builder(Component.literal("OVERRIDE"), b -> pressOverride())
            .bounds(x, y + 28, 300, 20).build());
        // (Baughn: charging a tower from your suit, e.g. to start a dead one)
        feedButton = addRenderableWidget(Button.builder(Component.literal("Feed from your suit"),
            b -> ClientPacketDistributor.sendToServer(new StasisNet.ConsoleFeed(pos))).bounds(x, y + 52, 300, 20).build());
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
        } else armedUntil = now + 8000;
    }

    @Override
    public void tick() {
        if (++ticks % 10 == 0) ClientPacketDistributor.sendToServer(new StasisNet.ConsoleQuery(pos));
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 8 * 8) onClose();
        if (overrideButton != null) {
            boolean on = v(12) > 0, armed = System.currentTimeMillis() < armedUntil;
            overrideButton.setMessage(Component.literal(on ? "OVERRIDE ON - press to restore the safety"
                : armed ? "CONFIRM OVERRIDE: press again" : "OVERRIDE: disable the safety"));
        }
        // a signal sets the target: the buttons step aside (they were silently ignored); a locked tower keeps its settings
        boolean locked = st != null && st.v().size() > 17 && v(17) > 0;
        for (Button b : targetButtons) b.active = v(13) <= 0 && !locked;
        if (overrideButton != null) overrideButton.active = !locked;
        if (feedButton != null) {
            boolean feeding = st != null && st.v().size() > 18 && v(18) > 0;
            feedButton.setMessage(Component.literal(feeding ? String.format("FEEDING: %.1f kJ so far - press to stop", v(19))
                : "Feed from your suit"));
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
        g.fill(x - 8, y - 8, x + 308, height / 2 + 126, 0xD0101018);
        super.extractRenderState(g, mouseX, mouseY, a);
        g.text(font, "STASIS TOWER", x, y, 0xFFB8A8E8);
        if (st == null) {
            g.text(font, "...", x, y + 14, 0xFFA0A0A0);
            return;
        }
        String[] states = {"OFF", "FIELD UP", "COLLAPSING"};
        int s = (int) v(0);
        g.text(font, states[Math.max(0, Math.min(2, s))], x + 90, y, s == 2 ? 0xFFFF5050 : s == 1 ? 0xFF70E070 : 0xFFA0A0A0);
        // charge bar, with the safety's 60% mark
        double share = v(1) / Math.max(1, v(2));
        int by = y + 24;   // (room for the 15/50/80% labels under the title)
        g.fill(x, by, x + 300, by + 10, 0xFF202028);
        g.fill(x, by, x + (int) (300 * Math.max(0, Math.min(1, share))), by + 10, share < 0.15 ? 0xFFD05030 : share < 0.5 ? 0xFFD0A030 : 0xFF50A0D0);
        // the safety's marks: the last ditch (15%) and where a retreated field grows back (80%)
        g.fill(x + 45, by - 2, x + 46, by + 12, 0xFFFF8060);
        g.fill(x + 240, by - 2, x + 241, by + 12, 0xFFFFFFFF);
        g.fill(x + 150, by - 2, x + 151, by + 12, 0xFF60E060);   // (Baughn: green at 50%, where a dead tower starts)
        g.text(font, "15%", x + 37, by - 10, 0xFFB0B0B0);
        g.text(font, "50%", x + 142, by - 10, 0xFF80E080);
        g.text(font, "80%", x + 232, by - 10, 0xFFB0B0B0);
        g.text(font, String.format("Charge %.0f%%: %.0f of %.0f kJ", 100 * share, v(1) / 1000, v(2) / 1000), x, by + 14, 0xFFE0E0E0);
        double in = v(3), draw = s == 1 ? v(4) : 0, net = in - draw;
        String power = String.format("Power in %.0f W, draw %.0f W%s, net %+.0f W", in, draw, v(5) > 1.001 ? String.format(" x%.2f", v(5)) : "", net);
        g.text(font, power, x, by + 26, net < 0 ? 0xFFE0A060 : 0xFFE0E0E0);
        // (after the power line, however long it is: the x1.04 pushed it into "Empty in", AdventurAgent)
        if (net < 0 && s == 1) g.text(font, String.format("Empty in %s", time(v(1) / -net)), x + Math.max(220, font.width(power) + 12), by + 26, 0xFFE0A060);
        g.text(font, String.format("Radius %.0f now, goal %.0f, target %.0f%s", v(10), v(11), v(9), v(13) > 0 ? " by signal" : ""), x, by + 42,
            v(13) > 0 ? 0xFF80C0FF : 0xFFE0E0E0);
        g.text(font, String.format("Preferred %.0f, range %.0f to %.0f", v(6), v(7), v(8)), x, by + 54, 0xFFA0A0B0);
        boolean on = v(12) > 0;
        boolean armed = System.currentTimeMillis() < armedUntil;
        // (wrapped to the panel: long lines ran off its edge, AdventurAgent)
        boolean retreat = st.v().size() > 14 && v(14) > 0;
        double carry = st.v().size() > 15 ? v(15) : -1, strainNow = st.v().size() > 16 ? v(16) : 0;
        String sustain = carry > 0 ? String.format("its power carries r %.0f", carry) : "its power carries none of it";
        String safety = on ? "SAFETY OFF: no retreat; it collapses when the store runs dry"
            : armed ? "Confirm: the field will not save itself and collapses when the store runs dry"
            : retreat ? String.format("SAFETY RETREAT to r %.0f to recharge; back to r %.0f at 80%%", v(11), v(9))
            : strainNow > 0.05 ? String.format("FAILING, strain %.0f%%: %s", 100 * strainNow, sustain)
            : "Safety: on";
        int ly = by + 68;
        for (var line : font.split(Component.literal(safety), 300)) {
            g.text(font, line, x, ly, on || armed ? 0xFFFF4040 : retreat || strainNow > 0.05 ? 0xFFE0A040 : 0xFF70C070);
            ly += 10;
        }
        if (st.v().size() > 17 && v(17) > 0) {
            g.text(font, "LOCKED: the settings can't be changed here", x, ly, 0xFFB0B0FF);
            ly += 10;
        }
        // (a refused or disallowed layout in red: Baughn missed it in grey)
        boolean bad = st.text().startsWith("Tower layout not allowed") || st.text().startsWith("Tower refused") || st.text().startsWith("Tower could not");
        int n = 0;
        for (var line : font.split(Component.literal(st.text()), 300)) {
            if (n++ == 3) break;   // (at most three lines: the buttons are below)
            g.text(font, line, x, ly + 2, bad ? 0xFFFF5050 : 0xFF909098);
            ly += 10;
        }
    }

    static String time(double s) {
        if (s > 86400) return ">1 day";
        if (s > 3600) return String.format("%.1f h", s / 3600);
        return String.format("%d:%02d", (int) s / 60, (int) s % 60);
    }
}
