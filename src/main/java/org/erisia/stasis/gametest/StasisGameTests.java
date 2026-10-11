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

    record Spec(int ticks, Case body, boolean field) {
        Spec(int ticks, Case body) {
            this(ticks, body, true);
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
            ResourceKey<Consumer<GameTestHelper>> fn = ResourceKey.create(Registries.TEST_FUNCTION, id);
            event.registerTest(id, new FunctionGameTestInstance(fn,
                new TestData<>(env, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "floor_16x6x8"), e.getValue().ticks() + 40, 0, true)));
        }
    }
}
