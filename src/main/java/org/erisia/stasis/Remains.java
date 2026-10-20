package org.erisia.stasis;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every player death leaves a {@link Statue} holding what would have dropped, not just a freeze (Baughn, msg
 * 1562051850273034272: he died in running land before hard mode and turned into items). Where time runs the statue
 * falls and lies there as remains; in stopped land it stands, as a frozen one does.
 * <ul>
 *   <li>The drops of any death (Curios slots and other mods' additions too) go into the statue: LivingDropsEvent at
 *       LOWEST, after everyone else has added theirs.</li>
 *   <li>A freeze already made its statue ({@link Freezing#freeze}, {@link #FREEZING}); what the death still drops
 *       (accessory slots) joins it.</li>
 *   <li>Experience: all of it, as a freeze keeps it (not vanilla's level x 7 capped at 100); no orbs.</li>
 *   <li>keepInventory: nothing drops, so no statue.</li>
 *   <li>The owner hears where it is when they respawn.</li>
 * </ul>
 */
public final class Remains {
    private Remains() {
    }

    /** The statue a freeze is making for this player, while its death runs (server thread). */
    static final Map<UUID, Statue> FREEZING = new ConcurrentHashMap<>();
    /** Where each player's latest remains were left (memory only), told at respawn. */
    private static final Map<UUID, String> WHERE = new ConcurrentHashMap<>();

    static boolean keeps(ServerLevel level) {
        return level.getGameRules().get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
    }

    /** No experience orbs from a player: the remains hold it all. */
    static void experience(LivingExperienceDropEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && !keeps(p.level())) e.setDroppedExperience(0);
    }

    static void drops(LivingDropsEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p) || p.isSpectator()) return;
        ServerLevel level = p.level();
        Statue statue = FREEZING.get(p.getUUID());
        boolean fresh = statue == null;
        int xp = keeps(level) ? 0 : p.totalExperience;
        if (fresh && e.getDrops().isEmpty() && xp <= 0) return;
        if (fresh) {
            statue = Statue.remains(level, p, xp);
            p.totalExperience = 0;
            p.experienceLevel = 0;
            p.experienceProgress = 0;
        }
        for (ItemEntity item : e.getDrops()) {
            ItemStack st = item.getItem();
            if (!st.isEmpty()) statue.items().add(st.copy());
        }
        e.getDrops().clear();
        if (fresh) {
            level.addFreshEntity(statue);
            StasisDelvers.LOG.info("{} died at {}: remains with {} stacks, {} xp", p.getName().getString(),
                statue.blockPosition().toShortString(), statue.items().size(), statue.xp());
        }
        WHERE.put(p.getUUID(), String.format("Your remains lie at %d %d %d (%s).", statue.getBlockX(), statue.getBlockY(),
            statue.getBlockZ(), level.dimension().identifier()));
    }

    static void respawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.isEndConquered() || !(e.getEntity() instanceof ServerPlayer p)) return;
        String where = WHERE.remove(p.getUUID());
        if (where != null) p.sendSystemMessage(Component.literal(where));
    }
}
