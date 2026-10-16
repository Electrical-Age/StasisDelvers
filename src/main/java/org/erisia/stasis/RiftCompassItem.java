package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.LodestoneTracker;

import java.util.Optional;

/**
 * The rift compass (crystal uses; PLAN.md "Ideas"): points to the nearest rift's core (static deeps within ~1.5 km,
 * gate rifts included), and spins while a storm runs. It uses the vanilla compass's lodestone needle (an untracked
 * target), so its look is the compass's.
 */
public class RiftCompassItem extends Item {
    public RiftCompassItem(Properties p) {
        super(p);
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, EquipmentSlot slot) {
        if (level.getGameTime() % 10 != 0) return;
        LodestoneTracker want;
        int seed = Deeps.seed(level);
        if (seed == 0 || !Stasis.stops(level) || Storms.intensity(level) > 0) want = new LodestoneTracker(Optional.empty(), false);
        else {
            StaticDeeps.Deep best = null;
            double bd = Double.MAX_VALUE;
            for (StaticDeeps.Deep d : StaticDeeps.near((float) owner.getX(), (float) owner.getY(), (float) owner.getZ(), 1500, seed)) {
                float[] c = StaticDeeps.core(d);
                double dd = Math.pow(c[0] - owner.getX(), 2) + Math.pow(c[2] - owner.getZ(), 2) + Math.pow(c[1] - owner.getY(), 2);
                if (dd < bd) {
                    bd = dd;
                    best = d;
                }
            }
            if (best == null) want = new LodestoneTracker(Optional.empty(), false);
            else {
                float[] c = StaticDeeps.core(best);
                want = new LodestoneTracker(Optional.of(GlobalPos.of(level.dimension(), BlockPos.containing(c[0], c[1], c[2]))), false);
            }
        }
        if (!want.equals(stack.get(DataComponents.LODESTONE_TRACKER))) stack.set(DataComponents.LODESTONE_TRACKER, want);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }
}
