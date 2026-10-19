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
 * | remove | clear | list, /stasis at &lt;pos&gt;, /stasis spread status | set &lt;coverage&gt; | hardmode. Rates are shares of 20 TPS in (0, 1]; added fields default to 1.
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
            // a whole two-block tower core with its bottom block at pos (setblock would place a single cell)
            .then(Commands.literal("core").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                BlockPos p = BlockPosArgument.getLoadedBlockPos(c, "pos");
                if (!TowerBlock.placeCore(c.getSource().getLevel(), p)) {
                    c.getSource().sendFailure(Component.literal("No room for a tower core at " + p.toShortString()));
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
                        c.getSource().sendSuccess(() -> Component.literal(String.format("  %d: %.1f %.1f %.1f r %.1f rate %.3f%s%s", f.id(), f.x(), f.y(), f.z(), f.radius(), f.rate(), f.column() ? " column" : "",
                            f.stretch().isOne() ? "" : String.format(" stretch E%.2f S%.2f W%.2f N%.2f", f.stretch().e(), f.stretch().s(), f.stretch().w(), f.stretch().n()))), false);
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
            // spreading mode (Spread): where the stillness has got to, and test hooks to move it
            .then(Commands.literal("spread")
                .then(Commands.literal("status").executes(c -> {
                    String msg = Spread.status(c.getSource().getServer(), c.getSource().getPlayer());
                    c.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                }))
                .then(Commands.literal("set").then(Commands.argument("coverage", DoubleArgumentType.doubleArg(0, 1)).executes(c -> {
                    if (!StasisConfig.spreading) {
                        c.getSource().sendFailure(Component.literal("Not in spreading mode (config mode)"));
                        return 0;
                    }
                    String err = Spread.setCoverage(c.getSource().getServer(), DoubleArgumentType.getDouble(c, "coverage"));
                    if (err != null) {
                        c.getSource().sendFailure(Component.literal(err));
                        return 0;
                    }
                    String msg = Spread.status(c.getSource().getServer(), c.getSource().getPlayer());
                    c.getSource().sendSuccess(() -> Component.literal("Spread clock moved; " + msg), true);
                    return 1;
                })))
                .then(Commands.literal("hardmode").executes(c -> {
                    if (!StasisConfig.spreading) {
                        c.getSource().sendFailure(Component.literal("Not in spreading mode (config mode)"));
                        return 0;
                    }
                    boolean now = HardMode.enter(c.getSource().getServer(), "/stasis spread hardmode by " + c.getSource().getTextName());
                    c.getSource().sendSuccess(() -> Component.literal(now ? "Hard mode: the stillness spreads on towards all of the land"
                        : "Already in hard mode"), true);
                    return now ? 1 : 0;
                }))
                // the End's island breaking up (EndRift): a pillar's crystal broken by hand, for the lab; and its state
                .then(Commands.literal("endstage").then(Commands.argument("pillar", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 31)).executes(c -> {
                    var end = c.getSource().getServer().getLevel(net.minecraft.world.level.Level.END);
                    if (!StasisConfig.spreading || end == null) {
                        c.getSource().sendFailure(Component.literal("Not in spreading mode (config mode), or no End"));
                        return 0;
                    }
                    int k = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "pillar");
                    boolean now = EndRift.crystalBroken(end, k);
                    c.getSource().sendSuccess(() -> Component.literal(now ? "Pillar " + k + "'s part of the island lifts"
                        : "Nothing: that pillar's part went already, there is no such pillar, or hard mode has begun"), true);
                    return now ? 1 : 0;
                })))
                .then(Commands.literal("endstatus").executes(c -> {
                    var end = c.getSource().getServer().getLevel(net.minecraft.world.level.Level.END);
                    if (end == null) {
                        c.getSource().sendFailure(Component.literal("No End"));
                        return 0;
                    }
                    String msg = EndRift.status(end);
                    c.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                })))
            .then(Commands.literal("storm").executes(c -> {
                    // the storm schedule: now, the next one, and the warped clock
                    var level = c.getSource().getLevel();
                    int seed = Deeps.seed(level);
                    double t = Deeps.realTime(level);
                    double i = Storms.intensity(level), o = Storms.origin(level);
                    var next = Storms.next(seed, o, t);
                    String msg = String.format("storm intensity %.2f, warning %.2f; %s; weather clock %.0f s ahead of real time",
                        i, Storms.warning(seed, Storms.override(level), o, t),
                        next == null ? "no storms until hard mode (spreading mode)"
                            : t >= next.start() ? String.format("this storm ends in %.0f min", (next.end() - t) / 60)
                            : String.format("next storm in %.1f h (%.0f min long)", (next.start() - t) / 3600, next.length() / 60),
                        Deeps.time(level) - t);
                    c.getSource().sendSuccess(() -> Component.literal(msg), false);
                    return 1;
                })
                .then(Commands.literal("start").executes(c -> {
                    if (Double.isNaN(Storms.origin(c.getSource().getLevel()))) {
                        c.getSource().sendFailure(Component.literal("No storms before hard mode in spreading mode"));
                        return 0;
                    }
                    Storms.start(c.getSource().getLevel(), 0);
                    c.getSource().sendSuccess(() -> Component.literal("A storm starts now (test override)"), true);
                    return 1;
                }).then(Commands.argument("minutes", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 600)).executes(c -> {
                    double m = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(c, "minutes");
                    if (Double.isNaN(Storms.origin(c.getSource().getLevel()))) {
                        c.getSource().sendFailure(Component.literal("No storms before hard mode in spreading mode"));
                        return 0;
                    }
                    Storms.start(c.getSource().getLevel(), m);
                    c.getSource().sendSuccess(() -> Component.literal("A storm starts in " + m + " min (test override; its glow shows now)"), true);
                    return 1;
                })))
                .then(Commands.literal("stop").executes(c -> {
                    Storms.stop(c.getSource().getLevel());
                    c.getSource().sendSuccess(() -> Component.literal("The test storm ramps out"), true);
                    return 1;
                })))
            // the lance's gates, until L2's multiblock opens them (docs/lance.md): the nearest rift, or one by id
            .then(Commands.literal("lance")
                .then(Commands.literal("list").executes(c -> Lance.list(c.getSource())))
                .then(Commands.literal("open").executes(c -> Lance.command(c.getSource(), true, null))
                    .then(Commands.argument("rift", IntegerArgumentType.integer(1)).executes(c ->
                        Lance.command(c.getSource(), true, IntegerArgumentType.getInteger(c, "rift")))))
                .then(Commands.literal("close").executes(c -> Lance.command(c.getSource(), false, null))
                    .then(Commands.argument("rift", IntegerArgumentType.integer(1)).executes(c ->
                        Lance.command(c.getSource(), false, IntegerArgumentType.getInteger(c, "rift")))))
                // test power for the nearest lance core within 32 blocks (lab checks without an EA grid); < 0 clears
                .then(Commands.literal("supply").then(Commands.argument("watts", DoubleArgumentType.doubleArg()).executes(c -> {
                    CommandSourceStack src = c.getSource();
                    double w = DoubleArgumentType.getDouble(c, "watts");
                    BlockPos at = BlockPos.containing(src.getPosition());
                    LanceBlockEntity best = null;
                    double bd = 32 * 32;
                    for (BlockPos q : BlockPos.betweenClosed(at.offset(-32, -32, -32), at.offset(32, 32, 32))) {
                        if (src.getLevel().isLoaded(q) && src.getLevel().getBlockEntity(q) instanceof LanceBlockEntity l && q.distSqr(at) < bd) {
                            best = l;
                            bd = q.distSqr(at);
                        }
                    }
                    if (best == null) {
                        src.sendFailure(Component.literal("No lance core within 32 blocks"));
                        return 0;
                    }
                    best.testSupplyW = w < 0 ? Double.NaN : w;
                    BlockPos bp = best.getBlockPos();
                    src.sendSuccess(() -> Component.literal(w < 0 ? "Test supply off at " + bp.toShortString()
                        : "Test supply " + (int) w + " W into the lance core at " + bp.toShortString()), true);
                    return 1;
                }))))
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
                        sd.x(), sd.y(), sd.z(), sd.radius(), sd.radius() * 0.35, Math.hypot(sd.x() - pos.x, sd.z() - pos.z),
                        StaticDeeps.isGate(sd) ? "none (gate rift)" : Strata.shapeOf(sd).id() + ", to " + Gates.destination(sd))
                        + String.format(", eye at %.0f %.0f %.0f", StaticDeeps.core(sd)[0], StaticDeeps.core(sd)[1], StaticDeeps.core(sd)[2])
                        + Ruins.describe(level, sd) + (DeepFeature.messiness(sd) > 0 ? String.format(", messy %.2f", DeepFeature.messiness(sd)) : "")), false);
                return list.size();
            }))
            // a structure template from the world, saved to disk at once (structure blocks only save to disk from their
            // screen): world/generated/stasisdelvers/structure/<name>.nbt (the spawn site, Baughn 2026-10-18)
            .then(Commands.literal("savetemplate").then(Commands.argument("from", BlockPosArgument.blockPos())
                .then(Commands.argument("to", BlockPosArgument.blockPos()).then(Commands.argument("name", com.mojang.brigadier.arguments.StringArgumentType.word())
                    .executes(c -> {
                        BlockPos a = BlockPosArgument.getLoadedBlockPos(c, "from"), b = BlockPosArgument.getLoadedBlockPos(c, "to");
                        BlockPos lo = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
                        var size = new net.minecraft.core.Vec3i(Math.abs(a.getX() - b.getX()) + 1, Math.abs(a.getY() - b.getY()) + 1, Math.abs(a.getZ() - b.getZ()) + 1);
                        var id = net.minecraft.resources.Identifier.fromNamespaceAndPath(StasisDelvers.MODID, com.mojang.brigadier.arguments.StringArgumentType.getString(c, "name"));
                        var mgr = c.getSource().getLevel().getStructureTemplateManager();
                        var t = mgr.getOrCreate(id);
                        t.fillFromWorld(c.getSource().getLevel(), lo, size, true, java.util.List.of(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID));
                        boolean ok = mgr.save(id);
                        c.getSource().sendSuccess(() -> Component.literal("template " + id + " " + size.toShortString() + (ok ? " saved" : " NOT saved")), false);
                        return ok ? 1 : 0;
                    })))))
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
                String stats = String.format("rift worldgen: %d chunks, mean %.2f ms, max %.2f ms; time scars: %d falls, %d gravel, %d volleys; ruin slices %d", n,
                    n == 0 ? 0 : DeepFeature.Stats.NANOS.get() / 1e6 / n, DeepFeature.Stats.MAX.get() / 1e6,
                    TimeScars.FALLS.get(), TimeScars.GRAVEL.get(), TimeScars.VOLLEYS.get(), Ruins.SLICES.get());
                src.sendSuccess(() -> Component.literal((sb.length() == 0 ? "no static deep near; " : sb.toString()) + stats), false);
                // the strata weather here, and the next violet within 2 pockets (for clips)
                var level = src.getLevel();
                double t = Deeps.realTime(level);
                var w = Pockets.at(level, x, y, z);
                String here = w == null ? "pocket: none here" : String.format("pocket %d %d %d: %s, density %.2f, %.0f s to the next change",
                    Math.floorDiv(x, Pockets.POCKET), Math.floorDiv(y, Pockets.POCKET), Math.floorDiv(z, Pockets.POCKET),
                    w.state().name().toLowerCase(), w.density(), w.untilNext());
                String next = "";
                double best = Double.MAX_VALUE;
                for (int dx = -2; dx <= 2; dx++)
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dz = -2; dz <= 2; dz++) {
                            int bx = x + dx * Pockets.POCKET, by = y + dy * Pockets.POCKET, bz = z + dz * Pockets.POCKET;
                            var sd = Pockets.strataAt(level, bx, by, bz);
                            if (sd == null) continue;
                            int px = Math.floorDiv(bx, Pockets.POCKET), py = Math.floorDiv(by, Pockets.POCKET), pz = Math.floorDiv(bz, Pockets.POCKET);
                            var pw = Pockets.weather(sd.id(), px, py, pz, t);
                            if (pw.state() == Pockets.State.MIST && pw.untilNext() < best
                                && Pockets.weather(sd.id(), px, py, pz, t + pw.untilNext() + 0.01).state() == Pockets.State.VIOLET) {
                                best = pw.untilNext();
                                next = String.format("; next violet: pocket at %d %d %d in %.0f s", px * Pockets.POCKET + 6, py * Pockets.POCKET + 6, pz * Pockets.POCKET + 6, best);
                            }
                        }
                String line = here + next;
                src.sendSuccess(() -> Component.literal(line), false);
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
