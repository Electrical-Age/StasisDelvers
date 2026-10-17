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
 * A full, charged Delver suit on a player's first join (Baughn 2026-10-17): cows for leather are near impossible early
 * on. Worn where the slot is free, else carried (or dropped). Once per player, remembered across deaths.
 */
public final class StarterKit {
    private StarterKit() {
    }

    public static final String TAG = "stasisdelvers_starter_suit";

    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (!StasisConfig.starterSuit || !(e.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag persisted = p.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        if (persisted.getBooleanOr(TAG, false)) return;
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
    }
}
