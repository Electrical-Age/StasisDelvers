package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The stability compass (Baughn 2026-10-19, spreading mode; docs/spreading-mode.md): points to the nearest place whose
 * temporal stability (SpreadMap rank / N: the coverage at which it stills) is at least 90 %, high ground where time runs
 * longest. Every player gets one on the first join in spreading mode (StarterKit). It spins outside the spreading mode,
 * off the overworld, once the spread is complete, and where no such place is left. Used: "Temporal stability here".
 * <p>
 * Like the rift compass it uses the vanilla compass's lodestone needle (an untracked target), so the client needs nothing
 * but the item model. The target only depends on the player's cell (and the threshold), so it is cached per player and
 * searched again when the player changes cell (SpreadMap.nearestAtLeast, ring by ring out from the player).
 */
public class StabilityCompassItem extends Item {
    /** The stability the needle looks for (Baughn: >= 90 %). */
    public static final double TARGET = 0.9;

    public StabilityCompassItem(Properties p) {
        super(p);
    }

    static final LodestoneTracker SPIN = new LodestoneTracker(Optional.empty(), false);

    /** The last search per player (server thread only): the map, the player's cell, the threshold, the answer. */
    private record Cached(SpreadRanks map, int cellX, int cellZ, int minRank, LodestoneTracker target) {
    }

    private static final Map<UUID, Cached> CACHE = new HashMap<>();

    /** Server stop: forget the searches (the map goes). */
    static void clearCache() {
        CACHE.clear();
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
        if (level.getGameTime() % 20 != 0) return;
        update(stack, level, owner);
    }

    /** Points the needle from where owner stands (GameTests call this directly). */
    public static void update(ItemStack stack, ServerLevel level, Entity owner) {
        LodestoneTracker want = target(level, owner);
        if (!want.equals(stack.get(DataComponents.LODESTONE_TRACKER))) stack.set(DataComponents.LODESTONE_TRACKER, want);
    }

    /**
     * The needle's target. The threshold is 90 % of N, or P once the stillness is past 90 % (after hard mode), so the
     * needle always points at land still running.
     */
    static LodestoneTracker target(ServerLevel level, Entity owner) {
        Spread.State s = Spread.server;
        if (!Spread.active(level) || !(s.map() instanceof SpreadMap m)) return SPIN;
        int min = Math.max((int) Math.ceil(TARGET * s.count()), s.p());
        int cx = Math.floorDiv(owner.getBlockX(), SpreadMap.CELL), cz = Math.floorDiv(owner.getBlockZ(), SpreadMap.CELL);
        Cached c = CACHE.get(owner.getUUID());
        if (c != null && c.map() == m && c.cellX() == cx && c.cellZ() == cz && c.minRank() == min) return c.target();
        // up to twice the map's width in rings: the whole map from anywhere within a radius of its edge (and a bounded
        // search from far beyond it, where the needle spins)
        int[] t = SpreadMap.nearestAtLeast(m, owner.getBlockX(), owner.getBlockZ(), min, 2 * m.n);
        LodestoneTracker want = t == null ? SPIN
            : new LodestoneTracker(Optional.of(GlobalPos.of(level.dimension(), new BlockPos(t[0], owner.getBlockY(), t[1]))), false);
        CACHE.put(owner.getUUID(), new Cached(m, cx, cz, min, want));
        return want;
    }

    /** Temporal stability of column (x, z) in [0, 1] (the coverage at which it stills), or NaN where no spread decides. */
    static double stabilityAt(ServerLevel level, int x, int z) {
        Spread.State s = Spread.server;
        if (!Spread.active(level) || s.map() == null || s.count() == 0) return Double.NaN;
        long r = SpreadMap.rank1024(s.map(), x, z);
        return Math.max(0, r / (double) SpreadMap.SCALE / s.count());
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel sl && player instanceof ServerPlayer sp) {
            double st = stabilityAt(sl, sp.getBlockX(), sp.getBlockZ());
            Spread.State s = Spread.server;
            Component msg;
            if (!Double.isNaN(st))
                msg = Component.translatable("item.stasisdelvers.stability_compass.here", (int) Math.floor(st * 100),
                    (int) Math.floor(100.0 * s.p() / Math.max(1, s.count())));
            else msg = Component.translatable("item.stasisdelvers.stability_compass.none");
            sp.sendOverlayMessage(msg);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
                                Consumer<Component> out, net.minecraft.world.item.TooltipFlag flag) {
        out.accept(Component.translatable("item.stasisdelvers.stability_compass.tooltip").withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }
}
