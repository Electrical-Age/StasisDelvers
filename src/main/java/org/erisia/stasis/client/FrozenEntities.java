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
        if (frozen(en)) e.setCanceled(true);
    }

    /** Is this client-side entity in stopped land (whole-block rule, no eddies: they mustn't flicker)? */
    public static boolean frozen(Entity en) {
        var level = Minecraft.getInstance().level;
        if (level == null || en.level() != level || en instanceof Player || !Stasis.stops(level)) return false;
        return !Stasis.isRunningSteady(level, en.blockPosition());
    }
}
