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

    /** Right-click equips a pendant into the necklace slot (AdventurAgent: it had to be dragged in). */
    static void register(net.neoforged.bus.api.IEventBus modBus) {
        if (LOADED) modBus.addListener(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent.class, e -> e.enqueueWork(Impl::register));
    }

    /** A spent pendant crumbles: the crystal is gone, the string stays in the slot. */
    static void crumble(ServerPlayer p) {
        if (LOADED) Impl.crumble(p);
    }

    private static final class Impl {
        static void register() {
            top.theillusivec4.curios.api.type.capability.ICurioItem curio = new top.theillusivec4.curios.api.type.capability.ICurioItem() {
                @Override
                public boolean canEquipFromUse(top.theillusivec4.curios.api.SlotContext ctx, ItemStack stack) {
                    return true;
                }
            };
            top.theillusivec4.curios.api.CuriosApi.registerCurio(StasisRegistry.RIFT_PENDANT.get(), curio);
            top.theillusivec4.curios.api.CuriosApi.registerCurio(StasisRegistry.CHARGED_RIFT_PENDANT.get(), curio);
        }

        static ItemStack pendant(ServerPlayer p) {
            return top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p)
                .flatMap(h -> h.findFirstCurio(s -> s.getItem() instanceof RiftPendantItem pi && pi.getEnergy(s) > 0))
                .map(top.theillusivec4.curios.api.SlotResult::stack).orElse(ItemStack.EMPTY);
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
