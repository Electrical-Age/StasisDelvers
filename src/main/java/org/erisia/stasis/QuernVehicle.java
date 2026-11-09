package org.erisia.stasis;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A vehicle carrying a rift quern (λ-Nero 2026-11-09, notes/quern-vehicles/PLAN.md): the Quern Minecart and the Quern
 * Boat. Its quern grinds and holds a field round it, bound to it so the field moves with it (FieldData.bind / move).
 * Its quern ticks even while the vehicle is frozen (StasisEvents.entityTick), so it can put its field up in stopped land.
 */
public interface QuernVehicle {
    QuernCore quern();

    /** "Quern Minecart", "Quern Boat": its status line's name. */
    String quernName();

    /** The entity itself. */
    default Entity self() {
        return (Entity) this;
    }

    /** Each server tick, frozen or not (StasisEvents.entityTick). */
    default void quernTick(ServerLevel level) {
        if (!self().isRemoved()) quern().tick(level);
    }

    /**
     * A player uses it: a shard joins the queue; sneaking with an empty hand takes the last one back; an empty hand shows
     * its status. PASS for anything else (a boat's riding, a chest boat's inventory).
     */
    static InteractionResult use(QuernVehicle v, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        Entity e = v.self();
        if (QuernCore.isFuel(held)) {
            if (e.level().isClientSide()) return InteractionResult.SUCCESS;
            if (v.quern().offer(held)) {
                held.consume(1, player);
                return InteractionResult.SUCCESS_SERVER;
            }
            player.sendOverlayMessage(Component.literal(v.quernName() + ": its queue is full."));
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    /** Sneak-use with an empty hand: the last queued shard back, or the status. */
    static InteractionResult takeOrStatus(QuernVehicle v, Player player, InteractionHand hand) {
        if (!player.getItemInHand(hand).isEmpty()) return InteractionResult.PASS;
        if (v.self().level().isClientSide()) return InteractionResult.SUCCESS;
        ItemStack back = player.isSecondaryUseActive() ? v.quern().takeLast() : ItemStack.EMPTY;
        if (!back.isEmpty()) {
            if (!player.addItem(back)) v.self().spawnAtLocation((ServerLevel) v.self().level(), back);
        } else player.sendOverlayMessage(v.quern().statusText(v.quernName()));
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Gone (destroyed, unloaded, through a portal): its field goes; destroyed, the queue drops (the burning shard is spent). */
    static void removed(QuernVehicle v, Entity.RemovalReason reason) {
        Entity e = v.self();
        if (!(e.level() instanceof ServerLevel sl)) return;
        v.quern().field(sl, false);
        if (reason.shouldDestroy())
            for (ItemStack s : v.quern().takeQueue()) e.spawnAtLocation(sl, s);
    }

    /** Its quern's state, with the level its field id belongs to (a vehicle through a portal mustn't take over another
     *  level's field with that id). */
    static void save(QuernVehicle v, ValueOutput out) {
        ValueOutput q = out.child("quern");
        v.quern().save(q);
        q.putString("level", v.self().level().dimension().identifier().toString());
    }

    static void load(QuernVehicle v, ValueInput in) {
        in.child("quern").ifPresent(q -> {
            v.quern().load(q);
            if (!q.getStringOr("level", "").equals(v.self().level().dimension().identifier().toString())) v.quern().fieldId = Stasis.NO_FIELD;
        });
    }
}
