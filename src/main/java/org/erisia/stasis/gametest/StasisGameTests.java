package org.erisia.stasis.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.erisia.stasis.Field;
import org.erisia.stasis.Freezing;
import org.erisia.stasis.Statue;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import org.erisia.stasis.FieldData;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StasisDelvers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Game tests of the stasis rule. Each test runs in a 16x6x8 box (stone floor at y = 0) far from spawn, so time is
 * stopped there by default; the test puts a field (sphere r 4.2 at local 3.5, 2.5, 4.5) over its west end. Local x
 * 0..6 is inside, x 7 at y 2 is cut by the boundary (outside by the whole-block rule), x >= 9 is outside.
 * Run headless: gradlew runGameTestServer.
 */
public final class StasisGameTests {
    private StasisGameTests() {
    }

    static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
        DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, StasisDelvers.MODID);

    static final double R = 4.2;

    interface Case {
        /** Set up; return the check to run after `ticks`. */
        Runnable build(GameTestHelper h, Checks c);
    }

    /** isolated: runs in a batch of its own (towers make big fields that would reach the neighbouring tests). */
    record Spec(int ticks, Case body, boolean field, boolean isolated) {
        Spec(int ticks, Case body) {
            this(ticks, body, true, false);
        }

        Spec(int ticks, Case body, boolean field) {
            this(ticks, body, field, false);
        }
    }

    static final Map<String, Spec> CASES = new LinkedHashMap<>();

    static {
        CASES.put("rule_whole_block", new Spec(1, (h, c) -> () -> {
            Field f = new Field(0, 3.5, 2.5, 4.5, R);
            c.check("block at the centre inside", f.containsBlock(3, 2, 4));
            c.check("block cut by the boundary (centre inside, far corner outside) is outside",
                f.containsPoint(7.5, 2.5, 4.5) && !f.containsBlock(7, 2, 4));
            c.check("block well outside", !f.containsBlock(12, 2, 4));
            ServerLevel level = h.getLevel();
            BlockPos out = h.absolutePos(new BlockPos(12, 2, 4));
            c.check("outside the field time is stopped here", !Stasis.isRunning(level, out));
            c.check("inside it runs", Stasis.isRunning(level, h.absolutePos(new BlockPos(3, 2, 4))));
        }));
        // scheduled block ticks and the falling-block entity
        CASES.put("sand_falls_only_inside", new Spec(40, (h, c) -> {
            h.setBlock(new BlockPos(3, 3, 4), Blocks.SAND);
            h.setBlock(new BlockPos(7, 2, 4), Blocks.SAND);
            h.setBlock(new BlockPos(12, 3, 4), Blocks.SAND);
            return () -> {
                StringBuilder col = new StringBuilder();
                for (int y = 0; y <= 4; y++) col.append(y).append('=').append(h.getBlockState(new BlockPos(3, y, 4)).getBlock().getName().getString()).append(' ');
                var falling = h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class, new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(20));
                for (var fe : falling) col.append("falling@").append(String.format("%.2f,%.2f,%.2f ", fe.getX(), fe.getY(), fe.getZ()));
                c.check("inside: the sand fell to the floor", h.getBlockState(new BlockPos(3, 3, 4)).isAir()
                    && h.getBlockState(new BlockPos(3, 1, 4)).is(Blocks.SAND), col.toString() + " origin " + h.absolutePos(BlockPos.ZERO));
                c.check("cut by the boundary: the sand hangs", h.getBlockState(new BlockPos(7, 2, 4)).is(Blocks.SAND));
                c.check("outside: the sand hangs", h.getBlockState(new BlockPos(12, 3, 4)).is(Blocks.SAND));
            };
        }));
        // scheduled fluid ticks
        CASES.put("water_flows_only_inside", new Spec(30, (h, c) -> {
            h.setBlock(new BlockPos(3, 1, 4), Blocks.WATER);
            h.setBlock(new BlockPos(12, 1, 4), Blocks.WATER);
            return () -> {
                c.check("inside: water spread to the next block", h.getBlockState(new BlockPos(3, 1, 3)).is(Blocks.WATER));
                c.check("outside: water stayed in its block", h.getBlockState(new BlockPos(12, 1, 3)).isAir());
            };
        }));
        // entity ticks: gravity
        CASES.put("items_fall_only_inside", new Spec(20, (h, c) -> {
            ItemEntity in = item(h, new Vec3(3.5, 4.0, 4.5)), out = item(h, new Vec3(12.5, 4.0, 4.5));
            double inY = in.getY(), outY = out.getY();
            return () -> {
                c.check("inside: the item fell", in.getY() < inY - 1.0, String.format("%.2f -> %.2f", inY, in.getY()));
                c.check("outside: the item hangs where it was", out.getY() == outY, String.format("%.2f -> %.2f", outY, out.getY()));
            };
        }));
        // block entities
        CASES.put("furnace_burns_only_inside", new Spec(20, (h, c) -> {
            FurnaceBlockEntity in = furnace(h, new BlockPos(3, 1, 4)), out = furnace(h, new BlockPos(12, 1, 4));
            return () -> {
                c.check("inside: the furnace lit and took a coal", in.getItem(1).getCount() == 1, in.getItem(1).toString());
                c.check("outside: the furnace never lit", out.getItem(1).getCount() == 2, out.getItem(1).toString());
            };
        }));
        // a column with no field at all: LevelTicks keeps its scheduled ticks queued, and they run once a field
        // covers the block (time resumes where it stopped)
        CASES.put("time_resumes", new Spec(60, (h, c) -> {
            h.setBlock(new BlockPos(12, 3, 4), Blocks.SAND);
            ServerLevel level = h.getLevel();
            h.runAfterDelay(20, () -> {
                c.check("before the field: the sand hangs", h.getBlockState(new BlockPos(12, 3, 4)).is(Blocks.SAND));
                Vec3 p = h.absoluteVec(new Vec3(12.5, 2.5, 4.5));
                Field f = FieldData.get(level).add(level, p.x, p.y, p.z, 3.0);
                c.cleanup.add(() -> FieldData.get(level).remove(level, f.id()));
            });
            return () -> c.check("after the field came: the sand fell", h.getBlockState(new BlockPos(12, 1, 4)).is(Blocks.SAND)
                && h.getBlockState(new BlockPos(12, 3, 4)).isAir());
        }, false));
        // random ticks: cactus grows by random ticks only (no light needed); random tick speed raised for the test
        CASES.put("cactus_grows_only_inside", new Spec(40, (h, c) -> {
            ServerLevel level = h.getLevel();
            var rules = level.getGameRules();
            int speed = rules.get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
            rules.set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 4096, level.getServer());
            c.cleanup.add(() -> rules.set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, speed, level.getServer()));
            for (int x : new int[]{3, 12}) {
                h.setBlock(new BlockPos(x, 0, 4), Blocks.SAND);
                h.setBlock(new BlockPos(x, 1, 4), Blocks.CACTUS);
            }
            return () -> {
                var in = h.getBlockState(new BlockPos(3, 1, 4)); var out = h.getBlockState(new BlockPos(12, 1, 4));
                c.check("inside: the cactus grew", h.getBlockState(new BlockPos(3, 2, 4)).is(Blocks.CACTUS)
                    || in.getValue(net.minecraft.world.level.block.CactusBlock.AGE) > 0, in.toString());
                c.check("outside: the cactus didn't", out.is(Blocks.CACTUS) && out.getValue(net.minecraft.world.level.block.CactusBlock.AGE) == 0
                    && h.getBlockState(new BlockPos(12, 2, 4)).isAir(), out.toString());
            };
        }));
        // players: outside without a suit they freeze into a statue holding their things
        CASES.put("player_freezes_outside", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            p.getInventory().add(new ItemStack(Items.DIAMOND, 7));
            p.giveExperiencePoints(50);
            return () -> {
                var statues = h.getLevel().getEntitiesOfClass(Statue.class, new AABB(h.absolutePos(new BlockPos(12, 1, 4))).inflate(2));
                c.check("the player died", !p.isAlive(), "alive " + p.isAlive());
                c.check("one statue stands where the player froze", statues.size() == 1, statues.toString());
                if (statues.size() == 1) {
                    Statue s = statues.get(0);
                    c.check("the statue holds the diamonds", s.items().stream().anyMatch(i -> i.is(Items.DIAMOND) && i.getCount() == 7), s.items().toString());
                    c.check("the statue holds the experience", s.xp() == 50, "xp " + s.xp());
                    c.check("the statue belongs to the player", s.owner().equals(p.getUUID()));
                    s.crumble(h.getLevel());
                    var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(new BlockPos(12, 1, 4))).inflate(2));
                    c.check("crumbling gives the diamonds back", drops.stream().anyMatch(d -> d.getItem().is(Items.DIAMOND)), drops.toString());
                    drops.forEach(Entity::discard);
                }
                remove(h, p);
            };
        }));
        CASES.put("player_safe_inside", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            return () -> {
                c.check("inside the field the player is fine", p.isAlive() && Freezing.ticks(p) == 0, "ticks " + Freezing.ticks(p));
                remove(h, p);
            };
        }));
        CASES.put("player_grace_after_login", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), false);
            return () -> {
                c.check("just logged in: no freezing yet", p.isAlive() && Freezing.ticks(p) == 0, "ticks " + Freezing.ticks(p));
                remove(h, p);
            };
        }));
        // suits: a full charged set keeps its wearer going (and draws power); part of a set, or no charge, doesn't
        CASES.put("delver_suit_protects", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, true, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(),
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), org.erisia.stasis.StasisRegistry.DELVER_BOOTS.get());
            return () -> {
                double e = ((org.erisia.stasis.DelverSuitItem) p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem())
                    .getEnergy(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
                c.check("suited: no freezing", p.isAlive() && Freezing.ticks(p) == 0, "ticks " + Freezing.ticks(p));
                c.check("the suit drew power (10 W per piece)", e < 40000 && e > 40000 - 10 * 135 / 20.0 - 1, String.format("%.1f J", e));
                c.check("suited in stopped land: Mining Fatigue I", p.hasEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE));
                remove(h, p);
            };
        }));
        CASES.put("partial_suit_freezes", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, true, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(),
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), null);
            return () -> {
                c.check("three pieces of four: the player froze", !p.isAlive());
                remove(h, p);
            };
        }));
        CASES.put("empty_suit_freezes", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, false, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(),
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), org.erisia.stasis.StasisRegistry.DELVER_BOOTS.get());
            return () -> {
                c.check("an uncharged suit: the player froze", !p.isAlive());
                remove(h, p);
            };
        }));
        CASES.put("stasis_armor_protects", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, true, org.erisia.stasis.StasisRegistry.STASIS_HELMET.get(), org.erisia.stasis.StasisRegistry.STASIS_CHEST.get(),
                org.erisia.stasis.StasisRegistry.STASIS_LEGS.get(), org.erisia.stasis.StasisRegistry.STASIS_BOOTS.get());
            return () -> {
                c.check("stasis-proof E-Coal: no freezing", p.isAlive() && Freezing.ticks(p) == 0, "ticks " + Freezing.ticks(p));
                remove(h, p);
            };
        }));
        // towers: height sets the radius; half a store starts the field; an empty store collapses it over 15 s
        CASES.put("tower_runs_a_field", new Spec(10, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 3);
            var store = t.energy();
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                store.insert((int) (org.erisia.stasis.TowerBlockEntity.storeFor(20) * 0.4 * 4 / 3), tx);
                tx.commit();
            }
            h.runAfterDelay(3, () -> c.check("40% charged: still off", t.state() == org.erisia.stasis.TowerBlockEntity.State.OFF, t.state().name()));
            h.runAfterDelay(4, () -> {
                try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                    store.insert((int) (org.erisia.stasis.TowerBlockEntity.storeFor(20) * 0.2 * 4 / 3), tx);
                    tx.commit();
                }
            });
            return () -> {
                c.check("three segments: radius 20", t.radius() == 20.0, "r " + t.radius());
                c.check("over half charged: running", t.state() == org.erisia.stasis.TowerBlockEntity.State.RUNNING, t.state().name()
                    + " stored " + t.storedJ() + " of " + t.storeMaxJ() + " J, ticks " + t.ticks + ", same BE " + (h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 1, 4))) == t));
                var fields = FieldData.get(h.getLevel()).fields();
                c.check("its field is up, r 20", fields.stream().anyMatch(f -> f.radius() == 20.0
                    && f.containsPoint(h.absoluteVec(new Vec3(3.5, 1.5, 4.5)).x, h.absoluteVec(new Vec3(3.5, 1.5, 4.5)).y, h.absoluteVec(new Vec3(3.5, 1.5, 4.5)).z)), fields.toString());
                c.check("time runs next to the tower", Stasis.isRunning(h.getLevel(), h.absolutePos(new BlockPos(6, 1, 4))));
                t.setRemoved();   // breaking the tower takes its field down
                c.check("tower gone: field gone", FieldData.get(h.getLevel()).fields().stream().noneMatch(f -> f.radius() == 20.0));
            };
        }, false, true));
        CASES.put("tower_collapses_when_empty", new Spec(330, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 0);
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                t.energy().insert((int) (org.erisia.stasis.TowerBlockEntity.storeFor(8) * 0.6 * 4 / 3), tx);
                tx.commit();
            }
            h.runAfterDelay(5, () -> {
                c.check("running", t.state() == org.erisia.stasis.TowerBlockEntity.State.RUNNING, t.state().name());
                t.testDrawFactor = 1e6;   // empty the store in a tick
            });
            h.runAfterDelay(160, () -> {
                c.check("halfway through the collapse", t.state() == org.erisia.stasis.TowerBlockEntity.State.COLLAPSING, t.state().name());
                var f = FieldData.get(h.getLevel()).fields().stream().filter(x -> x.radius() == 8.0).findFirst();
                c.check("its field is partly collapsed", f.isPresent() && f.get().collapse() > 0.3 && f.get().collapse() < 0.7, f.toString());
            });
            return () -> {
                c.check("collapsed: off", t.state() == org.erisia.stasis.TowerBlockEntity.State.OFF, t.state().name());
                c.check("collapsed: the field is gone", FieldData.get(h.getLevel()).fields().stream().noneMatch(f -> f.radius() == 8.0));
            };
        }, false, true));
        // Electrical Age: nodes in stopped land leave the simulation; inside a field they run; cables and batteries in
        // a tower's chunk run even outside its field
        CASES.put("ea_nodes_stop_outside", new Spec(30, (h, c) -> {
            var inSrc = eaSix(h, new BlockPos(3, 1, 4), 0 + (3 << 6));           // Electrical Source, inside the field
            var outSrc = eaSix(h, new BlockPos(12, 1, 4), 0 + (3 << 6));         // outside
            var outCable = eaSix(h, new BlockPos(12, 1, 2), 4 + (32 << 6));      // Low Voltage Cable, outside
            ServerLevel level = h.getLevel();
            h.runAfterDelay(10, () -> {
                org.erisia.stasis.NodeFreezer.pass();
                c.check("inside: the source runs", !org.erisia.stasis.NodeFreezer.isFrozen(inSrc.sixNode)
                    && inSrc.electricalLoadList.get(0).getSubSystem() != null);
                c.check("outside: the source is out of the simulation", org.erisia.stasis.NodeFreezer.isFrozen(outSrc.sixNode)
                    && outSrc.electricalLoadList.get(0).getSubSystem() == null);
                c.check("outside: the cable too", org.erisia.stasis.NodeFreezer.isFrozen(outCable.sixNode));
                // time comes back: a field over the outside source puts it back
                Vec3 p = h.absoluteVec(new Vec3(12.5, 1.5, 4.5));
                Field f = FieldData.get(level).add(level, p.x, p.y, p.z, 3.0);
                c.cleanup.add(() -> FieldData.get(level).remove(level, f.id()));
                org.erisia.stasis.NodeFreezer.pass();
            });
            return () -> c.check("a field arrived: the source runs again", !org.erisia.stasis.NodeFreezer.isFrozen(outSrc.sixNode)
                && outSrc.electricalLoadList.get(0).getSubSystem() != null);
        }, true, true));
        CASES.put("ea_tower_chunk_keeps_cables", new Spec(30, (h, c) -> {
            tower(h, new BlockPos(12, 1, 6), 0);                                   // unpowered: no field
            var cable = eaSix(h, new BlockPos(12, 1, 4), 4 + (32 << 6));
            var src = eaSix(h, new BlockPos(11, 1, 4), 0 + (3 << 6));
            boolean sameChunk = (h.absolutePos(new BlockPos(12, 1, 6)).getX() >> 4) == (h.absolutePos(new BlockPos(11, 1, 4)).getX() >> 4)
                && (h.absolutePos(new BlockPos(12, 1, 6)).getZ() >> 4) == (h.absolutePos(new BlockPos(11, 1, 4)).getZ() >> 4)
                && (h.absolutePos(new BlockPos(12, 1, 6)).getZ() >> 4) == (h.absolutePos(new BlockPos(12, 1, 4)).getZ() >> 4);
            return () -> {
                org.erisia.stasis.NodeFreezer.pass();
                if (!sameChunk) {
                    c.check("(tower and devices fell in different chunks: skipped)", true);
                    return;
                }
                c.check("in a tower's chunk the cable runs", !org.erisia.stasis.NodeFreezer.isFrozen(cable.sixNode));
                c.check("a source is machinery: stopped", org.erisia.stasis.NodeFreezer.isFrozen(src.sixNode));
            };
        }, false, true));
        // stopped water and lava are solid; stopped lava hurts like magma and doesn't ignite. An enderman (unbound,
        // so it moves) dropped onto each; outside the field both fluids are stopped
        CASES.put("stopped_fluids_are_solid", new Spec(5, (h, c) -> {
            h.setBlock(new BlockPos(12, 1, 2), Blocks.WATER);
            h.setBlock(new BlockPos(12, 1, 6), Blocks.LAVA);
            h.setBlock(new BlockPos(3, 1, 2), Blocks.WATER);
            var pig = (net.minecraft.world.entity.LivingEntity) h.spawn(EntityTypes.PIG, new Vec3(12.5, 2.0, 6.5));
            return () -> {
                ServerLevel level = h.getLevel();
                var ctx = net.minecraft.world.phys.shapes.CollisionContext.of(pig);
                BlockPos water = h.absolutePos(new BlockPos(12, 1, 2)), lava = h.absolutePos(new BlockPos(12, 1, 6));
                BlockPos waterIn = h.absolutePos(new BlockPos(3, 1, 2));
                var full = net.minecraft.world.phys.shapes.Shapes.block();
                c.check("stopped water is solid to entities", level.getBlockState(water).getCollisionShape(level, water, ctx).equals(full));
                c.check("stopped lava is solid to entities", level.getBlockState(lava).getCollisionShape(level, lava, ctx).equals(full));
                c.check("running water isn't", level.getBlockState(waterIn).getCollisionShape(level, waterIn, ctx).isEmpty());
                float before = pig.getHealth();
                level.getBlockState(lava).getBlock().stepOn(level, lava, level.getBlockState(lava), pig);
                c.check("standing on stopped lava hurts like magma", pig.getHealth() < before, before + " -> " + pig.getHealth());
                c.check("and doesn't set it on fire", !pig.isOnFire());
                pig.discard();
            };
        }));
        // endermen, the dragon and the warden ignore stasis
        CASES.put("enderman_unbound", new Spec(20, (h, c) -> {
            // with AI on: a mob without AI doesn't fall at all, stopped or not
            Entity ender = h.spawn(EntityTypes.ENDERMAN, new Vec3(12.5, 3.0, 2.5));
            Entity pig = h.spawn(EntityTypes.PIG, new Vec3(12.5, 3.0, 6.0));
            double ey = ender.getY(), py = pig.getY();
            return () -> {
                c.check("outside: the enderman fell (not stopped)", ender.getY() < ey - 1.0, String.format("%.2f -> %.2f", ey, ender.getY()));
                c.check("outside: the pig hangs", pig.getY() == py, String.format("%.2f -> %.2f", py, pig.getY()));
                ender.discard();
                pig.discard();
            };
        }));
    }

    /** Place an EA six-node device (1.12 id) on the floor at a local position, as a player would. */
    static mods.eln.node.six.SixNodeElement eaSix(GameTestHelper h, BlockPos local, int damage) {
        mods.eln.node.six.SixNodeDescriptor d = mods.eln.Eln.sixNodeItem.getDescriptor(damage);
        ServerLevel level = h.getLevel();
        BlockPos p = h.absolutePos(local);
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
        if (!((mods.eln.node.six.SixNodeItem) d.parentItem).placeAt(d.newItemStack(), player, level, p, mods.eln.misc.Direction.YN))
            throw new IllegalStateException("placing " + d.name + " failed");
        var node = (mods.eln.node.six.SixNode) mods.eln.node.NodeManager.instance.getNodeFromCoordonate(new mods.eln.misc.Coordonate(p, level));
        return node.getElement(mods.eln.misc.Direction.YN);
    }

    static org.erisia.stasis.TowerBlockEntity tower(GameTestHelper h, BlockPos base, int segments) {
        for (int i = 1; i <= segments; i++) h.setBlock(base.above(i), org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get());
        h.setBlock(base, org.erisia.stasis.StasisRegistry.TOWER.get());
        return (org.erisia.stasis.TowerBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(base));
    }

    /** A survival-mode player placed at a local position (the vanilla mock player is in creative). */
    static ServerPlayer survivalPlayer(GameTestHelper h, Vec3 local, boolean endGrace) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "stasis-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 a = h.absoluteVec(local);
        p.teleportTo(a.x, a.y, a.z);
        if (endGrace) Freezing.endGrace(p);
        // the mock player's connection isn't ticked by the server, so no PlayerTickEvent: drive its freezing here
        h.onEachTick(() -> {
            if (p.isAlive() && !p.isRemoved()) Freezing.tick(p);
        });
        return p;
    }

    /** Put suit pieces on (null: leave the slot empty), charged full or empty. */
    static void wear(ServerPlayer p, boolean charged, net.minecraft.world.item.Item head, net.minecraft.world.item.Item chest,
                     net.minecraft.world.item.Item legs, net.minecraft.world.item.Item feet) {
        net.minecraft.world.item.Item[] items = {head, chest, legs, feet};
        net.minecraft.world.entity.EquipmentSlot[] slots = {net.minecraft.world.entity.EquipmentSlot.HEAD, net.minecraft.world.entity.EquipmentSlot.CHEST,
            net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            if (items[i] == null) continue;
            ItemStack st = new ItemStack(items[i]);
            var b = (mods.eln.item.electricalinterface.IItemEnergyBattery) items[i];
            b.setEnergy(st, charged ? b.getEnergyMax(st) : 0);
            p.setItemSlot(slots[i], st);
        }
    }

    static void remove(GameTestHelper h, ServerPlayer p) {
        h.getLevel().getServer().getPlayerList().remove(p);
    }

    static ItemEntity item(GameTestHelper h, Vec3 local) {
        Vec3 p = h.absoluteVec(local);
        ItemEntity e = new ItemEntity(h.getLevel(), p.x, p.y, p.z, new ItemStack(Items.STONE));
        e.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(e);
        return e;
    }

    static FurnaceBlockEntity furnace(GameTestHelper h, BlockPos local) {
        h.setBlock(local, Blocks.FURNACE);
        FurnaceBlockEntity f = (FurnaceBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(local));
        f.setItem(0, new ItemStack(Items.RAW_IRON, 4));
        f.setItem(1, new ItemStack(Items.COAL, 2));
        return f;
    }

    /** Collected checks; the test fails at the end listing every failed one. */
    static final class Checks {
        final List<String> failures = new ArrayList<>();
        final List<Runnable> cleanup = new ArrayList<>();
        final String id;

        Checks(String id) {
            this.id = id;
        }

        void check(String what, boolean ok) {
            check(what, ok, "");
        }

        void check(String what, boolean ok, String detail) {
            String l = (ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail);
            StasisDelvers.LOG.info("[{}] {}", id, l);
            if (!ok) failures.add(l);
        }
    }

    static void run(GameTestHelper h, String id, Spec spec) {
        ServerLevel level = h.getLevel();
        // the stone floor (the structure template only gives the empty box)
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 8; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
        Vec3 centre = h.absoluteVec(new Vec3(3.5, 2.5, 4.5));
        Checks c = new Checks(id);
        if (spec.field()) {
            Field field = FieldData.get(level).add(level, centre.x, centre.y, centre.z, R);
            c.cleanup.add(() -> FieldData.get(level).remove(level, field.id()));
        }
        Runnable check;
        try {
            check = spec.body().build(h, c);
        } catch (RuntimeException e) {
            c.cleanup.forEach(Runnable::run);
            h.fail(id + " build: " + e);
            return;
        }
        h.runAfterDelay(spec.ticks(), () -> {
            try {
                check.run();
            } catch (RuntimeException e) {
                c.check(id + " measure", false, e.toString());
            } finally {
                c.cleanup.forEach(Runnable::run);
            }
            if (c.failures.isEmpty()) h.succeed();
            else h.fail(String.join("\n", c.failures));
        });
    }

    public static void register(IEventBus modBus) {
        for (var e : CASES.entrySet()) FUNCTIONS.register(e.getKey(), () -> h -> run(h, e.getKey(), e.getValue()));
        FUNCTIONS.register(modBus);
        modBus.addListener(RegisterGameTestsEvent.class, StasisGameTests::registerTests);
    }

    static void registerTests(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> env = event.registerEnvironment(
            Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "default"), new TestEnvironmentDefinition.AllOf(List.of()));
        for (var e : CASES.entrySet()) {
            Identifier id = Identifier.fromNamespaceAndPath(StasisDelvers.MODID, e.getKey());
            // a test environment of its own puts an isolated test in a batch of its own
            Holder<TestEnvironmentDefinition<?>> testEnv = !e.getValue().isolated() ? env : event.registerEnvironment(
                Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "alone_" + e.getKey()), new TestEnvironmentDefinition.AllOf(List.of()));
            ResourceKey<Consumer<GameTestHelper>> fn = ResourceKey.create(Registries.TEST_FUNCTION, id);
            event.registerTest(id, new FunctionGameTestInstance(fn,
                new TestData<>(testEnv, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "floor_16x6x8"), e.getValue().ticks() + 40, 0, true)));
        }
    }
}
