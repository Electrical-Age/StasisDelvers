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
 * Op commands for testing without towers: /stasis field add|remove|clear|list, /stasis at &lt;pos&gt;.
 */
final class StasisCommands {
    private StasisCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("stasis").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .then(Commands.literal("field")
                .then(Commands.literal("add").then(Commands.argument("pos", BlockPosArgument.blockPos())
                    .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0.5, 512)).executes(c -> {
                        BlockPos p = BlockPosArgument.getLoadedBlockPos(c, "pos");
                        double r = DoubleArgumentType.getDouble(c, "radius");
                        ServerLevel level = c.getSource().getLevel();
                        Field f = FieldData.get(level).add(level, p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, r);
                        c.getSource().sendSuccess(() -> Component.literal("Field " + f.id() + " at " + p.toShortString() + ", r " + r), true);
                        return f.id();
                    }))))
                .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerLevel level = c.getSource().getLevel();
                    int id = IntegerArgumentType.getInteger(c, "id");
                    boolean ok = FieldData.get(level).remove(level, id);
                    c.getSource().sendSuccess(() -> Component.literal(ok ? "Removed field " + id : "No field " + id), true);
                    return ok ? 1 : 0;
                })))
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
                        c.getSource().sendSuccess(() -> Component.literal(String.format("  %d: %.1f %.1f %.1f r %.1f", f.id(), f.x(), f.y(), f.z(), f.radius())), false);
                    return fields.size();
                })))
            .then(Commands.literal("at").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> {
                BlockPos p = BlockPosArgument.getBlockPos(c, "pos");
                boolean run = Stasis.isRunning(c.getSource().getLevel(), p);
                c.getSource().sendSuccess(() -> Component.literal(p.toShortString() + ": time " + (run ? "runs" : "is stopped")), false);
                return run ? 1 : 0;
            }))));
    }
}
