package org.erisia.stasis;

import net.minecraft.world.item.Item;

/**
 * A rift lance's crystal focus (LANCE-v2 point 3, Baughn's decision 1: crafted, not consumed). Tier 1 (rift shards)
 * breaches rifts that lead to the Nether; tier 2 (charged shards) is needed for the End, and does the Nether too.
 */
public class RiftFocusItem extends Item {
    final int tier;

    public RiftFocusItem(Properties p, int tier) {
        super(p);
        this.tier = tier;
    }

    public int tier() {
        return tier;
    }

    /** The tier a gate of this kind needs: 1 to the Nether, 2 to the End. */
    public static int needs(Lance.Kind k) {
        return k == Lance.Kind.NETHER ? 1 : 2;
    }
}
