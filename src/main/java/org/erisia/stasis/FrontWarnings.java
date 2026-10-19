package org.erisia.stasis;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Spreading mode's front warnings (spread-DESIGN.md step 3; Baughn 2026-10-19): once a second each overworld player in
 * running spread land hears, on the action bar, how long until the stillness reaches the spot they stand on. The
 * estimate is Spread.secondsUntilStill: the online time until this column's rank falls below P (Argon: time, not
 * distance to the front, so a dead-end cave whose mouth stills first is warned by its own column). At the 80 % cap
 * before hard mode P stands still, so nothing is warned there; hard mode moves it again.
 * <p>
 * Once per threshold crossing (10, 5, 2, 1 min), then a countdown each second under 30 s. Players with a working suit
 * (Suits.protectsWithoutDraw) get only one note at 30 s; creative and spectator players, and those inside a field or
 * the spawn area (Stasis.heldOff: they keep running when the column stills), none. Where time doesn't run here now
 * (stilled land, a rift, a drifting deep, an eddy) Freezing owns the action bar, so nothing is sent.
 */
public final class FrontWarnings {
    private FrontWarnings() {
    }

    /** Thresholds in online seconds; the last (30 s) starts the countdown. */
    static final int[] THRESHOLDS = {600, 300, 120, 60, 30};
    static final int COUNTDOWN = THRESHOLDS.length - 1;
    /** A mark is forgotten (so a later approach warns again) once the estimate is this far over its threshold. */
    static final double RESET = 1.5;

    private static final class State {
        /** The lowest threshold announced (index into THRESHOLDS), -1 none. */
        int mark = -1;
        /** For tests: the last message sent, and how many. */
        String last;
        int sent;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    /** The threshold the estimate is within (the highest index with secs <= THRESHOLDS[i]), -1 above them all. */
    static int level(double secs) {
        int i = -1;
        while (i + 1 < THRESHOLDS.length && secs <= THRESHOLDS[i + 1]) i++;
        return i;
    }

    /** ServerTickEvent.Post: every 20 ticks, each overworld player. */
    static void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post e) {
        MinecraftServer srv = e.getServer();
        if (srv.getTickCount() % 20 != 7) return;
        if (!StasisConfig.spreading) {
            STATES.clear();
            return;
        }
        ServerLevel ow = srv.overworld();
        Set<UUID> here = new HashSet<>();
        for (ServerPlayer p : ow.players()) {
            here.add(p.getUUID());
            check(p);
        }
        STATES.keySet().retainAll(here);   // logged out or left the overworld: start fresh
    }

    /** One check of one player (public for tests). */
    public static void check(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level) || !Spread.active(level) || p.isCreative() || p.isSpectator()) {
            STATES.remove(p.getUUID());
            return;
        }
        // time doesn't run here now (stilled, a rift, a drifting deep): Freezing's messages own the action bar
        if (!Stasis.spreadRuns(level, p.getBlockX(), p.getBlockZ()) || !Stasis.isRunning(level, p.blockPosition())) {
            STATES.remove(p.getUUID());
            return;
        }
        State s = STATES.computeIfAbsent(p.getUUID(), u -> new State());
        if (Stasis.heldOff(level, p.getBlockX(), p.getBlockY(), p.getBlockZ())) return;   // a field keeps this spot running
        double secs = Spread.secondsUntilStill(level.getServer(), p.getBlockX(), p.getBlockZ());
        if (Double.isNaN(secs)) return;
        int lv = level(secs);
        if (s.mark >= 0 && lv < s.mark && secs > THRESHOLDS[s.mark] * RESET) s.mark = lv;   // moved to safer ground
        if (lv < 0) return;
        boolean suited = Suits.protectsWithoutDraw(p);
        if (lv == COUNTDOWN) {
            if (!suited) {
                send(p, s, "The stillness reaches this spot in " + (int) Math.ceil(secs) + " s", ChatFormatting.RED);
            } else if (s.mark < COUNTDOWN) {
                send(p, s, "The stillness reaches this spot in about " + (int) Math.ceil(secs) + " s: your suit will take over", ChatFormatting.GOLD);
            }
            s.mark = COUNTDOWN;
        } else if (lv > s.mark && !suited) {
            int min = (int) Math.ceil(secs / 60);
            send(p, s, "The stillness reaches this spot in about " + min + (min == 1 ? " minute" : " minutes"), ChatFormatting.GOLD);
            s.mark = lv;
        }
    }

    private static void send(ServerPlayer p, State s, String text, ChatFormatting style) {
        p.sendOverlayMessage(Component.literal(text).withStyle(style));
        s.last = text;
        s.sent++;
    }

    /** For tests: "sent N: last message" (or "none"). */
    public static String describe(ServerPlayer p) {
        State s = STATES.get(p.getUUID());
        return s == null || s.sent == 0 ? "none" : "sent " + s.sent + ": " + s.last;
    }

    /** For tests: forget the player's marks. */
    public static void testForget(ServerPlayer p) {
        STATES.remove(p.getUUID());
    }
}
