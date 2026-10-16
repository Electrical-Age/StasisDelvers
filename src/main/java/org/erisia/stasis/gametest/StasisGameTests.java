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
        // walk apart): sand in its grace falls; once the grace is over, new sand hangs
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
        }, false));
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
                c.check("ladder top inside the mast", s.ladderTop > 2 && s.ladderTop <= 14, "ladder top " + s.ladderTop);
                boolean ok = true;
                StringBuilder sb = new StringBuilder();
                for (int k = 1; k <= 12; k++) {
                    var part = h.getBlockState(base.above(1 + k)).getValue(org.erisia.stasis.TowerPoleBlock.PART);
                    var want = k + 2 <= s.ladderTop ? org.erisia.stasis.TowerPoleBlock.Part.LADDER : org.erisia.stasis.TowerPoleBlock.Part.HIDDEN;
                    if (part != want) { ok = false; sb.append(k).append('=').append(part).append(' '); }
                }
                c.check("mast poles: ladder up to the ladder top, hidden above", ok, sb.toString());
                c.check("core blocks hidden (the tower draws its core)", h.getBlockState(base).getValue(org.erisia.stasis.TowerBlock.HIDDEN)
                    && h.getBlockState(base.above()).getValue(org.erisia.stasis.TowerBlock.HIDDEN));
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
        // local time (visual): a field's clock skews at 50-200% towards the world's; when it goes, its chunks keep its time
        CASES.put("field_clock_skews_and_chunks_remember", new Spec(41, (h, c) -> {
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
                FieldData.get(level).remove(level, f.id());
                long chunk = net.minecraft.world.level.ChunkPos.pack(net.minecraft.core.BlockPos.containing(a.x, a.y, a.z));
                double kept = org.erisia.stasis.LocalTime.ChunkClocks.get(level).at(chunk);
                c.check("its centre chunk keeps that time", Math.abs(kept - now) < 0.001, "kept " + kept + " clock " + now);
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
                t.testCharge(0.3);
                double g = t.goalRadius();
                c.check("at 30% the safety halves the way to the minimum", g >= 22 && g <= 24 && g % 2 == 0, String.valueOf(g));
                t.setControls(Double.NaN, true);
                c.check("OVERRIDE holds the full range", t.goalRadius() == 40, String.valueOf(t.goalRadius()));
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
                c.check("no input: draining, and the shield shows it", t.draining() && t.failing() > 0.2 && t.failing() < 0.5,
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
            var changed = org.erisia.stasis.FrozenTextures.changed(List.of(a, b), List.of(a.withFailing(0.5), b.withRadius(12), new Field(3, 0, 50, 0, 5)));
            c.check("re-mesh: shape changes and new fields count, failing doesn't", changed.size() == 3
                && changed.stream().noneMatch(f -> f.id() == 1), changed.toString());
        }, false));
        // frozen textures mesh per section where they can: a section said to be all stopped / all running must be
        CASES.put("section_state_matches_blocks", new Spec(1, (h, c) -> {
            Field f = field(h, c, new Vec3(3.5, 2.5, 4.5), 30, 1.0);
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
                                        if ((Stasis.rateAtSteady(level, bx, by, bz) > 0) != (st == Stasis.SECTION_RUNNING)) {
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
            Field a = new Field(1, 0.5, 0.5, 0.5, 20).withSeed(12345).withCollapse(0.5), b = a.withSeed(987654);
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
                c.check("and drains x2 (20 W per piece)", e < 40000 - 10 * 135 / 20.0 - 1, String.format("%.1f J", e));
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
                c.check("its field is partly collapsed", f.isPresent() && f.get().collapse() > 0.3 && f.get().collapse() < 0.7, f.toString());
            });
            return () -> {
                c.check("collapsed: off", t.state() == org.erisia.stasis.TowerBlockEntity.State.OFF, t.state().name());
                c.check("collapsed: the field is gone", FieldData.get(h.getLevel()).fields().stream().noneMatch(f -> f.id() == fid[0]));
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

    static org.erisia.stasis.TowerBlockEntity tower(GameTestHelper h, BlockPos base, int segments) {
        for (int i = 1; i <= segments; i++) h.setBlock(base.above(1 + i), org.erisia.stasis.StasisRegistry.TOWER_SEGMENT.get());
        if (!org.erisia.stasis.TowerBlock.placeCore(h.getLevel(), h.absolutePos(base), null)) throw new IllegalStateException("no room for the core");
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
        try {
            var f = net.minecraft.server.level.ServerPlayerGameMode.class.getDeclaredField("gameTicks");
            f.setAccessible(true);
            return f.getInt(p.gameMode);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
