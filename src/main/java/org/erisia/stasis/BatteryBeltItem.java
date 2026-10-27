package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The battery belt (Baughn 2026-10-27): a Curios belt with four pockets for EA portable batteries and condensators, or
 * rift capacitors. Worn, its batteries feed the suit as carried batteries do (Suits.carried) and its capacitors take
 * the weather's surcharge (Capacitors.absorb); the HUD shows its charge beside the suit gauge. As an EA item battery it
 * stands for what it holds, so an EA charger charges the batteries in it and vents its capacitors. Right-click in hand
 * opens its pockets (BeltMenu).
 */
public class BatteryBeltItem extends Item implements IItemEnergyBattery {
    public static final int SLOTS = 4;

    public BatteryBeltItem(Properties properties) {
        super(properties);
    }

    /** The belt the player wears (Curios belt slot), or EMPTY. */
    public static ItemStack worn(Player p) {
        ItemStack t = TEST_WORN.get(p.getUUID());
        return t != null ? t : CuriosCompat.belt(p);
    }

    /** GameTests: a belt "worn" by a test player (Curios isn't loaded in the test server). */
    public static final java.util.Map<java.util.UUID, ItemStack> TEST_WORN = new java.util.concurrent.ConcurrentHashMap<>();

    /** What may go in a pocket: an EA portable battery or condensator, or a rift capacitor. */
    public static boolean accepts(ItemStack s) {
        if (s.isEmpty()) return false;
        if (s.getItem() instanceof CapacitorItem) return true;
        return mods.eln.misc.Utils.getItemObject(s) instanceof mods.eln.item.electricalitem.BatteryItem;
    }

    /** The pockets' contents (copies). */
    public static NonNullList<ItemStack> contents(ItemStack belt) {
        NonNullList<ItemStack> out = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        belt.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(out);
        return out;
    }

    public static void setContents(ItemStack belt, List<ItemStack> items) {
        belt.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
    }

    static boolean isBattery(ItemStack s) {
        return Suits.battery(s) != null && !(s.getItem() instanceof CapacitorItem);
    }

    /** The batteries' charge as a share of what they hold (for the HUD); NaN when it holds none. */
    public static double batteryShare(ItemStack belt) {
        double e = 0, m = 0;
        for (ItemStack s : contents(belt))
            if (isBattery(s)) {
                IItemEnergyBattery b = Suits.battery(s);
                e += b.getEnergy(s);
                m += b.getEnergyMax(s);
            }
        return m <= 0 ? Double.NaN : e / m;
    }

    /** One pocket as a suit battery cell: reads and writes through the belt's contents. */
    static Suits.Cell cell(ItemStack belt, int k) {
        return new Suits.Cell() {
            ItemStack item() {
                return contents(belt).get(k);
            }

            public double energy() {
                ItemStack s = item();
                return Suits.battery(s) instanceof IItemEnergyBattery b ? b.getEnergy(s) : 0;
            }

            public double max() {
                ItemStack s = item();
                return Suits.battery(s) instanceof IItemEnergyBattery b ? b.getEnergyMax(s) : 0;
            }

            public double discharge() {
                ItemStack s = item();
                return Suits.battery(s) instanceof IItemEnergyBattery b ? b.getDischagePower(s) : 0;
            }

            public void set(double energy) {
                var all = contents(belt);
                ItemStack s = all.get(k);
                if (!(Suits.battery(s) instanceof IItemEnergyBattery b)) return;
                b.setEnergy(s, energy);
                setContents(belt, all);
            }
        };
    }

    /** The worn belt's batteries, as cells for Suits. */
    static List<Suits.Cell> batteryCells(ItemStack belt) {
        List<Suits.Cell> out = new java.util.ArrayList<>();
        var all = contents(belt);
        for (int k = 0; k < SLOTS; k++) if (isBattery(all.get(k))) out.add(cell(belt, k));
        return out;
    }

    // ---- an EA item battery standing for what it holds: chargers fill the pockets in order; drawing takes from batteries

    @Override
    public double getEnergy(ItemStack stack) {
        double e = 0;
        for (ItemStack s : contents(stack)) if (Suits.battery(s) instanceof IItemEnergyBattery b) e += b.getEnergy(s);
        return e;
    }

    @Override
    public double getEnergyMax(ItemStack stack) {
        double e = 0;
        for (ItemStack s : contents(stack)) if (Suits.battery(s) instanceof IItemEnergyBattery b) e += b.getEnergyMax(s);
        return e;
    }

    @Override
    public void setEnergy(ItemStack stack, double value) {
        var all = contents(stack);
        double delta = value - getEnergy(stack);
        for (ItemStack s : all) {
            if (Math.abs(delta) < 1e-9) break;
            if (!(Suits.battery(s) instanceof IItemEnergyBattery b)) continue;
            double e = b.getEnergy(s);
            if (delta > 0) {
                double put = Math.min(delta, b.getEnergyMax(s) - e);
                if (put > 0) {
                    b.setEnergy(s, e + put);
                    delta -= put;
                }
            } else if (isBattery(s)) {   // (a capacitor's charge isn't power)
                double take = Math.min(-delta, e);
                if (take > 0) {
                    b.setEnergy(s, e - take);
                    delta += take;
                }
            }
        }
        setContents(stack, all);
    }

    @Override
    public double getChargePower(ItemStack stack) {
        double p = 0;
        for (ItemStack s : contents(stack)) if (Suits.battery(s) instanceof IItemEnergyBattery b) p += b.getChargePower(s);
        return p;
    }

    @Override
    public double getDischagePower(ItemStack stack) {
        double p = 0;
        for (ItemStack s : contents(stack)) if (isBattery(s)) p += Suits.battery(s).getDischagePower(s);
        return p;
    }

    @Override
    public int getPriority(ItemStack stack) {
        return 0;
    }

    @Override
    public void electricalItemUpdate(ItemStack stack, double time) {
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp) BeltMenu.open(sp, hand);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display,
                                java.util.function.Consumer<Component> out, net.minecraft.world.item.TooltipFlag flag) {
        int n = 0;
        for (ItemStack s : contents(stack)) if (!s.isEmpty()) n++;
        double share = batteryShare(stack);
        out.accept(Component.literal(String.format("%d of %d pockets used%s", n, SLOTS,
            Double.isNaN(share) ? "" : String.format("; batteries %.0f%%", share * 100))).withStyle(net.minecraft.ChatFormatting.GRAY));
        out.accept(Component.literal("Worn as a belt: its batteries feed your suit, its capacitors take the weather").withStyle(net.minecraft.ChatFormatting.GRAY));
        out.accept(Component.literal("Right-click to open; an EA charger charges what's inside").withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return !Double.isNaN(batteryShare(stack));
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        double s = batteryShare(stack);
        return (int) Math.round(13 * (Double.isNaN(s) ? 0 : Math.max(0, Math.min(1, s))));
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x50a0d0;
    }
}
