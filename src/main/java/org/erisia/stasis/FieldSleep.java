package org.erisia.stasis;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Sleep where time stops (Baughn 2026-10-17): no sleeping in stopped land (no night passes there); in a field, night
 * and day are the field's own clock (LocalTime), and when every player inside a field sleeps, only that field's clock
 * moves on to the next morning. The world's clock never skips. Field time is visual (sky, sun, moon, light); the
 * world's rules (mobs, daylight sensors) still follow the world's clock.
 */
public final class FieldSleep {
    private FieldSleep() {
    }

    static final Player.BedSleepingProblem STOPPED = new Player.BedSleepingProblem(
        Component.literal("Time stands still here: there is no night to sleep through"));
    static final Player.BedSleepingProblem FIELD_DAY = new Player.BedSleepingProblem(
        Component.translatable("block.minecraft.bed.no_sleep"));

    /** The field a player is in (the fastest that holds them, as Stasis.rateAt: a rift quern inside a tower's field
     *  doesn't take its night; Argon), or null. */
    static @Nullable Field fieldAt(ServerLevel level, double x, double y, double z) {
        Field best = null;
        for (Field f : FieldData.get(level).fields())
            if (f.containsPoint(x, y, z) && (best == null || f.rate() > best.rate())) best = f;
        return best;
    }

    /** Vanilla's "you can only sleep at night" (the bed rule's dark check), which follows the world's clock. */
    static boolean isNightProblem(Player.@Nullable BedSleepingProblem p) {
        return p != null && p.message() != null && p.message().getContents() instanceof TranslatableContents t
            && t.getKey().equals("block.minecraft.bed.no_sleep");
    }

    static void canSleep(CanPlayerSleepEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !Stasis.stops(level)) return;
        var p = e.getProblem();
        if (p != null && !isNightProblem(p)) return;   // (too far, obstructed, ...: vanilla's word stands)
        var pos = e.getPos();
        // land the spreading stillness hasn't reached runs on the world's clock, fields or not: vanilla's night, unless a
        // drifting deep stops it there
        if (Stasis.spreadRuns(level, pos.getX(), pos.getZ())) {
            if (!Stasis.isRunning(level, pos)) e.setProblem(STOPPED);
            return;
        }
        Field f = fieldAt(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (f == null) {
            if (!Stasis.isRunning(level, pos)) e.setProblem(STOPPED);
            return;   // (running land without a field, the spawn area: the world's night, vanilla's rule)
        }
        // night in a field is the field's own
        e.setProblem(LocalTime.night(LocalTime.fieldClock(level, f)) ? null : FIELD_DAY);
    }

    static void canContinue(CanContinueSleepingEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || !(sp.level() instanceof ServerLevel level) || !Stasis.stops(level)) return;
        if (e.getProblem() != null && e.getProblem() != Player.BedSleepingProblem.OTHER_PROBLEM) return;   // (no bed: wake)
        if (Stasis.spreadRuns(level, sp.getBlockX(), sp.getBlockZ())) return;   // (running spread land: vanilla's night)
        Field f = fieldAt(level, sp.getX(), sp.getY(), sp.getZ());
        if (f != null) e.setContinueSleeping(LocalTime.night(LocalTime.fieldClock(level, f)));
    }

    /** Before the world's own sleep check: a field whose players all sleep deeply wakes them to its next morning. */
    static void tick(LevelTickEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel level) || !Stasis.stops(level)) return;
        for (Field f : FieldData.get(level).fields()) {
            // a field on running spread land keeps the world's clock: the world's own sleep wakes its sleepers
            if (Stasis.spreadRuns(level, (int) Math.floor(f.x()), (int) Math.floor(f.z()))) continue;
            List<ServerPlayer> in = new ArrayList<>();
            boolean all = true;
            for (ServerPlayer sp : level.players()) {
                if (sp.isSpectator() || !f.containsPoint(sp.getX(), sp.getY(), sp.getZ())) continue;
                in.add(sp);
                all &= sp.isSleepingLongEnough();
            }
            if (in.isEmpty() || !all) continue;
            double c = LocalTime.fieldClock(level, f);
            double morning = Math.floor(c / LocalTime.DAY) * LocalTime.DAY + LocalTime.DAY;   // (next day, 06:00)
            LocalTime.setFieldClock(level, f, morning);
            for (ServerPlayer sp : in) sp.stopSleepInBed(false, true);
            StasisDelvers.LOG.info("Field {}: {} slept through its night", f.id(), in.size());
        }
    }

    /**
     * The world's clock never skips a night where time stops (each field sleeps on its own clock). In spreading mode
     * the overworld mostly runs until the spread is complete: there the world's night passes as in vanilla.
     */
    static void noWorldSkip(SleepFinishedTimeEvent e) {
        if (e.getLevel() instanceof ServerLevel level && Stasis.stops(level) && !Spread.active(level)) e.setCanceled(true);
    }
}
