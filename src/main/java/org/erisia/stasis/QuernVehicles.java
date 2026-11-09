package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.gameevent.GameEvent;

/** Helpers for the quern vehicles (QuernVehicle): the cart's item, their dust, the frozen-tick hook. */
public final class QuernVehicles {
    private QuernVehicles() {
    }

    /** StasisEvents.entityTick (server): a quern vehicle's quern ticks even while the vehicle is frozen. */
    static void serverTick(Entity e) {
        if (e instanceof QuernVehicle v && e.level() instanceof ServerLevel sl) v.quernTick(sl);
    }

    /** Client: the quern's dust and sparks while it grinds (as QuernBlockEntity.clientTick), at height dy over the entity. */
    static void clientDust(Entity e, boolean grinding, double dy) {
        if (!grinding) return;
        Level level = e.level();
        var r = level.getRandom();
        double x = e.getX(), y = e.getY() + dy, z = e.getZ();
        if (r.nextInt(2) == 0)
            level.addParticle(new DustParticleOptions(r.nextInt(3) == 0 ? 0xF4EEFF : 0xC090FF, 0.25f + 0.15f * r.nextFloat()),
                x + (r.nextDouble() - 0.5) * 0.12, y, z + (r.nextDouble() - 0.5) * 0.12, 0, -0.04, 0);
        if (r.nextInt(6) == 0)
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + (r.nextDouble() - 0.5) * 0.2, y + 0.04, z + (r.nextDouble() - 0.5) * 0.2, 0, 0, 0);
    }

    /** The Quern Minecart item: placed on a rail, as a minecart is (MinecartItem, with the type looked up late). */
    public static final class CartItem extends Item {
        final java.util.function.Supplier<? extends EntityType<? extends AbstractMinecart>> type;

        public CartItem(java.util.function.Supplier<? extends EntityType<? extends AbstractMinecart>> type, Properties p) {
            super(p);
            this.type = type;
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            Level level = ctx.getLevel();
            BlockPos pos = ctx.getClickedPos();
            var state = level.getBlockState(pos);
            if (!state.is(BlockTags.RAILS)) return InteractionResult.FAIL;
            ItemStack stack = ctx.getItemInHand();
            RailShape shape = state.getBlock() instanceof BaseRailBlock rail ? rail.getRailDirection(state, level, pos, null) : RailShape.NORTH_SOUTH;
            double up = shape.isSlope() ? 0.5 : 0;
            AbstractMinecart cart = AbstractMinecart.createMinecart(level, pos.getX() + 0.5, pos.getY() + 0.0625 + up, pos.getZ() + 0.5,
                type.get(), EntitySpawnReason.DISPENSER, stack, ctx.getPlayer());
            if (cart == null) return InteractionResult.FAIL;
            if (level instanceof ServerLevel sl) {
                sl.addFreshEntity(cart);
                sl.gameEvent(GameEvent.ENTITY_PLACE, pos, GameEvent.Context.of(ctx.getPlayer(), sl.getBlockState(pos.below())));
            }
            stack.shrink(1);
            return InteractionResult.SUCCESS;
        }
    }
}
