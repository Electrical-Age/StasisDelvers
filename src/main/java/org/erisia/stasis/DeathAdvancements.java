package org.erisia.stasis;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

/**
 * Deaths to rift fauna, as hidden advancements (Baughn: one for being killed by each mob, sarcastic and snappy, fitting
 * the death message; a possessed cow is still a cow): data/stasisdelvers/advancement/deaths. They are awarded here, as
 * "possessed" isn't something an advancement's conditions can see.
 */
public final class DeathAdvancements {
    private DeathAdvancements() {
    }

    static void died(net.neoforged.neoforge.event.entity.living.LivingDeathEvent e) {
        // your own possessed statue, killed by you (λ-Nero: "It wears what you were, not what you are")
        if (e.getEntity() instanceof PossessedStatue ps && e.getSource().getEntity() instanceof ServerPlayer killer
            && ps.owner().equals(killer.getUUID())) award(killer, "were_then");
        if (!(e.getEntity() instanceof ServerPlayer p) || !(p.level() instanceof ServerLevel level)) return;
        var by = e.getSource().getEntity();
        if (by instanceof PossessedStatue ps && ps.owner().equals(p.getUUID())) award(p, "self_made");
        else if (by instanceof Mob m && Possession.possessed(m)) award(p, hostKey(m.getType()));
    }

    /** The per-animal ones, else the catch-all. */
    static String hostKey(EntityType<?> t) {
        if (t == net.minecraft.world.entity.EntityTypes.COW) return "cow";
        if (t == net.minecraft.world.entity.EntityTypes.PIG) return "pig";
        if (t == net.minecraft.world.entity.EntityTypes.SHEEP) return "sheep";
        if (t == net.minecraft.world.entity.EntityTypes.CHICKEN) return "chicken";
        return "puppet_show";
    }

    /** Freezing.freeze, before the fauna near a fresh statue go: frozen with Static feeding on the suit. */
    static void freezing(ServerLevel level, ServerPlayer p) {
        award(p, "end_of_universe");   // (Baughn: froze up completely and respawned; a deep's hold, let pass, isn't this)
        // in the weather that wants the whole suit, wearing part of one (λ-Nero's "It's Damp")
        if (Suits.missing(p) > 0 && Suits.missing(p) < 4 && deepWeather(level, p)) award(p, "its_damp");
        if (RiftStatic.feeding(level, p) > 0) award(p, "low_battery");
    }

    /** Mist, a deep, a rift's outer zone or a storm where the player stands (where a partial suit isn't enough). */
    static boolean deepWeather(ServerLevel level, ServerPlayer p) {
        int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
        if (StaticDeeps.zone(level, x, y, z) >= 1 || Deeps.at(level, x, y, z) > Deeps.THRESHOLD || Storms.at(level, x, y, z) > 0) return true;
        var w = Pockets.at(level, x, y, z);
        return w != null && w.state() != Pockets.State.CLEAR;
    }

    public static void award(ServerPlayer p, String key) {
        var server = p.level().getServer();
        var adv = server.getAdvancements().get(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "deaths/" + key));
        if (adv != null) p.getAdvancements().award(adv, "done");
    }
}
