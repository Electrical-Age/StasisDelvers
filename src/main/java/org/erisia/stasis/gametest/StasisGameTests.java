package org.erisia.stasis.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.erisia.stasis.Flames;
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
            Field col = new Field(0, 3.5, 2.5, 4.5, R, true);
            c.check("column field: far above and below the centre is inside", col.containsBlock(3, 300, 4) && col.containsBlock(3, -60, 4));
            c.check("column field: outside its radius is outside", !col.containsBlock(12, 2, 4));
            ServerLevel level = h.getLevel();
            BlockPos out = h.absolutePos(new BlockPos(12, 2, 4));
            c.check("outside the field time is stopped here", !Stasis.isRunning(level, out));
            c.check("inside it runs", Stasis.isRunning(level, h.absolutePos(new BlockPos(3, 2, 4))));
        }));
        // Baughn: a tower's booms stretch its field towards their side (Field.Stretch; notes/tower/PLAN-stretch.md)
        CASES.put("stretched_field_shape", new Spec(1, (h, c) -> () -> {
            Field f = new Field(0, 0.5, 0.5, 0.5, 10).withStretch(new Field.Stretch(1.5, 1, 1, 1.25));
            c.check("east reaches 1.5 r (whole blocks to x 14)", f.containsBlock(14, 0, 0) && !f.containsBlock(15, 0, 0));
            c.check("west stays at r", f.containsBlock(-9, 0, 0) && !f.containsBlock(-10, 0, 0));
            c.check("north reaches 1.25 r, south r", f.containsBlock(0, 0, -11) && !f.containsBlock(0, 0, -12) && !f.containsBlock(0, 0, 10));
            c.check("up and down stay at r", f.containsBlock(0, 9, 0) && !f.containsBlock(0, 10, 0) && !f.containsBlock(0, -10, 0));
            c.check("points: on the stretched axis", f.containsPoint(15.4, 0.5, 0.5) && !f.containsPoint(15.6, 0.5, 0.5));
            c.check("a diagonal follows the ellipse", f.containsPoint(0.5 + 15 * 0.7, 0.5, 0.5 - 12.5 * 0.7) && !f.containsPoint(0.5 + 15 * 0.72, 0.5, 0.5 - 12.5 * 0.72));
            c.check("bounds", f.minX() == -9.5 && f.maxX() == 15.5 && f.minZ() == -12 && f.maxZ() == 10.5, f.minX() + " " + f.maxX() + " " + f.minZ() + " " + f.maxZ());
            var enc = Field.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, f).getOrThrow();
            c.check("codec round trip keeps the stretch", Field.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, enc).getOrThrow().equals(f), enc.toString());
            var old = enc.getAsJsonObject().deepCopy();
            old.remove("stretch");
            c.check("a field saved before stretch loads round", Field.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, old).getOrThrow().stretch().isOne());
            Field round = new Field(0, 0.5, 0.5, 0.5, 10);
            boolean same = true;
            for (int x = -12; x <= 12; x++) for (int y = -12; y <= 12; y++) for (int z = -12; z <= 12; z++) {
                double dx = (x + 0.5 >= 0.5 ? x + 1 : x) - 0.5, dy = (y + 0.5 >= 0.5 ? y + 1 : y) - 0.5, dz = (z + 0.5 >= 0.5 ? z + 1 : z) - 0.5;
                if (round.containsBlock(x, y, z) != (dx * dx + dy * dy + dz * dz <= 100)) same = false;
            }
            c.check("an unstretched field is the same sphere as before", same);
            var lay = new org.erisia.stasis.towergen.Layout(4, List.of(new org.erisia.stasis.towergen.Layout.Boom(2, 2, 2),
                new org.erisia.stasis.towergen.Layout.Boom(2, 3, 1)));
            var st = org.erisia.stasis.TowerBlockEntity.stretchFor(lay);
            c.check("booms: a full-length one stretches its side 1 + k, a half one 1 + k/2 (k " + org.erisia.stasis.StasisConfig.boomStretch() + ")",
                Math.abs(st.w() - (1 + org.erisia.stasis.StasisConfig.boomStretch())) < 1e-9 && Math.abs(st.n() - (1 + 0.5 * org.erisia.stasis.StasisConfig.boomStretch())) < 1e-9
                    && st.e() == 1 && st.s() == 1, st.toString());
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
        // Baughn: a newly generated chunk runs a few seconds before it stops (mobs generated on top of each other
        // walk apart): sand in its grace falls; once the grace is over, new sand hangs. Isolated: the grace is for a
        // whole chunk column, and the test grid (rows 15 apart) can put a neighbour's blocks in the same chunk (in
        // 1 run of 16, time_resumes' sand fell in this grace)
        CASES.put("new_chunk_grace", new Spec(60, (h, c) -> {
            ServerLevel level = h.getLevel();
            BlockPos a = new BlockPos(12, 3, 4), b = new BlockPos(13, 3, 4);
            long key = net.minecraft.world.level.ChunkPos.pack(h.absolutePos(a).getX() >> 4, h.absolutePos(a).getZ() >> 4);
            org.erisia.stasis.NewChunkGrace.grant(level, key, 25);
            h.setBlock(a, Blocks.SAND);
            h.runAfterDelay(30, () -> h.setBlock(b, Blocks.SAND));
            return () -> {
                c.check("in its grace, the new chunk ran: the sand fell", h.getBlockState(a).isAir() && h.getBlockState(new BlockPos(12, 1, 4)).is(Blocks.SAND),
                    h.getBlockState(a).toString());
                c.check("after the grace it stopped: new sand hangs", h.getBlockState(b).is(Blocks.SAND));
            };
        }, false, true));
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
        // λ-Nero/Baughn: a furnace cart inside a field pushes a cart frozen at the border out (one tick's worth per push,
        // no momentum kept)
        CASES.put("furnace_cart_pushes_frozen_cart", new Spec(100, (h, c) -> {
            for (int x = 1; x <= 15; x++) h.setBlock(new BlockPos(x, 1, 4), Blocks.RAIL);
            ServerLevel level = h.getLevel();
            Vec3 cp = h.absoluteVec(new Vec3(7.3, 1.1, 4.5));   // (just past the border, in the first stopped block: frozen)
            var cart = new net.minecraft.world.entity.vehicle.minecart.Minecart(net.minecraft.world.entity.EntityTypes.MINECART, level);
            cart.setPos(cp.x, cp.y, cp.z);
            level.addFreshEntity(cart);
            Vec3 fp = h.absoluteVec(new Vec3(4.5, 1.1, 4.5));
            var furnace = new net.minecraft.world.entity.vehicle.minecart.MinecartFurnace(net.minecraft.world.entity.EntityTypes.FURNACE_MINECART, level);
            furnace.setPos(fp.x, fp.y, fp.z);
            level.addFreshEntity(furnace);
            furnace.addFuel(fp.add(-1, 0, 0), new ItemStack(Items.COAL));
            double x0 = cart.getX();
            return () -> {
                c.check("the frozen cart was pushed out (east)", cart.getX() > x0 + 0.5, String.format("%.2f -> %.2f; the furnace cart at %.2f, fuel push %s", x0, cart.getX(), furnace.getX(), furnace.push));
                c.check("it keeps no momentum", cart.getDeltaMovement().horizontalDistanceSqr() < 1e-6, cart.getDeltaMovement().toString());
                c.check("the server marks it frozen for the client", cart.getData(org.erisia.stasis.StasisRegistry.FROZEN.get()), "");
            };
        }));
        // Baughn: mobs avoid stopped land: a step into it costs (a running mob's path), inside it doesn't
        // λ-Nero: blocks go against stopped water (the crosshair passes through it), so a hole in it isn't a trap
        CASES.put("place_against_stopped_water", new Spec(2, (h, c) -> {
            BlockPos w = new BlockPos(12, 1, 4);
            h.setBlock(w, Blocks.WATER);
            var player = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            Vec3 eye = h.absoluteVec(new Vec3(12.5, 1.5, 7.5));
            player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
            player.setYRot(180);   // (facing north, -z: at the water's south face)
            player.setXRot(0);
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 4));
            var r = net.neoforged.neoforge.common.CommonHooks.onItemRightClick(player, net.minecraft.world.InteractionHand.MAIN_HAND);
            return () -> c.check("cobblestone went in front of the water's face", h.getBlockState(w.south()).is(Blocks.COBBLESTONE)
                && h.getBlockState(w).is(Blocks.WATER), r + " " + h.getBlockState(w.south()) + " / " + h.getBlockState(w));
        }));
        // λ-Nero: a lead lets a player drag a mob through stopped land (frozen, it moves only by the lead's pull)
        CASES.put("lead_drags_frozen_mob", new Spec(40, (h, c) -> {
            ServerLevel level = h.getLevel();
            Vec3 p = h.absoluteVec(new Vec3(14.5, 1.0, 4.5));
            var cow = new net.minecraft.world.entity.animal.cow.Cow(net.minecraft.world.entity.EntityTypes.COW, level);
            cow.setPos(p.x, p.y, p.z);
            level.addFreshEntity(cow);
            var player = h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
            Vec3 pp = h.absoluteVec(new Vec3(6.5, 1.0, 4.5));
            player.setPos(pp.x, pp.y, pp.z);
            cow.setLeashedTo(player, true);
            double x0 = cow.getX();
            return () -> {
                c.check("the leashed cow was dragged towards the player", cow.getX() < x0 - 1, String.format("%.2f -> %.2f", x0 - p.x + 14.5, cow.getX() - p.x + 14.5));
                c.check("frozen, it keeps no momentum", cow.getX() < h.absoluteVec(new Vec3(7, 0, 0)).x
                    || cow.getDeltaMovement().horizontalDistanceSqr() < 1e-6, cow.getDeltaMovement().toString());
                cow.discard();
            };
        }));
        CASES.put("mobs_avoid_stopped_land", new Spec(5, (h, c) -> {
            ServerLevel level = h.getLevel();
            Vec3 p = h.absoluteVec(new Vec3(3.5, 1.0, 4.5));
            var cow = new net.minecraft.world.entity.animal.cow.Cow(net.minecraft.world.entity.EntityTypes.COW, level);
            cow.setPos(p.x, p.y, p.z);
            level.addFreshEntity(cow);
            var ctx = new net.minecraft.world.level.pathfinder.PathfindingContext(level, cow);
            var in = net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getPathTypeStatic(ctx, h.absolutePos(new BlockPos(4, 1, 4)).mutable());
            var out = net.minecraft.world.level.pathfinder.WalkNodeEvaluator.getPathTypeStatic(ctx, h.absolutePos(new BlockPos(13, 1, 4)).mutable());
            return () -> {
                c.check("inside: plain walkable", in == net.minecraft.world.level.pathfinder.PathType.WALKABLE, in.toString());
                c.check("outside: costly", out == net.minecraft.world.level.pathfinder.PathType.DAMAGING_IN_NEIGHBOR, out.toString());
                cow.discard();
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
        // eddies (Baughn): a weak field has drifting stopped pockets; flames and EA keep the steady rule; an unsuited
        // player caught in one is held but never made a statue
        CASES.put("eddies_in_a_weak_field", new Spec(5, (h, c) -> {
            ServerLevel level = h.getLevel();
            Field f = field(h, c, new Vec3(8.5, 6.5, 4.5), 14, 1.0);
            int[] counts = new int[3];
            BlockPos[] pocket = {null};
            Runnable count = () -> {
                counts[0] = counts[1] = counts[2] = 0;
                Field now = FieldData.get(level).get(f.id()).orElseThrow();
                for (int x = -10; x <= 10; x++)
                    for (int y = -6; y <= 6; y++)
                        for (int z = -10; z <= 10; z++) {
                            BlockPos p = BlockPos.containing(now.x() + x, now.y() + y, now.z() + z);
                            if (!now.containsBlock(p.getX(), p.getY(), p.getZ())) continue;
                            counts[0]++;
                            if (!Stasis.isRunning(level, p)) {
                                counts[1]++;
                                if (Stasis.isRunningSteady(level, p)) counts[2]++;
                                if (pocket[0] == null && y == -6 + 2) pocket[0] = p;
                            }
                        }
            };
            return () -> {
                count.run();
                c.check("a healthy field has no pockets", counts[1] == 0, counts[1] + " of " + counts[0]);
                FieldData.get(level).update(level, f.id(), x -> x.withFailing(0.95));
                pocket[0] = null;
                count.run();
                c.check("a weak field: 5-60% of it in stopped pockets", counts[1] > 0.05 * counts[0] && counts[1] < 0.6 * counts[0], counts[1] + " of " + counts[0]);
                c.check("the steady rule (flames, EA) ignores them", counts[2] == counts[1], counts[2] + " vs " + counts[1]);
                if (pocket[0] != null) {
                    ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
                    p.teleportTo(pocket[0].getX() + 0.5, pocket[0].getY(), pocket[0].getZ() + 0.5);
                    for (int i = 0; i < 400; i++) Freezing.tick(p);
                    c.check("held in the pocket, never a statue", p.isAlive() && Freezing.ticks(p) > 0 && Freezing.ticks(p) < org.erisia.stasis.StasisConfig.freezeTicks(),
                        "alive " + p.isAlive() + ", ticks " + Freezing.ticks(p));
                    remove(h, p);
                } else c.check("found a pocket at player height", false, "none");
            };
        }, false, true));
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
                double full = org.erisia.stasis.StasisRegistry.DELVER_HELMET.get().getEnergyMax(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
                c.check("the suit drew power (10 W per piece)", e < full && e > full - 10 * 135 / 20.0 - 1, String.format("%.1f J", e));
                c.check("a full Delver suit lasts 30 minutes (Baughn)", Math.abs(full - 10 * 60 * 30) < 1e-6, full + " J");
                c.check("a full suit: no Mining Fatigue (Baughn)", !p.hasEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE));
                remove(h, p);
            };
        }));
        // λ-Nero, Baughn 2026-10-18: in the plain Grey a chest piece is enough (for other armour and enchantments), but it
        // carries the whole suit's draw; without one the suit doesn't work
        CASES.put("partial_suit_freezes", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, true, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), null,
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), org.erisia.stasis.StasisRegistry.DELVER_BOOTS.get());
            return () -> {
                c.check("three pieces but no chest piece: the player froze", !p.isAlive());
                remove(h, p);
            };
        }));
        CASES.put("chest_piece_alone_protects", new Spec(130, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, true, null, org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(), null, null);
            var chest = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            var item = (org.erisia.stasis.DelverSuitItem) chest.getItem();
            double full = item.getEnergy(chest);
            return () -> {
                c.check("a chest piece alone: no freezing in the plain Grey", p.isAlive() && Freezing.ticks(p) == 0, "ticks " + Freezing.ticks(p));
                c.check("but digging is slow (Mining Fatigue I: a full suit takes it away)", p.hasEffect(net.minecraft.world.effect.MobEffects.MINING_FATIGUE));
                double used = full - item.getEnergy(chest), perPiece = item.stasisDraw(chest) * 130 / 20.0;
                c.check("it draws for the whole suit (4 pieces' worth)", used > 3.8 * perPiece && used < 4.3 * perPiece,
                    String.format("%.1f J, one piece's share %.1f J", used, perPiece));
                remove(h, p);
            };
        }));
        CASES.put("chest_piece_alone_in_mist", new Spec(60, (h, c) -> {
            var box = new AABB(h.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 6, 9);
            org.erisia.stasis.Pockets.TEST.put(box, new org.erisia.stasis.Pockets.Weather(org.erisia.stasis.Pockets.State.MIST, 0.6f, 1000));
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, true, null, org.erisia.stasis.StasisRegistry.STASIS_CHEST.get(), null, null);
            var chest = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST);
            var item = (org.erisia.stasis.StasisArmorItem) chest.getItem();
            double full = item.getEnergy(chest);
            return () -> {
                org.erisia.stasis.Pockets.TEST.remove(box);
                c.check("in mist a lone chest piece (even stasis armour) lets the freeze creep in", p.isAlive() && Freezing.ticks(p) > 20,
                    "ticks " + Freezing.ticks(p));
                double used = full - item.getEnergy(chest), plain = 4 * item.stasisDraw(chest) * 60 / 20.0;
                c.check("and drains x2 x (1 + 3 missing) over the whole suit's draw", used > 7 * plain, String.format("%.1f J vs %.1f", used, plain));
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
        // survey poles extend like scaffolding: the mast from anywhere on it, booms outward, sneaking starts a boom
        CASES.put("poles_extend_like_scaffolding", new Spec(25, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 6);   // poles y3..y8
            var player = h.makeMockPlayer(net.minecraft.world.level.GameType.CREATIVE);
            var pole = org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get();
            java.util.function.BiConsumer<BlockPos, net.minecraft.core.Direction> use = (local, face) -> {
                BlockPos a = h.absolutePos(local);
                var hit = new net.minecraft.world.phys.BlockHitResult(Vec3.atCenterOf(a).relative(face, 0.5), face, a, false);
                new ItemStack(pole).useOn(new net.minecraft.world.item.context.UseOnContext(h.getLevel(), player,
                    net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(pole), hit));
            };
            java.util.function.BiPredicate<BlockPos, net.minecraft.core.Direction.Axis> at = (local, axis) -> {
                var st = h.getBlockState(local);
                return st.is(pole) && st.getValue(org.erisia.stasis.TowerPoleBlock.AXIS) == axis;
            };
            var Y = net.minecraft.core.Direction.Axis.Y;
            var X = net.minecraft.core.Direction.Axis.X;
            use.accept(new BlockPos(3, 3, 4), net.minecraft.core.Direction.NORTH);
            c.check("side of the lowest mast pole: the mast grows at its top", at.test(new BlockPos(3, 9, 4), Y) && h.getBlockState(new BlockPos(3, 3, 3)).isAir());
            player.setShiftKeyDown(true);
            use.accept(new BlockPos(3, 3, 4), net.minecraft.core.Direction.EAST);
            c.check("sneaking on the mast's side: a boom starts", at.test(new BlockPos(4, 3, 4), X));
            player.setShiftKeyDown(false);
            use.accept(new BlockPos(4, 3, 4), net.minecraft.core.Direction.UP);
            use.accept(new BlockPos(4, 3, 4), net.minecraft.core.Direction.UP);
            c.check("on the boom: it grows outward, away from the mast", at.test(new BlockPos(5, 3, 4), X) && at.test(new BlockPos(6, 3, 4), X)
                && !h.getBlockState(new BlockPos(4, 4, 4)).is(pole));
            player.setShiftKeyDown(true);
            use.accept(new BlockPos(3, 3, 4), net.minecraft.core.Direction.EAST);
            c.check("sneaking on the mast's side again: that boom grows", at.test(new BlockPos(7, 3, 4), X));
            // a boom at the top is against the rules (it needs 2 mast poles above it): refused at placement
            use.accept(new BlockPos(3, 9, 4), net.minecraft.core.Direction.EAST);
            c.check("a boom at the mast top is refused", !h.getBlockState(new BlockPos(4, 9, 4)).is(pole));
            player.setShiftKeyDown(false);
            return () -> c.check("only the mast counts as segments", t.segments() == 7, "segments " + t.segments());
        }));
        // Baughn: a boom may start from the core's top block (y 2), e.g. a lopsided H 4 tower
        CASES.put("boom_from_core_top", new Spec(1, (h, c) -> {
            BlockPos base = new BlockPos(3, 1, 4);
            tower(h, base, 2);                                                     // H 4
            var boomPole = org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get().defaultBlockState()
                .setValue(org.erisia.stasis.TowerPoleBlock.AXIS, net.minecraft.core.Direction.Axis.X);
            h.setBlock(base.above().east(1), boomPole);
            h.setBlock(base.above().east(2), boomPole);
            var l = org.erisia.stasis.TowerStructure.scan(h.getLevel(), h.absolutePos(base), 100);
            return () -> c.check("H 4 with a boom E 2 at y 2, valid", l != null && l.H() == 4
                && l.booms().equals(List.of(new org.erisia.stasis.towergen.Layout.Boom(2, 0, 2))) && l.violations().isEmpty(), l + " " + (l == null ? "" : l.violations()));
        }, false, true));
        // P3d: the core reads its poles into a layout, builds it off-thread, then turns the poles into ladder / hidden
        CASES.put("tower_reads_poles", new Spec(1, (h, c) -> {
            BlockPos base = new BlockPos(3, 1, 4);
            tower(h, base, 12);
            var boomPole = org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get().defaultBlockState()
                .setValue(org.erisia.stasis.TowerPoleBlock.AXIS, net.minecraft.core.Direction.Axis.X);
            for (int i = 1; i <= 4; i++) h.setBlock(base.above(7).east(i), boomPole);
            h.setBlock(base.above(10).west(1), boomPole);
            h.setBlock(base.above(4).north(1), org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get());   // vertical: not a boom
            var l = org.erisia.stasis.TowerStructure.scan(h.getLevel(), h.absolutePos(base), 100);
            return () -> c.check("mast 12 (H 14), booms E 4 at y 8 and W 1 at y 11",
                l != null && l.H() == 14 && l.booms().equals(List.of(new org.erisia.stasis.towergen.Layout.Boom(8, 0, 4),
                    new org.erisia.stasis.towergen.Layout.Boom(11, 2, 1))), String.valueOf(l));
        }, false, true));
        CASES.put("tower_builds_and_parts_poles", new Spec(120, (h, c) -> {
            org.erisia.stasis.TowerStructure.syncBuild = true;
            c.cleanup.add(() -> org.erisia.stasis.TowerStructure.syncBuild = false);
            BlockPos base = new BlockPos(3, 1, 4);
            var t = tower(h, base, 12);
            var boomPole = org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get().defaultBlockState()
                .setValue(org.erisia.stasis.TowerPoleBlock.AXIS, net.minecraft.core.Direction.Axis.X);
            for (int i = 1; i <= 4; i++) h.setBlock(base.above(7).east(i), boomPole);
            return () -> {
                var s = t.structure;
                c.check("built OK", s.status == org.erisia.stasis.TowerStructure.Status.OK, s.status + " " + s.message);
                double bk = org.erisia.stasis.StasisConfig.boomStretch();
                c.check("the boom E 4 at y 8 (of 6 allowed) stretches the field east only", Math.abs(t.stretch.e() - (1 + bk * 4 / 6.0)) < 1e-9
                    && t.stretch.s() == 1 && t.stretch.w() == 1 && t.stretch.n() == 1, t.stretch.toString());
                c.check("ladder top inside the mast", s.ladderTop > 2 && s.ladderTop <= 14, "ladder top " + s.ladderTop);
                boolean ok = true;
                StringBuilder sb = new StringBuilder();
                for (int k = 1; k <= 12; k++) {
                    var part = h.getBlockState(base.above(1 + k)).getValue(org.erisia.stasis.TowerPoleBlock.PART);
                    var want = k + 2 <= s.ladderTop ? org.erisia.stasis.TowerPoleBlock.Part.LADDER : org.erisia.stasis.TowerPoleBlock.Part.HIDDEN;
                    if (part != want) { ok = false; sb.append(k).append('=').append(part).append(' '); }
                }
                c.check("mast poles: ladder up to the ladder top, hidden above", ok, sb.toString());
                c.check("boom poles hidden", h.getBlockState(base.above(7).east(3)).getValue(org.erisia.stasis.TowerPoleBlock.PART)
                    == org.erisia.stasis.TowerPoleBlock.Part.HIDDEN);
            };
        }, false, true));
        CASES.put("tower_invalid_keeps_survey_poles", new Spec(120, (h, c) -> {
            BlockPos base = new BlockPos(3, 1, 4);
            var t = tower(h, base, 12);
            var boomPole = org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get().defaultBlockState()
                .setValue(org.erisia.stasis.TowerPoleBlock.AXIS, net.minecraft.core.Direction.Axis.X);
            h.setBlock(base.above(13).east(1), boomPole);       // a boom at the very top: y 14 > H - 2
            return () -> {
                c.check("invalid", t.structure.status == org.erisia.stasis.TowerStructure.Status.INVALID, t.structure.status + " " + t.structure.message);
                c.check("poles stay survey poles", h.getBlockState(base.above(6)).getValue(org.erisia.stasis.TowerPoleBlock.PART)
                    == org.erisia.stasis.TowerPoleBlock.Part.SURVEY);
            };
        }, false, true));
        // softlock audit 2: frozen items never count their pickup delay down; they must still be pickable
        CASES.put("items_pickable_in_stopped_land", new Spec(5, (h, c) -> {
            var item = new ItemEntity(h.getLevel(), h.absoluteVec(new Vec3(12.5, 1.5, 4.5)).x, h.absoluteVec(new Vec3(12.5, 1.5, 4.5)).y,
                h.absoluteVec(new Vec3(12.5, 1.5, 4.5)).z, new ItemStack(Items.COBBLESTONE, 3));
            item.setPickUpDelay(40);
            h.getLevel().addFreshEntity(item);
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1, 4.5), false);
            var thrown = new ItemEntity(h.getLevel(), h.absoluteVec(new Vec3(12.5, 1.5, 4.5)).x, h.absoluteVec(new Vec3(12.5, 1.5, 4.5)).y,
                h.absoluteVec(new Vec3(12.5, 1.5, 4.5)).z, new ItemStack(Items.COBBLESTONE, 2));
            thrown.setPickUpDelay(40);
            item.setPickUpDelay(4);
            h.runAfterDelay(1, () -> {
                h.getLevel().addFreshEntity(thrown);
                thrown.playerTouch(p);
                c.check("a fresh drop isn't picked up at once (Q-drop)", !thrown.isRemoved());
                thrown.discard();
            });
            return () -> {
                c.check("outside the field: stopped", !Stasis.isRunning(h.getLevel(), item.blockPosition()));
                item.playerTouch(p);
                c.check("picked up despite the frozen pickup delay", item.isRemoved() && p.getInventory().countItem(Items.COBBLESTONE) == 3,
                    "removed " + item.isRemoved() + ", in inventory " + p.getInventory().countItem(Items.COBBLESTONE));
                var fake = new ItemEntity(h.getLevel(), item.getX(), item.getY(), item.getZ(), new ItemStack(Items.DIRT));
                fake.makeFakeItem();
                h.getLevel().addFreshEntity(fake);
                fake.playerTouch(p);
                c.check("/give's fake item: not picked up (no dupe), and gone", p.getInventory().countItem(Items.DIRT) == 0 && fake.isRemoved());
                var never = new ItemEntity(h.getLevel(), item.getX(), item.getY(), item.getZ(), new ItemStack(Items.STICK));
                never.setNeverPickUp();
                h.getLevel().addFreshEntity(never);
                never.playerTouch(p);
                c.check("never-pickup item stays", p.getInventory().countItem(Items.STICK) == 0 && !never.isRemoved());
                never.discard();
                p.discard();
            };
        }));
        // softlock audit 4: a field that vanishes at once leaves its players a grace period, not just the 5 s freeze
        CASES.put("field_removed_gives_grace", new Spec(130, (h, c) -> {
            ServerLevel level = h.getLevel();
            Vec3 a = h.absoluteVec(new Vec3(3.5, 1, 4.5));
            Field f = FieldData.get(level).add(level, a.x, a.y, a.z, 6);
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1, 4.5), true);
            h.runAfterDelay(5, () -> FieldData.get(level).remove(level, f.id()));
            return () -> {
                c.check("still alive after more than the freeze time", p.isAlive() && Freezing.ticks(p) == 0, "alive " + p.isAlive() + " ticks " + Freezing.ticks(p));
                p.discard();
            };
        }, false, true));
        // core blocks: one broken out of a formed core drops itself; the other 17 fall back to loose core blocks
        CASES.put("core_breaks_into_loose_blocks", new Spec(5, (h, c) -> {
            BlockPos base = new BlockPos(3, 1, 4);
            tower(h, base, 0);
            ServerPlayer p = survivalPlayer(h, new Vec3(6.5, 1, 4.5), true);
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            p.gameMode.destroyBlock(h.absolutePos(base.above()));
            return () -> {
                int loose = 0, formed = 0;
                for (int dy = 0; dy <= 1; dy++) {
                    var st = h.getBlockState(base.above(dy));
                    if (st.is(org.erisia.stasis.StasisRegistry.TOWER.get())) {
                        if (org.erisia.stasis.TowerBlock.formed(st)) formed++; else loose++;
                    }
                }
                int items = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(base)).inflate(4))
                    .stream().mapToInt(e -> e.getItem().is(org.erisia.stasis.StasisRegistry.TOWER_ITEM.get()) ? e.getItem().getCount() : 0).sum();
                c.check("1 loose block left, none formed", loose == 1 && formed == 0, loose + " loose, " + formed + " formed");
                c.check("the broken block dropped itself", items == 1, items + " dropped");
                var be = h.getLevel().getBlockEntity(h.absolutePos(base));
                c.check("no tower left: off, no structure", be instanceof org.erisia.stasis.TowerBlockEntity t
                    && t.state() == org.erisia.stasis.TowerBlockEntity.State.OFF && t.structure.status == org.erisia.stasis.TowerStructure.Status.NONE);
                p.discard();
            };
        }));
        // suit recipes load (they use EA's copper coil and coal plates)
        CASES.put("suit_recipes_load", new Spec(1, (h, c) -> {
            var recipes = h.getLevel().getServer().getRecipeManager();
            List<String> missing = new ArrayList<>();
            for (String n : List.of("delver_helmet", "delver_chestplate", "delver_leggings", "delver_boots",
                "stasis_helmet", "stasis_chestplate", "stasis_leggings", "stasis_boots", "stasis_tower", "stasis_tower_segment")) {
                var key = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, n));
                if (recipes.byKey(key).isEmpty()) missing.add(n);
            }
            return () -> c.check("all suit and tower recipes loaded", missing.isEmpty(), "missing " + missing);
        }));
        // the spawn cottage: a template with a tower is placed on the surface, its tower starts full and runs a field
        CASES.put("spawn_cottage_places_a_running_tower", new Spec(10, (h, c) -> {
            ServerLevel level = h.getLevel();
            BlockPos base = new BlockPos(3, 1, 4);
            tower(h, base, 2);
            var t = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate();
            BlockPos from = h.absolutePos(new BlockPos(2, 1, 3));
            t.fillFromWorld(level, from, new net.minecraft.core.Vec3i(3, 4, 3), false, List.of());
            for (int x = 2; x <= 4; x++) for (int y = 1; y <= 4; y++) for (int z = 3; z <= 5; z++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            var saved = level.getRespawnData();
            c.cleanup.add(() -> level.setRespawnData(saved));
            BlockPos spawn = org.erisia.stasis.SpawnCottage.place(level, t, h.absolutePos(new BlockPos(11, 1, 4)));
            return () -> {
                BlockPos master = null;
                for (int x = -3; x <= 3; x++) for (int y = -6; y <= 0; y++) for (int z = -3; z <= 3; z++)
                    if (level.getBlockState(spawn.offset(x, y, z)).is(org.erisia.stasis.StasisRegistry.TOWER.get())
                        && level.getBlockState(spawn.offset(x, y, z)).getValue(org.erisia.stasis.TowerBlock.CELL) == org.erisia.stasis.TowerBlock.MASTER)
                        master = spawn.offset(x, y, z);
                c.check("the cottage's core arrived whole (2 cells)", master != null && countCore(level, master) == 2, "master " + master + " cells " + (master == null ? 0 : countCore(level, master)));
                var tb = master == null ? null : (org.erisia.stasis.TowerBlockEntity) level.getBlockEntity(master);
                c.check("its tower runs (full store)", tb != null && tb.state() == org.erisia.stasis.TowerBlockEntity.State.RUNNING, tb == null ? "-" : tb.state() + " " + tb.storedJ());
                c.check("spawn set to the cottage", level.getRespawnData().pos().equals(spawn), spawn.toShortString());
                if (tb != null) tb.setRemoved();
                for (int x = -3; x <= 3; x++) for (int y = -6; y <= 1; y++) for (int z = -3; z <= 3; z++)
                    level.setBlock(spawn.offset(x, y, z), Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            };
        }, false, true));
        // placing the second core block on the first forms the core (master bottom)
        CASES.put("core_forms_from_2_blocks", new Spec(2, (h, c) -> {
            BlockPos base = new BlockPos(3, 1, 4);
            var loose = org.erisia.stasis.StasisRegistry.TOWER.get().defaultBlockState();
            h.setBlock(base, loose);
            boolean before = org.erisia.stasis.TowerBlock.formed(h.getBlockState(base));
            h.setBlock(base.above(), loose);
            return () -> {
                BlockPos master = h.absolutePos(base);
                c.check("1 block: no core yet", !before);
                c.check("2nd block on top: formed, tower entity at the bottom", h.getLevel().getBlockEntity(master)
                    instanceof org.erisia.stasis.TowerBlockEntity && countCore(h.getLevel(), master) == 2);
                c.check("every cell has the same entity type (client and server agree)", h.getLevel().getBlockEntity(master.above())
                    instanceof org.erisia.stasis.TowerBlockEntity);
                if (h.getLevel().getBlockEntity(master) instanceof org.erisia.stasis.TowerBlockEntity t) t.setRemoved();
            };
        }));
        // Baughn: animals in stopped land were found dead (with loot dropped). They must stay alive, frozen.
        CASES.put("animals_live_in_stopped_land", new Spec(200, (h, c) -> {
            var pig = (net.minecraft.world.entity.LivingEntity) h.spawn(EntityTypes.PIG, new Vec3(12.5, 1.0, 4.5));
            var cow = (net.minecraft.world.entity.LivingEntity) h.spawn(EntityTypes.COW, new Vec3(13.5, 1.0, 2.5));
            ServerPlayer p = survivalPlayer(h, new Vec3(10.5, 1, 6.5), false);
            float hp = pig.getHealth();
            return () -> {
                c.check("pig alive, unhurt", pig.isAlive() && pig.getHealth() == hp, "alive " + pig.isAlive() + " hp " + pig.getHealth() + " of " + hp
                    + " last damage " + pig.getLastDamageSource());
                c.check("cow alive", cow.isAlive(), "hp " + cow.getHealth() + " last damage " + cow.getLastDamageSource());
                pig.discard(); cow.discard(); p.discard();
            };
        }, false, true));
        // a frozen mob can be hit more than once (its hit cooldown counts down in level time)
        CASES.put("frozen_mob_takes_repeated_hits", new Spec(40, (h, c) -> {
            var pig = (net.minecraft.world.entity.LivingEntity) h.spawn(EntityTypes.PIG, new Vec3(12.5, 1.0, 4.5));
            h.runAfterDelay(2, () -> pig.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 3f));
            h.runAfterDelay(20, () -> pig.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 3f));
            return () -> {
                c.check("two hits 18 ticks apart both land", Math.abs(pig.getHealth() - 4f) < 0.01f, "hp " + pig.getHealth());
                pig.discard();
            };
        }, false, true));
        // a mob killed in stopped land finishes dying and goes (it used to lie there at 0 health, frozen mid-death)
        CASES.put("killed_mob_goes_in_stopped_land", new Spec(60, (h, c) -> {
            var pig = (net.minecraft.world.entity.LivingEntity) h.spawn(EntityTypes.PIG, new Vec3(12.5, 1.0, 4.5));
            h.runAfterDelay(5, () -> pig.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 100f));
            return () -> c.check("the dead pig is gone", pig.isRemoved(), "removed " + pig.isRemoved() + " hp " + pig.getHealth() + " deathTime " + pig.deathTime);
        }, false, true));
        // local time (visual): a field's clock starts from the grey's and skews at 50-200% towards the world's; when the
        // field goes, so does its clock (Baughn: no per-chunk memory, the grey has one time)
        CASES.put("field_clock_skews_and_goes", new Spec(41, (h, c) -> {
            ServerLevel level = h.getLevel();
            Vec3 a = h.absoluteVec(new Vec3(3.5, 1, 4.5));
            Field f = FieldData.get(level).add(level, a.x, a.y, a.z, 12);   // r 12 always covers its own chunk's centre
            double[] first = new double[1];
            h.runAfterDelay(1, () -> first[0] = org.erisia.stasis.LocalTime.clockOf(level, f.id()));
            return () -> {
                double now = org.erisia.stasis.LocalTime.clockOf(level, f.id());
                double moved = org.erisia.stasis.LocalTime.wrapDiff(first[0], now);
                c.check("the clock moves at 0.5x or 2x (40 ticks: 20 or 80)", Math.abs(moved - 20) < 2.5 || Math.abs(moved - 80) < 2.5,
                    "moved " + moved + " from " + first[0]);
                c.check("a new field starts from the grey's time (a sunset)", Math.abs(first[0] - org.erisia.stasis.LocalTime.DEFAULT_TOD) < 81, "first " + first[0]);
                FieldData.get(level).remove(level, f.id());
                c.check("when it goes, its clock goes (the land shows the grey's time again)", Double.isNaN(org.erisia.stasis.LocalTime.clockOf(level, f.id())));
            };
        }, false, true));
        CASES.put("tower_runs_a_field", new Spec(10, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 3);
            var store = t.energy();
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                store.insert((int) (org.erisia.stasis.TowerBlockEntity.storeFor(40) * 0.4 * 4 / 3), tx);
                tx.commit();
            }
            h.runAfterDelay(3, () -> c.check("40% charged: still off", t.state() == org.erisia.stasis.TowerBlockEntity.State.OFF, t.state().name()));
            h.runAfterDelay(4, () -> {
                try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                    store.insert((int) (org.erisia.stasis.TowerBlockEntity.storeFor(40) * 0.3 * 4 / 3), tx);   // (70%: over the safety's 60%)
                    tx.commit();
                }
            });
            return () -> {
                c.check("three segments: radius 40", t.radius() == 40.0, "r " + t.radius());
                c.check("over half charged: running", t.state() == org.erisia.stasis.TowerBlockEntity.State.RUNNING, t.state().name()
                    + " stored " + t.storedJ() + " of " + t.storeMaxJ() + " J, ticks " + t.ticks + ", same BE " + (h.getLevel().getBlockEntity(h.absolutePos(new BlockPos(3, 1, 4))) == t));
                var fields = FieldData.get(h.getLevel()).fields();
                c.check("its field is up, r 40", fields.stream().anyMatch(f -> f.radius() == 40.0
                    && f.containsPoint(h.absoluteVec(new Vec3(3.5, 1.5, 4.5)).x, h.absoluteVec(new Vec3(3.5, 1.5, 4.5)).y, h.absoluteVec(new Vec3(3.5, 1.5, 4.5)).z)), fields.toString());
                c.check("time runs next to the tower", Stasis.isRunning(h.getLevel(), h.absolutePos(new BlockPos(6, 1, 4))));
                t.setRemoved();   // breaking the tower takes its field down
                c.check("tower gone: field gone", FieldData.get(h.getLevel()).fields().stream().noneMatch(f -> f.radius() == 40.0));
            };
        }, false, true));
        // Baughn: booms stretch the running tower's field their way, and the draw follows the volume
        CASES.put("tower_booms_stretch_field", new Spec(80, (h, c) -> {
            org.erisia.stasis.TowerStructure.syncBuild = true;
            c.cleanup.add(() -> org.erisia.stasis.TowerStructure.syncBuild = false);
            BlockPos base = new BlockPos(3, 1, 4);
            var t = tower(h, base, 3);                                             // H 5, r 40
            var boomPole = org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get().defaultBlockState()
                .setValue(org.erisia.stasis.TowerPoleBlock.AXIS, net.minecraft.core.Direction.Axis.X);
            h.setBlock(base.above().west(1), boomPole);
            h.setBlock(base.above().west(2), boomPole);                            // W 2 at y 2: 2 of 3 allowed
            t.testCharge(1.0);
            return () -> {
                double k = org.erisia.stasis.StasisConfig.boomStretch(), want = 1 + k * 2 / 3.0;
                c.check("built", t.structure.status == org.erisia.stasis.TowerStructure.Status.OK, t.structure.status + " " + t.structure.message);
                var f = FieldData.get(h.getLevel()).get(t.fieldId()).orElse(null);
                c.check("its field reaches further west", f != null && Math.abs(f.stretch().w() - want) < 1e-9 && f.stretch().e() == 1
                    && f.stretch().n() == 1 && f.stretch().s() == 1, String.valueOf(f));
                Vec3 cp = h.absoluteVec(new Vec3(3.5, 1.5, 4.5));
                c.check("a point 45 west is inside, 45 east isn't", f != null && f.containsPoint(cp.x - 45, cp.y, cp.z) && !f.containsPoint(cp.x + 45, cp.y, cp.z));
                double vs = Math.cbrt((1 + want + want + 1) / 4);
                c.check("draw by volume", Math.abs(t.volumeScale() - vs) < 1e-9 && Math.abs(t.drawAt(40) - org.erisia.stasis.TowerBlockEntity.drawFor(40 * vs)) < 1e-6,
                    t.volumeScale() + " vs " + vs);
                t.setRemoved();
            };
        }, false, true));
        // the tower's range (console): the preferred range from the mast; a target from the tower's own size up to +25%
        // at (R/Rd)^6 draw; below 60% charge the safety shrinks it in proportion, in 2-block steps; OVERRIDE holds it
        CASES.put("tower_range", new Spec(10, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 3);
            t.testCharge(1.0);
            return () -> {
                c.check("preferred range 40", t.designRadius() == 40 && t.goalRadius() == 40, t.designRadius() + " / " + t.goalRadius());
                c.check("the minimum covers the tower", t.minRadius() == 7, String.valueOf(t.minRadius()));
                t.setControls(1000, false);
                c.check("expansion stops at +25%", t.target() == 50, String.valueOf(t.target()));
                c.check("at +25% the draw is ~3.8x", Math.abs(t.drawPenalty(50) - Math.pow(1.25, 6)) < 1e-9, String.valueOf(t.drawPenalty(50)));
                c.check("below the preferred range, no penalty", t.drawPenalty(30) == 1);
                t.setControls(20, false);
                c.check("a smaller target", t.goalRadius() == 20, String.valueOf(t.goalRadius()));
                t.setControls(Double.NaN, false);
                // the failure cycle: no input, half charge, failing: no retreat (nothing would carry it)
                t.testCharge(0.5);
                t.testSafety(0, 0.6);
                c.check("no input: it holds on at full size", !t.retreating() && t.goalRadius() == 40, t.goalRadius() + "");
                // some input: it retreats to what the input carries
                double w = org.erisia.stasis.TowerBlockEntity.drawFor(24) / 0.9 + 1;
                t.testSafety(w, 0.6);
                c.check("with input: it retreats to what the input carries", t.retreating() && Math.abs(t.goalRadius() - 24) <= 1, t.goalRadius() + "");
                t.testCharge(0.85);
                t.testSafety(w, 0.3);
                c.check("recharged past 80%: it grows back", !t.retreating() && t.goalRadius() == 40);
                t.testCharge(0.1);
                t.testSafety(0, 0.7);
                c.check("the last ditch below 15%: down to the minimum", t.retreating() && t.goalRadius() == t.minRadius(), t.goalRadius() + "");
                t.setControls(Double.NaN, true);
                t.testSafety(0, 0.7);
                c.check("OVERRIDE holds the full range", !t.retreating() && t.goalRadius() == 40, String.valueOf(t.goalRadius()));
                c.check("the store never shrinks with the target", t.storeMaxJ() == org.erisia.stasis.TowerBlockEntity.storeFor(40));
                t.setControls(Double.NaN, false);
                t.setRemoved();   // (its 40-block field would reach the next tests)
            };
        }, false, true));
        // the console answers players at the tower (within 8 blocks), not from afar; its readout has the radii
        CASES.put("tower_console_access", new Spec(5, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 2);
            ServerPlayer near = survivalPlayer(h, new Vec3(5.5, 1.0, 4.5), true), far = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            return () -> {
                far.setPos(far.getX() + 20, far.getY(), far.getZ());
                c.check("near: the console answers", org.erisia.stasis.StasisNet.consoleTower(near, t.getBlockPos()) == t);
                c.check("20 blocks off: refused", org.erisia.stasis.StasisNet.consoleTower(far, t.getBlockPos()) == null);
                var st = t.consoleState();
                c.check("the readout has the preferred range", st.v().get(6) == 32.0, st.v().toString());
                remove(h, near);
                remove(h, far);
                t.setRemoved();
            };
        }, false, true));
        // early game (Baughn 2026-10-17): a charged Delver suit on the first join, once
        CASES.put("starter_suit_once", new Spec(5, (h, c) -> {
            ServerPlayer p = survivalPlayer(h, new Vec3(5.5, 1.0, 4.5), false, true);
            return () -> {
                int worn = 0;
                for (var slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD,
                        net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
                    var st = p.getItemBySlot(slot);
                    if (st.getItem() instanceof org.erisia.stasis.DelverSuitItem d && d.getEnergy(st) >= d.getEnergyMax(st) - 1e-6) worn++;
                }
                c.check("first join: a full charged Delver suit, worn", worn == 4, worn + " pieces");
                org.erisia.stasis.StarterKit.login(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(p));
                int inv = 0;
                for (int i = 0; i < p.getInventory().getContainerSize(); i++)
                    if (p.getInventory().getItem(i).getItem() instanceof org.erisia.stasis.DelverSuitItem) inv++;
                c.check("the next login: no second suit", inv == 4, inv + " suit items in all");
                remove(h, p);
            };
        }, false, true));
        // a ruined tower's core (coreDrop 0) breaks into scrap, which smelts into an iron nugget
        CASES.put("ruined_core_scrap", new Spec(2, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 0);
            return () -> {
                var level = h.getLevel();
                var pos = t.getBlockPos();
                var tool = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE);
                var whole = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(pos), level, pos, t, null, tool);
                c.check("a working core drops itself", whole.stream().anyMatch(st -> st.is(org.erisia.stasis.StasisRegistry.TOWER_ITEM.get())), whole.toString());
                t.coreDrop = 0;
                var ruined = net.minecraft.world.level.block.Block.getDrops(level.getBlockState(pos), level, pos, t, null, tool);
                c.check("a ruined core drops scrap only", !ruined.isEmpty() && ruined.stream().allMatch(st -> st.is(org.erisia.stasis.StasisRegistry.SCRAP.get())),
                    ruined.toString());
                var in = new net.minecraft.world.item.crafting.SingleRecipeInput(new net.minecraft.world.item.ItemStack(org.erisia.stasis.StasisRegistry.SCRAP.get()));
                var r = level.recipeAccess().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.SMELTING, in, level);
                c.check("scrap smelts into an iron nugget", r.isPresent() && r.get().value().assemble(in).is(net.minecraft.world.item.Items.IRON_NUGGET),
                    String.valueOf(r));
                t.setRemoved();
            };
        }, false, true));
        // water poured in stopped land freezes as it lands (only crash-time water shows the water lines)
        CASES.put("poured_water_freezes", new Spec(2, (h, c) -> () -> {
            BlockPos at = h.absolutePos(new BlockPos(12, 1, 4));
            ((net.minecraft.world.item.BucketItem) net.minecraft.world.item.Items.WATER_BUCKET).emptyContents(null, h.getLevel(), at, null, null);
            c.check("stopped land: stilled water", h.getLevel().getBlockState(at).is(org.erisia.stasis.StasisRegistry.STILLED_WATER_BLOCK.get()),
                h.getLevel().getBlockState(at).toString());
        }, false));
        // the End (Baughn): end cities (and their ships) no longer generate; tower and airship sites do
        CASES.put("end_sites_replace_cities", new Spec(1, (h, c) -> () -> {
            var reg = h.getLevel().registryAccess();
            var cityBiomes = reg.lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                .get(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BIOME, Identifier.withDefaultNamespace("has_structure/end_city")));
            c.check("no biome has end cities", cityBiomes.map(t -> t.size() == 0).orElse(true), String.valueOf(cityBiomes.map(t -> t.size())));
            var structures = reg.lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE);
            for (String n : new String[]{"end_tower_site", "airship_site"})
                c.check(n + " registered", structures.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.STRUCTURE,
                    Identifier.fromNamespaceAndPath(org.erisia.stasis.StasisDelvers.MODID, n))).isPresent());
        }, false));
        // a whole time suit keeps its wearer at full speed in a slow field (Baughn 2026-10-17)
        CASES.put("suit_full_speed_in_slow_field", new Spec(2, (h, c) -> {
            var level = h.getLevel();
            Vec3 a = h.absoluteVec(new Vec3(4.5, 1.0, 4.5));
            var f = org.erisia.stasis.FieldData.get(level).add(level, a.x, a.y, a.z, 6, false, 0.5);
            ServerPlayer bare = survivalPlayer(h, new Vec3(4.5, 1.0, 4.5), true), suited = survivalPlayer(h, new Vec3(4.5, 1.0, 4.5), true);
            wear(suited, true, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(),
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), org.erisia.stasis.StasisRegistry.DELVER_BOOTS.get());
            return () -> {
                c.check("unsuited in the 0.5 field: half speed", org.erisia.stasis.PlayerRates.rate(bare) == 0.5, "" + org.erisia.stasis.PlayerRates.rate(bare));
                c.check("suited: full speed", org.erisia.stasis.PlayerRates.rate(suited) == 1.0, "" + org.erisia.stasis.PlayerRates.rate(suited));
                org.erisia.stasis.FieldData.get(level).remove(level, f.id());
                remove(h, bare);
                remove(h, suited);
            };
        }, false, true));
        // a ruined cottage's tower comes dead and never drops as a core (the template processor)
        CASES.put("ruined_cottage_core", new Spec(1, (h, c) -> () -> {
            var nbt = new net.minecraft.nbt.CompoundTag();
            nbt.putString("state", "RUNNING");
            nbt.putInt("field", 7);
            nbt.putInt("energy", 5000);
            var info = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(BlockPos.ZERO,
                org.erisia.stasis.StasisRegistry.TOWER.get().defaultBlockState(), nbt);
            var out = org.erisia.stasis.RuinedCottages.RuinedCore.CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,
                new com.google.gson.JsonObject()).getOrThrow().process(h.getLevel(), BlockPos.ZERO, BlockPos.ZERO, info, info,
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(), null);
            var o = out.nbt();
            c.check("dead: off, empty, no field", "OFF".equals(o.getStringOr("state", "")) && o.getIntOr("field", -1) == 0 && o.getIntOr("energy", -1) == 0, o.toString());
            c.check("coreDrop 0", o.getDoubleOr("coreDrop", 1) == 0, o.toString());
            var dirt = new net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo(BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), null);
            c.check("other blocks untouched", org.erisia.stasis.RuinedCottages.RuinedCore.CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,
                new com.google.gson.JsonObject()).getOrThrow().process(h.getLevel(), BlockPos.ZERO, BlockPos.ZERO, dirt, dirt,
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings(), null) == dirt);
        }, false));
        // every ruined-cottage template places a dead, ruined core: no tower is read or drawn (the bare poles show the
        // damage) until it runs; then its tower is read anew on the spot (valid, built)
        CASES.put("ruined_cottage_templates", new Spec(160, (h, c) -> {
            ServerLevel level = h.getLevel();
            var m = level.getStructureTemplateManager();
            var ids = org.erisia.stasis.RuinedCottages.templates(m);
            c.check("there are cottage templates", !ids.isEmpty(), "" + ids);
            boolean sync = org.erisia.stasis.TowerStructure.syncBuild;
            org.erisia.stasis.TowerStructure.syncBuild = true;
            c.cleanup.add(() -> org.erisia.stasis.TowerStructure.syncBuild = sync);
            List<BlockPos> at = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                BlockPos p = h.absolutePos(new BlockPos(16 + 14 * (i % 2), 1, -6 + 14 * (i / 2)));   // (apart: booms mustn't meet; near: ticking)
                var rot = net.minecraft.world.level.block.Rotation.values()[i % 4];
                m.getOrCreate(ids.get(i)).placeInWorld(level, p, p, org.erisia.stasis.RuinedCottages.Piece.settings(rot), level.getRandom(), 2);
                at.add(p);
            }
            java.util.function.Function<BlockPos, org.erisia.stasis.TowerBlockEntity> core = p -> {
                for (BlockPos q : BlockPos.betweenClosed(p.offset(-5, 0, -5), p.offset(5, 13, 5)))
                    if (level.getBlockEntity(q) instanceof org.erisia.stasis.TowerBlockEntity t
                        && level.getBlockState(q).getValue(org.erisia.stasis.TowerBlock.CELL) == org.erisia.stasis.TowerBlock.MASTER) return t;
                return null;
            };
            h.runAfterDelay(70, () -> {
                for (int i = 0; i < ids.size(); i++) {
                    var tb = core.apply(at.get(i));
                    String n = ids.get(i).getPath();
                    c.check(n + ": a dead ruin", tb != null && tb.ruined && tb.coreDrop == 0 && tb.state() == org.erisia.stasis.TowerBlockEntity.State.OFF && tb.storedJ() == 0,
                        tb == null ? "no core" : tb.ruined + " " + tb.state() + " " + tb.storedJ());
                    if (tb == null) continue;
                    c.check(n + ": no tower read or drawn while ruined", tb.structure.status == org.erisia.stasis.TowerStructure.Status.NONE, tb.structure.status.name());
                    tb.ruined = false;   // (as when it first runs)
                }
            });
            return () -> {
                for (int i = 0; i < ids.size(); i++) {
                    var tb = core.apply(at.get(i));
                    if (tb == null) continue;
                    // (built if its chunk ticked; the far ones sometimes don't in the test grid: then read the layout here)
                    var l = org.erisia.stasis.TowerStructure.scan(level, tb.getBlockPos(), 30);
                    boolean ok = tb.structure.status == org.erisia.stasis.TowerStructure.Status.OK
                        || tb.structure.status == org.erisia.stasis.TowerStructure.Status.NONE && l != null && l.violations().isEmpty();
                    c.check(ids.get(i).getPath() + ": once running, its tower is read and valid here", ok,
                        tb.structure.status + " " + tb.structure.message + " " + l + " " + (l == null ? "" : l.violations()));
                }
                for (BlockPos p : at)
                    for (BlockPos q : BlockPos.betweenClosed(p.offset(-5, -1, -5), p.offset(5, 14, 5)))
                        level.setBlock(q, Blocks.AIR.defaultBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            };
        }, false, true));
        // a locked tower (the spawn tower) keeps its settings; anyone can feed a tower from their suit (a jump-start)
        CASES.put("tower_lock_and_feed", new Spec(200, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 0);
            ServerPlayer p = survivalPlayer(h, new Vec3(5.5, 1.0, 4.5), false);
            wear(p, true, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(),
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), org.erisia.stasis.StasisRegistry.DELVER_BOOTS.get());
            t.locked = true;
            double before = t.target();
            t.setControls(before + 8, true);
            c.check("locked: the console can't change the radius or OVERRIDE", t.target() == before && !t.override(), t.target() + " " + t.override());
            ServerPlayer poor = survivalPlayer(h, new Vec3(1.5, 1.0, 4.5), false);
            wear(poor, false, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(),
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), org.erisia.stasis.StasisRegistry.DELVER_BOOTS.get());
            t.toggleFeed(poor);
            c.check("a dead tower refuses a feed that can't start it", t.consoleState(poor).v().get(18) == 0.0);
            remove(h, poor);
            t.testCharge(0.45);   // (a dead tower a few kJ short of starting)
            t.toggleFeed(p);
            c.check("a suit that can start it may feed it", t.consoleState(p).v().get(18) == 1.0, String.format("store %.0f of %.0f J", t.storedJ(), t.storeMaxJ()));
            return () -> {
                var head = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
                var d = (org.erisia.stasis.DelverSuitItem) head.getItem();
                double given = 4 * (d.getEnergyMax(head) - d.getEnergy(head));
                c.check("the feed stopped as the tower started: the suit kept the rest", given > 4000 && given < 0.5 * 4 * d.getEnergyMax(head),
                    String.format("suit gave %.0f J", given));
                c.check("feeding: the suit gave its charge and the tower started", given > 4000
                        && t.state() == org.erisia.stasis.TowerBlockEntity.State.RUNNING, String.format("suit gave %.0f J, %s", given, t.state()));
                remove(h, p);
                t.setRemoved();
            };
        }, false, true));
        // Baughn: the shield shows the charge only while the store drains, averaged (no flicker at break-even)
        CASES.put("tower_shield_shows_drain", new Spec(120, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 0);
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                t.energy().insert((int) (org.erisia.stasis.TowerBlockEntity.storeFor(16) * 0.6 * 4 / 3), tx);
                tx.commit();
            }
            double[] early = {-1};
            h.runAfterDelay(3, () -> early[0] = t.failing());
            return () -> {
                c.check("running", t.state() == org.erisia.stasis.TowerBlockEntity.State.RUNNING, t.state().name());
                c.check("just started: no dropout", early[0] < 0.05, "" + early[0]);
                c.check("no input: draining, and the shield shows it", t.draining() && t.failing() > 0.2 && t.failing() < 0.85,
                    "draining " + t.draining() + ", failing " + t.failing());
                t.setRemoved();   // take its field down (other tower tests look for an r 16 field)
            };
        }, false, true));
        // frozen textures (client): the still copies' names and the UV move onto them
        CASES.put("frozen_texture_math", new Spec(1, (h, c) -> () -> {
            var id = net.minecraft.resources.Identifier.withDefaultNamespace("block/lava_still");
            c.check("frozen id", org.erisia.stasis.FrozenTextures.frozenId(id).toString().equals("stasisdelvers:frozen/minecraft/block/lava_still"),
                org.erisia.stasis.FrozenTextures.frozenId(id).toString());
            long uv = org.erisia.stasis.FrozenTextures.packUV(0.25f, 0.75f);
            c.check("UV packing as UVPair (u high, v low)", uv == ((long) Float.floatToIntBits(0.25f) << 32 | (Float.floatToIntBits(0.75f) & 0xFFFFFFFFL))
                && org.erisia.stasis.FrozenTextures.unpackU(uv) == 0.25f && org.erisia.stasis.FrozenTextures.unpackV(uv) == 0.75f);
            // a quad corner a quarter into a sprite at (0.5..0.5625, 0.25..0.3125) lands a quarter into (0.125..0.1875, 0.0..0.0625)
            long in = org.erisia.stasis.FrozenTextures.packUV(0.515625f, 0.265625f);
            long out = org.erisia.stasis.FrozenTextures.remapUV(in, 0.5f, 0.25f, 0.5625f, 0.3125f, 0.125f, 0f, 0.1875f, 0.0625f);
            float u = org.erisia.stasis.FrozenTextures.unpackU(out), v = org.erisia.stasis.FrozenTextures.unpackV(out);
            c.check("UV remap keeps the place in the sprite", Math.abs(u - 0.140625f) < 1e-6 && Math.abs(v - 0.015625f) < 1e-6, u + "," + v);
            long corner = org.erisia.stasis.FrozenTextures.remapUV(org.erisia.stasis.FrozenTextures.packUV(0.5625f, 0.25f), 0.5f, 0.25f, 0.5625f, 0.3125f, 0.125f, 0f, 0.1875f, 0.0625f);
            c.check("corners map to corners", org.erisia.stasis.FrozenTextures.unpackU(corner) == 0.1875f && org.erisia.stasis.FrozenTextures.unpackV(corner) == 0f);
            Field a = new Field(1, 0, 0, 0, 10), b = new Field(2, 50, 0, 0, 10);
            var changed = Field.changedShapes(List.of(a, b), List.of(a.withFailing(0.5), b.withRadius(12), new Field(3, 0, 50, 0, 5)));
            c.check("re-mesh: shape changes and new fields count, failing doesn't", changed.size() == 3
                && changed.stream().noneMatch(f -> f.id() == 1), changed.toString());
        }, false));
        // frozen textures mesh per section where they can: a section said to be all stopped / all running must be
        for (boolean stretched : new boolean[]{false, true})
        CASES.put(stretched ? "section_state_matches_stretched" : "section_state_matches_blocks", new Spec(1, (h, c) -> {
            Field f0 = field(h, c, new Vec3(3.5, 2.5, 4.5), stretched ? 22 : 30, 1.0);
            if (stretched) FieldData.get(h.getLevel()).update(h.getLevel(), f0.id(), x -> x.withStretch(new Field.Stretch(1.5, 1.2, 1, 1.4)));
            Field f = FieldData.get(h.getLevel()).get(f0.id()).orElse(f0);
            return () -> {
                ServerLevel level = h.getLevel();
                int sx = (int) Math.floor(f.x()) >> 4, sy = (int) Math.floor(f.y()) >> 4, sz = (int) Math.floor(f.z()) >> 4;
                int[] n = new int[3];
                int wrong = 0;
                String first = "";
                for (int x = sx - 3; x <= sx + 3; x++)
                    for (int y = sy - 3; y <= sy + 3; y++)
                        for (int z = sz - 3; z <= sz + 3; z++) {
                            int st = Stasis.sectionState(level, x, y, z);
                            n[st]++;
                            if (st == Stasis.SECTION_MIXED) continue;
                            for (int bx = x << 4; bx < (x << 4) + 16; bx++)
                                for (int by = y << 4; by < (y << 4) + 16; by++)
                                    for (int bz = z << 4; bz < (z << 4) + 16; bz++)
                                        if ((Stasis.rateAtLand(level, bx, by, bz) > 0) != (st == Stasis.SECTION_RUNNING)) {
                                            if (wrong++ == 0) first = "section " + x + "," + y + "," + z + " state " + st + " block " + bx + "," + by + "," + bz;
                                        }
                        }
                c.check("whole-section answers agree with every block", wrong == 0, wrong + " blocks; first " + first);
                c.check("stopped, running and mixed sections all occur", n[0] > 0 && n[1] > 0 && n[2] > 0,
                    "stopped " + n[0] + " running " + n[1] + " mixed " + n[2]);
            };
        }, false, true));
        // Baughn: no two collapses alike; the outside still tends to go first
        CASES.put("collapse_differs_by_seed", new Spec(1, (h, c) -> () -> {
            Field a = new Field(1, 0.5, 0.5, 0.5, 20).withSeed(12345).withCollapse(0.25), b = a.withSeed(987654);   // (collapses end by 0.5)
            int differ = 0, n = 0, outerA = 0, outerN = 0, innerA = 0, innerN = 0;
            for (int x = -19; x <= 19; x += 2)
                for (int y = -19; y <= 19; y += 2)
                    for (int z = -19; z <= 19; z += 2) {
                        double d = Math.sqrt(x * x + y * y + z * z);
                        if (d > 18) continue;
                        boolean ia = a.containsBlock(x, y, z), ib = b.containsBlock(x, y, z);
                        n++;
                        if (ia != ib) differ++;
                        if (d > 13) { outerN++; if (!ia) outerA++; }
                        if (d < 6) { innerN++; if (!ia) innerA++; }
                    }
            c.check("two seeds stop different blocks (>15% differ)", differ > 0.15 * n, differ + " of " + n);
            c.check("outer blocks stop more often than inner ones", outerA / (double) outerN > innerA / (double) Math.max(1, innerN),
                outerA + "/" + outerN + " vs " + innerA + "/" + innerN);
        }, false));
        // rift strata (Strata): every shape is a pure function, makes rock and open space inside its bounds at a small and
        // a large radius, and nothing outside them; the weighted pick follows the config list
        CASES.put("strata_shapes", new Spec(1, (h, c) -> () -> {
            java.util.Random rng = new java.util.Random(7);
            for (float R : new float[]{12, 40})
                for (var shape : org.erisia.stasis.Strata.Shape.values()) {
                    var d = new org.erisia.stasis.StaticDeeps.Deep(1000, 100, -2000, R, 4242);
                    var b = org.erisia.stasis.Strata.bounds(shape, d);
                    int[] count = new int[6];
                    boolean pure = true;
                    for (int i = 0; i < 20000; i++) {
                        double a = rng.nextDouble() * 2 * Math.PI, r = Math.sqrt(rng.nextDouble()) * b.radius();
                        int x = (int) Math.floor(d.x() + r * Math.cos(a)), z = (int) Math.floor(d.z() + r * Math.sin(a));
                        int y = b.minY() + rng.nextInt(Math.max(1, b.maxY() - b.minY()));
                        int k = org.erisia.stasis.Strata.at(shape, d, x, y, z);
                        count[k]++;
                        pure &= k == org.erisia.stasis.Strata.at(shape, d, x, y, z);
                    }
                    String tag = shape.id() + " r" + (int) R;
                    c.check(tag + " deterministic", pure);
                    c.check(tag + " has rock", count[1] + count[4] + count[5] > 0, java.util.Arrays.toString(count));
                    c.check(tag + " has open space", count[2] + count[3] > 0, java.util.Arrays.toString(count));
                    int outside = 0;
                    for (int i = 0; i < 2000; i++) {
                        double a = rng.nextDouble() * 2 * Math.PI, r = b.radius() + 1 + rng.nextDouble() * 50;
                        int x = (int) Math.floor(d.x() + r * Math.cos(a)), z = (int) Math.floor(d.z() + r * Math.sin(a));
                        int y = b.minY() - 40 + rng.nextInt(b.maxY() - b.minY() + 80);
                        if (org.erisia.stasis.Strata.at(shape, d, x, y, z) != org.erisia.stasis.Strata.OUT) outside++;
                        int y2 = rng.nextBoolean() ? b.minY() - 1 - rng.nextInt(30) : b.maxY() + 1 + rng.nextInt(30);
                        if (org.erisia.stasis.Strata.at(shape, d, (int) d.x(), y2, (int) d.z()) != org.erisia.stasis.Strata.OUT) outside++;
                    }
                    c.check(tag + " nothing outside its bounds", outside == 0, outside + " hits");
                }
            try {
                org.erisia.stasis.Strata.setWeights(java.util.List.of("pods:1"));
                boolean all = true;
                for (int i = 0; i < 200; i++)
                    all &= org.erisia.stasis.Strata.shapeOf(new org.erisia.stasis.StaticDeeps.Deep(0, 0, 0, 30, i * 7919)) == org.erisia.stasis.Strata.Shape.PODS;
                c.check("a one-entry list forces that shape", all);
                org.erisia.stasis.Strata.setWeights(java.util.List.of("saddle:1", "staircase:1", "splash:1", "pods:1"));
                int[] n = new int[4];
                for (int i = 0; i < 4000; i++) n[org.erisia.stasis.Strata.shapeOf(new org.erisia.stasis.StaticDeeps.Deep(0, 0, 0, 30, i * 7919 & 0xFFFFFF)).ordinal()]++;
                boolean even = true;
                for (int v : n) even &= v > 800 && v < 1200;
                c.check("equal weights pick about evenly", even, java.util.Arrays.toString(n));
            } finally {
                org.erisia.stasis.Strata.setWeights(java.util.List.of("saddle:1", "staircase:1", "splash:1", "pods:1"));
            }
        }, false));
        // strata weather (Pockets): the cycle's shares, a 20 s violet warning before every rift, untilNext and exposure
        // agree with a brute-force walk, and the mask is the strata's cells
        CASES.put("strata_pockets", new Spec(1, (h, c) -> () -> {
            double[] share = new double[4];
            int samples = 0;
            java.util.Random rng = new java.util.Random(11);
            for (int i = 0; i < 200000; i++) {
                var w = org.erisia.stasis.Pockets.weather(4242, rng.nextInt(200) - 100, rng.nextInt(20), rng.nextInt(200) - 100, rng.nextDouble() * 3.0e6);
                share[w.state().ordinal()]++;
                samples++;
            }
            for (int i = 0; i < 4; i++) share[i] /= samples;
            String sh = String.format("clear %.3f mist %.3f violet %.3f rift %.3f", share[0], share[1], share[2], share[3]);
            c.check("clear ~25%", Math.abs(share[0] - 0.25) < 0.01, sh);
            c.check("rift ~15%", Math.abs(share[3] - 0.15) < 0.015, sh);
            c.check("mist ~58%", share[1] > 0.54 && share[1] < 0.62, sh);
            boolean det = org.erisia.stasis.Pockets.weather(77, 3, 4, 5, 123456.7).equals(org.erisia.stasis.Pockets.weather(77, 3, 4, 5, 123456.7));
            c.check("deterministic", det);
            // walk a few pockets second by second over many cycles
            int rifts = 0, badWarn = 0, badUntil = 0;
            double expoErr = 0;
            for (int k = 0; k < 6; k++) {
                int px = 10 * k - 7, py = k, pz = 3 - k;
                double t0 = 5.0e6 + k * 777, grey = 0;
                var prev = org.erisia.stasis.Pockets.weather(99, px, py, pz, t0);
                double violetSince = -1;
                for (int s = 1; s <= 30000; s++) {
                    double t = t0 + s;
                    var w = org.erisia.stasis.Pockets.weather(99, px, py, pz, t);
                    if (prev.state() != org.erisia.stasis.Pockets.State.CLEAR) grey += 1;
                    if (w.state() == org.erisia.stasis.Pockets.State.VIOLET && prev.state() != w.state()) violetSince = t;
                    if (w.state() == org.erisia.stasis.Pockets.State.RIFT && prev.state() != w.state()) {
                        rifts++;
                        if (prev.state() != org.erisia.stasis.Pockets.State.VIOLET || Math.abs(t - violetSince - 20) > 1.01) badWarn++;
                    }
                    if (prev.state() == w.state() && Math.abs(prev.untilNext() - 1 - w.untilNext()) > 1e-6) badUntil++;
                    prev = w;
                }
                double e = org.erisia.stasis.Pockets.exposure(99, px, py, pz, t0, t0 + 30000);
                expoErr = Math.max(expoErr, Math.abs(e - grey));
            }
            c.check("rifts seen", rifts > 50, String.valueOf(rifts));
            c.check("every rift comes after 20 s of violet", badWarn == 0, badWarn + " of " + rifts);
            c.check("untilNext counts down", badUntil == 0, String.valueOf(badUntil));
            c.check("exposure matches the walk", expoErr <= 6, String.format("%.1f s", expoErr));
            // the mask: strata blocks are masked; far outside the bounds nothing is
            var d = new org.erisia.stasis.StaticDeeps.Deep(1000, 100, -2000, 40, 4242);
            var b = org.erisia.stasis.Strata.bounds(d);
            int hits = 0, missed = 0, outside = 0;
            for (int i = 0; i < 20000; i++) {
                double a = rng.nextDouble() * 2 * Math.PI, r = Math.sqrt(rng.nextDouble()) * b.radius();
                int x = (int) Math.floor(d.x() + r * Math.cos(a)), z = (int) Math.floor(d.z() + r * Math.sin(a));
                int y = b.minY() + rng.nextInt(b.maxY() - b.minY());
                if (!org.erisia.stasis.Pockets.open(org.erisia.stasis.Strata.at(d, x, y, z))) continue;
                hits++;
                int cx = Math.floorDiv(x, 4) * 4, cy = Math.floorDiv(y, 4) * 4, cz = Math.floorDiv(z, 4) * 4;
                // a strata block inside a cell is not always a corner, so check that most cells with strata are masked
                if (!org.erisia.stasis.Pockets.masked(d, cx, cy, cz)) missed++;
            }
            for (int i = 0; i < 2000; i++) {
                double a = rng.nextDouble() * 2 * Math.PI, r = b.radius() + 8 + rng.nextDouble() * 50;
                if (org.erisia.stasis.Pockets.masked(d, (int) Math.floor(d.x() + r * Math.cos(a)) & ~3, b.minY() + rng.nextInt(b.maxY() - b.minY()) & ~3,
                    (int) Math.floor(d.z() + r * Math.sin(a)) & ~3)) outside++;
            }
            c.check("strata cavity cells are masked", hits > 30 && missed < hits / 10, missed + " of " + hits + " missed");
            c.check("nothing masked outside the bounds", outside == 0, String.valueOf(outside));
        }, false));
        // a rift pocket (forced) freezes even a stasis-armoured player outside a field; a field holds it; mist drains
        // a Delver suit at x2
        CASES.put("strata_rift_pocket", new Spec(100, (h, c) -> {
            var box = new AABB(h.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 6, 9);
            org.erisia.stasis.Pockets.TEST.put(box, new org.erisia.stasis.Pockets.Weather(org.erisia.stasis.Pockets.State.RIFT, 1, 100));
            ServerPlayer out = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true), in = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            for (ServerPlayer p : new ServerPlayer[]{out, in})
                wear(p, true, org.erisia.stasis.StasisRegistry.STASIS_HELMET.get(), org.erisia.stasis.StasisRegistry.STASIS_CHEST.get(),
                    org.erisia.stasis.StasisRegistry.STASIS_LEGS.get(), org.erisia.stasis.StasisRegistry.STASIS_BOOTS.get());
            return () -> {
                org.erisia.stasis.Pockets.TEST.remove(box);
                c.check("a rift pocket: stasis armour doesn't help", !out.isAlive());
                c.check("inside a field the rift pocket is held", in.isAlive() && Freezing.ticks(in) == 0, "ticks " + Freezing.ticks(in));
                remove(h, out);
                remove(h, in);
            };
        }));
        // a rift capacitor (λ-Nero) takes the mist's surcharge on the suit and a Delver suit's slow freeze there
        CASES.put("capacitor_takes_mist", new Spec(130, (h, c) -> {
            var box = new AABB(h.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 6, 9);
            org.erisia.stasis.Pockets.TEST.put(box, new org.erisia.stasis.Pockets.Weather(org.erisia.stasis.Pockets.State.MIST, 0.6f, 1000));
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, true, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(),
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), org.erisia.stasis.StasisRegistry.DELVER_BOOTS.get());
            ItemStack cap = new ItemStack(org.erisia.stasis.StasisRegistry.RIFT_CAPACITOR.get());
            var ci = (org.erisia.stasis.CapacitorItem) cap.getItem();
            ci.setEnergy(cap, ci.getEnergyMax(cap));
            p.getInventory().add(cap);
            double capFull = ci.getEnergyMax(cap);
            return () -> {
                org.erisia.stasis.Pockets.TEST.remove(box);
                var head = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD);
                double e = ((org.erisia.stasis.DelverSuitItem) head.getItem()).getEnergy(head);
                double full = org.erisia.stasis.StasisRegistry.DELVER_HELMET.get().getEnergyMax(head);
                ItemStack held = ItemStack.EMPTY;
                for (int i = 0; i < p.getInventory().getContainerSize(); i++)
                    if (p.getInventory().getItem(i).getItem() instanceof org.erisia.stasis.CapacitorItem) held = p.getInventory().getItem(i);
                double left = held.isEmpty() ? 0 : ci.getEnergy(held);
                c.check("with a capacitor the Delver suit doesn't freeze in mist", p.isAlive() && Freezing.ticks(p) == 0, "ticks " + Freezing.ticks(p));
                c.check("and drains only at the plain rate (10 W)", e > full - 10 * 135 / 20.0 - 1, String.format("%.1f J of %.0f", e, full));
                c.check("the capacitor took it", left < capFull - 1000, String.format("%.0f of %.0f J", left, capFull));
                remove(h, p);
            };
        }, false, true));
        CASES.put("strata_mist_pocket", new Spec(130, (h, c) -> {
            var box = new AABB(h.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 6, 9);
            org.erisia.stasis.Pockets.TEST.put(box, new org.erisia.stasis.Pockets.Weather(org.erisia.stasis.Pockets.State.MIST, 0.6f, 1000));
            ServerPlayer p = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            wear(p, true, org.erisia.stasis.StasisRegistry.DELVER_HELMET.get(), org.erisia.stasis.StasisRegistry.DELVER_CHEST.get(),
                org.erisia.stasis.StasisRegistry.DELVER_LEGS.get(), org.erisia.stasis.StasisRegistry.DELVER_BOOTS.get());
            return () -> {
                org.erisia.stasis.Pockets.TEST.remove(box);
                double e = ((org.erisia.stasis.DelverSuitItem) p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).getItem())
                    .getEnergy(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
                c.check("in a mist pocket the Delver suit freezes slowly", p.isAlive() && Freezing.ticks(p) > 0, "ticks " + Freezing.ticks(p));
                double full = org.erisia.stasis.StasisRegistry.DELVER_HELMET.get().getEnergyMax(p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD));
                c.check("and drains x2 (20 W per piece)", e < full - 10 * 135 / 20.0 - 1, String.format("%.1f J", e));
                remove(h, p);
            };
        }));
        // rift crystals grow from the strata weather (Crystals): a chunk's first sweep keeps generated buds; an unloaded
        // gap is bridged by the closed form; a mined bud starts over; a field stops growth
        for (boolean inField : new boolean[]{false, true})
            CASES.put(inField ? "rift_crystals_no_growth_in_field" : "rift_crystals_grow", new Spec(1, (h, c) -> () -> {
                var level = h.getLevel();
                BlockPos bud0 = h.absolutePos(inField ? new BlockPos(3, 3, 4) : new BlockPos(12, 3, 4));
                var box = new AABB(bud0).inflate(3);
                org.erisia.stasis.Pockets.TEST_STRATA.put(box, 4242);
                try {
                    level.setBlockAndUpdate(bud0, org.erisia.stasis.StasisRegistry.BUDDING_RIFT_CRYSTAL.get().defaultBlockState());
                    for (var dir : net.minecraft.core.Direction.values()) level.setBlockAndUpdate(bud0.relative(dir), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    BlockPos gen = bud0.relative(net.minecraft.core.Direction.EAST);
                    level.setBlockAndUpdate(gen, org.erisia.stasis.StasisRegistry.RIFT_CRYSTAL_MEDIUM.get().defaultBlockState()
                        .setValue(net.minecraft.world.level.block.AmethystClusterBlock.FACING, net.minecraft.core.Direction.EAST));
                    // (the buds may lie in a neighbouring chunk: sweep all around)
                    var cp0 = net.minecraft.world.level.ChunkPos.containing(bud0);
                    java.util.List<net.minecraft.world.level.ChunkPos> cps = new java.util.ArrayList<>();
                    for (int ddx = -1; ddx <= 1; ddx++)
                        for (int ddz = -1; ddz <= 1; ddz++) cps.add(new net.minecraft.world.level.ChunkPos(cp0.x() + ddx, cp0.z() + ddz));
                    Runnable sweepAll = () -> cps.forEach(k -> org.erisia.stasis.Crystals.sweep(level, k));
                    cps.forEach(k -> org.erisia.stasis.Crystals.testForget(level, k));
                    org.erisia.stasis.Crystals.FACE_SHARE = 1f;
                    try {
                        sweepAll.run();
                    } finally {
                        org.erisia.stasis.Crystals.FACE_SHARE = 0.6f;
                    }
                    int stage = org.erisia.stasis.StasisConfig.crystalStageSeconds;
                    c.check("a generated bud keeps its stage", level.getBlockState(gen).is(org.erisia.stasis.StasisRegistry.RIFT_CRYSTAL_MEDIUM.get())
                        && org.erisia.stasis.Crystals.testExposure(level, gen, 0) == 2.0 * stage, String.valueOf(org.erisia.stasis.Crystals.testExposure(level, gen, 0)));
                    BlockPos face = null;
                    net.minecraft.core.Direction fd = null;
                    for (var dir : net.minecraft.core.Direction.values())
                        if (dir != net.minecraft.core.Direction.EAST && !Double.isNaN(org.erisia.stasis.Crystals.testExposure(level, bud0.relative(dir), 0))) {
                            face = bud0.relative(dir);
                            fd = dir;
                            break;
                        }
                    c.check("an open face can grow a bud", face != null);
                    if (face == null) return;
                    double t = org.erisia.stasis.Deeps.time(level);
                    org.erisia.stasis.Crystals.testExposure(level, face, 10000);
                    sweepAll.run();
                    double e = org.erisia.stasis.Crystals.testExposure(level, face, 0);
                    int px = Math.floorDiv(face.getX(), 12), py = Math.floorDiv(face.getY(), 12), pz = Math.floorDiv(face.getZ(), 12);
                    double want = org.erisia.stasis.Pockets.exposure(4242, px, py, pz, t - 10000, t);
                    if (inField) {
                        c.check("in a field: no growth", e == 0 && level.getBlockState(face).isAir(), String.format("%.1f s", e));
                        return;
                    }
                    c.check("the gap is bridged by the closed form", Math.abs(e - want) < 2 && want > 5000, String.format("%.1f vs %.1f", e, want));
                    var st = level.getBlockState(face);
                    c.check("it grew to a cluster facing out", st.is(org.erisia.stasis.StasisRegistry.RIFT_CRYSTAL_CLUSTER.get())
                        && st.getValue(net.minecraft.world.level.block.AmethystClusterBlock.FACING) == fd, st.toString());
                    level.setBlockAndUpdate(face, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    sweepAll.run();
                    c.check("mined: it starts over", org.erisia.stasis.Crystals.testExposure(level, face, 0) < 5 && level.getBlockState(face).isAir());
                    level.setBlockAndUpdate(bud0, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    sweepAll.run();
                    c.check("the budding block gone: its buds are forgotten", Double.isNaN(org.erisia.stasis.Crystals.testExposure(level, gen, 0)));
                } finally {
                    org.erisia.stasis.Pockets.TEST_STRATA.remove(box);
                }
            }, inField));
        // grades: a cluster broken in a violet pocket gives charged shards, otherwise shards; small buds give dust
        CASES.put("rift_crystal_grades", new Spec(1, (h, c) -> () -> {
            var level = h.getLevel();
            var pos = h.absolutePos(new net.minecraft.core.BlockPos(12, 2, 4));
            var box = new AABB(pos).inflate(1);
            var tool = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE);
            var cluster = org.erisia.stasis.StasisRegistry.RIFT_CRYSTAL_CLUSTER.get().defaultBlockState();
            try {
                int charged = 0, plain = 0;
                org.erisia.stasis.Pockets.TEST.put(box, new org.erisia.stasis.Pockets.Weather(org.erisia.stasis.Pockets.State.VIOLET, 0.8f, 10));
                for (var st : net.minecraft.world.level.block.Block.getDrops(cluster, level, pos, null, null, tool)) {
                    if (st.is(org.erisia.stasis.StasisRegistry.CHARGED_RIFT_SHARD.get())) charged += st.getCount();
                    if (st.is(org.erisia.stasis.StasisRegistry.RIFT_SHARD.get())) plain += st.getCount();
                }
                c.check("violet: charged shards", charged >= 2 && plain == 0, charged + "/" + plain);
                org.erisia.stasis.Pockets.TEST.put(box, new org.erisia.stasis.Pockets.Weather(org.erisia.stasis.Pockets.State.MIST, 0.6f, 10));
                charged = plain = 0;
                for (var st : net.minecraft.world.level.block.Block.getDrops(cluster, level, pos, null, null, tool)) {
                    if (st.is(org.erisia.stasis.StasisRegistry.CHARGED_RIFT_SHARD.get())) charged += st.getCount();
                    if (st.is(org.erisia.stasis.StasisRegistry.RIFT_SHARD.get())) plain += st.getCount();
                }
                c.check("mist: plain shards", charged == 0 && plain >= 2, charged + "/" + plain);
                int dust = 0;
                for (var st : net.minecraft.world.level.block.Block.getDrops(org.erisia.stasis.StasisRegistry.RIFT_CRYSTAL_SMALL.get().defaultBlockState(), level, pos, null, null, tool))
                    if (st.is(org.erisia.stasis.StasisRegistry.RIFT_DUST.get())) dust += st.getCount();
                c.check("a small bud gives dust", dust == 1, String.valueOf(dust));
            } finally {
                org.erisia.stasis.Pockets.TEST.remove(box);
            }
        }, false));
        // upwellings: every rift breathes out puffs (drifting deeps) that ride the wind from its hole, grow and fade
        CASES.put("rift_upwellings", new Spec(1, (h, c) -> () -> {
            int seed = 777;
            org.erisia.stasis.StaticDeeps.Deep sd = null;
            for (int i = -4; i < 4 && sd == null; i++)
                for (int k = -4; k < 4 && sd == null; k++) {
                    var d = org.erisia.stasis.StaticDeeps.cell(i, 0, k, seed);
                    if (d != null && d.y() > 0) sd = d;
                }
            c.check("a rift to test", sd != null);
            if (sd == null) return;
            int seen = 0, moved = 0, felt = 0;
            double t0 = 2.0e6;
            for (int s = 0; s < 3600; s += 30) {
                double t = t0 + s;
                var a = org.erisia.stasis.Deeps.near(sd.x(), sd.z(), t, seed);
                var b = org.erisia.stasis.Deeps.near(sd.x(), sd.z(), t + 10, seed);
                for (var pa : a) {
                    for (var pb : b)
                        if (pa.id() == pb.id() && pa.radius() < 25) {
                            // after 10 s a puff has moved with the wind (0.65-1.35 b/s), give or take its growth
                            double m = Math.hypot(pb.x() - pa.x(), pb.z() - pa.z());
                            if (Math.hypot(pa.x() - sd.x(), pa.z() - sd.z()) < 500) {
                                seen++;
                                if (m > 5 && m < 15) moved++;
                                if (pa.strength() > 0.6f && org.erisia.stasis.Deeps.horizontal(pa.x(), pa.z(), t, seed) > 0.5f) felt++;
                            }
                        }
                }
            }
            c.check("puffs come out", seen > 50, String.valueOf(seen));
            c.check("they ride the wind", moved > seen * 0.6, moved + " of " + seen);
            c.check("they are deeps where they are", felt > 10, String.valueOf(felt));
            var x1 = org.erisia.stasis.Deeps.near(sd.x(), sd.z(), t0, seed);
            var x2 = org.erisia.stasis.Deeps.near(sd.x(), sd.z(), t0, seed);
            c.check("deterministic", x1.equals(x2));
        }, false));
        // gates (Baughn): rifts lead to the End or the Nether at 2:1; Nether rifts have netherrack and soul sand in their
        // rock, End rifts none; a gate rift (stronghold portal room) is a static deep with a rift core and no strata
        CASES.put("rift_gates", new Spec(1, (h, c) -> () -> {
            int end = 0, nether = 0, other = 0;
            for (int i = 0; i < 3000; i++) {
                String dest = org.erisia.stasis.Gates.destination(new org.erisia.stasis.StaticDeeps.Deep(0, 0, 0, 30, i * 7919 & 0xFFFFFF));
                if (dest.equals("minecraft:the_end")) end++;
                else if (dest.equals("minecraft:the_nether")) nether++;
                else other++;
            }
            c.check("End 2 : Nether 1", other == 0 && Math.abs(end / (double) (end + nether) - 2 / 3.0) < 0.03, end + "/" + nether + "/" + other);
            org.erisia.stasis.StaticDeeps.Deep netherDeep = null, endDeep = null;
            for (int i = 1; netherDeep == null || endDeep == null; i++) {
                var d = new org.erisia.stasis.StaticDeeps.Deep(0, 0, 0, 30, i * 7919 & 0xFFFFFF);
                if (org.erisia.stasis.Gates.destination(d).equals("minecraft:the_nether")) netherDeep = d;
                else endDeep = d;
            }
            int scattered = 0, bad = 0, endScatter = 0;
            for (int x = 0; x < 40; x++)
                for (int y = 0; y < 40; y++)
                    for (int z = 0; z < 10; z++) {
                        var b = org.erisia.stasis.Gates.scatterForTest(netherDeep, x, y, z);
                        if (b != null) {
                            scattered++;
                            if (!b.is(net.minecraft.world.level.block.Blocks.NETHERRACK) && !b.is(net.minecraft.world.level.block.Blocks.SOUL_SAND)) bad++;
                        }
                        if (org.erisia.stasis.Gates.scatterForTest(endDeep, x, y, z) != null) endScatter++;
                    }
            c.check("a Nether rift: some netherrack and soul sand", scattered > 200 && scattered < 3200 && bad == 0, scattered + " of 16000, " + bad + " other");
            c.check("an End rift: no scatter", endScatter == 0, String.valueOf(endScatter));
            int seed = 31337;
            var centre = new net.minecraft.core.BlockPos(5000, 20, -7000);
            try {
                org.erisia.stasis.StaticDeeps.setGates(seed, java.util.List.of(centre.asLong()));
                var near = org.erisia.stasis.StaticDeeps.near(5000, 20, -7000, 10, seed);
                var gate = near.stream().filter(org.erisia.stasis.StaticDeeps::isGate).findFirst().orElse(null);
                c.check("the gate rift is a static deep", gate != null, near.toString());
                c.check("its core is rift", org.erisia.stasis.StaticDeeps.zone(seed, 5000.5f, 20.5f, -6999.5f) == 2,
                    gate == null ? "-" : String.format("shape %.3f", org.erisia.stasis.StaticDeeps.shape(gate, 5000.5f, 20.5f, -6999.5f)));
                c.check("it leads to the End", gate != null && org.erisia.stasis.Gates.destination(gate).equals("minecraft:the_end"));
                c.check("no strata around it", org.erisia.stasis.Pockets.strataAt(seed, 5000, 10, -7000) == null);
                // another level (its own deep seed) with no gates leaves this one's alone (the End loads after the Overworld)
                org.erisia.stasis.StaticDeeps.setGates(seed + 1, java.util.List.of());
                c.check("another level's gates don't clear these", org.erisia.stasis.StaticDeeps.zone(seed, 5000.5f, 20.5f, -6999.5f) == 2);
            } finally {
                org.erisia.stasis.StaticDeeps.setGates(seed, java.util.List.of());
            }
        }, false));
        // storms (stage 3): about one per block of stormEveryHours, 20 min, ramped; the warped clock is continuous,
        // monotone and runs 10x at full intensity
        CASES.put("storm_schedule", new Spec(1, (h, c) -> () -> {
            int seed = 4242;
            double B = 10 * 3600.0;
            double gaps = 0, prev = -1;
            int n = 0;
            boolean inBlock = true;
            for (long k = 0; k < 400; k++) {
                var st = org.erisia.stasis.Storms.scheduled(seed, k);
                inBlock &= st.start() >= k * B && st.start() + st.length() <= (k + 1) * B;
                if (prev >= 0) {
                    gaps += st.start() - prev;
                    n++;
                }
                prev = st.start();
            }
            c.check("each storm lies in its block", inBlock);
            c.check("mean gap ~10 h", Math.abs(gaps / n / 3600 - 10) < 0.6, String.format("%.2f h", gaps / n / 3600));
            var s0 = org.erisia.stasis.Storms.scheduled(seed, 3);
            double mid = s0.start() + s0.length() / 2;
            c.check("full intensity mid-storm", org.erisia.stasis.Storms.intensity(seed, null, mid) > 0.999);
            c.check("none before it", org.erisia.stasis.Storms.intensity(seed, null, s0.start() - 1) == 0);
            double r = (org.erisia.stasis.Storms.tau(seed, null, mid + 0.5) - org.erisia.stasis.Storms.tau(seed, null, mid - 0.5));
            c.check("10x mid-storm", Math.abs(r - 10) < 1e-6, String.format("%.4f", r));
            double r2 = org.erisia.stasis.Storms.tau(seed, null, s0.start() - 100) - org.erisia.stasis.Storms.tau(seed, null, s0.start() - 101);
            c.check("1x outside", Math.abs(r2 - 1) < 1e-6, String.format("%.4f", r2));
            // continuous and monotone across many storms, and across block edges
            double worst = 0, last = org.erisia.stasis.Storms.tau(seed, null, 0);
            boolean mono = true;
            for (double t = 1; t < 50 * B; t += 7.3) {
                double v = org.erisia.stasis.Storms.tau(seed, null, t);
                double d = v - last;
                mono &= d > 0;
                worst = Math.max(worst, d);
                last = v;
            }
            c.check("tau monotone", mono);
            c.check("tau continuous (a 7.3 s step moves at most 73 s)", worst <= 73.001, String.format("%.3f", worst));
            // an override (test storm) adds to tau without jumps, also after it ends and is folded
            double[] ov = {1000, 1000 + 1200, 0};
            double a = org.erisia.stasis.Storms.tau(seed, ov, 1500), b = org.erisia.stasis.Storms.tau(seed, ov, 1501);
            c.check("a test storm speeds the clock", b - a > 9.9, String.format("%.3f", b - a));
        }, false));
        // storm coverage: at full intensity about half the land in storm mist, a tenth of that rift; ramping with it
        CASES.put("storm_coverage", new Spec(1, (h, c) -> () -> {
            int seed = 4242;
            java.util.Random rng = new java.util.Random(5);
            for (double i : new double[]{1.0, 0.5}) {
                int mist = 0, rift = 0, n = 20000;
                for (int k = 0; k < n; k++) {
                    int kind = org.erisia.stasis.Storms.kind(seed, i, rng.nextFloat() * 20000 - 10000, rng.nextFloat() * 20000 - 10000, 123456.0);
                    if (kind >= 1) mist++;
                    if (kind == 2) rift++;
                }
                double m = mist / (double) n, r = rift / (double) n;
                c.check("covered share at " + i, Math.abs(m - org.erisia.stasis.Storms.mistShare(i)) < 0.04, String.format("%.3f", m));
                c.check("rift share at " + i, Math.abs(r - org.erisia.stasis.Storms.riftShare(i)) < 0.015, String.format("%.3f", r));
            }
            c.check("no storm, no cover", org.erisia.stasis.Storms.kind(seed, 0, 5, 5, 1) == 0);
        }, false));
        // in a storm a tower draws x2 in mist and x3 in rift (Baughn), and a field holds a storm rift
        CASES.put("storm_draw_and_rules", new Spec(100, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 0);
            double fm = t.testStorm(1f, 0f), fr = t.testStorm(0f, 1f), fh = t.testStorm(0.5f, 0.5f);
            c.check("storm mist: x2", Math.abs(fm - 2) < 1e-9, String.valueOf(fm));
            c.check("storm rift: x3", Math.abs(fr - 3) < 1e-9, String.valueOf(fr));
            c.check("half and half: x2.5", Math.abs(fh - 2.5) < 1e-9, String.valueOf(fh));
            var box = new AABB(h.absolutePos(new BlockPos(0, 0, 0))).expandTowards(16, 6, 9);
            org.erisia.stasis.Storms.TEST.put(box, 2);
            ServerPlayer out = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true), in = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            for (ServerPlayer p : new ServerPlayer[]{out, in})
                wear(p, true, org.erisia.stasis.StasisRegistry.STASIS_HELMET.get(), org.erisia.stasis.StasisRegistry.STASIS_CHEST.get(),
                    org.erisia.stasis.StasisRegistry.STASIS_LEGS.get(), org.erisia.stasis.StasisRegistry.STASIS_BOOTS.get());
            return () -> {
                org.erisia.stasis.Storms.TEST.remove(box);
                c.check("storm rift: suits don't help", !out.isAlive());
                c.check("a field holds a storm rift", in.isAlive() && Freezing.ticks(in) == 0, "ticks " + Freezing.ticks(in));
                remove(h, out);
                remove(h, in);
            };
        }));
        // stilled liquids thaw where time runs (inside the field) and stay solid in stopped land; crops after the crash
        // stand at all stages
        CASES.put("stilled_liquids_and_crops", new Spec(80, (h, c) -> {
            var level = h.getLevel();
            BlockPos in = h.absolutePos(new BlockPos(3, 2, 4)), out = h.absolutePos(new BlockPos(12, 2, 4));
            level.setBlockAndUpdate(in, org.erisia.stasis.StasisRegistry.STILLED_WATER_BLOCK.get().defaultBlockState());
            level.setBlockAndUpdate(out, org.erisia.stasis.StasisRegistry.STILLED_LAVA_BLOCK.get().defaultBlockState());
            return () -> {
                c.check("in a field it thaws to water", level.getBlockState(in).is(net.minecraft.world.level.block.Blocks.WATER), level.getBlockState(in).toString());
                c.check("in stopped land it stays stilled", level.getBlockState(out).is(org.erisia.stasis.StasisRegistry.STILLED_LAVA_BLOCK.get()), level.getBlockState(out).toString());
                c.check("stilled lava stacks to 4", new net.minecraft.world.item.ItemStack(org.erisia.stasis.StasisRegistry.STILLED_LAVA.get()).getMaxStackSize() == 4);
                level.setBlockAndUpdate(in, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(out, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                var wheat = (net.minecraft.world.level.block.CropBlock) net.minecraft.world.level.block.Blocks.WHEAT;
                java.util.Set<Integer> ages = new java.util.HashSet<>();
                for (int i = 0; i < 200; i++)
                    ages.add(wheat.getAge(org.erisia.stasis.TimeScars.cropAge(wheat, wheat.defaultBlockState(), new BlockPos(i * 7, 64, i * 3))));
                c.check("crops at every stage", ages.size() == wheat.getMaxAge() + 1, ages.toString());
            };
        }));
        // crafting a stasis-suit piece from a charged Delver piece keeps the charge
        CASES.put("suit_craft_keeps_charge", new Spec(1, (h, c) -> () -> {
            var level = h.getLevel();
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("stasisdelvers", "stasis_helmet"));
            var holder = level.getServer().getRecipeManager().byKey(key);
            c.check("the stasis helmet recipe is a suit upgrade", holder.isPresent() && holder.get().value() instanceof org.erisia.stasis.SuitUpgradeRecipe,
                holder.map(r -> r.value().getClass().getSimpleName()).orElse("missing"));
            if (holder.isEmpty()) return;
            ItemStack delver = new ItemStack(org.erisia.stasis.StasisRegistry.DELVER_HELMET.get());
            ((org.erisia.stasis.DelverSuitItem) delver.getItem()).setEnergy(delver, 5000);
            java.util.List<ItemStack> items = new java.util.ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
            items.set(7, delver);
            ItemStack result = ((net.minecraft.world.item.crafting.CraftingRecipe) holder.get().value()).assemble(
                net.minecraft.world.item.crafting.CraftingInput.of(3, 3, items));
            double e = result.getItem() instanceof org.erisia.stasis.StasisArmorItem a ? a.getEnergy(result) : -1;
            c.check("the charge carried over", Math.abs(e - 5000) < 1e-6, String.valueOf(e));
        }, false));
        // a rift crystal cluster mined with a pickaxe drops 2-4 shards (26.x loot format: "modifier", not "functions")
        CASES.put("rift_crystal_drops", new Spec(1, (h, c) -> () -> {
            var level = h.getLevel();
            var pos = h.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
            var state = org.erisia.stasis.StasisRegistry.RIFT_CRYSTAL_CLUSTER.get().defaultBlockState();
            var tool = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE);
            int min = 99, max = 0;
            for (int i = 0; i < 40; i++) {
                int n = 0;
                for (var st : net.minecraft.world.level.block.Block.getDrops(state, level, pos, null, null, tool))
                    if (st.is(org.erisia.stasis.StasisRegistry.RIFT_SHARD.get())) n += st.getCount();
                min = Math.min(min, n);
                max = Math.max(max, n);
            }
            c.check("2-4 shards per cluster", min >= 2 && max <= 4 && max > min, min + ".." + max);
            int stone = 0;
            for (var st : net.minecraft.world.level.block.Block.getDrops(org.erisia.stasis.StasisRegistry.RIFTSTONE.get().defaultBlockState(), level, pos, null, null, tool))
                stone += st.getCount();
            c.check("riftstone drops itself", stone == 1, String.valueOf(stone));
        }, false));
        // riftstone ores (λ-Nero): each drops what its stone source drops (Fortune too), silk touch gives the riftstone
        // ore itself, and it carries the source's tags (not the scatter's c:ores_in_ground/stone|deepslate)
        CASES.put("riftstone_ore_drops", new Spec(1, (h, c) -> () -> {
            var level = h.getLevel();
            var pos = h.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1));
            var ench = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
            var plain = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE);
            var silk = plain.copy();
            silk.enchant(ench.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH), 1);
            var fortune = plain.copy();
            fortune.enchant(ench.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FORTUNE), 3);
            var inGround = java.util.List.of("ores_in_ground/stone", "ores_in_ground/deepslate").stream()
                .map(t -> net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.fromNamespaceAndPath("c", t))).toList();
            var riftTag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK, net.minecraft.resources.Identifier.fromNamespaceAndPath("c", "ores_in_ground/riftstone"));
            int tested = 0;
            for (var b : org.erisia.stasis.RiftOres.BLOCKS) {
                var variant = b.get();
                var name = b.getId().getPath();
                var srcId = net.minecraft.resources.Identifier.parse(org.erisia.stasis.RiftOres.stoneSource(variant));
                var source = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(srcId).orElse(null);
                c.check(name + ": riftstone ore tag, not the scatter's", variant.defaultBlockState().is(riftTag)
                    && inGround.stream().noneMatch(t -> variant.defaultBlockState().is(t)));
                if (source == null) continue;   // a pack mod's ore (tin, silver, sal ammoniac): not in the dev run
                tested++;
                var vs = variant.defaultBlockState();
                var ss = source.defaultBlockState();
                // drops: the same items, counts in the source's range (from many draws)
                java.util.Map<net.minecraft.world.item.Item, int[]> want = new java.util.HashMap<>(), got = new java.util.HashMap<>();
                for (int i = 0; i < 200; i++) {
                    tally(want, net.minecraft.world.level.block.Block.getDrops(ss, level, pos, null, null, plain));
                    tally(got, net.minecraft.world.level.block.Block.getDrops(vs, level, pos, null, null, plain));
                }
                boolean same = want.keySet().equals(got.keySet()) && got.entrySet().stream().allMatch(e -> e.getValue()[0] >= want.get(e.getKey())[0] && e.getValue()[1] <= want.get(e.getKey())[1]);
                c.check(name + " drops what " + srcId + " drops", same && !got.isEmpty(), show(got) + " vs " + show(want));
                var st = net.minecraft.world.level.block.Block.getDrops(vs, level, pos, null, null, silk);
                c.check(name + ": silk touch gives itself", st.size() == 1 && st.get(0).is(variant.asItem()) && st.get(0).getCount() == 1, st.toString());
                if (name.equals("riftstone_diamond_ore")) {
                    int more = 0;
                    for (int i = 0; i < 100; i++)
                        for (var d : net.minecraft.world.level.block.Block.getDrops(vs, level, pos, null, null, fortune)) if (d.getCount() > 1) more++;
                    c.check("fortune III: sometimes more than one diamond", more > 10, more + " of 100");
                    c.check("no fortune: one diamond", got.get(net.minecraft.world.item.Items.DIAMOND) != null && got.get(net.minecraft.world.item.Items.DIAMOND)[1] == 1);
                }
                // the source's tags, block and item, except the scatter's own and other mods' world-shape tags
                var missing = new java.util.ArrayList<String>();
                source.builtInRegistryHolder().tags().filter(t -> !inGround.contains(t) && !t.location().getPath().equals("snaps_goat_horn") && !t.location().getNamespace().equals("terralith"))
                    .filter(t -> !vs.is(t)).forEach(t -> missing.add(t.location().toString()));
                source.asItem().builtInRegistryHolder().tags().filter(t -> !t.location().getPath().startsWith("ores_in_ground/"))
                    .filter(t -> !variant.asItem().builtInRegistryHolder().is(t)).forEach(t -> missing.add("item " + t.location()));
                c.check(name + " has " + srcId + "'s tags", missing.isEmpty(), missing.toString());
                c.check(name + ": the source's hardness", vs.getDestroySpeed(level, pos) == ss.getDestroySpeed(level, pos)
                    && variant.getExplosionResistance() == source.getExplosionResistance());
            }
            c.check("the vanilla ores' riftstone ores tested", tested == 8, String.valueOf(tested));
        }, false));
        // the rift scatter places riftstone ores, never the stone or deepslate ores (new chunks; λ-Nero): a test rift in
        // a stone-filled chunk far from the test grid, carved through a recording writer
        CASES.put("riftstone_ore_scatter", new Spec(1, (h, c) -> () -> {
            var level = h.getLevel();
            int n = 0, other = 0;
            java.util.Set<net.minecraft.world.level.block.Block> riftOres = new java.util.HashSet<>();
            org.erisia.stasis.RiftOres.BLOCKS.forEach(b -> riftOres.add(b.get()));
            for (int i = 0; i < 200000; i++) {
                var o = org.erisia.stasis.DeepFeature.oreForTest(i % 97, i / 97 % 61, i / 5917, 0x5A0);
                if (o == null) continue;
                n++;
                if (!riftOres.contains(o.getBlock())) other++;
            }
            c.check("the draw gives riftstone ores only", n > 400 && other == 0, n + " ores, " + other + " not riftstone");
            int cx = 40000, cz = 40000, cy = 100;
            var d = new org.erisia.stasis.StaticDeeps.Deep(cx + 8.5f, cy + 0.5f, cz + 8.5f, 30, 0x2B0E5);
            level.getChunk(cx >> 4, cz >> 4);
            var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            var filled = new java.util.ArrayList<net.minecraft.core.BlockPos>();
            for (int x = cx; x < cx + 16; x++)
                for (int z = cz; z < cz + 16; z++)
                    for (int y = cy - 40; y <= cy + 40; y++) {
                        float s = org.erisia.stasis.StaticDeeps.shape(d, x + 0.5f, y + 0.5f, z + 0.5f);
                        if (s < 0.30f || s >= 0.42f) continue;
                        var p = new net.minecraft.core.BlockPos(x, y, z);
                        level.setBlock(p, stone, 2);
                        filled.add(p);
                    }
            java.util.Map<net.minecraft.world.level.block.Block, Integer> placed = new java.util.HashMap<>();
            try {
                org.erisia.stasis.DeepFeature.apply(level, (p, st) -> {
                    if (riftOres.contains(st.getBlock()) || st.is(net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                        net.minecraft.resources.Identifier.fromNamespaceAndPath("c", "ores")))) placed.merge(st.getBlock(), 1, Integer::sum);
                }, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, d, cx, cz, false);
            } finally {
                for (var p : filled) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            }
            int ore = placed.values().stream().mapToInt(Integer::intValue).sum();
            boolean onlyRift = placed.keySet().stream().allMatch(riftOres::contains);
            c.check("ores in the test rift's shell, all riftstone ores", ore > 0 && onlyRift, filled.size() + " shell blocks, " + placed);
        }, false));
        // the drifting deeps' clock never wraps: a deep moves smoothly (< 0.2 blocks in 0.05 s, but it moves) across the
        // old 14.5 h wrap of Eddies.time and weeks or a year into a world
        CASES.put("deeps_clock_continuous", new Spec(1, (h, c) -> () -> {
            for (double t : new double[]{1048576 / 20.0, 3.0e6, 3.0e7}) {
                var a = org.erisia.stasis.Deeps.near(0, 0, t - 0.025, 777);
                var b = org.erisia.stasis.Deeps.near(0, 0, t + 0.025, 777);
                int matched = 0;
                double worst = 0, moved = 0;
                for (var da : a)
                    for (var db : b)
                        if (da.id() == db.id()) {
                            matched++;
                            double m = Math.hypot(db.x() - da.x(), db.z() - da.z());
                            worst = Math.max(worst, m);
                            moved = Math.max(moved, m);
                        }
                c.check("deeps found at t=" + t, matched > 0, a.size() + "/" + b.size());
                c.check("smooth at t=" + t, worst < 0.2, String.format("%.3f blocks", worst));
                c.check("moving at t=" + t, moved > 0.005, String.format("%.4f blocks", moved));
            }
        }, false));
        // static deeps (Baughn): about a quarter of grid cells; organic; a rift core at the centre, outer zone around
        CASES.put("static_deeps_shape", new Spec(1, (h, c) -> () -> {
            int seed = 4242, found = 0, cells = 0;
            org.erisia.stasis.StaticDeeps.Deep d0 = null;
            for (int i = -6; i < 6; i++)
                for (int k = -6; k < 6; k++) {
                    cells++;
                    var d = org.erisia.stasis.StaticDeeps.cell(i, 0, k, seed);
                    if (d != null) {
                        found++;
                        if (d0 == null) d0 = d;
                    }
                }
            c.check("about a quarter of the cells hold one", found > 0.12 * cells && found < 0.4 * cells, found + " of " + cells);
            if (d0 != null) {
                var d = d0;
                c.check("the centre is rift", org.erisia.stasis.StaticDeeps.zone(seed, d.x(), d.y(), d.z()) == 2);
                int outer = 0, n = 0;
                for (int a = 0; a < 16; a++) {
                    double ang = a * Math.PI / 8;
                    float x = (float) (d.x() + Math.cos(ang) * d.radius() * 0.7), z = (float) (d.z() + Math.sin(ang) * d.radius() * 0.7);
                    n++;
                    if (org.erisia.stasis.StaticDeeps.zone(seed, x, d.y(), z) == 1) outer++;
                }
                c.check("most of a ring at 0.7 r is the outer zone (organic: not all)", outer >= n / 3 && outer <= n, outer + " of " + n);
                c.check("far outside is nothing", org.erisia.stasis.StaticDeeps.zone(seed, d.x() + d.radius() * 2.5f, d.y(), d.z()) == 0);
            }
        }, false));
        // weather (Baughn): a deep over a field raises the tower's draw (+50% at full drifting overlap); the overlap
        // measure sees a deep at its centre and nothing far from any
        CASES.put("tower_weather_raises_draw", new Spec(45, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 0);
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                t.energy().insert((int) (org.erisia.stasis.TowerBlockEntity.storeFor(16) * 0.9 * 4 / 3), tx);
                tx.commit();
            }
            double[] at = new double[2];
            h.runAfterDelay(4, () -> {
                t.testWeather(1.0f, 0f);
                at[0] = t.storedJ();
            });
            h.runAfterDelay(44, () -> at[1] = t.storedJ());
            return () -> {
                double used = at[0] - at[1], expect = org.erisia.stasis.TowerBlockEntity.drawFor(16) * 1.5 / 20 * 40;
                c.check("full drifting overlap: 1.5x the draw", Math.abs(used - expect) < 0.05 * expect, String.format("%.0f J vs %.0f J", used, expect));
                // the overlap measure, with deeps on and a known seed
                ServerLevel level = h.getLevel();
                int old = org.erisia.stasis.Deeps.seed(level);
                org.erisia.stasis.Deeps.testDeeps = true;
                try {
                    FieldData.get(level).testDeepSeed(777);
                    var near = org.erisia.stasis.Deeps.near(0, 0, org.erisia.stasis.Deeps.time(level), 777);
                    var d = near.stream().filter(x -> x.strength() > 0.9f && x.radius() > 20).findFirst();
                    if (d.isEmpty()) c.check("found a strong deep to measure", false, near.toString());
                    else {
                        int bx = (int) d.get().x(), bz = (int) d.get().z();
                        for (int cx = -1; cx <= 1; cx++)
                            for (int cz = -1; cz <= 1; cz++) level.getChunk((bx >> 4) + cx, (bz >> 4) + cz);
                        int surf = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
                        Field in = new Field(1, bx + 0.5, surf + 4.5, bz + 0.5, 10), out = new Field(2, bx + 0.5 + 2000, surf + 4.5, bz + 0.5, 10);
                        float oi = org.erisia.stasis.Deeps.overlap(level, in), oo = org.erisia.stasis.Deeps.overlap(level, out);
                        c.check("a field at a deep's centre is mostly in it", oi > 0.5f, "" + oi);
                        c.check("far away: nothing (unless another deep happens to be there)", oo < 0.5f || oo == oi, "" + oo);
                    }
                } finally {
                    org.erisia.stasis.Deeps.testDeeps = false;
                    FieldData.get(level).testDeepSeed(old);
                }
                t.setRemoved();
            };
        }, false, true));
        // eddies stop mobs in a pocket (and only there)
        CASES.put("eddies_stop_mobs", new Spec(4, (h, c) -> {
            ServerLevel level = h.getLevel();
            Field f = field(h, c, new Vec3(8.5, 3.5, 4.5), 12, 1.0);
            FieldData.get(level).update(level, f.id(), x -> x.withFailing(0.95));
            BlockPos inPocket = null, inField = null;
            // (any height in the field: at some moments the drifting pockets miss the floor row)
            for (int y = 1; y <= 6 && (inPocket == null || inField == null); y++)
            for (int x = 1; x < 16 && (inPocket == null || inField == null); x++)
                for (int z = 1; z < 8; z++) {
                    BlockPos p = h.absolutePos(new BlockPos(x, y, z));
                    if (inPocket != null && inField != null) break;
                    if (!h.getLevel().getBlockState(p).isAir()) continue;
                    if (!f.containsBlock(p.getX(), p.getY(), p.getZ())) continue;
                    boolean run = Stasis.isRunning(level, p), steady = Stasis.isRunningSteady(level, p);
                    if (!run && steady && inPocket == null) inPocket = p;
                    if (run && inField == null) inField = p;
                }
            if (inPocket == null || inField == null) {
                c.check("found a pocket and running land at floor level", false, inPocket + " / " + inField);
                return () -> { };
            }
            var held = (net.minecraft.world.entity.Mob) EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            var free = (net.minecraft.world.entity.Mob) EntityTypes.PIG.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            held.setPos(Vec3.atBottomCenterOf(inPocket));
            free.setPos(Vec3.atBottomCenterOf(inField));
            level.addFreshEntity(held);
            level.addFreshEntity(free);
            int h0 = held.tickCount, f0 = free.tickCount;
            BlockPos hp = inPocket, fp = inField;
            // (eddies drift: the free pig's spot may turn into a pocket during the test; only require ticks while it ran)
            boolean[] ranSometime = {false};
            for (int d = 1; d <= 3; d++) h.runAfterDelay(d, () -> ranSometime[0] |= Stasis.isRunning(level, fp));
            return () -> {
                boolean stillPocket = !Stasis.isRunning(level, hp);
                c.check("the pig in running land ticked", !ranSometime[0] || free.tickCount > f0, f0 + " -> " + free.tickCount);
                c.check("the pig in the pocket didn't (while the pocket stayed)", !stillPocket || held.tickCount == h0, h0 + " -> " + held.tickCount);
                held.discard();
                free.discard();
            };
        }, false, true));
        CASES.put("tower_collapses_when_empty", new Spec(560, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 0);
            try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                t.energy().insert((int) (org.erisia.stasis.TowerBlockEntity.storeFor(16) * 0.6 * 4 / 3), tx);
                tx.commit();
            }
            int[] fid = {0};
            h.runAfterDelay(5, () -> {
                c.check("running", t.state() == org.erisia.stasis.TowerBlockEntity.State.RUNNING, t.state().name());
                fid[0] = t.fieldId();   // (by id: the safety may shrink it below 16 as it empties)
                t.testDrawFactor = 1e6;   // empty the store in a tick
            });
            h.runAfterDelay(160, () -> {
                c.check("halfway through the collapse", t.state() == org.erisia.stasis.TowerBlockEntity.State.COLLAPSING, t.state().name());
                var f = FieldData.get(h.getLevel()).fields().stream().filter(x -> x.id() == fid[0]).findFirst();
                c.check("its field is partly collapsed", f.isPresent() && f.get().collapse() > 0.02 && f.get().collapse() < 0.5, f.toString());
            });
            return () -> {
                c.check("collapsed: off", t.state() == org.erisia.stasis.TowerBlockEntity.State.OFF, t.state().name());
                c.check("collapsed: the field is gone", FieldData.get(h.getLevel()).fields().stream().noneMatch(f -> f.id() == fid[0]));
                // Baughn: its last moment stays, frozen (three held bolts, a few hexes), stopped just as half of the
                // shield's hexes are lost (dropout + collapse)
                var rem = t.remnant();
                c.check("collapsed: a frozen remnant at half the hexes lost", rem != null && rem.bolts().size() == 3
                        && Math.abs(rem.collapse() + rem.dropout() - 0.5) < 1e-6,
                    String.valueOf(rem));
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
        // frozen cables still show their connections (Baughn: they looked cut), while staying out of the simulation
        CASES.put("ea_frozen_cables_look_connected", new Spec(20, (h, c) -> {
            var a = eaSix(h, new BlockPos(12, 1, 2), 4 + (32 << 6));
            var b = eaSix(h, new BlockPos(12, 1, 3), 4 + (32 << 6));
            return () -> {
                org.erisia.stasis.NodeFreezer.pass();
                var na = (mods.eln.node.six.SixNode) a.sixNode;
                var nb = (mods.eln.node.six.SixNode) b.sixNode;
                c.check("both frozen", org.erisia.stasis.NodeFreezer.isFrozen(na) && org.erisia.stasis.NodeFreezer.isFrozen(nb));
                c.check("both show a connection", na.lrduElementMask.get(mods.eln.misc.Direction.YN).mask != 0
                    && nb.lrduElementMask.get(mods.eln.misc.Direction.YN).mask != 0,
                    na.lrduElementMask.get(mods.eln.misc.Direction.YN).mask + " / " + nb.lrduElementMask.get(mods.eln.misc.Direction.YN).mask);
                c.check("no electrical link", na.nodeConnectionList.isEmpty() && nb.nodeConnectionList.isEmpty());
                na.reconnect();   // as EA does when a device is added, toggled, burnt or repaired
                c.check("an EA reconnect keeps a frozen node out of the simulation (the veto)", !na.isAdded()
                    && na.lrduElementMask.get(mods.eln.misc.Direction.YN).mask != 0);
            };
        }, false, true));
        // EA infrastructure within 6 blocks of a tower core runs in stopped land (a battery can start the tower)
        // flames (Baughn): no light in stopped land; light comes back when a field covers them; guttering dims and
        // recovers; the overworld lets monsters spawn up to block light 7
        CASES.put("flames_dark_in_the_grey", new Spec(20, (h, c) -> {
            h.setBlock(new BlockPos(1, 1, 4), Blocks.TORCH);       // in the field
            h.setBlock(new BlockPos(15, 1, 4), Blocks.TORCH);      // stopped land, 14 blocks from the other
            h.setBlock(new BlockPos(15, 1, 7), Blocks.LANTERN);
            return () -> {
                var light = settledLight(h);
                BlockPos tp = h.absolutePos(new BlockPos(1, 1, 4)), op = h.absolutePos(new BlockPos(15, 1, 4));
                // (or it's guttering: random ticks may dim a flame at any time, Flames.gutterChance)
                c.check("a torch in the field gives light 14", light.getLightValue(tp) == 14 || Flames.guttering(h.getLevel(), tp), "" + light.getLightValue(tp));
                c.check("a torch in stopped land gives none", light.getLightValue(op) == 0, "" + light.getLightValue(op));
                c.check("a lantern in stopped land gives none", light.getLightValue(h.absolutePos(new BlockPos(15, 1, 7))) == 0);
                c.check("the overworld's monster spawn block light limit is 7", h.getLevel().dimensionType().monsterSpawnBlockLightLimit() == 7,
                    "" + h.getLevel().dimensionType().monsterSpawnBlockLightLimit());
            };
        }, true, true));
        CASES.put("flames_light_up_when_a_field_comes", new Spec(20, (h, c) -> {
            h.setBlock(new BlockPos(12, 1, 4), Blocks.TORCH);
            h.runAfterDelay(5, () -> halfField(h, c, 1.0));
            return () -> {
                int l = settledLight(h).getLightValue(h.absolutePos(new BlockPos(12, 1, 4)));
                BlockPos tp = h.absolutePos(new BlockPos(12, 1, 4));
                c.check("the torch the new field covers gives light 14", l == 14 || Flames.guttering(h.getLevel(), tp), l + " emission " + Flames.emission(h.getLevel().getBlockState(tp), h.getLevel(), tp)
                    + " running " + Stasis.isRunning(h.getLevel(), tp));
            };
        }, true, true));
        CASES.put("flames_go_dark_when_the_field_goes", new Spec(20, (h, c) -> {
            ServerLevel level = h.getLevel();
            h.setBlock(new BlockPos(12, 1, 4), Blocks.TORCH);
            Field f = halfField(h, c, 1.0);
            h.runAfterDelay(5, () -> FieldData.get(level).remove(level, f.id()));
            return () -> {
                int l = settledLight(h).getLightValue(h.absolutePos(new BlockPos(12, 1, 4)));
                // (a little light can leak in from a neighbouring test's torches)
                c.check("the torch the removed field covered gives no light", l <= 3, "" + l);
            };
        }, true, true));
        CASES.put("flames_gutter_and_recover", new Spec(40, (h, c) -> {
            BlockPos local = new BlockPos(3, 1, 4);
            h.setBlock(local, Blocks.TORCH);
            int[] dimmed = {-1};
            h.runAfterDelay(2, () -> Flames.gutter(h.getLevel(), h.absolutePos(local), 5, 15));
            h.runAfterDelay(10, () -> dimmed[0] = settledLight(h).getLightValue(h.absolutePos(local)));
            return () -> {
                var light = settledLight(h);
                c.check("while guttering the torch gives light 5", dimmed[0] == 5, "" + dimmed[0]);
                int l = light.getLightValue(h.absolutePos(local));
                c.check("after the gutter it gives 14 again", l == 14 && !Flames.guttering(h.getLevel(), h.absolutePos(local)), "" + l);
            };
        }, true, true));
        // Baughn: a flame doesn't gutter while two or more right next to it (diagonals too) do; one is fine
        CASES.put("flames_gutter_not_beside_two", new Spec(5, (h, c) -> {
            ServerLevel level = h.getLevel();
            // a and b gutter; z touches only a (diagonally), x touches both
            BlockPos a = new BlockPos(2, 1, 3), b = new BlockPos(3, 1, 4), x = new BlockPos(2, 1, 4), z = new BlockPos(1, 1, 2);
            for (BlockPos p : List.of(a, b, x, z)) h.setBlock(p, Blocks.TORCH);
            return () -> {
                var rnd = net.minecraft.util.RandomSource.create(1);
                Flames.gutter(level, h.absolutePos(a), 5, 200);
                Flames.gutter(level, h.absolutePos(b), 5, 200);
                Flames.tryGutter(level.getBlockState(h.absolutePos(z)), level, h.absolutePos(z), rnd, 1.0);
                Flames.tryGutter(level.getBlockState(h.absolutePos(x)), level, h.absolutePos(x), rnd, 1.0);
                c.check("beside one guttering torch it gutters", Flames.guttering(level, h.absolutePos(z)));
                c.check("beside two guttering torches it doesn't", !Flames.guttering(level, h.absolutePos(x)));
                for (BlockPos p : List.of(a, b, x, z)) c.cleanup.add(() -> Flames.gutter(level, h.absolutePos(p), 5, 0));
            };
        }, true, true));
        // random ticks gutter flames where time runs (here: very fast random ticks), never in stopped land
        CASES.put("flames_gutter_on_random_ticks", new Spec(60, (h, c) -> {
            ServerLevel level = h.getLevel();
            var rules = level.getGameRules();
            int speed = rules.get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
            rules.set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 4096, level.getServer());
            c.cleanup.add(() -> rules.set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, speed, level.getServer()));
            h.setBlock(new BlockPos(3, 1, 4), Blocks.TORCH);
            h.setBlock(new BlockPos(12, 1, 4), Blocks.TORCH);
            return () -> {
                BlockPos in = h.absolutePos(new BlockPos(3, 1, 4)), out = h.absolutePos(new BlockPos(12, 1, 4));
                int e = Flames.emission(level.getBlockState(in), level, in);
                c.check("the torch in the field guttered (light 4-7)", e >= 4 && e <= 7, "" + e);
                c.check("the torch in stopped land has no gutter", !Flames.guttering(level, out));
                c.cleanup.add(() -> Flames.gutter(level, in, 5, 0));
            };
        }, true, true));
        // EA lamps light the world inside a field (Baughn: "EA lights don't work"): a Lamp Supply powering a Robust Lamp
        // Socket by channel, all in the field
        CASES.put("ea_lamp_lights_in_a_field", new Spec(100, (h, c) -> {
            var src = (mods.eln.sixnode.electricalsource.ElectricalSourceElement) eaSix(h, new BlockPos(1, 1, 4), 0 + (3 << 6));
            src.networkUnserialize(eaStream(o -> {
                o.writeByte(mods.eln.sixnode.electricalsource.ElectricalSourceElement.setVoltageId);
                o.writeFloat(50f);
            }));
            eaSix(h, new BlockPos(2, 1, 4), 4 + (32 << 6));
            var supply = (mods.eln.sixnode.lampsupply.LampSupplyElement) eaSix(h, new BlockPos(3, 1, 4), 0 + (65 << 6));
            for (var f : mods.eln.misc.LRDU.values())
                if (supply.side.applyLRDU(f) == mods.eln.misc.Direction.XN) {
                    supply.front = f;
                    supply.sixNode.reconnect();
                }
            supply.getInventory().setItem(0, mods.eln.Eln.sixNodeItem.getDescriptor(4 + (32 << 6)).newItemStack(1));
            supply.getInventory().setChanged();
            String ch = "stasis lamp " + System.nanoTime();
            supply.networkUnserialize(eaStream(o -> {
                o.writeByte(mods.eln.sixnode.lampsupply.LampSupplyElement.setPowerName);
                o.writeByte(0);
                o.writeUTF(ch);
            }));
            var lamp = (mods.eln.sixnode.lampsocket.LampSocketElement) eaSix(h, new BlockPos(5, 1, 4), 4 + (64 << 6));
            lamp.getInventory().setItem(0, mods.eln.Eln.sharedItem.getDescriptor(0 + (4 << 6)).newItemStack());
            lamp.getInventory().setItem(1, mods.eln.Eln.sixNodeItem.getDescriptor(4 + (32 << 6)).newItemStack(1));
            lamp.getInventory().setChanged();
            lamp.networkUnserialize(eaStream(o -> {
                o.writeByte(4);   // LampSocketElement.setChannel
                o.writeUTF(ch);
            }));
            return () -> {
                var light = settledLight(h);
                int max = 0;
                BlockPos lp = h.absolutePos(new BlockPos(5, 1, 4));
                for (BlockPos q : BlockPos.betweenClosed(lp.offset(-1, 0, -1), lp.offset(1, 4, 1))) max = Math.max(max, light.getLightValue(q));
                c.check("the lamp lights the world in the field", max >= 12, "max block light around the lamp " + max
                    + ", lamp U " + String.format("%.1f", lamp.positiveLoad.getU()));
            };
        }));
        CASES.put("ea_tower_yard_keeps_cables", new Spec(30, (h, c) -> {
            tower(h, new BlockPos(12, 1, 6), 0);                                   // unpowered: no field
            var cable = eaSix(h, new BlockPos(12, 1, 4), 4 + (32 << 6));
            var src = eaSix(h, new BlockPos(11, 1, 4), 0 + (3 << 6));
            var far = eaSix(h, new BlockPos(3, 1, 2), 4 + (32 << 6));            // 8 blocks from the core
            return () -> {
                org.erisia.stasis.NodeFreezer.pass();
                c.check("next to the core the cable runs", !org.erisia.stasis.NodeFreezer.isFrozen(cable.sixNode));
                c.check("a source is machinery: stopped", org.erisia.stasis.NodeFreezer.isFrozen(src.sixNode));
                c.check("8 blocks away a cable is stopped", org.erisia.stasis.NodeFreezer.isFrozen(far.sixNode));
            };
        }, false, true));
        // an EA-to-FE exporter beside the core feeds the tower in stopped land (its push runs in EA's simulation, which
        // NodeFreezer keeps going in the tower yard; it used to be a block-entity tick, exempted in Stasis)
        CASES.put("ea_tower_yard_exporter_feeds_the_tower", new Spec(30, (h, c) -> {
            var tower = tower(h, new BlockPos(12, 1, 6), 0);                     // unpowered: no field
            var d = mods.eln.registry.batch.Wp11Content.elnToOtherLvu;
            ServerLevel level = h.getLevel();
            BlockPos p = h.absolutePos(new BlockPos(12, 1, 5)), core = h.absolutePos(new BlockPos(12, 1, 6));
            var towards = net.minecraft.core.Direction.getApproximateNearest(core.getX() - p.getX(), 0, core.getZ() - p.getZ());
            var player = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
            float yaw = player.getYRot(), pitch = player.getXRot();
            player.setYRot(towards.toYRot());                                     // looking at the core: back to it
            player.setXRot(0f);
            mods.eln.node.transparent.TransparentNode n;
            try {
                n = ((mods.eln.node.transparent.TransparentNodeItem) d.parentItem).placeAt(d.newItemStack(), player, level, p, mods.eln.misc.Direction.YN);
            } finally {
                player.setYRot(yaw);
                player.setXRot(pitch);
            }
            if (n == null) throw new IllegalStateException("placing the exporter failed");
            var exporter = (mods.eln.transparentnode.energyconverter.EnergyConverterElnToOtherElement) n.element;
            exporter.energyBuffer = 300;                                          // J: 400 FE
            return () -> {
                org.erisia.stasis.NodeFreezer.pass();
                c.check("time is stopped at the exporter", !Stasis.isRunning(level, p));
                c.check("the exporter beside the core runs", !org.erisia.stasis.NodeFreezer.isFrozen(n));
                c.check("its buffer went into the tower", exporter.energyBuffer < 1, exporter.energyBuffer + " J left; front " + exporter.getFront()
                    + ", FE behind " + level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Energy.BLOCK, core, towards.getOpposite()));
                c.check("the tower got it", tower.storedJ() > 290, tower.storedJ() + " J stored");
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

        // ---- field tick rates (rate = share of 20 TPS; stopped land = 0; overlapping fields: the fastest) ----
        // the gate and the scheduled-tick delay, exactly
        CASES.put("rate_math", new Spec(1, (h, c) -> () -> {
            for (double r : new double[]{0, 0.001, 0.25, 0.5, 0.6, 0.75, 1}) {
                int n = 0;
                for (long t = 123456789L; t < 123456789L + 1000; t++) if (Stasis.runs(r, t)) n++;
                c.check("rate " + r + ": " + Math.round(r * 1000) + " of 1000 ticks run", n == Math.round(r * 1000), "ran " + n);
            }
            boolean ok = true;
            String bad = "";
            for (double r : new double[]{0.1, 0.5, 0.6, 0.7, 0.999, 1})
                for (long now = 1000; now < 1040; now++)
                    for (int d = 1; d <= 25; d++) {
                        long due = Stasis.dueTick(r, now, d);
                        int n = 0;
                        for (long t = now + 1; t <= due; t++) if (Stasis.runs(r, t)) n++;
                        if (n != d || !Stasis.runs(r, due)) {
                            ok = false;
                            bad = "r " + r + " now " + now + " d " + d + " due " + due + " ran " + n;
                        }
                    }
            c.check("a scheduled tick falls due `delay` running ticks later, on a running tick", ok, bad);
            c.check("rate 1: vanilla's now + delay", Stasis.dueTick(1.0, 5000, 7) == 5007);
            ServerLevel level = h.getLevel();
            Field half = halfField(h, c, 0.5);
            BlockPos in = h.absolutePos(new BlockPos(12, 1, 4)), full = h.absolutePos(new BlockPos(3, 1, 4));
            long d = level.createTick(in, Blocks.STONE, 10).triggerTick() - level.getGameTime();
            long d1 = level.createTick(full, Blocks.STONE, 10).triggerTick() - level.getGameTime();
            c.check("createTick in a 0.5 field: delay 10 -> ~20", d >= 19 && d <= 21, "delay " + d + ", field " + half);
            c.check("createTick in a full-speed field: delay 10", d1 == 10, "delay " + d1);
            c.check("rateAt: 0.5 / 1 / 0 (stopped)", Stasis.rateAt(level, in) == 0.5 && Stasis.rateAt(level, full) == 1.0
                && Stasis.rateAt(level, h.absolutePos(new BlockPos(15, 1, 0))) == 0.0);
        }));
        // block entities: a hopper pulls an item every 8 of its ticks; 160 game ticks give 20 / 10 / 0 items
        CASES.put("rate_hopper", new Spec(160, (h, c) -> {
            halfField(h, c, 0.5);
            var full = hopper(h, new BlockPos(3, 1, 4)); var half = hopper(h, new BlockPos(12, 1, 4)); var out = hopper(h, new BlockPos(15, 1, 1));
            return () -> {
                int n1 = count(full), n05 = count(half), n0 = count(out);
                c.check("full speed: about 20 items", n1 >= 18 && n1 <= 21, "items " + n1);
                c.check("0.5 field: half as many", n05 * 2 >= n1 - 2 && n05 * 2 <= n1 + 2, n05 + " vs " + n1);
                c.check("stopped: none", n0 == 0, "items " + n0);
            };
        }));
        // scheduled ticks: a stone button stays pressed 20 ticks (40 in a 0.5 field, forever outside)
        CASES.put("rate_scheduled_button", new Spec(50, (h, c) -> {
            halfField(h, c, 0.5);
            BlockPos b1 = new BlockPos(3, 1, 2), b05 = new BlockPos(12, 1, 4), b0 = new BlockPos(15, 1, 6);
            var button = Blocks.STONE_BUTTON.defaultBlockState().setValue(net.minecraft.world.level.block.ButtonBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR);
            for (BlockPos b : new BlockPos[]{b1, b05, b0}) {
                h.setBlock(b, button);
                h.pressButton(b);
            }
            h.runAfterDelay(30, () -> {
                c.check("30 ticks: full speed released", !pressed(h, b1));
                c.check("30 ticks: 0.5 field still pressed", pressed(h, b05));
            });
            return () -> {
                c.check("50 ticks: 0.5 field released", !pressed(h, b05));
                c.check("50 ticks: stopped still pressed", pressed(h, b0));
            };
        }));
        // random ticks: cactus age counts the random ticks a block got. Raised tick speed, four rounds of 24 ticks
        CASES.put("rate_random_ticks", new Spec(100, (h, c) -> {
            ServerLevel level = h.getLevel();
            var rules = level.getGameRules();
            int speed = rules.get(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED);
            rules.set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 1024, level.getServer());
            c.cleanup.add(() -> rules.set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, speed, level.getServer()));
            halfField(h, c, 0.5);
            int[][] spots = {{11, 3}, {13, 3}, {11, 5}, {13, 5}, {12, 4}, {10, 4}, {14, 4}, {12, 2}, {12, 6}};
            List<BlockPos> half = new ArrayList<>(), full = new ArrayList<>(), out = List.of(new BlockPos(15, 1, 0), new BlockPos(15, 1, 7));
            for (int[] s : spots) {
                half.add(new BlockPos(s[0], 1, s[1]));
                full.add(new BlockPos(s[0] - 9, 1, s[1]));
            }
            for (List<BlockPos> l : List.of(half, full, out))
                for (BlockPos b : l) {
                    h.setBlock(b.below(), Blocks.SAND);
                    h.setBlock(b, Blocks.CACTUS);
                }
            int[] sums = new int[3];
            for (int round = 1; round <= 4; round++)
                h.runAfterDelay(24 * round, () -> {
                    sums[0] += cactusTicks(h, full);
                    sums[1] += cactusTicks(h, half);
                    sums[2] += cactusTicks(h, out);
                });
            return () -> {
                double ratio = sums[1] / (double) Math.max(1, sums[0]);
                c.check("full speed: random ticks (~216 expected)", sums[0] > 120, "ticks " + sums[0]);
                // (random ticks are Poisson: ~150 samples a side wander; once 0.72 was seen)
                c.check("0.5 field: about half", ratio > 0.28 && ratio < 0.78, sums[1] + " vs " + sums[0] + String.format(" (%.2f)", ratio));
                c.check("stopped: none", sums[2] == 0, "ticks " + sums[2]);
            };
        }, true, true));
        // overlapping fields: the fastest wins; changing a rate takes effect at once
        CASES.put("rate_overlap_max", new Spec(1, (h, c) -> () -> {
            ServerLevel level = h.getLevel();
            Field a = field(h, c, new Vec3(11.5, 2.5, 4.5), 3.0, 0.3);
            Field b = field(h, c, new Vec3(13.5, 2.5, 4.5), 3.0, 0.7);
            field(h, c, new Vec3(6.5, 2.5, 4.5), 2.0, 0.3);
            c.check("in both: 0.7", Stasis.rateAt(level, h.absolutePos(new BlockPos(12, 1, 4))) == 0.7);
            c.check("only the 0.3 one: 0.3", Stasis.rateAt(level, h.absolutePos(new BlockPos(10, 1, 4))) == 0.3);
            c.check("only the 0.7 one: 0.7", Stasis.rateAt(level, h.absolutePos(new BlockPos(15, 1, 4))) == 0.7);
            c.check("a 0.3 field inside a full-speed one: 1", Stasis.rateAt(level, h.absolutePos(new BlockPos(6, 1, 4))) == 1.0);
            c.check("in neither: stopped", Stasis.rateAt(level, h.absolutePos(new BlockPos(15, 1, 0))) == 0.0);
            FieldData.get(level).update(level, a.id(), f -> f.withRate(0.9));
            c.check("raised the 0.3 field to 0.9: now the fastest", Stasis.rateAt(level, h.absolutePos(new BlockPos(12, 1, 4))) == 0.9);
            c.check("rates clamp into (0, 1]", b.withRate(5).rate() == 1.0 && b.withRate(0).rate() > 0);
        }));
        // spreading mode (Baughn 2026-10-19; Spread): a column the stillness hasn't reached runs at rate 1 with or without
        // a field; a stilled one follows the base rules (stopped, or its field's rate); P moving on changes the answer
        // (the server keeps no P-dependent cache: Stasis.version is the client's); the Nether runs until the spread is
        // complete; sectionState agrees with the blocks
        CASES.put("spread_rates", new Spec(1, (h, c) -> () -> {
            ServerLevel level = h.getLevel();
            BlockPos spawn = h.absolutePos(new BlockPos(3, 1, 4));
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, spawn.getX(), spawn.getZ(), 200);
            // the centre column of the first cell to still (rank 0)
            BlockPos still = null;
            for (int i = 0; i < m.n && still == null; i++)
                for (int k = 0; k < m.n && still == null; k++)
                    if (m.rank(m.cellX0 + i, m.cellZ0 + k) == 0)
                        still = new BlockPos((m.cellX0 + i) * org.erisia.stasis.SpreadMap.CELL + 16, spawn.getY(), (m.cellZ0 + k) * org.erisia.stasis.SpreadMap.CELL + 16);
            if (still == null) {
                c.check("the map has a rank 0 cell", false);
                return;
            }
            BlockPos b = still;
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            int v0 = Stasis.version();
            // (below the split, where every running low cell, spawn's among them, is connected to spawn)
            org.erisia.stasis.Spread.testInstall(m, m.count / 3);
            c.check("installing bumps the version", Stasis.version() != v0);
            c.check("spawn column runs (no field needed)", Stasis.rateAt(level, spawn) == 1.0 && Stasis.rateAt(level, spawn.above(40)) == 1.0);
            c.check("a stilled column is stopped", Stasis.rateAt(level, b) == 0.0 && !Stasis.isRunning(level, b.below(30)));
            c.check("mayRunInColumn: spawn's chunk", Stasis.mayRunInColumn(level, net.minecraft.world.level.ChunkPos.pack(spawn.getX() >> 4, spawn.getZ() >> 4)));
            Field slow = field(h, c, h.relativeVec(Vec3.atCenterOf(spawn)), 3.0, 0.3);
            c.check("a slow field in running land: still 1", Stasis.rateAt(level, spawn) == 1.0, "" + Stasis.rateAt(level, spawn));
            Field f = FieldData.get(level).add(level, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5, 3.0, false, 0.6);
            c.cleanup.add(0, () -> FieldData.get(level).remove(level, f.id()));
            c.check("a field in stilled land runs at its rate", Stasis.rateAt(level, b) == 0.6, "" + Stasis.rateAt(level, b));
            c.check("just outside it: stopped", Stasis.rateAt(level, b.east(5)) == 0.0 || !org.erisia.stasis.SpreadMap.stilled(m, b.getX() + 5, b.getZ(), m.count / 3));
            // sectionState never claims RUNNING or STOPPED for a section with a block that disagrees
            for (BlockPos at : new BlockPos[]{spawn, b}) {
                int sx = at.getX() >> 4, sy = at.getY() >> 4, sz = at.getZ() >> 4;
                int st = Stasis.sectionState(level, sx, sy, sz);
                boolean any = false, all = true;
                for (int x = 0; x < 16; x++)
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++) {
                            boolean r = Stasis.isRunningLand(level, new BlockPos((sx << 4) + x, (sy << 4) + y, (sz << 4) + z));
                            any |= r;
                            all &= r;
                        }
                c.check("sectionState agrees with the blocks at " + at.toShortString(),
                    st == Stasis.SECTION_MIXED || (st == Stasis.SECTION_RUNNING) == all && (st == Stasis.SECTION_STOPPED) == !any, "state " + st + " any " + any + " all " + all);
            }
            var nether = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
            if (nether != null) c.check("the Nether runs until the spread is complete", !Stasis.stops(nether) && Stasis.rateAt(nether, BlockPos.ZERO) == 1.0);
            // P back to 0: nothing inside the map is stilled
            org.erisia.stasis.Spread.testInstall(m, 0);
            c.check("P 0: the stilled column runs again, at 1 (over its field's 0.6)", Stasis.rateAt(level, b) == 1.0);
            // P = N: everything is stilled, spawn too (Baughn: decision 2), fields still work
            org.erisia.stasis.Spread.testInstall(m, m.count);
            c.check("P N: spawn is stilled; its slow field runs it at 0.3", Stasis.rateAt(level, spawn) == 0.3 && Stasis.rateAt(level, spawn.above(40)) == 0.0);
            c.check("outside the map: stilled from the start", Stasis.rateAt(level, spawn.east(500)) == 0.0);
            org.erisia.stasis.Spread.testReset();
            c.check("back in stopped mode: the stilled column is stopped, its field runs", Stasis.rateAt(level, b) == 0.6 && Stasis.rateAt(level, b.east(9)) == 0.0);
            if (nether != null) c.check("back in stopped mode the Nether stops", Stasis.stops(nether));
            c.check("slow field", slow.rate() == 0.3);
        }, false, true));
        // spreading mode, drifting deeps (Baughn 2026-10-19): a drifting deep stops time on running land it passes over
        // where no field holds it (rateAt 0, so a block tick doesn't run); the land's own rate (flames, meshing) stays 1;
        // an unsuited player caught in one is held, not made a statue (Freezing.SPREAD_DEEP_HOLDS)
        CASES.put("spread_deep_stops_running_land", new Spec(1, (h, c) -> () -> {
            ServerLevel level = h.getLevel();
            int old = org.erisia.stasis.Deeps.seed(level);
            org.erisia.stasis.Deeps.testDeeps = true;
            FieldData.get(level).testDeepSeed(777);
            c.cleanup.add(() -> {
                org.erisia.stasis.Deeps.testDeeps = false;
                FieldData.get(level).testDeepSeed(old);
            });
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            var near = org.erisia.stasis.Deeps.near(0, 0, org.erisia.stasis.Deeps.time(level), 777);
            var d = near.stream().filter(x -> x.strength() > 0.9f && x.radius() > 20 && !x.puff()).findFirst();
            if (d.isEmpty()) {
                c.check("found a strong deep", false, near.toString());
                return;
            }
            int bx = (int) Math.floor(d.get().x()), bz = (int) Math.floor(d.get().z());
            for (int cx = -1; cx <= 1; cx++)
                for (int cz = -1; cz <= 1; cz++) level.getChunk((bx >> 4) + cx, (bz >> 4) + cz);
            int surf = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            BlockPos in = new BlockPos(bx, surf + 1, bz);
            // a map round the deep at P 0: every column inside it runs
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(777, bx, bz, 600);
            org.erisia.stasis.Spread.testInstall(m, 0);
            c.check("the deep's centre column is running spread land", Stasis.spreadRuns(level, bx, bz));
            c.check("the deep is there", org.erisia.stasis.Deeps.at(level, in.getX(), in.getY(), in.getZ()) > org.erisia.stasis.Deeps.THRESHOLD,
                "" + org.erisia.stasis.Deeps.at(level, in.getX(), in.getY(), in.getZ()));
            c.check("a drifting deep stops running land", Stasis.rateAt(level, in) == 0.0 && Stasis.rateAtSteady(level, in.getX(), in.getY(), in.getZ()) == 0.0);
            c.check("so a block tick there doesn't run", !Stasis.ticksNow(level, in));
            c.check("the land itself still runs (flame light, meshing)", Stasis.rateAtLand(level, in.getX(), in.getY(), in.getZ()) == 1.0);
            c.check("caves far under it run", Stasis.rateAt(level, in.below(40)) == 1.0 || org.erisia.stasis.StaticDeeps.inRift(level, bx, in.getY() - 40, bz));
            BlockPos far = in.east((int) d.get().radius() + 80);
            if (org.erisia.stasis.Deeps.at(level, far.getX(), far.getY(), far.getZ()) <= 0)
                c.check("outside the deep: running", Stasis.rateAt(level, far) == 1.0);
            Field f = FieldData.get(level).add(level, bx + 0.5, surf + 1.5, bz + 0.5, 4.0, false, 0.5);
            c.cleanup.add(0, () -> FieldData.get(level).remove(level, f.id()));
            c.check("a field holds the deep off: rate 1 (running land)", Stasis.rateAt(level, in) == 1.0, "" + Stasis.rateAt(level, in));
            FieldData.get(level).remove(level, f.id());
            // an unsuited player caught in it is held (one game tick: the deep stands still meanwhile)
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            p.teleportTo(in.getX() + 0.5, in.getY(), in.getZ() + 0.5);
            boolean was = Freezing.SPREAD_DEEP_HOLDS;
            Freezing.SPREAD_DEEP_HOLDS = true;
            for (int i = 0; i < org.erisia.stasis.StasisConfig.freezeTicks() + 50; i++) Freezing.tick(p);
            Freezing.SPREAD_DEEP_HOLDS = was;
            c.check("held by the deep, not a statue", p.isAlive() && Freezing.describe(p).contains("held by a deep"),
                "alive " + p.isAlive() + ", " + Freezing.describe(p));
            remove(h, p);
        }, false, true));
        // spreading mode's front warnings (FrontWarnings; Baughn, spread-DESIGN.md step 3): a player in running land
        // hears on the action bar how long until the stillness reaches this column (Argon: the time until it stills, not
        // the distance), once per threshold crossed, then a countdown under 30 s; nothing inside a field, nothing once
        // stilled (Freezing's messages then). The clock is set so P sits just below the player's column's rank.
        CASES.put("spread_front_warnings", new Spec(1, (h, c) -> () -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            BlockPos at = h.absolutePos(new BlockPos(3, 1, 4));
            var savedSpawn = level.getRespawnData();
            c.cleanup.add(() -> level.setRespawnData(savedSpawn));
            // the spawn area keeps its land running when stilled, so it gets no warnings: put spawn far away
            level.setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData.of(net.minecraft.world.level.Level.OVERWORLD,
                at.offset(100_000, 0, 100_000), 0f, 0f));
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, at.getX(), at.getZ(), 200);
            org.erisia.stasis.Spread.testInstall(m, 0);
            double[] was = org.erisia.stasis.Spread.testClock(server, 0, false, 0);
            c.cleanup.add(0, () -> org.erisia.stasis.Spread.testClockRestore(server, was));
            double tAt = org.erisia.stasis.Spread.testSecondsUntilStill(server, at.getX(), at.getZ());
            boolean hard = Double.isInfinite(tAt);
            if (hard) {   // above the cap: only hard mode stills it
                org.erisia.stasis.Spread.testClock(server, 0, true, 0);
                tAt = org.erisia.stasis.Spread.testSecondsUntilStill(server, at.getX(), at.getZ());
            }
            if (!(tAt > 1200)) {
                c.check("the column stills later than 20 min in", false, "" + tAt);
                return;
            }
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            c.cleanup.add(() -> remove(h, p));
            double t = tAt;
            java.util.function.DoubleFunction<String> before = left -> {
                org.erisia.stasis.Spread.testClock(server, t - left, hard, 0);
                org.erisia.stasis.FrontWarnings.check(p);
                return org.erisia.stasis.FrontWarnings.describe(p);
            };
            c.check("the player's spot runs", Stasis.spreadRuns(level, p.getBlockX(), p.getBlockZ()) && Stasis.isRunning(level, p.blockPosition()));
            c.check("20 min out: nothing", before.apply(1200).equals("none"), before.apply(1200));
            c.check("250 s out: about 5 minutes", before.apply(250).equals("sent 1: The stillness reaches this spot in about 5 minutes"), org.erisia.stasis.FrontWarnings.describe(p));
            c.check("again: no repeat", before.apply(240).startsWith("sent 1:"), org.erisia.stasis.FrontWarnings.describe(p));
            c.check("100 s out: about 2 minutes", before.apply(100).equals("sent 2: The stillness reaches this spot in about 2 minutes"), org.erisia.stasis.FrontWarnings.describe(p));
            c.check("20 s out: the countdown", before.apply(20).equals("sent 3: The stillness reaches this spot in 20 s"), org.erisia.stasis.FrontWarnings.describe(p));
            c.check("each second", before.apply(19).equals("sent 4: The stillness reaches this spot in 19 s"), org.erisia.stasis.FrontWarnings.describe(p));
            // back to safer ground (here: the clock back) forgets the marks, so a later approach warns again
            before.apply(1000);
            c.check("approaching again warns again", before.apply(250).equals("sent 5: The stillness reaches this spot in about 5 minutes"), org.erisia.stasis.FrontWarnings.describe(p));
            // a field holds the spot: it keeps running, so no warning
            Field f = FieldData.get(level).add(level, p.getX(), p.getY() + 1, p.getZ(), 3.0, false, 0.6);
            c.cleanup.add(0, () -> FieldData.get(level).remove(level, f.id()));
            c.check("inside a field: no countdown", before.apply(20).startsWith("sent 5:"), org.erisia.stasis.FrontWarnings.describe(p));
            FieldData.get(level).remove(level, f.id());
            // stilled: Freezing's messages own the action bar
            c.check("once stilled: nothing (Freezing's turn)", before.apply(-5).equals("none") && !Stasis.spreadRuns(level, p.getBlockX(), p.getBlockZ()),
                org.erisia.stasis.FrontWarnings.describe(p));
        }, false, true));
        // spreading mode's hard mode (step 4a; Baughn's brief: the dragon's death starts it): the one entry point (the
        // dragon's death through EnderDragonFightMixin, and the command) sets it once; the coverage goes on from where it
        // stood, P doesn't jump; storms are off before and scheduled from the instant after (tau = t up to it); the End
        // keeps running until the flash, when its players are sent home with the advancement; a death in the End meanwhile
        // is a teleport instead; then the End is stilled and the way in is closed. Players stay beyond 192 blocks of the
        // island's centre so the test End's dragon fight doesn't wake.
        CASES.put("spread_hard_mode_entry", new Spec((int) org.erisia.stasis.HardMode.flashTicks() + 30, (h, c) -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            ServerLevel ow = server.overworld(), end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null) {
                c.check("the test server has the End", false);
                return () -> { };
            }
            BlockPos at = h.absolutePos(new BlockPos(3, 1, 4));
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, at.getX(), at.getZ(), 400);
            org.erisia.stasis.Spread.testInstall(m, 0);
            // a fifth of spreadHours online: coverage a fifth of the cap, well short of it
            double secs = 0.2 * org.erisia.stasis.StasisConfig.spreadHoursForTests() * 3600;
            double[] was = org.erisia.stasis.Spread.testClock(server, secs, false, 0);
            var before = org.erisia.stasis.Spread.testServerState();
            List<ServerPlayer> ps = new ArrayList<>();
            c.cleanup.add(() -> ps.forEach(p -> remove(h, p)));
            c.cleanup.add(org.erisia.stasis.HardMode::testReset);
            c.cleanup.add(() -> org.erisia.stasis.Spread.testClockRestore(server, was));
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            c.check("before: not hard, storms off (no origin)", !org.erisia.stasis.Spread.hard(server) && Double.isNaN(org.erisia.stasis.Storms.origin(ow))
                && org.erisia.stasis.Storms.intensity(12345, null, org.erisia.stasis.Storms.origin(ow), 1e6) == 0);
            c.check("before: the End runs", !Stasis.stops(end));
            ServerPlayer stays = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true), dies = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            ps.add(stays);
            ps.add(dies);
            for (ServerPlayer p : ps)
                p.teleport(new net.minecraft.world.level.portal.TeleportTransition(end, new Vec3(300.5, 4, 0.5 + 10 * ps.indexOf(p)), Vec3.ZERO, 0, 0,
                    net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            c.check("both players are in the End", ps.stream().allMatch(p -> p.level() == end));
            double now = ow.getGameTime() / 20.0;
            // (the End rift's work would rebuild the test End for minutes: off here, EndRift has its own tests)
            org.erisia.stasis.EndRift.testOff = true;
            c.cleanup.add(() -> org.erisia.stasis.EndRift.testOff = false);
            // a dragon still alive at the command's entry (AdventurAgent): it goes at the flash, with no XP and the fight ended
            var dragon = net.minecraft.world.entity.EntityTypes.ENDER_DRAGON.create(end, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            if (dragon != null) {
                // (above the players, its chunk held loaded: a dragon added to an unloaded chunk isn't in the level's lookups)
                if (end.setChunkForced(300 >> 4, 0, true)) c.cleanup.add(() -> end.setChunkForced(300 >> 4, 0, false));
                dragon.setPos(300.5, 30, 5.5);
                dragon.setNoAi(true);   // (it would fly about the test End for the 6 s)
                boolean added = end.addFreshEntity(dragon);
                c.cleanup.add(dragon::discard);
                c.check("a dragon is alive in the End at the entry", added && !dragon.isRemoved());
            }
            boolean first = org.erisia.stasis.HardMode.enter(server, "test"), again = org.erisia.stasis.HardMode.enter(server, "test again");
            c.check("the entry point enters once", first && !again && org.erisia.stasis.Spread.hard(server), first + " " + again);
            var after = org.erisia.stasis.Spread.testServerState();
            c.check("P doesn't jump at the instant", after.p() == before.p() && after.hard(), before.p() + " -> " + after.p());
            c.check("status: from the coverage of the instant", org.erisia.stasis.Spread.status(server, null).contains("from coverage 0.") 
                && !org.erisia.stasis.Spread.status(server, null).contains("from coverage 0.8"), org.erisia.stasis.Spread.status(server, null));
            double o = org.erisia.stasis.Storms.origin(ow);
            c.check("storms: the origin is the instant", Math.abs(o - now) < 0.051, o + " vs " + now);
            c.check("tau is the real clock up to the origin and on from it", org.erisia.stasis.Storms.tau(ow, o - 100) == o - 100
                && Math.abs(org.erisia.stasis.Storms.tau(ow, o) - o) < 1e-9);
            var next = org.erisia.stasis.Storms.next(12345, o, o);
            c.check("after: storms are scheduled from the origin", next != null && next.start() >= o
                && org.erisia.stasis.Storms.intensity(12345, null, o, next.start() + next.length() / 2) > 0.99, String.valueOf(next));
            c.check("the End keeps running until the flash", !Stasis.stops(end) && Boolean.FALSE.equals(org.erisia.stasis.HardMode.testFlashed()));
            // the finale hurts nobody (AdventurAgent took 10.5 before the flash); /kill still works
            var ds = end.damageSources();
            c.check("until the flash no damage in the End", org.erisia.stasis.HardMode.shields(stays, ds.explosion(null, null))
                && org.erisia.stasis.HardMode.shields(stays, ds.fall()) && org.erisia.stasis.HardMode.shields(stays, ds.generic())
                && !org.erisia.stasis.HardMode.shields(stays, ds.genericKill()));
            // a death in the End before the flash: sent home instead
            // (die directly: a mock player has no loaded client, so ServerPlayer.isInvulnerableTo turns all damage away)
            dies.setHealth(0);
            dies.die(end.damageSources().fellOutOfWorld());
            boolean[] diedOk = {dies.isAlive() && dies.level().dimension() == net.minecraft.world.level.Level.OVERWORLD && hasBreakingIt(dies)};
            boolean[] stillThere = {stays.level() == end};
            boolean[] flashed = new boolean[1], endStopped = new boolean[1], blocked = new boolean[1], shieldAfter = new boolean[1],
                dragonGone = new boolean[1], fightOver = new boolean[1], fightSeen = new boolean[1];
            String[] dragonInfo = {""};
            h.runAfterDelay(org.erisia.stasis.HardMode.flashTicks() + 5, () -> {
                flashed[0] = Boolean.TRUE.equals(org.erisia.stasis.HardMode.testFlashed());
                endStopped[0] = Stasis.stops(end) && org.erisia.stasis.Spread.testServerState().endStill();
                shieldAfter[0] = org.erisia.stasis.HardMode.shields(stays, end.damageSources().generic());
                int dragons = end.getEntities(net.minecraft.world.entity.EntityTypes.ENDER_DRAGON, x -> true).size();
                var orbs = end.getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, new net.minecraft.world.phys.AABB(-400, -64, -400, 400, 320, 400));
                dragonGone[0] = dragon != null && dragon.isRemoved() && dragons == 0 && orbs.isEmpty();
                dragonInfo[0] = dragons + " dragons, " + orbs.size() + " orbs" + (orbs.isEmpty() ? "" : " at " + orbs.get(0).position());
                var fight = end.getDragonFight();
                fightOver[0] = fight == null || fight.hasPreviouslyKilledDragon() && fight.dragonKilled && !fight.dragonEvent.isVisible();
                fightSeen[0] = fight != null;
                // the way back is closed for survival players
                var into = new net.minecraft.world.level.portal.TeleportTransition(end, new Vec3(300.5, 4, 0.5), Vec3.ZERO, 0, 0,
                    net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
                blocked[0] = stays.teleport(into) == null && stays.level() != end;
            });
            return () -> {
                c.check("a death in the End meanwhile: alive, at home, with the advancement", diedOk[0],
                    "alive " + dies.isAlive() + " in " + dies.level().dimension().identifier());
                c.check("the other stayed until the flash", stillThere[0]);
                c.check("the flash: sent home with the advancement", stays.isAlive() && stays.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                    && hasBreakingIt(stays), "in " + stays.level().dimension().identifier() + ", advancement " + hasBreakingIt(stays));
                c.check("the flash came, then the End stilled", flashed[0] && endStopped[0], flashed[0] + " " + endStopped[0]);
                c.check("after hard mode the End can't be entered", blocked[0]);
                c.check("after the flash damage counts again", !shieldAfter[0]);
                c.check("the flash removes a dragon still alive, no XP", dragonGone[0], dragon == null ? "no dragon made" : "removed " + dragon.isRemoved() + ", " + dragonInfo[0]);
                c.check("and ends the fight as a kill would (killed, no boss bar)", fightOver[0], fightSeen[0] ? "the test End's fight" : "no fight in the test End");
            };
        }, false, true));
        // the End rift's pieces (EndRift, step 5), in the test area: a pillar sector's column loses only its end stone and a
        // core column also the pillars' obsidian (a player's chest stays, floating); a shard flies and lands its block in air
        // only, then goes; the saved state round-trips with defaults for an older save
        CASES.put("end_rift_lift_and_shards", new Spec(org.erisia.stasis.EndRift.FLIGHT_MAX + 10, (h, c) -> {
            ServerLevel level = h.getLevel();
            c.check("the saved state's codec round-trips, with defaults for an empty tag", org.erisia.stasis.EndRift.testCodecRoundTrip());
            BlockPos a = h.absolutePos(new BlockPos(2, 1, 2));
            net.minecraft.world.level.block.Block[] col = {Blocks.END_STONE, Blocks.COBBLESTONE, Blocks.END_STONE, Blocks.OBSIDIAN, Blocks.END_STONE};
            for (int i = 0; i < col.length; i++) level.setBlock(a.above(i), col[i].defaultBlockState(), 2);
            int n = org.erisia.stasis.EndRift.testLiftColumn(level, a.getX(), a.getZ(), false, a.getY());
            c.check("a sector's column: only the end stone goes, the rest floats", n == 3 && level.getBlockState(a).isAir() && level.getBlockState(a.above(1)).is(Blocks.COBBLESTONE)
                && level.getBlockState(a.above(2)).isAir() && level.getBlockState(a.above(3)).is(Blocks.OBSIDIAN) && level.getBlockState(a.above(4)).isAir(), "removed " + n);
            BlockPos b = h.absolutePos(new BlockPos(4, 1, 2));
            net.minecraft.world.level.block.Block[] core = {Blocks.END_STONE, Blocks.OBSIDIAN, Blocks.CHEST, Blocks.END_STONE};
            for (int i = 0; i < core.length; i++) level.setBlock(b.above(i), core[i].defaultBlockState(), 2);
            n = org.erisia.stasis.EndRift.testLiftColumn(level, b.getX(), b.getZ(), true, b.getY());
            c.check("a core column: the obsidian goes too, a chest stays", n == 3 && level.getBlockState(b.above(1)).isAir() && level.getBlockState(b.above(2)).is(Blocks.CHEST),
                "removed " + n);
            BlockPos from = h.absolutePos(new BlockPos(2, 3, 6)), to = h.absolutePos(new BlockPos(10, 4, 6)), busy = h.absolutePos(new BlockPos(12, 2, 6));
            level.setBlock(busy, Blocks.COBBLESTONE.defaultBlockState(), 2);
            int before = org.erisia.stasis.EndRift.testShards();
            org.erisia.stasis.EndRift.testShard(level, from, to, Blocks.END_STONE.defaultBlockState());
            org.erisia.stasis.EndRift.testShard(level, from.east(), busy, Blocks.END_STONE.defaultBlockState());
            net.minecraft.world.phys.AABB area = new net.minecraft.world.phys.AABB(h.absolutePos(BlockPos.ZERO)).inflate(24);
            java.util.function.IntSupplier displays = () -> level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, area,
                x -> x.entityTags().contains("stasisdelvers_end_shard")).size();
            c.check("two shards in the air", org.erisia.stasis.EndRift.testShards() == before + 2 && displays.getAsInt() == 2,
                org.erisia.stasis.EndRift.testShards() + " shards, " + displays.getAsInt() + " displays");
            boolean[] midway = new boolean[1];
            h.runAfterDelay(30, () -> midway[0] = level.getBlockState(to).isAir() && displays.getAsInt() == 2);
            return () -> {
                c.check("midway: still flying, nothing placed", midway[0]);
                c.check("landed: its block is there", level.getBlockState(to).is(Blocks.END_STONE), level.getBlockState(to).toString());
                c.check("into air only: the block in the way stays", level.getBlockState(busy).is(Blocks.COBBLESTONE));
                c.check("the shards are gone", displays.getAsInt() == 0 && org.erisia.stasis.EndRift.testShards() == before,
                    displays.getAsInt() + " displays, " + org.erisia.stasis.EndRift.testShards() + " shards");
            };
        }));
        // the End's stages (EndRift; Baughn: each pillar crystal broken lifts part of the island): in the test End, a pillar's
        // own crystal destroyed records its pillar once; a player's crystal (no bottom) on a pillar and a crystal away from
        // the pillars don't count; the command's path records another; the lifts are queued (no block is written: paused)
        CASES.put("end_rift_crystal_stages", new Spec(12, (h, c) -> {
            var server = h.getLevel().getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null) {
                c.check("the test server has the End", false);
                return () -> { };
            }
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, 0, 0, 200);
            org.erisia.stasis.Spread.testInstall(m, 0);
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            org.erisia.stasis.EndRift.testPaused = true;
            org.erisia.stasis.EndRift.testReset(end);
            c.cleanup.add(() -> {
                org.erisia.stasis.EndRift.testReset(end);
                org.erisia.stasis.EndRift.testPaused = false;
            });
            var spikes = net.minecraft.world.level.levelgen.feature.EndSpikeFeature.getSpikesForLevel(end);
            c.check("the End has its ten pillars", spikes.size() == 10, String.valueOf(spikes.size()));
            if (spikes.size() < 3) return () -> { };
            var s0 = spikes.get(0);
            var s1 = spikes.get(1);
            List<long[]> forced = new ArrayList<>();
            for (int[] k : new int[][]{{s0.getCenterX() >> 4, s0.getCenterZ() >> 4}, {s1.getCenterX() >> 4, s1.getCenterZ() >> 4}, {0, 0}}) {
                end.getChunk(k[0], k[1]);
                if (end.setChunkForced(k[0], k[1], true)) forced.add(new long[]{k[0], k[1]});
            }
            c.cleanup.add(() -> forced.forEach(k -> end.setChunkForced((int) k[0], (int) k[1], false)));
            int[] after = new int[5];
            boolean[] cmd = new boolean[2];
            String[] jobs = {""};
            h.runAfterDelay(5, () -> {
                java.util.function.Function<double[], net.minecraft.world.entity.boss.enderdragon.EndCrystal> crystal = at -> {
                    var k = new net.minecraft.world.entity.boss.enderdragon.EndCrystal(end, at[0], at[1], at[2]);
                    end.addFreshEntity(k);
                    return k;
                };
                crystal.apply(new double[]{s0.getCenterX() + 0.5, s0.getHeight() + 1, s0.getCenterZ() + 0.5}).discard();
                after[0] = org.erisia.stasis.EndRift.testBroken(end);
                crystal.apply(new double[]{s0.getCenterX() + 0.5, s0.getHeight() + 1, s0.getCenterZ() + 0.5}).kill(end);
                after[1] = org.erisia.stasis.EndRift.testBroken(end);
                var mine = crystal.apply(new double[]{s1.getCenterX() + 0.5, s1.getHeight() + 1, s1.getCenterZ() + 0.5});
                mine.setShowBottom(false);
                mine.discard();
                after[2] = org.erisia.stasis.EndRift.testBroken(end);
                crystal.apply(new double[]{0.5, 90, 0.5}).discard();
                after[3] = org.erisia.stasis.EndRift.testBroken(end);
                cmd[0] = org.erisia.stasis.EndRift.crystalBroken(end, 2);
                cmd[1] = org.erisia.stasis.EndRift.crystalBroken(end, 2);
                after[4] = org.erisia.stasis.EndRift.testBroken(end);
                jobs[0] = org.erisia.stasis.EndRift.testJobs(end);
            });
            return () -> {
                c.check("a pillar's crystal destroyed: its pillar is recorded", after[0] == 1, Integer.toBinaryString(after[0]));
                c.check("again on the same pillar: once only", after[1] == 1, Integer.toBinaryString(after[1]));
                c.check("a player's crystal on a pillar doesn't count", after[2] == 1, Integer.toBinaryString(after[2]));
                c.check("a crystal away from the pillars doesn't count", after[3] == 1, Integer.toBinaryString(after[3]));
                c.check("the command's path records another, once", cmd[0] && !cmd[1] && after[4] == 0b101, cmd[0] + " " + cmd[1] + " " + Integer.toBinaryString(after[4]));
                c.check("their lifts are queued", jobs[0].contains("part 0 lift") && jobs[0].contains("part 2 lift"), jobs[0]);
            };
        }, false, true));
        // two of the End's stages carried out whole in the test End with nobody there (EndRift: the build's pace, its own
        // chunk tickets): a restart midway (the memory dropped) resumes from the saved state; then each sector's columns hold
        // no end stone, its islands stand, the core is untouched and no chunk stays forced
        CASES.put("end_rift_stage_builds", new Spec(1500, (h, c) -> {
            var server = h.getLevel().getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null) {
                c.check("the test server has the End", false);
                return () -> { };
            }
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, 0, 0, 200);
            org.erisia.stasis.Spread.testInstall(m, 0);
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            org.erisia.stasis.EndRift.testReset(end);
            c.cleanup.add(() -> org.erisia.stasis.EndRift.testReset(end));
            var pl = org.erisia.stasis.EndRift.testPlan(end);
            // (the islands may hang over their own sector's columns: their blocks aren't the island's)
            java.util.Set<Long> islands = new java.util.HashSet<>();
            for (int p = 0; p < pl.parts; p++) for (long b : pl.blocks(p)) islands.add(b);
            java.util.function.BiFunction<Integer, Integer, Integer> stone = (x, z) -> {
                int n = 0;
                for (int y = end.getMinY(); y < 128; y++)
                    if (end.getBlockState(new BlockPos(x, y, z)).is(Blocks.END_STONE) && !islands.contains(BlockPos.asLong(x, y, z))) n++;
                return n;
            };
            c.cleanup.add(() -> restoreFlatEnd(end, islands));
            int coreBefore = stone.apply(20, 12);
            long[] cols3 = pl.columns(3);
            int sectorBefore = 0;
            for (int i = 0; i < cols3.length; i += 97) sectorBefore += stone.apply(BlockPos.getX(cols3[i]), BlockPos.getZ(cols3[i]));
            c.check("before: the core and the sector have end stone", coreBefore > 0 && sectorBefore > 0, coreBefore + " " + sectorBefore);
            // slowly at first, so the restart finds them midway
            org.erisia.stasis.EndRift.testWrites = 200;
            c.cleanup.add(() -> org.erisia.stasis.EndRift.testWrites = 0);
            boolean ok = org.erisia.stasis.EndRift.crystalBroken(end, 3) && org.erisia.stasis.EndRift.crystalBroken(end, 5);
            c.check("two stages begin", ok);
            int[] mid = new int[4];
            h.runAfterDelay(25, () -> {
                int[] st = org.erisia.stasis.EndRift.testState(end);
                mid[0] = st[1];
                mid[1] = st[4];
                org.erisia.stasis.EndRift.testRestart(end);
                org.erisia.stasis.EndRift.testWrites = 0;
            });
            return () -> {
                int[] st = org.erisia.stasis.EndRift.testState(end);
                c.check("a restart came midway, with some chunks done and saved", mid[0] != 0b101000 && mid[1] > 0,
                    Integer.toBinaryString(mid[0]) + ", " + mid[1] + " chunks");
                c.check("both lifted and placed", (st[1] & 0b101000) == 0b101000 && (st[2] & 0b101000) == 0b101000,
                    Integer.toBinaryString(st[1]) + " " + Integer.toBinaryString(st[2]));
                c.check("no chunk stays forced", st[3] == 0, String.valueOf(st[3]));
                int left = 0;
                for (int p : new int[]{3, 5}) {
                    long[] cs = pl.columns(p);
                    for (int i = 0; i < cs.length; i += 37) left += stone.apply(BlockPos.getX(cs[i]), BlockPos.getZ(cs[i]));
                }
                c.check("the sectors' columns hold no end stone", left == 0, String.valueOf(left));
                int missing = 0, n = 0;
                for (int p : new int[]{3, 5})
                    for (long b : pl.blocks(p)) {
                        n++;
                        if (end.getBlockState(BlockPos.of(b)).isAir()) missing++;
                    }
                c.check("their islands stand", n > 0 && missing == 0, missing + " of " + n + " missing");
                c.check("the core is untouched", stone.apply(20, 12) == coreBefore, coreBefore + " -> " + stone.apply(20, 12));
            };
        }, false, true));
        // the dragon's death in the test End with nobody there (EndRift.finale, as HardMode.explodeEnd calls it): every part
        // is lifted and every island placed; the island's columns hold no end stone (the core no pillar obsidian or fountain
        // bedrock either) but the islands'; the End rift's core is registered; no chunk stays forced
        CASES.put("end_rift_finale_builds", new Spec(3000, (h, c) -> {
            var server = h.getLevel().getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null) {
                c.check("the test server has the End", false);
                return () -> { };
            }
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, 0, 0, 200);
            org.erisia.stasis.Spread.testInstall(m, 0);
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            org.erisia.stasis.EndRift.testReset(end);
            c.cleanup.add(() -> org.erisia.stasis.EndRift.testReset(end));
            var pl = org.erisia.stasis.EndRift.testPlan(end);
            java.util.Set<Long> islands = new java.util.HashSet<>();
            for (int p = 0; p < pl.parts; p++) for (long b : pl.blocks(p)) islands.add(b);
            var spike = net.minecraft.world.level.levelgen.feature.EndSpikeFeature.getSpikesForLevel(end).get(0);
            java.util.function.BiFunction<Integer, Integer, int[]> column = (x, z) -> {
                int[] n = new int[2];
                for (int y = end.getMinY(); y < 128; y++) {
                    var st = end.getBlockState(new BlockPos(x, y, z));
                    if (islands.contains(BlockPos.asLong(x, y, z))) continue;
                    if (st.is(Blocks.END_STONE)) n[0]++;
                    if (st.is(Blocks.OBSIDIAN) || st.is(Blocks.BEDROCK)) n[1]++;
                }
                return n;
            };
            c.cleanup.add(() -> restoreFlatEnd(end, islands));
            // (the test End is flat: bedrock and three end stone; a pillar's stump, the fountain's bedrock and a player's block)
            for (int y = 4; y < 12; y++) end.setBlock(new BlockPos(spike.getCenterX(), y, spike.getCenterZ()), Blocks.OBSIDIAN.defaultBlockState(), 2);
            end.setBlock(new BlockPos(0, 4, 0), Blocks.BEDROCK.defaultBlockState(), 2);
            BlockPos mine = new BlockPos(30, 5, 30);
            end.setBlock(mine, Blocks.COBBLESTONE.defaultBlockState(), 2);
            int[] pillar = column.apply(spike.getCenterX(), spike.getCenterZ()), mid = column.apply(20, 12);
            c.check("before: a pillar and island under it", pillar[1] >= 8 && mid[0] >= 3, pillar[1] + " obsidian, " + mid[0] + " end stone");
            // as after the flash: hard mode (no End event) and the End stilled; the build happens in stopped time
            double[] was = org.erisia.stasis.Spread.testClock(server, 100, true, 50);
            c.cleanup.add(0, () -> org.erisia.stasis.Spread.testClockRestore(server, was));
            c.check("the End is stilled", Stasis.stops(end));
            // a pillar's crystal still standing, and a shard flying in the stilled End
            var standing = new net.minecraft.world.entity.boss.enderdragon.EndCrystal(end, spike.getCenterX() + 0.5, 12, spike.getCenterZ() + 0.5);
            end.addFreshEntity(standing);
            // (beyond the island's columns, so no lift takes its block again)
            BlockPos shardTo = new BlockPos(org.erisia.stasis.EndRiftPlan.ISLAND_RADIUS + 5, 30, 0);
            end.getChunk(shardTo.getX() >> 4, 0);
            if (end.setChunkForced(shardTo.getX() >> 4, 0, true)) c.cleanup.add(() -> end.setChunkForced(shardTo.getX() >> 4, 0, false));
            org.erisia.stasis.EndRift.testShard(end, new BlockPos(org.erisia.stasis.EndRiftPlan.ISLAND_RADIUS + 5, 8, 0), shardTo, Blocks.END_STONE.defaultBlockState());
            org.erisia.stasis.EndRift.testFinale(end);
            return () -> {
                int[] st = org.erisia.stasis.EndRift.testState(end);
                c.check("built: every part lifted and placed", org.erisia.stasis.EndRift.testBuilt(end),
                    Integer.toBinaryString(st[1]) + " " + Integer.toBinaryString(st[2]));
                c.check("no chunk stays forced", st[3] == 0, String.valueOf(st[3]));
                int stoneLeft = 0, pillarsLeft = 0;
                for (int x = -150; x <= 150; x += 9)
                    for (int z = -150; z <= 150; z += 9)
                        if (x * x + z * z <= org.erisia.stasis.EndRiftPlan.ISLAND_RADIUS * org.erisia.stasis.EndRiftPlan.ISLAND_RADIUS)
                            stoneLeft += column.apply(x, z)[0];
                pillarsLeft = column.apply(spike.getCenterX(), spike.getCenterZ())[1] + column.apply(0, 0)[1];
                c.check("the island is gone", stoneLeft == 0, stoneLeft + " end stone left");
                c.check("its pillar and the fountain too", pillarsLeft == 0, pillarsLeft + " obsidian/bedrock left");
                c.check("a player's block stays, floating", end.getBlockState(mine).is(Blocks.COBBLESTONE));
                c.check("the pillar's crystal went with it", standing.isRemoved());
                c.check("a shard flew and landed in the stilled End", end.getBlockState(shardTo).is(Blocks.END_STONE) && org.erisia.stasis.EndRift.testShards() == 0,
                    end.getBlockState(shardTo) + ", " + org.erisia.stasis.EndRift.testShards() + " shards");
                end.setBlock(shardTo, Blocks.AIR.defaultBlockState(), 2);
                int missing = 0;
                for (long b : islands) if (end.getBlockState(BlockPos.of(b)).isAir()) missing++;
                c.check("the rift's islands stand", missing == 0, missing + " of " + islands.size() + " missing");
                int seed = org.erisia.stasis.FieldData.get(end).deepSeed();
                c.check("the End rift's core is registered", org.erisia.stasis.StaticDeeps.hasEndRift(seed)
                    && org.erisia.stasis.StaticDeeps.near(0, 64, 0, 4, seed).stream().anyMatch(org.erisia.stasis.StaticDeeps::isGate));
            };
        }, false, true));
        // spreading mode's End wall (EndBarrier; λ-Nero): a survival player past endBarrierRadius is put back where they
        // last stood on the ground inside (or just inside the wall without one); creative players pass; in the stopped mode
        // there is no wall. The check is driven directly (a mock player gets no tick events), within one tick and beyond
        // 192 blocks of the centre where possible, so the test End's dragon fight doesn't wake.
        CASES.put("spread_end_barrier", new Spec(1, (h, c) -> () -> {
            var server = h.getLevel().getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null) {
                c.check("the test server has the End", false);
                return;
            }
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, 0, 0, 200);
            org.erisia.stasis.Spread.testInstall(m, 0);
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            c.cleanup.add(() -> remove(h, p));
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            int r = org.erisia.stasis.EndBarrier.radius();
            p.teleport(new net.minecraft.world.level.portal.TeleportTransition(end, new Vec3(r + 100.5, 4, 0.5), Vec3.ZERO, 0, 0,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            c.check("in the End, outside the wall", p.level() == end && Math.hypot(p.getX(), p.getZ()) > r);
            boolean pushed = org.erisia.stasis.EndBarrier.check(p);
            double d = Math.hypot(p.getX(), p.getZ());
            c.check("no ground on record: put just inside the wall, on the ground", pushed && d <= r && d > r - 10 && p.getY() >= 4, d + " y " + p.getY());
            // standing on the ground inside: that's where a later crossing goes back to
            p.teleportTo(r - 20.5, 4, 0.5);
            p.setOnGround(true);
            c.check("inside: left alone", !org.erisia.stasis.EndBarrier.check(p) && Math.abs(p.getX() - (r - 20.5)) < 1e-6);
            p.setOnGround(false);
            p.teleportTo(r + 3.5, 30, 0.5);   // (a pearl, flying, a jump off the edge)
            c.check("past the wall: back where they last stood", org.erisia.stasis.EndBarrier.check(p)
                && Math.abs(p.getX() - (r - 20.5)) < 1e-6 && Math.abs(p.getY() - 4) < 1e-6, p.position().toString());
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            p.teleportTo(r + 100.5, 4, 0.5);
            c.check("creative players pass", !org.erisia.stasis.EndBarrier.check(p) && p.getX() > r);
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            org.erisia.stasis.Spread.testReset();
            c.check("the stopped mode has no wall", !org.erisia.stasis.EndBarrier.active(end) && !org.erisia.stasis.EndBarrier.check(p) && p.getX() > r);
        }, false, true));
        // the End's wall stops a player like a world border (EndBarrier.clamp via EndBarrierMixin; AdventurAgent: the old
        // put-back rubber-banded): a survival player walking or sprint-jumping into it ends at it, never past it, and is
        // never put back; one pressing along it slides round it; creative players pass. Within one tick (the dragon fight).
        CASES.put("spread_end_barrier_stops", new Spec(1, (h, c) -> () -> {
            var server = h.getLevel().getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null) {
                c.check("the test server has the End", false);
                return;
            }
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, 0, 0, 200);
            org.erisia.stasis.Spread.testInstall(m, 0);
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            c.cleanup.add(() -> remove(h, p));
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            int r = org.erisia.stasis.EndBarrier.radius();
            p.teleport(new net.minecraft.world.level.portal.TeleportTransition(end, new Vec3(r - 3.5, 4, 0.5), Vec3.ZERO, 0, 0,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            java.util.function.DoubleSupplier d = () -> Math.hypot(p.getX(), p.getZ());
            double wall = r - p.getBbWidth() / 2;
            double max = 0;
            int putBack = 0;
            for (int i = 0; i < 30; i++) {
                p.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.28, 0, 0.02));   // walking
                max = Math.max(max, d.getAsDouble());
                if (org.erisia.stasis.EndBarrier.check(p)) putBack++;
            }
            for (int i = 0; i < 10; i++) {
                p.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.7, 0, -0.1));   // sprint-jumping
                max = Math.max(max, d.getAsDouble());
                if (org.erisia.stasis.EndBarrier.check(p)) putBack++;
            }
            c.check("walking and sprinting into the wall: never past it", max <= wall + 1e-6, max + " > " + wall);
            c.check("stopped at the wall", d.getAsDouble() > wall - 0.05, String.valueOf(d.getAsDouble()));
            c.check("never put back (no teleport)", putBack == 0, String.valueOf(putBack));
            double a0 = Math.atan2(p.getZ(), p.getX());
            for (int i = 0; i < 20; i++) {
                p.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.1, 0, 0.25));   // along it, pressing outward
                max = Math.max(max, d.getAsDouble());
            }
            double moved = (Math.atan2(p.getZ(), p.getX()) - a0) * r;
            c.check("pressing along the wall: slides round it, inside", moved > 3 && max <= wall + 1e-6, moved + " blocks, max " + max);
            // already outside (lag, a pearl): only inward or along, never further out
            p.teleportTo(r + 1.0, 4, 0.5);
            double out = d.getAsDouble();
            p.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.3, 0, 0));
            c.check("outside: no further out", d.getAsDouble() <= out + 1e-6, out + " -> " + d.getAsDouble());
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            p.teleportTo(r - 1.0, 4, 0.5);
            for (int i = 0; i < 10; i++) p.move(net.minecraft.world.entity.MoverType.SELF, new Vec3(0.5, 0, 0));
            c.check("creative players pass", d.getAsDouble() > r + 2, String.valueOf(d.getAsDouble()));
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        }, false, true));
        // a fall into the void during the End event (HardMode.voidRescue; AdventurAgent fell through a crumbling sector and
        // died): not before the first stage; after it a survival player below the floor is put on the core's top by the
        // fountain with slow falling; creative players aren't. In the test End, within one tick (the dragon fight).
        CASES.put("end_event_void_rescue", new Spec(1, (h, c) -> () -> {
            var server = h.getLevel().getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null) {
                c.check("the test server has the End", false);
                return;
            }
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, 0, 0, 200);
            org.erisia.stasis.Spread.testInstall(m, 0);
            org.erisia.stasis.EndRift.testPaused = true;
            org.erisia.stasis.EndRift.testReset(end);
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            c.cleanup.add(() -> remove(h, p));
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            c.cleanup.add(() -> {
                org.erisia.stasis.EndRift.testReset(end);
                org.erisia.stasis.EndRift.testPaused = false;
            });
            p.teleport(new net.minecraft.world.level.portal.TeleportTransition(end, new Vec3(40.5, -5, 30.5), Vec3.ZERO, 0, 0,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            c.check("before any stage: no rescue", !org.erisia.stasis.HardMode.voidRescue(p) && p.getY() < 0, String.valueOf(p.getY()));
            c.check("a stage begins the End event, told to the clients", org.erisia.stasis.EndRift.crystalBroken(end, 1)
                && org.erisia.stasis.HardMode.endEventOn(server) && org.erisia.stasis.Spread.endEventNow());
            p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
            c.check("creative: no rescue", !org.erisia.stasis.HardMode.voidRescue(p));
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            p.fallDistance = 40;
            boolean saved = org.erisia.stasis.HardMode.voidRescue(p);
            double d = Math.hypot(p.getX(), p.getZ());
            c.check("rescued onto the core's top by the fountain", saved && p.getY() >= end.getMinY() + 1 && d < 32
                && end.getBlockState(BlockPos.containing(p.getX(), p.getY() - 1, p.getZ())).isSolid(), p.position() + " " + saved);
            c.check("with slow falling, the fall forgotten", p.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING) && p.fallDistance == 0);
        }, false, true));
        // spreading mode's client sync (SpreadSync): the server sends the rank tiles round an overworld player out to its
        // range, nearest first, and forgets the far ones when the player moves away (a mock player gets no packets, but
        // the server's list of what it sent is what a client would hold)
        CASES.put("spread_tiles_follow_player", new Spec(40, (h, c) -> {
            ServerLevel level = h.getLevel();
            BlockPos spawn = h.absolutePos(new BlockPos(3, 1, 4));
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(11, spawn.getX(), spawn.getZ(), 3000);
            org.erisia.stasis.Spread.testInstall(m, m.count / 3);
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            c.cleanup.add(() -> remove(h, p));
            int range = org.erisia.stasis.SpreadSync.range(level.getServer());
            java.util.function.IntSupplier want = () -> {
                int[] tx = org.erisia.stasis.SpreadTiles.tilesFor(p.getBlockX() - range, p.getBlockX() + range);
                int[] tz = org.erisia.stasis.SpreadTiles.tilesFor(p.getBlockZ() - range, p.getBlockZ() + range);
                return (tx[1] - tx[0] + 1) * (tz[1] - tz[0] + 1);
            };
            int[] seen = new int[3];
            h.runAfterDelay(15, () -> {
                seen[0] = org.erisia.stasis.SpreadSync.tilesSent(p);
                seen[1] = want.getAsInt();
                // (setPos: the server's tile choice reads the position; no chunk loading needed out there)
                p.setPos(p.getX() + 3000, p.getY(), p.getZ());
            });
            return () -> {
                c.check("every tile round the player was sent", seen[0] == seen[1], seen[0] + " of " + seen[1]);
                c.check("after a long move only the new place's tiles are held", org.erisia.stasis.SpreadSync.tilesSent(p) == want.getAsInt(),
                    org.erisia.stasis.SpreadSync.tilesSent(p) + " vs " + want.getAsInt());
            };
        }, false, true));
        // spreading mode's spawn move (Baughn 2026-10-19, SpawnMove): once the stillness reaches the world spawn's column,
        // the world spawn moves to the nearest place of stability >= 90 % that runs, on solid dry ground; once only, so a
        // second crossing (over the new spawn) leaves it where it is
        CASES.put("spread_spawn_moves_once", new Spec(160, (h, c) -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            BlockPos at = h.absolutePos(new BlockPos(3, 1, 4));
            var savedSpawn = level.getRespawnData();
            c.cleanup.add(() -> level.setRespawnData(savedSpawn));
            int[] savedMove = org.erisia.stasis.Spread.Clock.testSpawnMove(server, new int[6]);
            c.cleanup.add(() -> org.erisia.stasis.Spread.Clock.testSpawnMove(server, savedMove));
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            level.setRespawnData(net.minecraft.world.level.storage.LevelData.RespawnData.of(net.minecraft.world.level.Level.OVERWORLD, at, 0f, 0f));
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(31, at.getX(), at.getZ(), 250);
            long sr = m.rank1024(at.getX(), at.getZ());
            int pPast = (int) (sr / org.erisia.stasis.SpreadMap.SCALE) + 1;   // the least P that stills spawn's column
            int min = Math.max((int) Math.ceil(org.erisia.stasis.StabilityCompassItem.TARGET * m.count), pPast + 1);
            org.erisia.stasis.Spread.testInstall(m, pPast - 1);
            org.erisia.stasis.SpawnMove.testArm(true);
            BlockPos[] moved = new BlockPos[1];
            h.runAfterDelay(5, () -> {
                c.check("while spawn's column runs, spawn stays", level.getRespawnData().pos().equals(at), level.getRespawnData().pos().toShortString());
                org.erisia.stasis.Spread.testInstall(m, pPast);
            });
            h.runAfterDelay(110, () -> {
                BlockPos np = level.getRespawnData().pos();
                moved[0] = np;
                c.check("the stilled spawn moved", !np.equals(at), np.toShortString());
                c.check("to stability >= 90 % and above P", m.rank1024(np.getX(), np.getZ()) >= (long) min * org.erisia.stasis.SpreadMap.SCALE,
                    "rank " + m.rank1024(np.getX(), np.getZ()) / org.erisia.stasis.SpreadMap.SCALE + " of " + m.count + ", min " + min);
                c.check("a running column", Stasis.spreadRuns(level, np.getX(), np.getZ()));
                var ground = level.getBlockState(np.below());
                c.check("on solid dry ground with room above", !ground.isAir() && ground.getFluidState().isEmpty() && level.getBlockState(np).isAir()
                    && level.getBlockState(np.above()).isAir(), ground + " / " + level.getBlockState(np));
                // the stillness over the new spawn too: it stays (once only)
                org.erisia.stasis.Spread.testInstall(m, (int) (m.rank1024(np.getX(), np.getZ()) / org.erisia.stasis.SpreadMap.SCALE) + 1);
            });
            return () -> {
                c.check("the second crossing doesn't move it again", moved[0] != null && level.getRespawnData().pos().equals(moved[0]),
                    level.getRespawnData().pos().toShortString());
                c.check("status says it moved", org.erisia.stasis.Spread.status(server, null).contains("spawn moved once"),
                    org.erisia.stasis.Spread.status(server, null));
            };
        }, false, true));
        // spreading mode's strongholds (spread-DESIGN.md step 3; Baughn 2026-10-19): each library gets one surveyor's
        // desk on its floor (and a floor under it where a cave ate the library's), none in stopped mode; the portal room
        // stays a working End portal room (12 frames, the portal only if every frame rolled an eye) and is recorded once
        // in pending_gates with its whole box, though postProcess runs once per chunk it touches (twice here). The pieces
        // are built as worldgen does, chunk by chunk, each straddling four chunks.
        CASES.put("spread_stronghold_rooms", new Spec(5, (h, c) -> {
            ServerLevel level = h.getLevel();
            BlockPos base = h.absolutePos(new BlockPos(0, 20, 0));
            List<net.minecraft.world.level.levelgen.structure.BoundingBox> boxes = new ArrayList<>();
            // a piece's box with its min corner at chunk-relative (8, 8), so it touches four chunks
            java.util.function.BiFunction<net.minecraft.world.level.levelgen.structure.BoundingBox, Integer, net.minecraft.world.level.levelgen.structure.BoundingBox> place = (b, dx) -> {
                int x = ((base.getX() + dx) & ~15) + 8, z = (base.getZ() & ~15) + 8;
                var m = b.moved(x - b.minX(), base.getY() - b.minY(), z - b.minZ());
                boxes.add(m);
                return m;
            };
            java.util.function.BiConsumer<net.minecraft.world.level.levelgen.structure.BoundingBox, net.minecraft.world.level.block.state.BlockState> fill = (b, st) -> {
                for (BlockPos q : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) level.setBlock(q, st, 2 | 16);
            };
            java.util.function.Consumer<net.minecraft.world.level.levelgen.structure.StructurePiece> gen = piece -> {
                var b = piece.getBoundingBox();
                for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++)
                    for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                        var chunkBB = new net.minecraft.world.level.levelgen.structure.BoundingBox(cx << 4, level.getMinY(), cz << 4, (cx << 4) + 15, level.getMaxY(), (cz << 4) + 15);
                        piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(cx * 31L + cz),
                            chunkBB, new net.minecraft.world.level.ChunkPos(cx, cz), b.getCenter());
                    }
            };
            java.util.function.ToIntFunction<net.minecraft.world.level.levelgen.structure.BoundingBox> desks = b -> {
                int n = 0;
                for (BlockPos q : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()))
                    if (level.getBlockState(q).is(org.erisia.stasis.StasisRegistry.MAP_DESK_BLOCK.get())) n++;
                return n;
            };
            var stone = Blocks.STONE.defaultBlockState();
            // stopped mode: a library, no desk
            var b0 = place.apply(net.minecraft.world.level.levelgen.structure.BoundingBox.orientBox(0, 0, 0, -4, -1, 0, 14, 11, 15, net.minecraft.core.Direction.WEST), 0);
            fill.accept(b0, stone);
            gen.accept(new net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.Library(1, net.minecraft.util.RandomSource.create(1), b0, net.minecraft.core.Direction.WEST));
            c.check("stopped mode: no desk in the library", desks.applyAsInt(b0) == 0);
            c.cleanup.add(() -> {
                for (var b : boxes) fill.accept(b.inflatedBy(1), Blocks.AIR.defaultBlockState());
            });
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            org.erisia.stasis.Spread.testInstall(org.erisia.stasis.SpreadMap.build(7, base.getX(), base.getZ(), 200), 0);
            // a tall library in rock, a short one in a cave (air: its floor has holes)
            var b1 = place.apply(net.minecraft.world.level.levelgen.structure.BoundingBox.orientBox(0, 0, 0, -4, -1, 0, 14, 11, 15, net.minecraft.core.Direction.NORTH), 32);
            fill.accept(b1, stone);
            var lib1 = new net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.Library(1, net.minecraft.util.RandomSource.create(2), b1, net.minecraft.core.Direction.NORTH);
            gen.accept(lib1);
            var b2 = place.apply(net.minecraft.world.level.levelgen.structure.BoundingBox.orientBox(0, 0, 0, -4, -1, 0, 14, 6, 15, net.minecraft.core.Direction.EAST), 64);
            var lib2 = new net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.Library(1, net.minecraft.util.RandomSource.create(3), b2, net.minecraft.core.Direction.EAST);
            gen.accept(lib2);
            for (var lib : List.of(lib1, lib2)) {
                var b = lib.getBoundingBox();
                BlockPos d = lib.getWorldPos(6, 1, 13);
                c.check("one desk in the library at " + b, desks.applyAsInt(b) == 1, String.valueOf(desks.applyAsInt(b)));
                c.check("at the back wall, on a floor", level.getBlockState(d).is(org.erisia.stasis.StasisRegistry.MAP_DESK_BLOCK.get())
                    && level.getBlockState(d.below()).isFaceSturdy(level, d.below(), net.minecraft.core.Direction.UP), d.toShortString());
                c.check("the way from the door is clear", level.getBlockState(lib.getWorldPos(6, 1, 12)).isAir() || level.getBlockState(lib.getWorldPos(6, 1, 12)).is(Blocks.COBWEB));
            }
            // the portal room, generated twice over
            var b3 = place.apply(net.minecraft.world.level.levelgen.structure.BoundingBox.orientBox(0, 0, 0, -4, -1, 0, 11, 8, 16, net.minecraft.core.Direction.SOUTH), 96);
            fill.accept(b3, stone);
            var room = new net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.PortalRoom(1, b3, net.minecraft.core.Direction.SOUTH);
            gen.accept(room);
            gen.accept(room);
            BlockPos centre = room.getWorldPos(5, 4, 10).immutable();
            c.cleanup.add(() -> org.erisia.stasis.PendingGates.testForget(level, centre));
            int frames = 0, eyes = 0, portal = 0;
            for (BlockPos q : BlockPos.betweenClosed(b3.minX(), b3.minY(), b3.minZ(), b3.maxX(), b3.maxY(), b3.maxZ())) {
                var st = level.getBlockState(q);
                if (st.is(Blocks.END_PORTAL_FRAME)) {
                    frames++;
                    if (st.getValue(net.minecraft.world.level.block.EndPortalFrameBlock.HAS_EYE)) eyes++;
                }
                if (st.is(Blocks.END_PORTAL)) portal++;
            }
            c.check("the portal room keeps its 12 frames", frames == 12, String.valueOf(frames));
            c.check("and its portal if every eye is in", portal == (eyes == 12 ? 9 : 0), eyes + " eyes, " + portal + " portal");
            c.check("no shards or debris (not a gate room)", level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, AABB.of(b3).inflate(4)).isEmpty());
            return () -> {
                var rooms = org.erisia.stasis.PendingGates.list(level).stream().filter(r -> r.centre().equals(centre)).toList();
                c.check("the portal room is recorded once", rooms.size() == 1, rooms.toString());
                c.check("with the whole piece's box", rooms.size() == 1 && rooms.get(0).box().equals(b3), rooms + " vs " + b3);
                c.check("its centre is over the portal", centre.getY() == b3.minY() + 4, centre.toShortString());
            };
        }, false, true));
        // hard mode's stronghold rewrite (step 4b; Baughn's decision 6, the full gate rift): a portal room recorded before
        // hard mode becomes a gate room (no frames, shards) with its gate rift registered, and the rift's carve hollows
        // its core; the chunks loaded at the instant are written paced, not at once; an EA node and a chest in the way go
        // with their blocks (the node removed, the chest's items not dropped); a running player the new rift stops gets
        // the grace; each chunk is written once, also after the progress was saved and read back and the chunks loaded
        // again. A room generated after the instant gets stopped mode's room at worldgen and isn't recorded.
        CASES.put("spread_hard_mode_gate_rooms", new Spec(500, (h, c) -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            BlockPos base = h.absolutePos(new BlockPos(0, 24, 0));
            org.erisia.stasis.Spread.testInstall(org.erisia.stasis.SpreadMap.build(7, base.getX(), base.getZ(), 400), 0);
            double[] was = org.erisia.stasis.Spread.testClock(server, 0, false, 0);   // coverage 0: everything runs
            c.cleanup.add(org.erisia.stasis.HardMode::testReset);
            c.cleanup.add(() -> org.erisia.stasis.Spread.testClockRestore(server, was));
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            java.util.function.BiConsumer<net.minecraft.world.level.levelgen.structure.BoundingBox, net.minecraft.world.level.block.state.BlockState> fill = (b, st) -> {
                for (BlockPos q : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) level.setBlock(q, st, 2 | 16);
            };
            java.util.function.Consumer<net.minecraft.world.level.levelgen.structure.StructurePiece> gen = piece -> {
                var b = piece.getBoundingBox();
                for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++)
                    for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                        var chunkBB = new net.minecraft.world.level.levelgen.structure.BoundingBox(cx << 4, level.getMinY(), cz << 4, (cx << 4) + 15, level.getMaxY(), (cz << 4) + 15);
                        piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), net.minecraft.util.RandomSource.create(cx * 31L + cz),
                            chunkBB, new net.minecraft.world.level.ChunkPos(cx, cz), b.getCenter());
                    }
            };
            // the room straddles four chunks, in a block of rock big enough for the carve
            int rx = ((base.getX() + 64) & ~15) + 8, rz = (base.getZ() & ~15) + 8;
            var b0 = net.minecraft.world.level.levelgen.structure.BoundingBox.orientBox(0, 0, 0, -4, -1, 0, 11, 8, 16, net.minecraft.core.Direction.SOUTH);
            var box = b0.moved(rx - b0.minX(), base.getY() - b0.minY(), rz - b0.minZ());
            var room = new net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.PortalRoom(1, box, net.minecraft.core.Direction.SOUTH);
            BlockPos centre = room.getWorldPos(5, 4, 10).immutable();
            // rooms recorded before the orientation was kept: it follows from the box and centre, for all four
            for (var dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var ob = net.minecraft.world.level.levelgen.structure.BoundingBox.orientBox(rx, base.getY(), rz, -4, -1, 0, 11, 8, 16, dir);
                var or = new net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.PortalRoom(1, ob, dir);
                c.check("the orientation follows from box and centre: " + dir, org.erisia.stasis.PendingGates.orientationOf(ob, or.getWorldPos(5, 4, 10)) == dir);
            }
            var rock = new net.minecraft.world.level.levelgen.structure.BoundingBox(centre.getX() - 22, centre.getY() - 18, centre.getZ() - 22,
                centre.getX() + 22, centre.getY() + 14, centre.getZ() + 22);
            var deep = org.erisia.stasis.Gates.gateForTest(centre);
            List<Long> chunks = org.erisia.stasis.HardRifts.testChunks(new org.erisia.stasis.PendingGates.Room(box, centre, net.minecraft.core.Direction.SOUTH));
            for (long k : chunks) level.setChunkForced(net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k), true);
            BlockPos[] centre2 = new BlockPos[1];
            var clearBox = rock.inflatedBy(4);
            c.cleanup.add(() -> {
                org.erisia.stasis.PendingGates.testForget(level, centre);
                org.erisia.stasis.Gates.testForget(level, centre);
                if (centre2[0] != null) org.erisia.stasis.Gates.testForget(level, centre2[0]);
                fill.accept(clearBox, Blocks.AIR.defaultBlockState());
                for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, AABB.of(clearBox).inflate(8), e -> !(e instanceof ServerPlayer)))
                    e.discard();
                for (long k : chunks) level.setChunkForced(net.minecraft.world.level.ChunkPos.getX(k), net.minecraft.world.level.ChunkPos.getZ(k), false);
            });
            fill.accept(rock, Blocks.STONE.defaultBlockState());
            gen.accept(room);
            c.check("before: a working portal room (12 frames)", count(level, box, Blocks.END_PORTAL_FRAME) == 12, String.valueOf(count(level, box, Blocks.END_PORTAL_FRAME)));
            // in the rift's hollow: an EA node, a chest with an item, a player (running land: no grace yet)
            // (blocks deep in the hollow, s < 0.22, with air above: the shatter's debris and dripstone stay outside it)
            java.util.function.Function<BlockPos, BlockPos> hollow = not -> {
                for (BlockPos q : BlockPos.betweenClosed(centre.offset(-3, -2, -3), centre.offset(3, 2, 3)))
                    if (org.erisia.stasis.StaticDeeps.shape(deep, q.getX() + 0.5f, q.getY() + 0.5f, q.getZ() + 0.5f) < 0.22f
                        && !q.equals(centre) && level.getBlockState(q).isAir() && level.getBlockState(q.above()).isAir()
                        && (not == null || Math.abs(q.getX() - not.getX()) + Math.abs(q.getZ() - not.getZ()) >= 2)) return q.immutable();
                return null;
            };
            BlockPos ea = hollow.apply(null), chest = ea == null ? null : hollow.apply(ea);
            c.check("the hollow has room for the EA node and the chest", ea != null && chest != null && !ea.equals(chest),
                ea + " " + chest + String.format(" shape at the centre %.3f, %s", org.erisia.stasis.StaticDeeps.shape(deep, centre.getX() + 0.5f, centre.getY() + 0.5f,
                    centre.getZ() + 0.5f), level.getBlockState(centre)));
            if (ea == null || chest == null || ea.equals(chest)) return () -> { };
            level.setBlock(ea.below(), Blocks.STONE.defaultBlockState(), 2);
            eaSix(h, ea.subtract(h.absolutePos(BlockPos.ZERO)), 0 + (3 << 6));   // an Electrical Source
            var eaAt = new mods.eln.misc.Coordonate(ea, level);
            c.check("before: the EA node is there", mods.eln.node.NodeManager.instance.getNodeFromCoordonate(eaAt) != null);
            level.setBlock(chest, Blocks.CHEST.defaultBlockState(), 2);
            if (level.getBlockEntity(chest) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity cb) cb.setItem(0, new ItemStack(Items.DIAMOND, 7));
            Vec3 standAt = Vec3.atBottomCenterOf(centre).subtract(Vec3.atLowerCornerOf(h.absolutePos(BlockPos.ZERO)));
            ServerPlayer p = survivalPlayer(h, standAt, true);
            c.cleanup.add(0, () -> remove(h, p));
            c.check("before: the player runs, no grace", Stasis.isRunning(level, p.blockPosition()) && !org.erisia.stasis.Freezing.describe(p).contains("in grace"),
                org.erisia.stasis.Freezing.describe(p));

            // static deeps on from here (GameTests run without; before, a drifting deep could stop the player's spot)
            org.erisia.stasis.Deeps.testDeeps = true;
            c.cleanup.add(() -> org.erisia.stasis.Deeps.testDeeps = false);
            org.erisia.stasis.EndRift.testOff = true;   // (the End rift's work: not in this test)
            c.cleanup.add(() -> org.erisia.stasis.EndRift.testOff = false);
            boolean entered = org.erisia.stasis.HardMode.enter(server, "test");
            c.check("hard mode begins", entered);
            c.check("the gate rift is registered at once", org.erisia.stasis.Gates.list(level).contains(centre.asLong())
                && org.erisia.stasis.StaticDeeps.near(centre.getX(), centre.getY(), centre.getZ(), 4, org.erisia.stasis.Deeps.seed(level))
                .stream().anyMatch(org.erisia.stasis.StaticDeeps::isGate));
            c.check("its core is a rift now; the player there has the grace", org.erisia.stasis.StaticDeeps.inRift(level, centre.getX(), centre.getY(), centre.getZ())
                && org.erisia.stasis.Freezing.describe(p).contains("in grace"), org.erisia.stasis.Freezing.describe(p));
            var live = org.erisia.stasis.PendingGates.testRoom(level, centre);
            c.check("paced: the loaded chunks are queued, not written at once", live != null && live.carved().isEmpty()
                && org.erisia.stasis.HardRifts.testQueued(level) >= chunks.size() - 2, org.erisia.stasis.HardRifts.testQueued(level) + " of " + chunks.size());
            int[] hollowAir = new int[2];
            boolean[] sentinelKept = new boolean[1];
            BlockPos sentinel = ea.above(2);
            // (the test server ticks unthrottled, and a chunk near the room waits for its entities to load from disk:
            // look each tick until every chunk is done, for at most 420 ticks)
            boolean[] phase2 = new boolean[1];
            Runnable second = () -> {
                var r = org.erisia.stasis.PendingGates.testRoom(level, centre);
                c.check("every chunk is shattered and carved", r != null && r.carved().containsAll(chunks) && r.shattered().containsAll(chunks),
                    r == null ? "no room" : r.carved().size() + "/" + r.shattered().size() + " of " + chunks.size() + ", left: " + chunks.stream()
                        .filter(k -> !r.carved().contains(k)).map(k -> net.minecraft.world.level.ChunkPos.unpack(k) + (level.hasChunk(net.minecraft.world.level.ChunkPos.getX(k),
                            net.minecraft.world.level.ChunkPos.getZ(k)) ? " loaded" : " unloaded") + (level.areEntitiesLoaded(k) ? " entities" : " no entities")
                        + (level.getChunkSource().isPositionTicking(k) ? " ticking" : " not ticking")).toList()
                        + ", queued " + org.erisia.stasis.HardRifts.testQueued(level));
                c.check("the room is shattered: no frames, no portal", count(level, box, Blocks.END_PORTAL_FRAME) == 0 && count(level, box, Blocks.END_PORTAL) == 0);
                c.check("its shards hang there", !level.getEntitiesOfClass(net.minecraft.world.entity.Display.BlockDisplay.class, AABB.of(box).inflate(4),
                    e -> e.entityTags().contains(org.erisia.stasis.GateRoom.DEBRIS)).isEmpty());
                for (BlockPos q : BlockPos.betweenClosed(centre.offset(-6, -6, -6), centre.offset(6, 6, 6)))
                    if (org.erisia.stasis.StaticDeeps.shape(deep, q.getX() + 0.5f, q.getY() + 0.5f, q.getZ() + 0.5f) < 0.30f) {
                        hollowAir[1]++;
                        if (level.getBlockState(q).isAir()) hollowAir[0]++;
                    }
                c.check("the rift's core is carved hollow", hollowAir[1] > 30 && hollowAir[0] >= hollowAir[1] * 0.95, hollowAir[0] + " of " + hollowAir[1]);
                c.check("the EA node went with its block", mods.eln.node.NodeManager.instance.getNodeFromCoordonate(eaAt) == null
                    && !level.getBlockState(ea).is(mods.eln.Eln.sixNodeBlock), level.getBlockState(ea).toString());
                c.check("the chest went, its items not dropped", !level.getBlockState(chest).is(Blocks.CHEST)
                    && level.getEntitiesOfClass(ItemEntity.class, AABB.of(clearBox).inflate(4), e -> e.getItem().is(Items.DIAMOND)).isEmpty());
                // once only: a block put into the hollow stays through a reload of the progress and the chunks loading again
                level.setBlock(sentinel, Blocks.GLASS.defaultBlockState(), 2);
                org.erisia.stasis.PendingGates.testReload(level);
                var copy = org.erisia.stasis.PendingGates.testRoom(level, centre);
                c.check("the progress survives a save and load", copy != null && copy != r && copy.carved().containsAll(chunks)
                    && copy.shattered().containsAll(chunks) && copy.dir() == net.minecraft.core.Direction.SOUTH);
                if (copy != null) org.erisia.stasis.HardRifts.testReload(level, copy);
                // a room generated after the instant: stopped mode's room at worldgen, not recorded
                var b2 = b0.moved(rx - b0.minX(), base.getY() - b0.minY(), rz + 64 - b0.minZ());
                fill.accept(b2, Blocks.STONE.defaultBlockState());
                var room2 = new net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces.PortalRoom(1, b2, net.minecraft.core.Direction.SOUTH);
                centre2[0] = room2.getWorldPos(5, 4, 10).immutable();
                c.cleanup.add(() -> fill.accept(b2.inflatedBy(4), Blocks.AIR.defaultBlockState()));
                c.cleanup.add(() -> {
                    for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, AABB.of(b2).inflate(20), e -> !(e instanceof ServerPlayer)))
                        e.discard();
                });
                gen.accept(room2);
                c.check("after the instant a new room is shattered at worldgen", count(level, b2, Blocks.END_PORTAL_FRAME) == 0
                    && org.erisia.stasis.PendingGates.testRoom(level, centre2[0]) == null);
            };
            for (int t = 1; t <= 420; t++) {
                boolean last = t == 420;
                h.runAfterDelay(t, () -> {
                    if (phase2[0]) return;
                    var r = org.erisia.stasis.PendingGates.testRoom(level, centre);
                    if (!last && (r == null || !r.carved().containsAll(chunks) || !r.shattered().containsAll(chunks))) return;
                    phase2[0] = true;
                    second.run();
                });
            }
            h.runAfterDelay(480, () -> sentinelKept[0] = level.getBlockState(sentinel).is(Blocks.GLASS));
            return () -> {
                c.check("each chunk written once (the block put in the hollow stayed)", sentinelKept[0]);
                c.check("the new room's gate rift is registered", centre2[0] != null && org.erisia.stasis.Gates.list(level).contains(centre2[0].asLong()));
            };
        }, false, true));
        // the stability compass (Baughn 2026-10-19): in spreading mode it points at the nearest cell of stability >= 90 %
        // (rank >= 0.9 N); in stopped mode it spins
        CASES.put("stability_compass_target", new Spec(1, (h, c) -> () -> {
            ServerLevel level = h.getLevel();
            BlockPos at = h.absolutePos(new BlockPos(3, 1, 4));
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(23, at.getX(), at.getZ(), 300);
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            org.erisia.stasis.Spread.testInstall(m, m.count / 4);
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            c.cleanup.add(0, () -> remove(h, p));
            ItemStack compass = new ItemStack(org.erisia.stasis.StasisRegistry.STABILITY_COMPASS.get());
            org.erisia.stasis.StabilityCompassItem.update(compass, level, p);
            var tr = compass.get(net.minecraft.core.component.DataComponents.LODESTONE_TRACKER);
            int min = (int) Math.ceil(org.erisia.stasis.StabilityCompassItem.TARGET * m.count);
            int[] near = org.erisia.stasis.SpreadMap.nearestAtLeast(m, p.getBlockX(), p.getBlockZ(), min, m.n * 2);
            int[] want = near == null ? null : org.erisia.stasis.SpreadMap.climb(m, near[0], near[1]);
            c.check("a target is set", tr != null && tr.target().isPresent(), "" + tr);
            if (tr != null && tr.target().isPresent()) {
                BlockPos t = tr.target().get().pos();
                c.check("the target's stability is >= 90 %", m.rank1024(t.getX(), t.getZ()) >= (long) min * org.erisia.stasis.SpreadMap.SCALE,
                    "rank " + m.rank1024(t.getX(), t.getZ()) / org.erisia.stasis.SpreadMap.SCALE + " of " + m.count);
                c.check("it is the peak of the nearest such island", want != null && t.getX() == want[0] && t.getZ() == want[1], t.toShortString());
                c.check("in the overworld", tr.target().get().dimension() == net.minecraft.world.level.Level.OVERWORLD);
            }
            org.erisia.stasis.Spread.testReset();
            org.erisia.stasis.StabilityCompassItem.update(compass, level, p);
            var spin = compass.get(net.minecraft.core.component.DataComponents.LODESTONE_TRACKER);
            c.check("stopped mode: the needle spins", spin != null && spin.target().isEmpty(), "" + spin);
        }, false, true));
        // spreading mode's first join (Baughn 2026-10-19): exactly one stability compass, no suit, not again on the next
        // login
        CASES.put("stability_compass_first_join", new Spec(1, (h, c) -> () -> {
            BlockPos at = h.absolutePos(new BlockPos(3, 1, 4));
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(29, at.getX(), at.getZ(), 200);
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            org.erisia.stasis.Spread.testInstall(m, 0);
            // (survivalPlayer marks the suit as given: so would a suit come, it would be the starterSuit path's fault)
            ServerPlayer p = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            c.cleanup.add(0, () -> remove(h, p));
            org.erisia.stasis.StarterKit.login(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(p));
            int compasses = 0, other = 0;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                if (st.is(org.erisia.stasis.StasisRegistry.STABILITY_COMPASS.get())) compasses += st.getCount();
                else other++;
            }
            c.check("exactly one stability compass after joining and logging in again", compasses == 1, compasses + " compasses");
            c.check("nothing else", other == 0, other + " other stacks");
        }, false, true));
        // the commands: add with a rate (and column), set a rate, refuse rates out of range
        CASES.put("rate_command", new Spec(1, (h, c) -> () -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            var src = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
            BlockPos p = h.absolutePos(new BlockPos(12, 2, 4));
            String at = p.getX() + " " + p.getY() + " " + p.getZ();
            var before = new java.util.HashSet<Integer>();
            FieldData.get(level).fields().forEach(f -> before.add(f.id()));
            server.getCommands().performPrefixedCommand(src, "stasis field add " + at + " 2 0.25");
            server.getCommands().performPrefixedCommand(src, "stasis field add " + at + " 2 column 0.4");
            server.getCommands().performPrefixedCommand(src, "stasis field add " + at + " 2");
            var added = FieldData.get(level).fields().stream().filter(f -> !before.contains(f.id())).toList();
            for (Field f : added) c.cleanup.add(() -> FieldData.get(level).remove(level, f.id()));
            c.check("three fields added", added.size() == 3, added.toString());
            if (added.size() != 3) return;
            c.check("add ... 0.25: rate 0.25", added.get(0).rate() == 0.25 && !added.get(0).column(), added.get(0).toString());
            c.check("add ... column 0.4: a column at 0.4", added.get(1).rate() == 0.4 && added.get(1).column(), added.get(1).toString());
            c.check("add without a rate: 1", added.get(2).rate() == 1.0, added.get(2).toString());
            int id = added.get(0).id();
            server.getCommands().performPrefixedCommand(src, "stasis field rate " + id + " 0.5");
            c.check("field rate <id> 0.5", FieldData.get(level).get(id).map(Field::rate).orElse(-1.0) == 0.5);
            server.getCommands().performPrefixedCommand(src, "stasis field rate " + id + " 1.5");
            server.getCommands().performPrefixedCommand(src, "stasis field rate " + id + " 0");
            c.check("rates above 1 or at 0 are refused", FieldData.get(level).get(id).map(Field::rate).orElse(-1.0) == 0.5);
        }));
        // players: in a 0.5 field the client is sent 10 TPS, and eating (32 ticks for bread), mining progress
        // (gameTicks) and the attack cooldown count at half speed
        CASES.put("rate_player_eats_slowly", new Spec(80, (h, c) -> {
            halfField(h, c, 0.5);
            ServerPlayer full = survivalPlayer(h, new Vec3(3.5, 1.0, 4.5), true);
            ServerPlayer half = survivalPlayer(h, new Vec3(12.5, 1.0, 4.5), true);
            int[] ticks0 = new int[2];
            for (ServerPlayer p : new ServerPlayer[]{full, half}) {
                p.getFoodData().setFoodLevel(10);
                p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.BREAD));
                p.startUsingItem(net.minecraft.world.InteractionHand.MAIN_HAND);
                // the mock player's connection isn't ticked: drive the connection half of its tick
                h.onEachTick(() -> {
                    if (p.isAlive() && !p.isRemoved()) p.doTick();
                });
            }
            ticks0[0] = gameTicks(full);
            ticks0[1] = gameTicks(half);
            h.runAfterDelay(40, () -> {
                c.check("40 ticks: full speed has eaten", full.getMainHandItem().isEmpty() && full.getFoodData().getFoodLevel() > 10,
                    full.getMainHandItem() + " food " + full.getFoodData().getFoodLevel());
                c.check("40 ticks: in the 0.5 field still eating", half.isUsingItem() && half.getMainHandItem().is(Items.BREAD),
                    half.getMainHandItem() + " remaining " + half.getUseItemRemainingTicks());
                int g1 = gameTicks(full) - ticks0[0], g05 = gameTicks(half) - ticks0[1];
                c.check("mining clock: half speed in the field", Math.abs(g05 * 2 - g1) <= 2, g05 + " vs " + g1);
                c.check("client rate: 10 TPS sent in the field, nothing at full speed",
                    org.erisia.stasis.PlayerRates.sent(half) == 10f && org.erisia.stasis.PlayerRates.sent(full) == 0f,
                    org.erisia.stasis.PlayerRates.sent(half) + " / " + org.erisia.stasis.PlayerRates.sent(full));
            });
            return () -> {
                c.check("80 ticks: the 0.5 field has eaten too", half.getMainHandItem().isEmpty() && half.getFoodData().getFoodLevel() > 10,
                    half.getMainHandItem() + " food " + half.getFoodData().getFoodLevel());
                // leaving the field: back to the server's rate
                Vec3 a = h.absoluteVec(new Vec3(3.5, 1.0, 4.5));
                half.teleportTo(a.x, a.y, a.z);
                org.erisia.stasis.PlayerRates.update(h.getLevel().getServer(), half, false);
                c.check("left the field: rate restored", org.erisia.stasis.PlayerRates.sent(half) == 0f);
                remove(h, full);
                remove(h, half);
            };
        }));
        // MSPT with block entities in fields (logged, not checked): 512 hoppers in chains of four passing cobblestone
        // down, 100 ticks per setup, all setups twice (the first pass warms the JIT; the second is the one to read)
        CASES.put("rate_perf", new Spec(1120, (h, c) -> {
            ServerLevel level = h.getLevel();
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 8; z++)
                    for (int y = 1; y <= 4; y++) {
                        h.setBlock(new BlockPos(x, y, z), Blocks.HOPPER);
                        if (y == 4) ((net.minecraft.world.level.block.entity.HopperBlockEntity) level.getBlockEntity(h.absolutePos(new BlockPos(x, y, z))))
                            .setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                    }
            Vec3 o = h.absoluteVec(new Vec3(8, 2.5, 4));
            List<Field> made = new ArrayList<>();
            Runnable clear = () -> {
                made.forEach(f -> FieldData.get(level).remove(level, f.id()));
                made.clear();
            };
            c.cleanup.add(clear);
            String[] names = {"no field (all stopped)", "one r 20 field over all, rate 1",
                "one r 20 field, rate 0.5", "three overlapping r 20 fields 0.5/0.6/0.7", "three overlapping r 20 fields, rate 1"};
            double[][] setups = {{}, {1}, {0.5}, {0.5, 0.6, 0.7}, {1, 1, 1}};
            StringBuilder log = new StringBuilder();
            for (int i = 0; i < 2 * setups.length; i++) {
                int k = i % setups.length, pass = i / setups.length;
                h.runAfterDelay(10 + 110 * i, () -> {
                    clear.run();
                    for (int j = 0; j < setups[k].length; j++)
                        made.add(FieldData.get(level).add(level, o.x + 3 * j, o.y, o.z, 20, false, setups[k][j]));
                });
                h.runAfterDelay(10 + 110 * i + 105, () -> {
                    long t0 = System.nanoTime();
                    int n = 0;
                    BlockPos base = h.absolutePos(BlockPos.ZERO);
                    for (int rep = 0; rep < 2000; rep++)
                        for (int x = 0; x < 16; x++)
                            for (int z = 0; z < 8; z++) if (Stasis.ticksNow(level, base.offset(x, 1 + (rep & 3), z))) n++;
                    double ns = (System.nanoTime() - t0) / (2000.0 * 128);
                    String l = String.format("pass %d, %s: MSPT %.3f, ticksNow %.1f ns/call", pass + 1, names[k],
                        level.getServer().getAverageTickTimeNanos() / 1e6, ns);
                    StasisDelvers.LOG.info("[rate_perf] {}", l);
                    if (pass == 1) log.append(l).append("; ");
                });
            }
            return () -> c.check("measured", true, log.toString());
        }, false, true));
        // bug reports (client/report/Redactor): no paths, addresses, user names or tokens leave the player's machine,
        // while versions, class names and log times stay readable
        CASES.put("bugreport_redaction", new Spec(1, (h, c) -> () -> {
            var r = new org.erisia.stasis.client.report.Redactor(java.util.List.of("/home/alice/.local/share/PrismLauncher/instances/Incognito/minecraft/.",
                "/mnt/games/Incognito/minecraft", "C:\\Users\\Alice\\curseforge\\minecraft\\Instances\\Incognito"), "play.example.org:25565", "alice");
            java.util.function.BiConsumer<String, String> eq = (in, want) -> {
                String got = r.redact(in);
                c.check("redact: " + in, got.equals(want), "got '" + got + "', want '" + want + "'");
            };
            eq.accept("Loading /home/alice/.local/share/PrismLauncher/instances/Incognito/minecraft/mods/eln.jar",
                "Loading <game>/mods/eln.jar");
            eq.accept("real path /mnt/games/Incognito/minecraft/logs/latest.log", "real path <game>/logs/latest.log");
            eq.accept("C:\\Users\\Alice\\curseforge\\minecraft\\Instances\\Incognito\\config\\x.toml", "<game>\\config\\x.toml");
            eq.accept("url file:///C:/Users/Alice/curseforge/minecraft/Instances/Incognito/mods/a.jar", "url <game>/mods/a.jar");
            eq.accept("at /home/alice/.gradle/caches/foo-1.2.jar", "at <path>/foo-1.2.jar");
            eq.accept("jar:file:/opt/java/lib/rt.jar!/java/lang/Object.class", "<path>/rt.jar!/java/lang/Object.class");
            eq.accept("tmp /tmp/hsperf_x/123 done", "tmp <path> done");
            eq.accept("D:\\Games\\notes.txt here", "<path> here");
            eq.accept("share \\\\nas\\mc\\x", "share <path>");
            eq.accept("Connecting to play.example.org, 25565", "Connecting to <server>, 25565");
            eq.accept("Connecting to play.example.org:25565", "Connecting to <server>");
            eq.accept("from /192.168.1.10:25565 ok", "from /<ip> ok");
            eq.accept("peer 10.0.0.5.", "peer <ip>.");
            eq.accept("ipv6 [2001:db8::1]:25565 and fe80::1%eth0 and 2001:0db8:85a3:0000:0000:8a2e:0370:7334",
                "ipv6 <ip> and <ip>%eth0 and <ip>");
            eq.accept("neoforge-26.3.0.75-beta, version 1.2.3.400, mod v1.2.3.4", "neoforge-26.3.0.75-beta, version 1.2.3.400, mod v1.2.3.4");
            eq.accept("[12:34:56] [Render thread/INFO] [net.minecraft.client.Minecraft/]: Setting user: Bob",
                "[12:34:56] [Render thread/INFO] [net.minecraft.client.Minecraft/]: Setting user: Bob");
            eq.accept("at net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:827)",
                "at net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:827)");
            eq.accept("user Alice and ALICE but not Alicesmith", "user <user> and <user> but not Alicesmith");
            eq.accept("--username Bob --accessToken eyJhbGciOi.xyz --version 26.3", "--username Bob --accessToken <redacted> --version 26.3");
            eq.accept("password=hunter2 token: abc session=s1", "password=<redacted> token: <redacted> session=<redacted>");
            var shortServer = new org.erisia.stasis.client.report.Redactor(java.util.List.of(), "home", null);
            c.check("a short server name only as a whole word", shortServer.redact("welcome home, homeward").equals("welcome <server>, homeward"),
                shortServer.redact("welcome home, homeward"));
            var shortUser = new org.erisia.stasis.client.report.Redactor(java.util.List.of(), null, "bob");
            c.check("a user name under 4 characters stays", shortUser.redact("bob builds").equals("bob builds"));
            c.check("no game dir, no server: plain text unchanged", shortUser.redact("Stasis tower at 12, 64, -30").equals("Stasis tower at 12, 64, -30"));
        }, false));
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

    static java.io.DataInputStream eaStream(EaWriter w) {
        var bos = new java.io.ByteArrayOutputStream();
        try {
            w.write(new java.io.DataOutputStream(bos));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return new java.io.DataInputStream(new java.io.ByteArrayInputStream(bos.toByteArray()));
    }

    /** Per item: [min, max] count in one drop list (0 when absent in a draw is not counted). */
    static void tally(java.util.Map<net.minecraft.world.item.Item, int[]> m, java.util.List<ItemStack> drops) {
        java.util.Map<net.minecraft.world.item.Item, Integer> one = new java.util.HashMap<>();
        for (var d : drops) one.merge(d.getItem(), d.getCount(), Integer::sum);
        one.forEach((i, n) -> {
            int[] r = m.computeIfAbsent(i, k -> new int[]{Integer.MAX_VALUE, 0});
            r[0] = Math.min(r[0], n);
            r[1] = Math.max(r[1], n);
        });
    }

    static String show(java.util.Map<net.minecraft.world.item.Item, int[]> m) {
        var sb = new StringBuilder();
        m.forEach((i, r) -> sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(i)).append(' ').append(r[0]).append("..").append(r[1]).append(' '));
        return sb.toString().trim();
    }

    interface EaWriter {
        void write(java.io.DataOutputStream out) throws java.io.IOException;
    }

    static int countCore(ServerLevel level, BlockPos master) {
        int n = 0;
        for (int dy = 0; dy <= 1; dy++)
            if (level.getBlockState(master.above(dy)).is(org.erisia.stasis.StasisRegistry.TOWER.get())
                && org.erisia.stasis.TowerBlock.formed(level.getBlockState(master.above(dy)))) n++;
        return n;
    }

    // ---- the lance, L1: travel (Lance, docs/lance.md)

    /** A gate rift (r 9) registered at a local position and opened; forgotten with its arrivals afterwards. */
    static org.erisia.stasis.StaticDeeps.Deep lanceGate(GameTestHelper h, Checks c, BlockPos local, List<ServerLevel> dests) {
        ServerLevel level = h.getLevel();
        BlockPos centre = h.absolutePos(local);
        org.erisia.stasis.Gates.testRegister(level, centre);
        var d = org.erisia.stasis.Gates.gateForTest(centre);
        org.erisia.stasis.Lance.setOpen(level, d, true);
        c.cleanup.add(() -> {
            org.erisia.stasis.Gates.testForget(level, centre);
            for (ServerLevel dest : dests) {
                for (var a : org.erisia.stasis.Lance.ArrivalData.get(dest).all())
                    if (a.homeId() == d.id()) for (BlockPos q : org.erisia.stasis.Lance.testPad(a)) dest.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                org.erisia.stasis.Lance.testForget(level, d.id(), dest);
            }
        });
        return d;
    }

    /** Put the player in the disc's centre (feet just under the core) and take one step. */
    static boolean stepIntoCore(ServerPlayer p, ServerLevel level, org.erisia.stasis.StaticDeeps.Deep d) {
        float[] k = org.erisia.stasis.StaticDeeps.core(d);
        if (p.level() != level)
            p.teleport(new net.minecraft.world.level.portal.TeleportTransition(level, new Vec3(k[0], k[1] - 0.9, k[2]), Vec3.ZERO, 0, 0,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
        else p.teleportTo(k[0], k[1] - 0.9, k[2]);
        org.erisia.stasis.Lance.testCooldown(p);
        return org.erisia.stasis.Lance.step(p);
    }

    static {
        // stopped mode: a stronghold gate rift opened by the test command's code; a player at the rift's edge stays, one
        // stepping into the disc's centre arrives on a pad in the End with grace; the pad and its return rift are made
        // once (a second trip only clears the foot pocket); the return rift sends them to the rim of the home rift; with
        // the home gate closed to another open gate leading there, and with none open home regardless (Baughn's Q3 order)
        CASES.put("lance_travel_and_return", new Spec(5, (h, c) -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null || org.erisia.stasis.Spread.status(server, null).startsWith("mode spreading")) {
                c.check("the test server has the End, in stopped mode", false);
                return () -> { };
            }
            var gate = lanceGate(h, c, new BlockPos(8, 2, 4), List.of(end));
            ServerPlayer p = survivalPlayer(h, new Vec3(1.5, 1.0, 1.5), true);
            c.cleanup.add(() -> remove(h, p));
            c.check("a stronghold gate leads to the End near the island", org.erisia.stasis.Lance.kind(false, false, true, "minecraft:the_end")
                == org.erisia.stasis.Lance.Kind.STRONGHOLD_END && org.erisia.stasis.StaticDeeps.isStrongholdGate(gate));
            float[] k = org.erisia.stasis.StaticDeeps.core(gate);
            // the rift's edge is not the disc
            p.teleportTo(k[0] + 3.5, k[1] - 0.9, k[2]);
            org.erisia.stasis.Lance.testCooldown(p);
            c.check("at the rift's edge: no trip", !org.erisia.stasis.Lance.step(p) && p.level() == level);
            p.teleportTo(k[0], k[1] - 0.9, k[2]);
            c.check("the open gate's disc spares the player the rift's statue", org.erisia.stasis.Lance.spares(level, p));
            boolean went = stepIntoCore(p, level, gate);
            var arrivals = org.erisia.stasis.Lance.ArrivalData.get(end).all().stream().filter(a -> a.homeId() == gate.id()).toList();
            c.check("into the disc's centre: in the End", went && p.level() == end, p.level().dimension().toString());
            c.check("one arrival made", arrivals.size() == 1, arrivals.toString());
            if (arrivals.size() != 1) return () -> { };
            var a = arrivals.get(0);
            BlockPos foot = a.footPos();
            c.check("on the pad", p.blockPosition().equals(foot) && end.getBlockState(foot.below()).is(org.erisia.stasis.StasisRegistry.RIFTSTONE.get()),
                p.blockPosition().toShortString() + " vs " + foot.toShortString());
            double fromCentre = Math.hypot(foot.getX(), foot.getZ());
            c.check("150-200 blocks from the island's middle", fromCentre >= 148 && fromCentre <= 202, String.valueOf(fromCentre));
            c.check("with grace on arrival", org.erisia.stasis.Freezing.inGrace(p) && org.erisia.stasis.Freezing.ticks(p) == 0);
            int endSeed = FieldData.get(end).deepSeed();
            BlockPos ret = a.retPos();
            c.check("a return rift beside the pad, registered", org.erisia.stasis.StaticDeeps.near(ret.getX(), ret.getY(), ret.getZ(), 2, endSeed).stream()
                .anyMatch(org.erisia.stasis.StaticDeeps::isReturn) && org.erisia.stasis.Lance.returns(end).contains(ret.asLong()));
            c.check("the cooldown holds the player on the pad", !org.erisia.stasis.Lance.step(p) && p.level() == end);
            // a second trip: no new pad, the foot pocket cleared
            end.setBlock(foot, Blocks.END_STONE.defaultBlockState(), 3);
            end.setBlock(foot.above(), Blocks.END_STONE.defaultBlockState(), 3);
            boolean again = stepIntoCore(p, level, gate);
            c.check("a second trip: same arrival, pocket cleared", again && p.level() == end && p.blockPosition().equals(foot)
                && end.getBlockState(foot).isAir() && end.getBlockState(foot.above()).isAir()
                && org.erisia.stasis.Lance.ArrivalData.get(end).all().stream().filter(x -> x.homeId() == gate.id()).count() == 1);
            // home: into the return rift's core
            int seed = FieldData.get(level).deepSeed();
            java.util.function.BiFunction<org.erisia.stasis.StaticDeeps.Deep, ServerPlayer, String> rimCheck = (d, pl) -> {
                float[] q = org.erisia.stasis.StaticDeeps.core(d);
                double hd = Math.hypot(pl.getX() - q[0], pl.getZ() - q[2]);
                boolean ok = pl.level() == level && hd >= d.radius() * 0.35 && hd <= d.radius() + 14
                    && org.erisia.stasis.StaticDeeps.zone(seed, (float) pl.getX(), (float) pl.getY() + 0.5f, (float) pl.getZ()) != 2;
                return ok ? null : pl.level().dimension() + " " + pl.position() + " " + hd;
            };
            java.util.function.Supplier<Boolean> takeReturn = () -> {
                if (p.level() != end) p.teleport(new net.minecraft.world.level.portal.TeleportTransition(end, Vec3.atBottomCenterOf(foot), Vec3.ZERO, 0, 0,
                    net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
                p.teleportTo(ret.getX() + 0.5, foot.getY(), ret.getZ() + 0.5);
                org.erisia.stasis.Lance.testCooldown(p);
                return org.erisia.stasis.Lance.step(p);
            };
            boolean home = takeReturn.get();
            String bad = rimCheck.apply(gate, p);
            c.check("the return rift: home to the rift's rim", home && bad == null, String.valueOf(bad));
            c.check("home with grace", org.erisia.stasis.Freezing.inGrace(p));
            // the home gate closed, another open one leading to the End (over a platform above the test): there
            BlockPos other = new BlockPos(8, 40, 4);
            for (int dx = -14; dx <= 14; dx++)
                for (int dz = -14; dz <= 14; dz++) h.setBlock(other.offset(dx, -2, dz), Blocks.STONE);
            c.cleanup.add(() -> {
                for (int dx = -14; dx <= 14; dx++)
                    for (int dz = -14; dz <= 14; dz++) h.setBlock(other.offset(dx, -2, dz), Blocks.AIR);
            });
            var gate2 = lanceGate(h, c, other, List.of(end));
            org.erisia.stasis.Lance.setOpen(level, gate, false);
            boolean toOther = takeReturn.get();
            bad = rimCheck.apply(gate2, p);
            c.check("home gate closed: to another open gate's rim", toOther && bad == null, String.valueOf(bad));
            org.erisia.stasis.Lance.setOpen(level, gate2, false);
            boolean regardless = takeReturn.get();
            bad = rimCheck.apply(gate, p);
            c.check("none open: home regardless", regardless && bad == null, String.valueOf(bad));
            // a closed gate takes nobody
            c.check("a closed gate's disc: no trip", !stepIntoCore(p, level, gate) && p.level() == level);
            c.check("a closed gate spares nobody", !org.erisia.stasis.Lance.spares(level, p));
            return () -> { };
        }, false, true));
        // stopped mode, a wild End-bound rift: to the outer End (>= 1024 out, beside an End site's placement), on a pad
        CASES.put("lance_outer_end", new Spec(5, (h, c) -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END);
            if (end == null || org.erisia.stasis.Spread.status(server, null).startsWith("mode spreading")) {
                c.check("the test server has the End, in stopped mode", false);
                return () -> { };
            }
            BlockPos at = h.absolutePos(new BlockPos(3, 1, 4));
            org.erisia.stasis.StaticDeeps.Deep wild = null;
            for (var d : org.erisia.stasis.StaticDeeps.near(at.getX(), at.getY(), at.getZ(), 2048, FieldData.get(level).deepSeed()))
                if (!org.erisia.stasis.StaticDeeps.isGate(d) && org.erisia.stasis.Gates.destination(d).equals("minecraft:the_end")) {
                    wild = d;
                    break;
                }
            if (wild == null) {
                c.check("an End-bound wild rift within 2 km", false);
                return () -> { };
            }
            var d = wild;
            org.erisia.stasis.Lance.setOpen(level, d, true);
            c.cleanup.add(() -> {
                for (var a : org.erisia.stasis.Lance.ArrivalData.get(end).all())
                    if (a.homeId() == d.id()) for (BlockPos q : org.erisia.stasis.Lance.testPad(a)) end.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                org.erisia.stasis.Lance.testForget(level, d.id(), end);
            });
            ServerPlayer p = survivalPlayer(h, new Vec3(1.5, 1.0, 1.5), true);
            c.cleanup.add(() -> remove(h, p));
            boolean went = stepIntoCore(p, level, d);
            var ea = org.erisia.stasis.Lance.ArrivalData.get(end).all().stream().filter(a -> a.homeId() == d.id()).toList();
            c.check("through the gate into the End", went && p.level() == end, p.level().dimension().toString());
            c.check("an outer-End pad >= 1024 out", ea.size() == 1 && Math.hypot(ea.get(0).footPos().getX(), ea.get(0).footPos().getZ()) >= 1024
                && end.getBlockState(ea.get(0).footPos().below()).is(org.erisia.stasis.StasisRegistry.RIFTSTONE.get())
                && p.blockPosition().equals(ea.get(0).footPos()), ea.toString());
            return () -> { };
        }, false, true));
        // spreading mode (Baughn's decisions 5-7): before hard mode a gate leads to the Nether, whatever the rift; after it a
        // stronghold gate leads onto the End rift's islands (the closed End lets the lance through, and only it), the
        // player isn't evicted at login, and the End's wall is down
        CASES.put("lance_spreading_rules", new Spec(5, (h, c) -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            ServerLevel end = server.getLevel(net.minecraft.world.level.Level.END), nether = server.getLevel(net.minecraft.world.level.Level.NETHER);
            if (end == null || nether == null) {
                c.check("the test server has the End and the Nether", false);
                return () -> { };
            }
            BlockPos at = h.absolutePos(new BlockPos(3, 1, 4));
            org.erisia.stasis.SpreadMap m = org.erisia.stasis.SpreadMap.build(7, at.getX(), at.getZ(), 400);
            org.erisia.stasis.Spread.testInstall(m, 0);
            double[] was = org.erisia.stasis.Spread.testClock(server, 60, false, 0);
            org.erisia.stasis.EndRift.testOff = true;   // (hard mode below would start rebuilding the test End)
            c.cleanup.add(() -> org.erisia.stasis.EndRift.testOff = false);
            c.cleanup.add(() -> org.erisia.stasis.Spread.testClockRestore(server, was));
            c.cleanup.add(org.erisia.stasis.Spread::testReset);
            var gate = lanceGate(h, c, new BlockPos(8, 2, 4), List.of(end, nether));
            ServerPlayer p = survivalPlayer(h, new Vec3(1.5, 1.0, 1.5), true), q = survivalPlayer(h, new Vec3(1.5, 1.0, 2.5), true);
            c.cleanup.add(() -> remove(h, p));
            c.cleanup.add(() -> remove(h, q));
            c.check("before hard mode: the wall stands", org.erisia.stasis.EndBarrier.active(end));
            boolean went = stepIntoCore(p, level, gate);
            c.check("before hard mode: a stronghold gate leads to the Nether", went && p.level() == nether, p.level().dimension().toString());
            var na = org.erisia.stasis.Lance.ArrivalData.get(nether).all().stream().filter(a -> a.homeId() == gate.id()).toList();
            c.check("a Nether pad, over the lava sea", na.size() == 1 && na.get(0).footPos().getY() > nether.getSeaLevel()
                && p.blockPosition().equals(na.get(0).footPos()), na.toString());
            org.erisia.stasis.Spread.testClock(server, 60, true, 30);
            c.check("after hard mode: the wall is down", !org.erisia.stasis.EndBarrier.active(end));
            q.teleport(new net.minecraft.world.level.portal.TeleportTransition(end, new Vec3(300.5, 10, 0.5), Vec3.ZERO, 0, 0,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            c.check("a plain teleport into the closed End is turned away", q.level() != end);
            boolean toEnd = stepIntoCore(p, level, gate);
            var ea = org.erisia.stasis.Lance.ArrivalData.get(end).all().stream().filter(a -> a.homeId() == gate.id()).toList();
            c.check("after hard mode: through the lance into the End", toEnd && p.level() == end, p.level().dimension().toString());
            c.check("onto one of the rift's islands", ea.size() == 1 && ea.get(0).central() && ea.get(0).key().startsWith("island:")
                && end.getBlockState(ea.get(0).footPos().below()).is(org.erisia.stasis.StasisRegistry.RIFTSTONE.get()), ea.toString());
            if (ea.size() == 1) {
                var plan = org.erisia.stasis.EndRift.testPlan(end);
                long buried = org.erisia.stasis.Lance.testPad(ea.get(0)).stream()
                    .flatMap(b -> java.util.stream.Stream.of(b.above(), b.above(2), b.above(3))).filter(b -> plan.isIsland(b.asLong())).count();
                c.check("nothing the rift's islands will place lands on the pad or in the return rift", buried == 0, String.valueOf(buried));
            }
            c.check("arrived by lance: login leaves them in the End", org.erisia.stasis.Lance.arrivedByLance(p)
                && !org.erisia.stasis.HardMode.evictsOnLogin(p));
            // the central return rift: back to the gate the player came out of (open)
            if (ea.size() == 1) {
                BlockPos ret = ea.get(0).retPos();
                p.teleportTo(ret.getX() + 0.5, ea.get(0).footPos().getY(), ret.getZ() + 0.5);
                org.erisia.stasis.Lance.testCooldown(p);
                boolean home = org.erisia.stasis.Lance.step(p);
                c.check("the islands' return rift: home to the lance came out of", home && p.level() == level, p.level().dimension().toString());
                c.check("left the End: no longer 'arrived by lance'", !org.erisia.stasis.Lance.arrivedByLance(p));
            }
            return () -> { };
        }, false, true));
    }

    // ---- the lance, L2: the multiblock, power, breach, hold and relax (LanceBlockEntity, Lance; docs/lance.md)

    /** A gate rift (r 9) registered at a local position, not opened; forgotten (with its gate) afterwards. */
    static org.erisia.stasis.StaticDeeps.Deep lanceRift(GameTestHelper h, Checks c, BlockPos local) {
        ServerLevel level = h.getLevel();
        BlockPos centre = h.absolutePos(local);
        org.erisia.stasis.Gates.testRegister(level, centre);
        var d = org.erisia.stasis.Gates.gateForTest(centre);
        c.cleanup.add(() -> {
            org.erisia.stasis.Gates.testForget(level, centre);
            org.erisia.stasis.Lance.testForget(level, d.id(), null);
            org.erisia.stasis.Lance.publishAndSend(level);
        });
        return d;
    }

    /** A lance core at local `core` with a mast of `mast` poles and a boom of `boom` poles off its top towards `dir`. */
    static org.erisia.stasis.LanceBlockEntity lance(GameTestHelper h, Checks c, BlockPos core, int mast, Direction dir, int boom) {
        h.setBlock(core, org.erisia.stasis.StasisRegistry.LANCE.get());
        var pole = org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get().defaultBlockState();
        for (int i = 1; i <= mast; i++) h.setBlock(core.above(i), pole);
        for (int i = 1; i <= boom; i++)
            h.setBlock(core.above(mast).relative(dir, i), pole.setValue(org.erisia.stasis.TowerPoleBlock.AXIS, dir.getAxis()));
        var t = (org.erisia.stasis.LanceBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(core));
        c.cleanup.add(() -> {
            for (int i = 1; i <= boom; i++) h.setBlock(core.above(mast).relative(dir, i), Blocks.AIR);
            for (int i = 1; i <= mast; i++) h.setBlock(core.above(i), Blocks.AIR);
            h.setBlock(core, Blocks.AIR);
        });
        return t;
    }

    static {
        // the multiblock binds to the rift whose eye the boom's tip covers (within 1.5 r, above it); one lance per rift;
        // dismantled, the rift is free for another; the base (where return trips land) is beside the core
        CASES.put("lance_multiblock_binds", new Spec(5, (h, c) -> {
            ServerLevel level = h.getLevel();
            var d = lanceRift(h, c, new BlockPos(13, 2, 4));
            var g0 = (java.util.function.Supplier<org.erisia.stasis.Lance.Gate>) () -> org.erisia.stasis.Lance.GateData.get(level).gate(d.id());
            // a bare core, then a short mast: unbound
            h.setBlock(new BlockPos(1, 1, 4), org.erisia.stasis.StasisRegistry.LANCE.get());
            var t = (org.erisia.stasis.LanceBlockEntity) level.getBlockEntity(h.absolutePos(new BlockPos(1, 1, 4)));
            t.testTick();
            c.check("a bare core: unbound, asks for a mast", t.boundId() == 0 && t.problem().contains("mast"), t.problem());
            h.setBlock(new BlockPos(1, 1, 4), Blocks.AIR);
            // a boom pointing away: its tip is over no eye
            var away = lance(h, c, new BlockPos(1, 1, 4), 3, Direction.WEST, 6);
            away.testTick();
            c.check("a boom away from the rift: unbound", away.boundId() == 0 && away.problem().contains("no rift"), away.problem());
            for (int i = 1; i <= 6; i++) h.setBlock(new BlockPos(1 - i, 4, 4), Blocks.AIR);
            // the boom out over the eye: bound
            var pole = org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get().defaultBlockState().setValue(org.erisia.stasis.TowerPoleBlock.AXIS, Direction.Axis.X);
            for (int i = 1; i <= 12; i++) h.setBlock(new BlockPos(1 + i, 4, 4), pole);
            c.cleanup.add(() -> {
                for (int i = 1; i <= 12; i++) h.setBlock(new BlockPos(1 + i, 4, 4), Blocks.AIR);
            });
            away.testTick();
            BlockPos core = h.absolutePos(new BlockPos(1, 1, 4));
            c.check("the boom's tip over the eye: bound to that rift", away.boundId() == d.id() && g0.get() != null && g0.get().lance() == core.asLong(),
                away.problem() + " " + g0.get());
            c.check("the tip is the boom's last pole", h.absolutePos(new BlockPos(13, 4, 4)).equals(away.tip()), String.valueOf(away.tip()));
            BlockPos base = org.erisia.stasis.Lance.basePosition(level, g0.get());
            c.check("home is the lance's base: beside the core", Math.abs(base.getX() - core.getX()) <= 1 && Math.abs(base.getZ() - core.getZ()) <= 1
                && Math.abs(base.getY() - core.getY()) <= 1, base.toShortString() + " core " + core.toShortString());
            // a second lance over the same rift: refused
            var second = lance(h, c, new BlockPos(1, 1, 1), 3, Direction.EAST, 12);
            second.testTick();
            c.check("one lance per rift: the second is refused", second.boundId() == 0 && second.problem().contains("already"), second.problem());
            // two booms: not a lance
            h.setBlock(new BlockPos(1, 4, 5), org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get().defaultBlockState()
                .setValue(org.erisia.stasis.TowerPoleBlock.AXIS, Direction.Axis.Z));
            away.testTick();
            c.check("two booms: unbound, the rift let go", away.boundId() == 0 && away.problem().contains("one boom") && !g0.get().hasLance()
                && g0.get().dismantled() >= 0, away.problem());
            h.setBlock(new BlockPos(1, 4, 5), Blocks.AIR);
            // the first lance dismantled: the second takes the rift
            h.setBlock(new BlockPos(1, 1, 4), Blocks.AIR);
            second.testTick();
            c.check("the rift free: the second lance binds", second.boundId() == d.id() && g0.get().lance() == h.absolutePos(new BlockPos(1, 1, 1)).asLong(),
                second.problem());
            return () -> { };
        }, false, true));

        // powered with a fitting focus: the rift steps down a step at a time to a quarter (zones and the client's strength
        // follow), latches OPEN, holds on the hold draw; a storm over it stalls the breach at twice the draw; without power
        // the klaxon's grace, then it unlatches and relaxes back a step at a time (Baughn, LANCE-v2 decisions)
        CASES.put("lance_breach_hold_relax", new Spec(40, (h, c) -> {
            ServerLevel level = h.getLevel();
            var server = level.getServer();
            if (org.erisia.stasis.Spread.status(server, null).startsWith("mode spreading")) {
                c.check("the test server is in stopped mode", false);
                return () -> { };
            }
            var d = lanceRift(h, c, new BlockPos(13, 2, 4));
            var t = lance(h, c, new BlockPos(1, 1, 4), 3, Direction.EAST, 12);
            int seed = FieldData.get(level).deepSeed();
            java.util.function.Supplier<org.erisia.stasis.Lance.Gate> gs = () -> org.erisia.stasis.Lance.GateData.get(level).gate(d.id());
            t.testSpeed = 100;   // r 9: a step in 3 ticks, the grace 6, a relax step 6
            t.testSupplyW = 1e9;
            t.testStorm = false;
            t.testTick();
            c.check("bound", t.boundId() == d.id(), t.problem());
            // a probe inside the whole rift but outside its quarter
            float[] k = org.erisia.stasis.StaticDeeps.core(d);
            float[] probe = null;
            for (float dx = 0; dx < 4 && probe == null; dx += 0.25f) {
                float s = org.erisia.stasis.StaticDeeps.shape(d, k[0] + dx, k[1], k[2]);
                if (s > 0.15f && s < 0.3f) probe = new float[]{k[0] + dx, k[1], k[2]};
            }
            c.check("a probe in the rift's body", probe != null && org.erisia.stasis.StaticDeeps.zone(seed, probe[0], probe[1], probe[2]) == 2);
            // no focus, then a tier-1 focus on an End-bound stronghold gate: nothing moves
            for (int i = 0; i < 20; i++) t.testTick();
            c.check("no focus: no breach, no draw", gs.get().step() == 0 && t.drawW() == 0);
            t.setFocus(new net.minecraft.world.item.ItemStack(org.erisia.stasis.StasisRegistry.RIFT_FOCUS.get()));
            for (int i = 0; i < 20; i++) t.testTick();
            c.check("a rift focus can't breach an End gate", gs.get().step() == 0 && t.statusText().getString().contains("too weak"), t.statusText().getString());
            t.setFocus(new net.minecraft.world.item.ItemStack(org.erisia.stasis.StasisRegistry.CHARGED_RIFT_FOCUS.get()));
            // the breach
            int last = 0, ticks = 0;
            boolean oneAtATime = true, followed = true;
            while (!gs.get().open() && ticks++ < 200) {
                t.testTick();
                int st = gs.get().step();
                if (st < last || st > last + 1) oneAtATime = false;
                if (org.erisia.stasis.StaticDeeps.strength(seed, d) != org.erisia.stasis.Lance.strength(st)) followed = false;
                last = st;
            }
            c.check("the breach steps one at a time to the floor and latches OPEN", oneAtATime && gs.get().step() == org.erisia.stasis.Lance.STEPS
                && org.erisia.stasis.Lance.isOpen(level, d.id()), "step " + gs.get().step() + " after " + ticks + " ticks");
            c.check("the zones follow each step", followed && org.erisia.stasis.Lance.effectiveRadius(level, d) == d.radius() * 0.25f);
            if (probe != null) c.check("the rift's body shrank: the probe is outer zone now", org.erisia.stasis.StaticDeeps.zone(seed, probe[0], probe[1], probe[2]) == 1);
            // (at a quarter a r 9 rift's black is under a block across: somewhere at the eye, if not its exact solved centre)
            boolean riftAtEye = false;
            for (float dx = -1; dx <= 1; dx += 0.5f)
                for (float dy = -1; dy <= 1; dy += 0.5f)
                    for (float dz = -1; dz <= 1; dz += 0.5f)
                        if (org.erisia.stasis.StaticDeeps.zone(seed, k[0] + dx, k[1] + dy, k[2] + dz) == 2) riftAtEye = true;
            c.check("the eye is still rift", riftAtEye);
            c.check("the beam is on", t.beam());
            var forced = org.erisia.stasis.LanceTickets.forced(level, t.getBlockPos());
            c.check("the core's and the rift core's chunks are forced", forced.contains(net.minecraft.world.level.ChunkPos.pack((int) Math.floor(k[0]) >> 4,
                (int) Math.floor(k[2]) >> 4)) && forced.contains(net.minecraft.world.level.ChunkPos.pack(t.getBlockPos().getX() >> 4, t.getBlockPos().getZ() >> 4)),
                forced.toString());
            // the hold: the hold draw, and it stays
            for (int i = 0; i < 40; i++) t.testTick();
            c.check("held open on the hold draw", gs.get().open() && t.drawW() == org.erisia.stasis.Lance.holdW(d.radius()), "draw " + t.drawW());
            // power fails: the grace (klaxon), then unlatched and a step back at a time, never a snap
            t.testSupplyW = Double.NaN;
            t.energy().set(0);
            for (int i = 0; i < 4; i++) t.testTick();
            c.check("in the grace: still open, the klaxon", gs.get().open() && org.erisia.stasis.Lance.klaxon(gs.get(), 6), gs.get().toString());
            last = gs.get().step();
            ticks = 0;
            oneAtATime = true;
            boolean unlatched = false;
            while ((gs.get().step() > 0 || gs.get().progress() > 0) && ticks++ < 200) {
                t.testTick();
                int st = gs.get().step();
                if (st > last || st < last - 1) oneAtATime = false;
                if (!gs.get().open()) unlatched = true;
                last = st;
            }
            c.check("then unlatched, relaxing a step at a time to the whole rift", unlatched && oneAtATime && gs.get().step() == 0 && !gs.get().open()
                && ticks >= 6 * org.erisia.stasis.Lance.STEPS, "after " + ticks + " ticks: " + gs.get());
            c.check("the zones are whole again", org.erisia.stasis.StaticDeeps.strength(seed, d) == 1f
                && (probe == null || org.erisia.stasis.StaticDeeps.zone(seed, probe[0], probe[1], probe[2]) == 2));
            for (int i = 0; i < 3; i++) t.testTick();
            c.check("at rest: the tickets released, the beam off", org.erisia.stasis.LanceTickets.forced(level, t.getBlockPos()).isEmpty() && !t.beam());
            // a storm over the rift (at the boom's tip): no progress, twice the draw
            t.testStorm = null;
            t.testSupplyW = 1e9;
            BlockPos tip = t.tip();
            var box = new net.minecraft.world.phys.AABB(tip).inflate(1);
            for (int i = 0; i < 8; i++) t.testTick();
            int before = gs.get().step();
            org.erisia.stasis.Storms.TEST.put(box, 1);
            c.cleanup.add(() -> org.erisia.stasis.Storms.TEST.remove(box));
            t.testTick();
            float pr = gs.get().progress();
            for (int i = 0; i < 30; i++) t.testTick();
            c.check("a storm over the rift stalls the breach", before > 0 && gs.get().step() == before && gs.get().progress() == pr, gs.get().toString());
            c.check("at twice the breach draw", t.drawW() == 2 * org.erisia.stasis.Lance.breachW(d.radius()), "draw " + t.drawW());
            org.erisia.stasis.Storms.TEST.remove(box);
            for (int i = 0; i < 6; i++) t.testTick();
            c.check("the storm gone: on again", gs.get().step() > before);
            // dismantled mid-breach: the orphan relaxes by itself (Lance.tick), its grace first
            h.setBlock(new BlockPos(1, 1, 4), Blocks.AIR);
            c.check("dismantled: the rift is let go", !gs.get().hasLance() && gs.get().dismantled() >= 0);
            org.erisia.stasis.Lance.testSpeed = 100;
            c.cleanup.add(() -> org.erisia.stasis.Lance.testSpeed = 1);
            return () -> c.check("the orphan relaxed back to whole", gs.get() == null || gs.get().step() == 0, String.valueOf(gs.get()));
        }, false, true));
    }

    static org.erisia.stasis.TowerBlockEntity tower(GameTestHelper h, BlockPos base, int segments) {
        for (int i = 1; i <= segments; i++) h.setBlock(base.above(1 + i), org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get());
        if (!org.erisia.stasis.TowerBlock.placeCore(h.getLevel(), h.absolutePos(base))) throw new IllegalStateException("no room for the core");
        return (org.erisia.stasis.TowerBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(base));
    }

    /** A survival-mode player placed at a local position (the vanilla mock player is in creative). */
    static ServerPlayer survivalPlayer(GameTestHelper h, Vec3 local, boolean endGrace) {
        return survivalPlayer(h, local, endGrace, false);
    }

    /** starterSuit: let the first-join suit come (else the test player counts as having had it). */
    static ServerPlayer survivalPlayer(GameTestHelper h, Vec3 local, boolean endGrace, boolean starterSuit) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
            new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "stasis-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        if (!starterSuit) {
            var persisted = new net.minecraft.nbt.CompoundTag();
            persisted.putBoolean(org.erisia.stasis.StarterKit.TAG, true);
            p.getPersistentData().put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, persisted);
        }
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

    /** How many blocks of this kind in the box. */
    static int count(ServerLevel level, net.minecraft.world.level.levelgen.structure.BoundingBox b, net.minecraft.world.level.block.Block block) {
        int n = 0;
        for (BlockPos q : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) if (level.getBlockState(q).is(block)) n++;
        return n;
    }

    /** Has the player "Good job breaking it, hero" (HardMode)? */
    static boolean hasBreakingIt(ServerPlayer p) {
        var a = p.level().getServer().getAdvancements().get(org.erisia.stasis.HardMode.ADVANCEMENT);
        return a != null && p.getAdvancements().getOrStartProgress(a).isDone();
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

    /**
     * Wait for the light engine (its own thread; the test server runs ticks without pause, so light can lag many ticks
     * behind) to finish what is queued for the test's chunk.
     */
    static net.minecraft.world.level.lighting.LayerLightEventListener settledLight(GameTestHelper h) {
        var engine = (net.minecraft.server.level.ThreadedLevelLightEngine) h.getLevel().getChunkSource().getLightEngine();
        BlockPos o = h.absolutePos(BlockPos.ZERO);
        for (int round = 0; round < 2; round++) {   // twice: a relight can queue more work
            var f = engine.waitForPendingTasks(o.getX() >> 4, o.getZ() >> 4);
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (!f.isDone() && System.nanoTime() < deadline) {
                engine.tryScheduleUpdate();
                Thread.onSpinWait();
            }
        }
        return h.getLevel().getLightEngine().getLayerListener(net.minecraft.world.level.LightLayer.BLOCK);
    }

    /** A field at a local position (cleaned up after the test). */
    static Field field(GameTestHelper h, Checks c, Vec3 local, double r, double rate) {
        ServerLevel level = h.getLevel();
        Vec3 p = h.absoluteVec(local);
        Field f = FieldData.get(level).add(level, p.x, p.y, p.z, r, false, rate);
        c.cleanup.add(() -> FieldData.get(level).remove(level, f.id()));
        return f;
    }

    /** A slow field (r 3 at local 12.5, 2.5, 4.5) over the east end: (10..14, 1, 4) and (11..13, 1, 3..5) are inside. */
    static Field halfField(GameTestHelper h, Checks c, double rate) {
        return field(h, c, new Vec3(12.5, 2.5, 4.5), 3.0, rate);
    }

    /** A hopper on the floor under a chest of 64 cobblestone. */
    static net.minecraft.world.level.block.entity.HopperBlockEntity hopper(GameTestHelper h, BlockPos local) {
        h.setBlock(local.above(), Blocks.CHEST);
        ((net.minecraft.world.level.block.entity.ChestBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(local.above())))
            .setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        h.setBlock(local, Blocks.HOPPER);
        return (net.minecraft.world.level.block.entity.HopperBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(local));
    }

    static int count(net.minecraft.world.Container c) {
        int n = 0;
        for (int i = 0; i < c.getContainerSize(); i++) n += c.getItem(i).getCount();
        return n;
    }

    static boolean pressed(GameTestHelper h, BlockPos local) {
        return h.getBlockState(local).getValue(net.minecraft.world.level.block.ButtonBlock.POWERED);
    }

    /** Random ticks the cacti got since the last call (age, 16 per block grown), and reset them. */
    static int cactusTicks(GameTestHelper h, List<BlockPos> spots) {
        int n = 0;
        for (BlockPos b : spots) {
            var st = h.getBlockState(b);
            if (!st.is(Blocks.CACTUS)) continue;
            n += st.getValue(net.minecraft.world.level.block.CactusBlock.AGE);
            for (int y = 1; y <= 2; y++) {
                var above = h.getBlockState(b.above(y));
                if (above.is(Blocks.CACTUS)) n += 16 + above.getValue(net.minecraft.world.level.block.CactusBlock.AGE);
                if (!above.isAir()) h.setBlock(b.above(y), Blocks.AIR);
            }
            h.setBlock(b, Blocks.CACTUS);
        }
        return n;
    }

    /** ServerPlayerGameMode's tick count (what mining progress is measured against). */
    static int gameTicks(ServerPlayer p) {
        return p.gameMode.gameTicks;
    }

    /** Collected checks; the test fails at the end listing every failed one. */
    /**
     * After an End rift test: the test End's flat ground (bedrock and three end stone) back under the main island's columns
     * and the rift's islands gone, so other tests in the End (the wall's) find it as it was.
     */
    static void restoreFlatEnd(ServerLevel end, java.util.Set<Long> islands) {
        int r = org.erisia.stasis.EndRiftPlan.ISLAND_RADIUS;
        for (long b : islands) end.setBlock(BlockPos.of(b), Blocks.AIR.defaultBlockState(), 2 | 16);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z > r * r) continue;
                end.setBlock(p.set(x, 0, z), Blocks.BEDROCK.defaultBlockState(), 2 | 16);
                for (int y = 1; y < 4; y++) end.setBlock(p.set(x, y, z), Blocks.END_STONE.defaultBlockState(), 2 | 16);
            }
    }

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
        // test areas are new chunks: no grace for them (new_chunk_grace grants its own)
        org.erisia.stasis.NewChunkGrace.testsStarted(level);
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
