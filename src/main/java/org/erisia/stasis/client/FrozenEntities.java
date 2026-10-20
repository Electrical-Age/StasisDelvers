package org.erisia.stasis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.erisia.stasis.Stasis;

/**
 * Entities in stopped land stand still on the client too (Baughn's playtest: zombie arms kept swaying; an item dropped
 * in mid-air fell, snapped back up to where the server holds it, and fell again). The server doesn't tick them; the
 * client used to, so their own physics and idle animations ran. Players are left alone (suited ones do move there).
 */
public final class FrozenEntities {
    private FrozenEntities() {
    }

    static void tick(EntityTickEvent.Pre e) {
        Entity en = e.getEntity();
        if (!en.level().isClientSide() || en instanceof Player) return;
        if (!frozen(en)) return;
        e.setCanceled(true);
        // held exactly still (no lerp between an old and a new position every frame: the judder and the pearl streaks);
        // a move the server makes (a push) still comes through, one tick's worth
        en.setOldPosAndRot();
        en.getInterpolation().interpolate();
    }

    /** Is this client-side entity frozen: never for unbound types; else as the server says (StasisRegistry.FROZEN), else
     *  in stopped land by the whole-block rule without eddies (they mustn't flicker)? */
    public static boolean frozen(Entity en) {
        var level = Minecraft.getInstance().level;
        if (level == null || en.level() != level || en instanceof Player || !Stasis.stops(level)) return false;
        // unbound types are never frozen; the server never sends them FROZEN (it stays at its default, false), so the
        // guess below held endermen still on the client while the server moved them (Argon; Baughn saw them frozen)
        if (en.typeHolder().is(Stasis.UNBOUND)) return false;
        if (en.hasData(org.erisia.stasis.StasisRegistry.FROZEN.get())) return en.getData(org.erisia.stasis.StasisRegistry.FROZEN.get());
        return !Stasis.isRunningSteady(level, en.blockPosition());
    }
}
