package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/**
 * The wild resonator (Baughn 2026-11-08: "Tower fields should block mob spawns entirely, with a new attachable block
 * enabling them again, like the accelerator"): placed against a tower's core (either cell, any side), it lets mobs spawn
 * in that tower's field again, by vanilla's rules and by the field's own clock, night when the field shows night
 * (FieldDaylight; λ-Nero: "It should be day/night if the visual is day/night").
 */
public class WildResonatorBlock extends Block {
    public WildResonatorBlock(Properties p) {
        super(p);
    }

    /** Is a wild resonator against the core whose master cell is at master? */
    public static boolean against(Level level, BlockPos master) {
        for (int c = 0; c < TowerBlock.HEIGHT; c++) {
            BlockPos cell = master.above(c);
            for (Direction d : Direction.Plane.HORIZONTAL)
                if (level.getBlockState(cell.relative(d)).getBlock() instanceof WildResonatorBlock) return true;
        }
        return false;
    }
}
