package org.erisia.stasis;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The pendant in Curios' necklace slot, if Curios is loaded (no hard dependency: this class is only touched after
 * the ModList check in {@link #pendant}).
 */
final class CuriosCompat {
    private CuriosCompat() {
    }

    static final boolean LOADED = net.neoforged.fml.ModList.get() != null && net.neoforged.fml.ModList.get().isLoaded("curios");

    /** The worn rift pendant with charge left, or EMPTY. */
    static ItemStack pendant(ServerPlayer p) {
        if (!LOADED) return ItemStack.EMPTY;
        return Impl.pendant(p);
    }

    /** A chronal anchor worn in a Curios slot (charm). */
    static boolean anchor(ServerPlayer p) {
        return LOADED && Impl.anchor(p);
    }

    /** Right-click equips a pendant into the necklace slot (AdventurAgent: it had to be dragged in). */
    static void register(net.neoforged.bus.api.IEventBus modBus) {
        if (LOADED) modBus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, e -> e.enqueueWork(Impl::register));
    }

    /** Each worn accessory's stack and its slot ("identifier#index"), for the remains (none without Curios). */
    static java.util.Map<ItemStack, String> slots(ServerPlayer p) {
        return LOADED ? Impl.slots(p) : java.util.Map.of();
    }

    /** Put a stack back into its accessory slot ("identifier#index") if that slot is there and empty. */
    static boolean put(net.minecraft.world.entity.player.Player p, String key, ItemStack st) {
        return LOADED && key != null && !key.isEmpty() && Impl.put(p, key, st);
    }

    /** A spent pendant crumbles: the crystal is gone, the string stays in the slot. */
    static void crumble(ServerPlayer p) {
        if (LOADED) Impl.crumble(p);
    }

    private static final class Impl {
        static void register() {
            top.theillusivec4.curios.api.type.capability.ICurioItem curio = new top.theillusivec4.curios.api.type.capability.ICurioItem() {
                /**
                 * Only into an empty slot. Curios 17.0.0-beta.3 opens a root transaction to swap with an occupied slot
                 * (CuriosCommonEvents.curioRightClick: Transaction.open(null) passed to extract, never closed), and the
                 * next transaction anyone opens on the server thread then throws: Incognito crashed twice on 10-26
                 * (a pendant right-clicked while one was worn; the next solar panel tick died).
                 */
                @Override
                public boolean canEquipFromUse(top.theillusivec4.curios.api.SlotContext ctx, ItemStack stack) {
                    return top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(ctx.entity())
                        .flatMap(h -> h.getStacksHandler(ctx.identifier()))
                        .map(sh -> ctx.index() < sh.getStacks().getSlots() && sh.getStacks().getStackInSlot(ctx.index()).isEmpty())
                        .orElse(false);
                }
            };
            top.theillusivec4.curios.api.CuriosApi.registerCurio(StasisRegistry.RIFT_PENDANT.get(), curio);
            top.theillusivec4.curios.api.CuriosApi.registerCurio(StasisRegistry.CHARGED_RIFT_PENDANT.get(), curio);
            top.theillusivec4.curios.api.CuriosApi.registerCurio(StasisRegistry.CHRONAL_ANCHOR.get(), curio);
        }

        static ItemStack pendant(ServerPlayer p) {
            return top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p)
                .flatMap(h -> h.findFirstCurio(s -> s.getItem() instanceof RiftPendantItem pi && pi.getEnergy(s) > 0))
                .map(top.theillusivec4.curios.api.SlotResult::stack).orElse(ItemStack.EMPTY);
        }

        static boolean anchor(ServerPlayer p) {
            return top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p)
                .flatMap(h -> h.findFirstCurio(StasisRegistry.CHRONAL_ANCHOR.get())).isPresent();
        }

        static java.util.Map<ItemStack, String> slots(ServerPlayer p) {
            java.util.Map<ItemStack, String> m = new java.util.IdentityHashMap<>();
            top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).ifPresent(h -> h.getCurios().forEach((id, sh) -> {
                var stacks = sh.getStacks();
                for (int i = 0; i < stacks.getSlots(); i++) if (!stacks.getStackInSlot(i).isEmpty()) m.put(stacks.getStackInSlot(i), id + "#" + i);
            }));
            return m;
        }

        static boolean put(net.minecraft.world.entity.player.Player p, String key, ItemStack st) {
            int hash = key.lastIndexOf('#');
            if (hash <= 0) return false;
            String id = key.substring(0, hash);
            int index;
            try {
                index = Integer.parseInt(key.substring(hash + 1));
            } catch (NumberFormatException e) {
                return false;
            }
            var h = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).orElse(null);
            if (h == null) return false;
            var sh = h.getStacksHandler(id).orElse(null);
            if (sh == null || index < 0 || index >= sh.getStacks().getSlots() || !sh.getStacks().getStackInSlot(index).isEmpty()) return false;
            sh.getStacks().setStackInSlot(index, st);
            return true;
        }

        static void crumble(ServerPlayer p) {
            top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).ifPresent(h -> {
                for (var r : h.findCurios(s -> s.getItem() instanceof RiftPendantItem pi && pi.getEnergy(s) <= 0)) {
                    var ctx = r.slotContext();
                    h.getStacksHandler(ctx.identifier()).ifPresent(sh -> sh.getStacks().setStackInSlot(ctx.index(), new ItemStack(net.minecraft.world.item.Items.STRING)));
                    p.level().playSound(null, p.blockPosition(), net.minecraft.sounds.SoundEvents.AMETHYST_CLUSTER_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 0.8f, 1.2f);
                    p.sendOverlayMessage(net.minecraft.network.chat.Component.translatable("message.stasisdelvers.pendant_crumbled")
                        .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
                }
            });
        }
    }
}
