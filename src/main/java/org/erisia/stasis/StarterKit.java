package org.erisia.stasis;

import mods.eln.item.electricalinterface.IItemEnergyBattery;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * First-join gifts, once per player, remembered across deaths (persisted NBT flags).
 * <ul>
 * <li>Stopped mode: a full, charged Delver suit (Baughn 2026-10-17: cows for leather are near impossible early on), worn
 * where the slot is free, else carried (or dropped), and a small flashlight.</li>
 * <li>Spreading mode: one stability compass and nothing else (Baughn 2026-10-19: players have the whole spread to make
 * a suit; the compass shows them the high ground that runs longest).</li>
 * </ul>
 */
public final class StarterKit {
    private StarterKit() {
    }

    public static final String TAG = "stasisdelvers_starter_suit";
    public static final String COMPASS_TAG = "stasisdelvers_starter_compass";

    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag persisted = p.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        if (StasisConfig.spreading) {
            if (persisted.getBooleanOr(COMPASS_TAG, false)) return;
            p.getInventory().placeItemBackInInventory(new ItemStack(StasisRegistry.STABILITY_COMPASS.get()), net.minecraft.util.Prediction.SERVER_ONLY);
            persisted.putBoolean(COMPASS_TAG, true);
            p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
            StasisDelvers.LOG.info("Starter stability compass for {}", p.getName().getString());
            return;
        }
        if (!StasisConfig.starterSuit || persisted.getBooleanOr(TAG, false)) return;
        give(p);
        persisted.putBoolean(TAG, true);
        p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
        StasisDelvers.LOG.info("Starter Delver suit for {}", p.getName().getString());
    }

    static void give(ServerPlayer p) {
        Item[] items = {StasisRegistry.DELVER_HELMET.get(), StasisRegistry.DELVER_CHEST.get(), StasisRegistry.DELVER_LEGS.get(),
            StasisRegistry.DELVER_BOOTS.get()};
        EquipmentSlot[] slots = Suits.ARMOR;
        for (int i = 0; i < 4; i++) {
            ItemStack st = new ItemStack(items[i]);
            var b = (IItemEnergyBattery) items[i];
            b.setEnergy(st, b.getEnergyMax(st));
            if (p.getItemBySlot(slots[i]).isEmpty()) p.setItemSlot(slots[i], st);
            else p.getInventory().placeItemBackInInventory(st, net.minecraft.util.Prediction.SERVER_ONLY);   // (dropped if the inventory is full)
        }
        // and a charged small flashlight (Baughn: torches don't burn where time stands still)
        var d = mods.eln.Eln.sharedItemStackOne.getDescriptor(FLASHLIGHT);
        if (d != null) {
            ItemStack fl = d.newItemStack();
            if (d instanceof IItemEnergyBattery b) b.setEnergy(fl, b.getEnergyMax(fl));
            p.getInventory().placeItemBackInInventory(fl, net.minecraft.util.Prediction.SERVER_ONLY);
        }
    }

    /** EA's Small Flashlight (1.12 id: sub 0 of electrical tools, 121). */
    static final int FLASHLIGHT = 7744;
}
