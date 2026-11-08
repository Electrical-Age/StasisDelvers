package org.erisia.stasis;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Occultism's dawn and dusk rituals inside a field (λ-Nero 2026-11-08: they moved only the world's time). The ritual's
 * djinni works the world's clock round to the next dawn (or dusk) and then dies out of the world; when it does, every
 * field holding it moves its own clock (LocalTime) forward to that time of day too, at once (it would only have skewed
 * slowly towards the world's). Read from the djinni's saved job (Occultism's ChangeTimeJob: factoryId occultism:day_time
 * or night_time, newTime, and its tick counts, read every 5 ticks while it works, since the spirit drops its job before
 * it dies; a djinni killed before it finished, or a ritual that config disables, changes nothing), so Stasis needs no
 * Occultism classes.
 */
public final class RitualTime {
    private RitualTime() {
    }

    /** The last job seen on each working djinni (the spirit drops its job before it dies, so it's read while it works). */
    static final java.util.Map<java.util.UUID, CompoundTag> JOBS = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean djinni(net.minecraft.world.entity.Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("occultism:djinni");
    }

    /** Every 5 ticks, a djinni's job if it's a time one. */
    static void tick(net.neoforged.neoforge.event.tick.EntityTickEvent.Post e) {
        var en = e.getEntity();
        if (en.tickCount % 5 != 0 || !(en.level() instanceof ServerLevel level) || !djinni(en) || !Stasis.stops(level)) return;
        CompoundTag job = job(en, level);
        String f = job == null ? "" : job.getStringOr("factoryId", "");
        if (f.equals("occultism:day_time") || f.equals("occultism:night_time")) JOBS.put(en.getUUID(), job);
    }

    static void left(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent e) {
        if (djinni(e.getEntity())) JOBS.remove(e.getEntity().getUUID());
    }

    static void died(LivingDeathEvent e) {
        if (!(e.getEntity().level() instanceof ServerLevel level) || !Stasis.stops(level) || !djinni(e.getEntity())) return;
        CompoundTag job = JOBS.remove(e.getEntity().getUUID());
        if (Boolean.getBoolean("stasis.debugRitual")) StasisDelvers.LOG.info("RitualTime: djinni died ({}), job {}", e.getSource().getMsgId(), job);
        if (job == null) return;
        String f = job.getStringOr("factoryId", "");
        // the job ends with the djinni dying "out of the world"; killed any other way, or long before its end (a disabled
        // ritual ends at once), the world's time didn't get there either
        int done = job.getIntOr("currentChangeTicks", 0), need = job.getIntOr("requiredChangeTicks", Integer.MAX_VALUE);
        if (!e.getSource().getMsgId().equals("outOfWorld") || done < need - 6) return;
        int n = apply(level, e.getEntity().getX(), e.getEntity().getY(), e.getEntity().getZ(), job.getLongOr("newTime", 0));
        if (n > 0) StasisDelvers.LOG.info("Occultism {} ritual at {}: moved {} field clock(s) to time of day {}", f, e.getEntity().blockPosition().toShortString(), n,
            (long) LocalTime.wrap(job.getLongOr("newTime", 0)));
    }

    /** Every field holding (x, y, z) moves its clock forward to newTime's time of day. Returns how many. Public for tests. */
    public static int apply(ServerLevel level, double x, double y, double z, long newTime) {
        int n = 0;
        double tod = LocalTime.wrap(newTime);
        for (Field field : FieldData.get(level).fields()) {
            if (!field.containsPoint(x, y, z)) continue;
            double c = LocalTime.fieldClock(level, field);
            double ahead = LocalTime.wrap(tod - LocalTime.wrap(c));   // (forward to the next such time, as the ritual does)
            LocalTime.setFieldClock(level, field, c + ahead);
            n++;
        }
        return n;
    }

    static CompoundTag job(net.minecraft.world.entity.Entity e, ServerLevel level) {
        try {
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            e.saveWithoutId(out);
            return out.buildResult().getCompound("spiritJob").orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
