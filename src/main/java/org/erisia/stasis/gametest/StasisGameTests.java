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
        // survey poles extend like scaffolding: the mast from anywhere on it, booms outward, sneaking starts a boom
        CASES.put("poles_extend_like_scaffolding", new Spec(25, (h, c) -> {
            var t = tower(h, new BlockPos(3, 1, 4), 2);
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
            c.check("side of the lowest mast pole: the mast grows at its top", at.test(new BlockPos(3, 5, 4), Y) && h.getBlockState(new BlockPos(3, 3, 3)).isAir());
            player.setShiftKeyDown(true);
            use.accept(new BlockPos(3, 5, 4), net.minecraft.core.Direction.EAST);
            c.check("sneaking on the mast's side: a boom starts", at.test(new BlockPos(4, 5, 4), X));
            player.setShiftKeyDown(false);
            use.accept(new BlockPos(4, 5, 4), net.minecraft.core.Direction.UP);
            use.accept(new BlockPos(4, 5, 4), net.minecraft.core.Direction.UP);
            c.check("on the boom: it grows outward, away from the mast", at.test(new BlockPos(5, 5, 4), X) && at.test(new BlockPos(6, 5, 4), X)
                && !h.getBlockState(new BlockPos(4, 6, 4)).is(pole));
            player.setShiftKeyDown(true);
            use.accept(new BlockPos(3, 5, 4), net.minecraft.core.Direction.EAST);
            c.check("sneaking on the mast's side again: that boom grows", at.test(new BlockPos(7, 5, 4), X));
            player.setShiftKeyDown(false);
            return () -> c.check("only the mast counts as segments", t.segments() == 3, "segments " + t.segments());
        }));
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
                c.check("0.5 field: about half", ratio > 0.3 && ratio < 0.7, sums[1] + " vs " + sums[0] + String.format(" (%.2f)", ratio));
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
