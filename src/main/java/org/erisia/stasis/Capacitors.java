package org.erisia.stasis;

import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Rift capacitors (λ-Nero 2026-10-17, notes/rifts/CAPACITOR-SPEC.md): crystal stores that take a stopped world's harm
 * meant for whoever carries them, up to their limit, and are vented with EA power. They take the mist surcharge on suit
 * drain and the freezing a Delver suit suffers in a deep. A capacitor in the inventory takes it all; failing that, a raw
 * rift shard takes half and crumbles after SHARD_SECONDS. (Rifts themselves stay unprotected: Baughn's rule.)
 */
public final class Capacitors {
    private Capacitors() {
    }

    /** Venting cost: J of EA charge per second of protection taken; the rate an EA charger vents at. */
    static final double J_PER_SECOND = 500, VENT_W = 2000;
    /** Seconds of protection: a capacitor, a charged one, a raw shard (at half strength). */
    static final double CAPACITOR_SECONDS = 600, CHARGED_SECONDS = 2400, SHARD_SECONDS = 60;
    // (seconds of one harm: a deep is two at once, the surcharge and the slow freeze, so there a capacitor lasts 5 min,
    // a charged one 20, a shard 30 s: λ-Nero's numbers)

    /** Seconds the player's current shard has given (a shard crumbles when it reaches SHARD_SECONDS). */
    private static final Map<UUID, Double> SHARD_USED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Take `seconds` of harm for the player: 1 if a capacitor took it all, 0.5 if a raw shard took half, 0 if nothing
     * did. Creative players need none.
     */
    static double absorb(ServerPlayer p, double seconds) {
        if (seconds <= 0) return 1;
        var inv = p.getInventory();
        double need = seconds * J_PER_SECOND;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof CapacitorItem c && c.getEnergy(s) >= need) {
                c.setEnergy(s, c.getEnergy(s) - need);
                return 1;
            }
        }
        // the worn battery belt's pockets (Baughn 2026-10-27)
        ItemStack belt = BatteryBeltItem.worn(p);
        if (!belt.isEmpty()) {
            var all = BatteryBeltItem.contents(belt);
            for (ItemStack s : all)
                if (s.getItem() instanceof CapacitorItem c && c.getEnergy(s) >= need) {
                    c.setEnergy(s, c.getEnergy(s) - need);
                    BatteryBeltItem.setContents(belt, all);
                    return 1;
                }
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.is(StasisRegistry.RIFT_SHARD.get())) continue;
            double used = SHARD_USED.merge(p.getUUID(), seconds, Double::sum);
            if (used >= SHARD_SECONDS) {
                SHARD_USED.remove(p.getUUID());
                s.shrink(1);
                p.sendOverlayMessage(net.minecraft.network.chat.Component.literal("A rift shard crumbles")
                    .withStyle(net.minecraft.ChatFormatting.DARK_PURPLE));
            }
            return 0.5;
        }
        return 0;
    }

    /** The suit's draw factor in weather that multiplies it, after what capacitors take of the surcharge. */
    static double drawFactor(ServerPlayer p, double factor) {
        if (factor <= 1) return factor;
        double extra = factor - 1;
        return 1 + extra * (1 - absorb(p, extra / 20.0));
    }
}
