package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Suits that let a player move in stopped land: {@link TimeSuit} pieces with charge left. In the plain Grey a chest
 * piece is enough (λ-Nero, Baughn 2026-10-18); in mist, deeps and near rifts each missing piece makes it worse
 * (Freezing: faster drain, and the freeze creeps in).
 */
final class Suits {
    private Suits() {
    }

    static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** The suit piece in a slot: the armour worn there; for the chest, a rift pendant with charge left in the necklace
     *  slot when no suit chest piece is worn (RiftPendantItem: a Delver chest piece without the armour). */
    static ItemStack piece(ServerPlayer p, EquipmentSlot slot) {
        ItemStack s = p.getItemBySlot(slot);
        // (an empty chest piece doesn't block it: AdventurAgent died with a flat Delver chest and a fresh pendant)
        if (slot == EquipmentSlot.CHEST && !(s.getItem() instanceof TimeSuit && s.getItem() instanceof IItemEnergyBattery b && b.getEnergy(s) > 0)) {
            ItemStack pendant = CuriosCompat.pendant(p);
            if (!pendant.isEmpty()) return pendant;
        }
        return s;
    }

    /**
     * Why a worn suit doesn't protect (null: it does, or no suit pieces at all). Shown to the player in stopped land, so
     * a missing or empty piece isn't a silent death (softlock audit 7).
     */
    static String problem(ServerPlayer p) {
        int pieces = 0;
        for (EquipmentSlot slot : ARMOR) if (piece(p, slot).getItem() instanceof TimeSuit) pieces++;
        if (pieces == 0) return null;
        ItemStack chest = piece(p, EquipmentSlot.CHEST);
        if (!(chest.getItem() instanceof TimeSuit)) return "Suit incomplete: no chest piece";
        if (!working(p, 1).contains(EquipmentSlot.CHEST)) return "Suit piece out of charge: " + chest.getHoverName().getString();
        return null;
    }

    /**
     * The worn pieces that can pay their share of one tick's draw (x factor), the share counted over those pieces only:
     * an empty piece counts as not worn (Baughn: an empty piece should not be worse than not wearing it; it used to
     * switch the whole suit off with charge left in the chest piece). Empty without a working chest piece: no suit.
     */
    static java.util.EnumSet<EquipmentSlot> working(ServerPlayer p, double factor) {
        var set = java.util.EnumSet.noneOf(EquipmentSlot.class);
        for (EquipmentSlot slot : ARMOR) if (piece(p, slot).getItem() instanceof TimeSuit && piece(p, slot).getItem() instanceof IItemEnergyBattery) set.add(slot);
        // dropping a piece raises the others' share, which may drop another: at most 4 rounds
        for (boolean changed = true; changed && !set.isEmpty(); ) {
            changed = false;
            double f = factor * 4.0 / set.size();
            for (var it = set.iterator(); it.hasNext(); ) {
                ItemStack s = piece(p, it.next());
                if (((IItemEnergyBattery) s.getItem()).getEnergy(s) < f * ((TimeSuit) s.getItem()).stasisDraw(s) / 20.0) {
                    it.remove();
                    changed = true;
                }
            }
        }
        if (!set.contains(EquipmentSlot.CHEST)) set.clear();
        return set;
    }

    /** Each worn piece carries the draw of the missing ones too (Baughn: the same or worse drain in all cases; a partial
     *  suit is for other armour and enchantments, not to economise): 4 / worn pieces. */
    static double share(ServerPlayer p) {
        int worn = 4 - missing(p);
        return worn == 0 ? 1 : 4.0 / worn;
    }

    /** Suit pieces not worn, or worn empty (0..4; a chest piece alone: 3). */
    static int missing(ServerPlayer p) {
        int n = 0;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = piece(p, slot);
            if (!(s.getItem() instanceof TimeSuit) || !(s.getItem() instanceof IItemEnergyBattery b) || b.getEnergy(s) <= 0) n++;
        }
        return n;
    }

    /** Seconds of protection left: the emptiest piece (a full charged suit assumed). */
    static double secondsLeft(ServerPlayer p) {
        double left = Double.MAX_VALUE;
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = piece(p, slot);
            // (an empty piece counts as not worn: it doesn't end the suit)
            if (s.getItem() instanceof TimeSuit t && s.getItem() instanceof IItemEnergyBattery b && t.stasisDraw(s) > 0 && b.getEnergy(s) > 0)
                left = Math.min(left, b.getEnergy(s) / (t.stasisDraw(s) * share(p)));
        }
        return left;
    }

    /** J per durability point the advanced (stasis) suit spends to repair itself (Baughn: self-repair on power, as E-Coal). */
    static final double REPAIR_J = 500;

    /**
     * Once a second: suit pieces top themselves up from batteries carried in the inventory (EA portable batteries and
     * the like; Baughn: both suits run off inventory batteries), and the advanced suit repairs itself on power.
     */
    static void tick(ServerPlayer p) {
        GuideAdvancements.suits(p);
        if (p.tickCount % 20 != 0 || p.isSpectator()) return;
        // what the pieces want this second, and what the inventory's batteries can give
        java.util.List<ItemStack> pieces = new java.util.ArrayList<>();
        double[] want = new double[4];
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = piece(p, slot);
            if (!(s.getItem() instanceof TimeSuit) || !(s.getItem() instanceof IItemEnergyBattery b)) continue;
            want[pieces.size()] = Math.max(0, Math.min(b.getEnergyMax(s) - b.getEnergy(s), b.getChargePower(s)));
            pieces.add(s);
        }
        java.util.List<Cell> carried = carried(p);
        double supply = 0;
        for (Cell c : carried) supply += Math.max(0, Math.min(c.discharge(), c.energy()));
        // share it evenly (water-filling), so every piece gets charge at once: a suit protects only when all have some
        double[] give = new double[pieces.size()];
        double left = supply;
        for (int round = 0; round < 4 && left > 1e-6; round++) {
            int open = 0;
            for (int k = 0; k < give.length; k++) if (want[k] - give[k] > 1e-6) open++;
            if (open == 0) break;
            double share = left / open;
            for (int k = 0; k < give.length; k++) {
                double g = Math.min(share, want[k] - give[k]);
                if (g > 0) { give[k] += g; left -= g; }
            }
        }
        takeCarried(carried, supply - left);
        for (int k = 0; k < pieces.size(); k++) {
            ItemStack s = pieces.get(k);
            IItemEnergyBattery b = (IItemEnergyBattery) s.getItem();
            b.setEnergy(s, b.getEnergy(s) + give[k]);
            if (s.getItem() instanceof StasisArmorItem && s.isDamaged() && b.getEnergy(s) >= REPAIR_J) {
                s.setDamageValue(s.getDamageValue() - 1);
                b.setEnergy(s, b.getEnergy(s) - REPAIR_J);
            }
        }
    }

    /** A battery as available and drain see it (pure, so SuitsTest can check them without a player). */
    interface Cell {
        double energy();

        double max();

        /** J it gives up per drain (a second): a carried battery's discharge power. */
        double discharge();

        void set(double energy);
    }

    static Cell cell(ItemStack s) {
        IItemEnergyBattery b = (IItemEnergyBattery) s.getItem();
        return new Cell() {
            public double energy() { return b.getEnergy(s); }
            public double max() { return b.getEnergyMax(s); }
            public double discharge() { return b.getDischagePower(s); }
            public void set(double e) { b.setEnergy(s, e); }
        };
    }

    /** Batteries carried in the main inventory (not suit pieces). */
    static java.util.List<Cell> carried(ServerPlayer p) {
        java.util.List<Cell> out = new java.util.ArrayList<>();
        var inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack src = inv.getItem(i);
            if (!src.isEmpty() && !(src.getItem() instanceof TimeSuit) && src.getItem() instanceof IItemEnergyBattery) out.add(cell(src));
        }
        return out;
    }

    /** The worn suit pieces. */
    static java.util.List<Cell> worn(ServerPlayer p) {
        java.util.List<Cell> out = new java.util.ArrayList<>();
        for (EquipmentSlot slot : ARMOR) {
            ItemStack s = piece(p, slot);
            if (s.getItem() instanceof TimeSuit && s.getItem() instanceof IItemEnergyBattery) out.add(cell(s));
        }
        return out;
    }

    /** What a player could feed a tower: carried batteries and the worn suit pieces, J. */
    static double available(ServerPlayer p) {
        return available(carried(p), worn(p));
    }

    /** All that drain can take, given time: carried batteries that discharge at all (a rift capacitor doesn't), and
     *  the worn pieces. */
    static double available(java.util.List<Cell> carried, java.util.List<Cell> worn) {
        double sum = 0;
        for (Cell c : carried) if (c.discharge() > 0) sum += Math.max(0, c.energy());
        for (Cell c : worn) sum += Math.max(0, c.energy());
        return sum;
    }

    /**
     * Take up to maxJ from a player (a tower console's feed, Baughn: a jump-start from the suit): carried batteries first
     * (at their discharge power), then the worn suit pieces, evenly, down to nothing. Returns the J taken.
     */
    static double drain(ServerPlayer p, double maxJ) {
        return drain(p, maxJ, 0);
    }

    /** ... leaving each suit piece reserve (a share of its store). */
    static double drain(ServerPlayer p, double maxJ, double reserve) {
        return drain(carried(p), worn(p), maxJ, reserve);
    }

    static double drain(java.util.List<Cell> carried, java.util.List<Cell> worn, double maxJ, double reserve) {
        double left = takeCarried(carried, maxJ);
        java.util.List<Cell> pieces = new java.util.ArrayList<>();
        for (Cell c : worn) if (c.energy() > reserve * c.max() + 1e-6) pieces.add(c);
        // in equal shares, so the pieces run down together
        while (left > 1e-6 && !pieces.isEmpty()) {
            double share = left / pieces.size();
            for (var it = pieces.iterator(); it.hasNext(); ) {
                Cell c = it.next();
                double floor = reserve * c.max();
                double take = Math.min(share, c.energy() - floor);
                c.set(c.energy() - take);
                left -= take;
                if (c.energy() <= floor + 1e-6) it.remove();
            }
        }
        return maxJ - left;
    }

    /** Take up to maxJ from carried batteries in order, each at most its discharge power. Returns what is left of maxJ. */
    static double takeCarried(java.util.List<Cell> carried, double maxJ) {
        double left = maxJ;
        for (int i = 0; i < carried.size() && left > 1e-6; i++) {
            Cell c = carried.get(i);
            double take = Math.min(left, Math.min(c.discharge(), c.energy()));
            if (take <= 0) continue;
            c.set(c.energy() - take);
            left -= take;
        }
        return left;
    }

    /** Would the suit protect (a chest piece, every worn piece charged), without drawing power? */
    static boolean protectsWithoutDraw(ServerPlayer p) {
        return !working(p, 1).isEmpty();
    }

    static boolean protect(ServerPlayer p) {
        return protect(p, 1);
    }

    /** Only Delver pieces (no stasis suit piece): the weaker suit, not fully protective in a deep (Deeps). */
    static boolean delverOnly(ServerPlayer p) {
        for (EquipmentSlot slot : ARMOR) if (piece(p, slot).getItem() instanceof StasisArmorItem) return false;
        return true;
    }

    /** Is the player wearing a charged suit (a chest piece; every worn piece charged)? If so, draw one tick's power
     *  (x factor: deeps) from each worn piece. */
    static boolean protect(ServerPlayer p, double factor) {
        var set = working(p, factor);
        if (set.isEmpty()) return false;
        factor *= 4.0 / set.size();
        for (EquipmentSlot slot : set) {
            ItemStack s = piece(p, slot);
            IItemEnergyBattery b = (IItemEnergyBattery) s.getItem();
            b.setEnergy(s, b.getEnergy(s) - factor * ((TimeSuit) s.getItem()).stasisDraw(s) / 20.0);
            if (s.getItem() instanceof RiftPendantItem && b.getEnergy(s) <= 0) CuriosCompat.crumble(p);
        }
        return true;
    }
}
