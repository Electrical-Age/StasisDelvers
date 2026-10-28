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
    public static final String PAMPHLET_TAG = "stasisdelvers_pamphlet";
    static final int PAMPHLET_PAGES = 9;
    static final net.minecraft.resources.Identifier PAMPHLET_MODEL = net.minecraft.resources.Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "pamphlet");

    /** "Rift Safety", the government's pamphlet (Baughn's text, en_us book.stasisdelvers.pamphlet.*): a written book. */
    public static ItemStack pamphlet() {
        ItemStack b = new ItemStack(net.minecraft.world.item.Items.WRITTEN_BOOK);
        java.util.List<net.minecraft.server.network.Filterable<net.minecraft.network.chat.Component>> pages = new java.util.ArrayList<>();
        for (int i = 1; i <= PAMPHLET_PAGES; i++)
            pages.add(net.minecraft.server.network.Filterable.passThrough(net.minecraft.network.chat.Component.translatable("book.stasisdelvers.pamphlet.page" + i)));
        b.set(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT, new net.minecraft.world.item.component.WrittenBookContent(
            net.minecraft.server.network.Filterable.passThrough("Rift Safety"), "The Government", 0, pages, true));
        b.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.translatable("book.stasisdelvers.pamphlet.title")
            .withStyle(s -> s.withItalic(false)));
        // its own icon, a folded leaflet (Baughn)
        b.set(net.minecraft.core.component.DataComponents.ITEM_MODEL, PAMPHLET_MODEL);
        return b;
    }

    /** The government's pamphlet, any edition. */
    static boolean isPamphlet(ItemStack st) {
        var wb = st.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
        return st.is(net.minecraft.world.item.Items.WRITTEN_BOOK) && wb != null && "The Government".equals(wb.author())
            && "Rift Safety".equals(wb.title().raw());
    }

    public static void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        CompoundTag persisted = p.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        // pamphlets handed out before it had its icon, or before a page was added, are brought up to date (Baughn: the
        // pamphlet must stay updatable; the pages' text is translated, so only the page count needs this)
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (isPamphlet(st) && (!PAMPHLET_MODEL.equals(st.get(net.minecraft.core.component.DataComponents.ITEM_MODEL))
                || st.get(net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT).pages().size() != PAMPHLET_PAGES))
                p.getInventory().setItem(i, pamphlet().copyWithCount(st.getCount()));
        }
        if (StasisConfig.spreading) {
            // the rift-safety pamphlet (Baughn 2026-10-22), to everyone who hasn't had it, old players included
            if (!persisted.getBooleanOr(PAMPHLET_TAG, false)) {
                p.getInventory().placeItemBackInInventory(pamphlet(), net.minecraft.util.Prediction.SERVER_ONLY);
                persisted.putBoolean(PAMPHLET_TAG, true);
                p.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
                StasisDelvers.LOG.info("Rift-safety pamphlet for {}", p.getName().getString());
            }
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
