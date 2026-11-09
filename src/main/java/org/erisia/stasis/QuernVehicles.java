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

    /** How near a quern vehicle's field centre a minecart rides along unfrozen (blocks). */
    public static final double CARRY = QuernCore.RADIUS + 2;

    /**
     * Is minecart e part of a quern cart's train: within CARRY of a bound field's centre? Then it isn't frozen even where
     * the field doesn't hold its block (AdventurAgent 2026-11-09: a furnace cart pushing a minecart and a quern cart fell
     * out of the field's back as the quern cart sped off, and froze there with its fuel left: the train only coasted).
     */
    static boolean carried(ServerLevel level, Entity e) {
        if (!(e instanceof AbstractMinecart)) return false;
        FieldData d = FieldData.loaded(level);
        if (d == null) return false;
        for (Field f : d.boundFields()) {
            double dx = e.getX() - f.x(), dy = e.getY() - f.y(), dz = e.getZ() - f.z();
            if (dx * dx + dy * dy + dz * dz <= CARRY * CARRY) return true;
        }
        return false;
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

    /** The Quern Boat items: placed as a boat is (BoatItem, with the type looked up late). */
    public static final class BoatItem extends Item {
        final java.util.function.Supplier<? extends EntityType<? extends net.minecraft.world.entity.vehicle.boat.AbstractBoat>> type;

        public BoatItem(java.util.function.Supplier<? extends EntityType<? extends net.minecraft.world.entity.vehicle.boat.AbstractBoat>> type, Properties p) {
            super(p);
            this.type = type;
        }

        @Override
        public InteractionResult use(Level level, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
            ItemStack stack = player.getItemInHand(hand);
            var hit = getPlayerPOVHitResult(level, player, net.minecraft.world.level.ClipContext.Fluid.ANY);
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return InteractionResult.PASS;
            var view = player.getViewVector(1.0f);
            var from = player.getEyePosition();
            for (Entity e : level.getEntities(player, player.getBoundingBox().expandTowards(view.scale(5.0)).inflate(1.0), net.minecraft.world.entity.EntitySelector.CAN_BE_PICKED))
                if (e.getBoundingBox().inflate(e.getPickRadius()).contains(from)) return InteractionResult.PASS;
            var boat = type.get().create(level, EntitySpawnReason.SPAWN_ITEM_USE);
            if (boat == null) return InteractionResult.FAIL;
            var at = hit.getLocation();
            // (on stilled water, which is solid, the hit is its surface: stand it on top, as on ice; vanilla boats failed
            // there, inside the block's collision: AdventurAgent)
            if (hit instanceof net.minecraft.world.phys.BlockHitResult bh && level.getFluidState(bh.getBlockPos()).is(net.minecraft.tags.FluidTags.WATER)
                && !Stasis.isRunning(level, bh.getBlockPos()))
                at = new net.minecraft.world.phys.Vec3(at.x, bh.getBlockPos().getY() + 1.0, at.z);
            boat.setInitialPos(at.x, at.y, at.z);
            if (level instanceof ServerLevel sl) EntityType.<net.minecraft.world.entity.vehicle.boat.AbstractBoat>createDefaultStackConfig(sl, stack, player).apply(boat);
            boat.setYRot(player.getYRot());
            if (!level.noCollision(boat, boat.getBoundingBox())) return InteractionResult.FAIL;
            if (!level.isClientSide()) {
                level.addFreshEntity(boat);
                level.gameEvent(player, GameEvent.ENTITY_PLACE, at);
                stack.consume(1, player);
            }
            return InteractionResult.SUCCESS;
        }
    }

    /** The vanilla woods with a chest boat (26.3), and whether it's a raft: a Quern Boat each (quern_WOOD_boat / quern_bamboo_raft). */
    public static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "cherry", "dark_oak", "pale_oak", "mangrove", "poplar", "bamboo"};

    public static boolean raft(String wood) {
        return wood.equals("bamboo");
    }

    /** quern_oak_boat, quern_bamboo_raft. */
    public static String boatId(String wood) {
        return "quern_" + wood + (raft(wood) ? "_raft" : "_boat");
    }

    /** minecraft:oak_chest_boat, minecraft:bamboo_chest_raft. */
    public static String chestBoatId(String wood) {
        return wood + (raft(wood) ? "_chest_raft" : "_chest_boat");
    }
}
