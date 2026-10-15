package org.erisia.stasis;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * Op commands for testing without towers: /stasis field add &lt;pos&gt; &lt;radius&gt; [column] [rate] | rate &lt;id&gt; &lt;rate&gt;
 * | remove | clear | list, /stasis at &lt;pos&gt;. Rates are shares of 20 TPS in (0, 1]; added fields default to 1.
 */
final class StasisCommands {
    private StasisCommands() {
    }

    static int addField(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, boolean column, boolean withRate)
        throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        BlockPos p = BlockPosArgument.getLoadedBlockPos(c, "pos");
        double r = DoubleArgumentType.getDouble(c, "radius");
        double rate = withRate ? DoubleArgumentType.getDouble(c, "rate") : 1.0;
        ServerLevel level = c.getSource().getLevel();
        Field f = FieldData.get(level).add(level, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, r, column, rate);
        c.getSource().sendSuccess(() -> Component.literal("Field " + f.id() + (column ? " (column)" : "") + " at " + p.toShortString()
            + ", r " + r + ", rate " + f.rate()), true);
        return f.id();
    }

    static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double> rateArg() {
        return Commands.argument("rate", DoubleArgumentType.doubleArg(0.001, 1.0));
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("stasis").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            // a whole 3x3x2 tower core with its bottom centre at pos (setblock would place a single cell)
            .then(Commands.literal("core").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                BlockPos p = BlockPosArgument.getLoadedBlockPos(c, "pos");
                if (!TowerBlock.placeCore(c.getSource().getLevel(), p, null)) {
                    c.getSource().sendFailure(Component.literal("No room for a 3x3x2 core at " + p.toShortString()));
                    return 0;
                }
                c.getSource().sendSuccess(() -> Component.literal("Tower core at " + p.toShortString() + "; the mast starts at "
                    + TowerStructure.pole(p, 1).toShortString()), true);
                return 1;
            })))
            .then(Commands.literal("field")
                .then(Commands.literal("add").then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, 512)).executes(c -> addField(c, false, false))
                        .then(Commands.literal("column").executes(c -> addField(c, true, false))
                            .then(rateArg().executes(c -> addField(c, true, true))))
                        .then(rateArg().executes(c -> addField(c, false, true))))))
                .then(Commands.literal("rate").then(Commands.argument("id", IntegerArgumentType.integer(1))
                    .then(rateArg().executes(c -> {
                        ServerLevel level = c.getSource().getLevel();
                        int id = IntegerArgumentType.getInteger(c, "id");
                        double v = DoubleArgumentType.getDouble(c, "rate");
                        var data = FieldData.get(level);
                        if (data.get(id).isEmpty()) {
                            c.getSource().sendFailure(Component.literal("No field " + id));
                            return 0;
                        }
                        data.update(level, id, f -> f.withRate(v));
                        c.getSource().sendSuccess(() -> Component.literal("Field " + id + ": rate " + data.get(id).map(Field::rate).orElse(v)), true);
                        return 1;
                    }))))
                .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerLevel level = c.getSource().getLevel();
                    int id = IntegerArgumentType.getInteger(c, "id");
                    boolean ok = FieldData.get(level).remove(level, id);
                    c.getSource().sendSuccess(() -> Component.literal(ok ? "Removed field " + id : "No field " + id), true);
                    return ok ? 1 : 0;
                })))
                // test hooks for the visuals: set a field's shield failure or collapse by hand
                .then(Commands.literal("failing").then(Commands.argument("id", IntegerArgumentType.integer(1))
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 1)).executes(c -> {
                        ServerLevel level = c.getSource().getLevel();
                        double v = DoubleArgumentType.getDouble(c, "value");
                        FieldData.get(level).update(level, IntegerArgumentType.getInteger(c, "id"), f -> f.withFailing(v));
                        return 1;
                    }))))
                .then(Commands.literal("collapse").then(Commands.argument("id", IntegerArgumentType.integer(1))
                    .then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 1)).executes(c -> {
                        ServerLevel level = c.getSource().getLevel();
                        double v = DoubleArgumentType.getDouble(c, "value");
                        FieldData.get(level).update(level, IntegerArgumentType.getInteger(c, "id"), f -> f.withCollapse(v));
                        return 1;
                    }))))
                .then(Commands.literal("clear").executes(c -> {
                    ServerLevel level = c.getSource().getLevel();
                    int n = FieldData.get(level).clear(level);
                    c.getSource().sendSuccess(() -> Component.literal("Removed " + n + " field(s)"), true);
                    return n;
                }))
                .then(Commands.literal("list").executes(c -> {
                    var fields = FieldData.get(c.getSource().getLevel()).fields();
                    c.getSource().sendSuccess(() -> Component.literal(fields.size() + " field(s)"), false);
                    for (Field f : fields)
                        c.getSource().sendSuccess(() -> Component.literal(String.format("  %d: %.1f %.1f %.1f r %.1f rate %.3f%s", f.id(), f.x(), f.y(), f.z(), f.radius(), f.rate(), f.column() ? " column" : "")), false);
                    return fields.size();
                })))
            .then(Commands.literal("deeps").executes(c -> {
                var src = c.getSource();
                var level = src.getLevel();
                var pos = src.getPosition();
                var list = Deeps.near((float) pos.x, (float) pos.z, Deeps.time(level), Deeps.seed(level));
                src.sendSuccess(() -> Component.literal(list.size() + " drifting deep(s) around; here: "
                    + String.format("%.2f", Deeps.at(level, (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z)))), false);
                for (var dp : list)
                    src.sendSuccess(() -> Component.literal(String.format("  centre %.0f %.0f, radius %.0f, strength %.2f, %.0f blocks away",
                        dp.x(), dp.z(), dp.radius(), dp.strength(), Math.hypot(dp.x() - pos.x, dp.z() - pos.z))), false);
                return list.size();
            }))
            .then(Commands.literal("status").then(Commands.argument("player", net.minecraft.commands.arguments.EntityArgument.player()).executes(c -> {
                var p = net.minecraft.commands.arguments.EntityArgument.getPlayer(c, "player");
                var level = p.level();
                int x = p.getBlockX(), y = p.getBlockY(), z = p.getBlockZ();
                String msg = String.format("%s: %s; time %s here (steady %s), drifting deep %.2f, static zone %d",
                    p.getName().getString(), Freezing.describe(p), Stasis.isRunning(level, p.blockPosition()) ? "runs" : "stopped",
                    Stasis.isRunningSteady(level, p.blockPosition()) ? "runs" : "stopped", Deeps.at(level, x, y, z), StaticDeeps.zone(level, x, y, z));
                c.getSource().sendSuccess(() -> Component.literal(msg), false);
                return 1;
            })))
            .then(Commands.literal("rifts").executes(c -> {
                var src = c.getSource();
                var level = src.getLevel();
                var pos = src.getPosition();
                var list = StaticDeeps.near((float) pos.x, (float) pos.y, (float) pos.z, 1024, Deeps.seed(level));
                list.sort(java.util.Comparator.comparingDouble(sd0 -> Math.hypot(sd0.x() - pos.x, sd0.z() - pos.z)));
                src.sendSuccess(() -> Component.literal(list.size() + " static deep(s) within ~1 km; here: zone "
                    + StaticDeeps.zone(level, (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z))), false);
                for (var sd : list.subList(0, Math.min(8, list.size())))
                    src.sendSuccess(() -> Component.literal(String.format("  centre %.0f %.0f %.0f, radius %.0f (rift ~%.0f), %.0f blocks away, strata %s",
                        sd.x(), sd.y(), sd.z(), sd.radius(), sd.radius() * 0.35, Math.hypot(sd.x() - pos.x, sd.z() - pos.z), Strata.shapeOf(sd).id())), false);
                return list.size();
            }))
            .then(Commands.literal("strata").executes(c -> {
                // what the strata is here (each static deep nearby), and the worldgen time spent on rift chunks
                var src = c.getSource();
                var pos = src.getPosition();
                int x = (int) Math.floor(pos.x), y = (int) Math.floor(pos.y), z = (int) Math.floor(pos.z);
                String[] names = {"out", "body", "cavity", "vent", "vein", "spire"};
                StringBuilder sb = new StringBuilder();
                for (var sd : StaticDeeps.near((float) pos.x, (float) pos.y, (float) pos.z, 300, Deeps.seed(src.getLevel())))
                    sb.append(String.format("%s %s; ", Strata.shapeOf(sd).id(), names[Strata.at(sd, x, y, z)]));
                long n = DeepFeature.Stats.CHUNKS.get();
                String stats = String.format("rift worldgen: %d chunks, mean %.2f ms, max %.2f ms", n,
                    n == 0 ? 0 : DeepFeature.Stats.NANOS.get() / 1e6 / n, DeepFeature.Stats.MAX.get() / 1e6);
                src.sendSuccess(() -> Component.literal((sb.length() == 0 ? "no static deep near; " : sb.toString()) + stats), false);
                return 1;
            }))
            .then(Commands.literal("tower")
                .then(Commands.literal("charge").then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .then(Commands.argument("share", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 1)).executes(c -> {
                        if (!(c.getSource().getLevel().getBlockEntity(BlockPosArgument.getBlockPos(c, "pos")) instanceof TowerBlockEntity t)) {
                            c.getSource().sendFailure(Component.literal("No tower core there (the bottom core block)"));
                            return 0;
                        }
                        t.testCharge(com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "share"));
                        c.getSource().sendSuccess(() -> Component.literal("Tower store set"), true);
                        return 1;
                    }))))
                .then(Commands.literal("bolt").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                    if (!(c.getSource().getLevel().getBlockEntity(BlockPosArgument.getBlockPos(c, "pos")) instanceof TowerBlockEntity t)) {
                        c.getSource().sendFailure(Component.literal("No tower core there (the bottom core block)"));
                        return 0;
                    }
                    t.testBolt();
                    return 1;
                }))))
            .then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                BlockPos p = BlockPosArgument.getBlockPos(c, "pos");
                double rate = Stasis.rateAt(c.getSource().getLevel(), p);
                c.getSource().sendSuccess(() -> Component.literal(p.toShortString() + ": time " + (rate <= 0 ? "is stopped"
                    : rate >= 1 ? "runs" : String.format("runs at %.3f (%.1f TPS)", rate, 20 * rate))), false);
                return (int) Math.round(rate * 1000);
            }))));
    }
}
