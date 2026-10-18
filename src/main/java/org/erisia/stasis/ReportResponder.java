package org.erisia.stasis;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bug reports, server side (notes/bugreport/SPEC.md 4.2): the server's view around a player who pressed the bug-report
 * key, as JSON for StasisNet.ReportState. Runs on the server thread. Each section is caught on its own ("error").
 * (Named ReportResponder, not ReportQuery as in the spec: StasisNet.ReportQuery is the payload in this package.)
 */
public final class ReportResponder {
    private ReportResponder() {
    }

    static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();
    static final long MIN_INTERVAL_MS = 10_000;
    static final double LOOK_RANGE = 8;
    static final int TOWER_CHUNKS = 2;
    static final double FIELD_RANGE = 512;
    private static final Map<UUID, Long> LAST = new ConcurrentHashMap<>();

    /** The JSON answer, or null when rate limited (1 query per player per 10 s). */
    public static String answer(ServerPlayer sp, StasisNet.ReportQuery q) {
        long now = System.currentTimeMillis();
        Long last = LAST.get(sp.getUUID());
        if (last != null && now - last < MIN_INTERVAL_MS) return null;
        LAST.put(sp.getUUID(), now);
        StasisDelvers.LOG.info("Bug report query from {} nonce={}", sp.getName().getString(), q.nonce());
        ServerLevel level = sp.level();
        BlockPos look = q.look().filter(p -> sp.getEyePosition().distanceToSqr(Vec3.atCenterOf(p)) <= LOOK_RANGE * LOOK_RANGE
            && level.isLoaded(p)).orElse(null);
        net.minecraft.core.Direction face = q.face() >= 0 && q.face() < 6 ? net.minecraft.core.Direction.from3DDataValue(q.face()) : null;

        JsonObject o = new JsonObject();
        o.addProperty("status", "ok");
        section(o, "server", () -> server(level));
        section(o, "player", () -> player(sp));
        section(o, "statue", () -> statue(sp));
        section(o, "world", () -> world(level, sp.blockPosition()));
        section(o, "fields", () -> fields(level, sp));
        section(o, "towers", () -> towers(sp));
        if (q.look().isPresent() && look == null) o.addProperty("look_refused", "out of range or not loaded");
        if (look != null) {
            o.addProperty("look", look.toShortString());
            section(o, "eln", () -> eln(level, look, face));
        }
        // at most 64 KB: drop whole sections, the biggest first, never cut the JSON
        String s = GSON.toJson(o);
        for (String drop : new String[]{"eln", "towers", "fields", "world", "statue"}) {
            if (s.getBytes(StandardCharsets.UTF_8).length <= StasisNet.ReportState.REPORT_MAX - 256) break;
            if (!o.has(drop)) continue;
            JsonObject d = new JsonObject();
            d.addProperty("dropped", "reply over 64 KB");
            d.addProperty("bytes", GSON.toJson(o.get(drop)).length());
            o.add(drop, d);
            s = GSON.toJson(o);
        }
        if (s.getBytes(StandardCharsets.UTF_8).length > StasisNet.ReportState.REPORT_MAX - 256)
            s = "{\"status\":\"ok\",\"error\":\"reply over 64 KB\"}";
        return s;
    }

    static void section(JsonObject o, String name, Supplier<JsonElement> s) {
        try {
            o.add(name, s.get());
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            JsonObject e = new JsonObject();
            e.addProperty("error", t.toString());
            o.add(name, e);
        }
    }

    static String version(String modId) {
        return net.neoforged.fml.ModList.get().getModContainerById(modId).map(c -> c.getModInfo().getVersion().toString()).orElse(null);
    }

    static JsonObject server(ServerLevel level) {
        MinecraftServer server = level.getServer();
        JsonObject o = new JsonObject();
        o.addProperty("tick", server.getTickCount());
        o.addProperty("mspt", server.getAverageTickTimeNanos() / 1e6);
        o.addProperty("smoothed_tick_ms", server.getCurrentSmoothedTickTime());
        o.addProperty("tickrate", level.tickRateManager().tickrate());
        o.addProperty("frozen", level.tickRateManager().isFrozen());
        o.addProperty("game_time", level.getGameTime());
        o.addProperty("stasisdelvers", version(StasisDelvers.MODID));
        o.addProperty("eln", version("eln"));
        o.addProperty("neoforge", net.neoforged.neoforge.common.NeoForgeVersion.getVersion());
        return o;
    }

    static JsonObject player(ServerPlayer sp) {
        ServerLevel level = sp.level();
        BlockPos p = sp.blockPosition();
        JsonObject o = new JsonObject();
        o.addProperty("dimension", level.dimension().identifier().toString());
        o.addProperty("pos", p.toShortString());
        o.addProperty("freeze", Freezing.describe(sp));
        o.addProperty("freeze_ticks", Freezing.ticks(sp));
        o.addProperty("freeze_counted", Freezing.counted(sp));
        o.addProperty("player_rate", PlayerRates.rate(sp));
        o.addProperty("player_rate_sent", PlayerRates.sent(sp));
        o.addProperty("suit_problem", Suits.problem(sp));
        double left = Suits.secondsLeft(sp);
        o.addProperty("suit_seconds_left", left == Double.MAX_VALUE ? -1 : left);
        o.addProperty("running_steady", Stasis.isRunningSteady(level, p));
        o.addProperty("running", Stasis.isRunning(level, p));
        o.addProperty("rate", Stasis.rateAt(level, p));
        o.addProperty("world_stopped", Stasis.stops(level));
        o.addProperty("gamemode", sp.gameMode.getGameModeForPlayer().getName());
        o.addProperty("health", sp.getHealth());
        return o;
    }

    static JsonElement statue(ServerPlayer sp) {
        JsonArray a = new JsonArray();
        for (Statue s : sp.level().getEntitiesOfClass(Statue.class, sp.getBoundingBox().inflate(64), s -> sp.getUUID().equals(s.owner()))) {
            JsonObject o = new JsonObject();
            o.addProperty("pos", s.blockPosition().toShortString());
            o.addProperty("items", s.items().size());
            o.addProperty("xp", s.xp());
            a.add(o);
        }
        return a;
    }

    static JsonObject world(ServerLevel level, BlockPos p) {
        JsonObject o = new JsonObject();
        int x = p.getX(), y = p.getY(), z = p.getZ();
        o.addProperty("deeps_at", Deeps.at(level, x, y, z));
        o.addProperty("static_zone", StaticDeeps.zone(level, x, y, z));
        o.addProperty("in_rift", StaticDeeps.inRift(level, x, y, z));
        Pockets.Weather w = Pockets.at(level, x, y, z);
        if (w != null) {
            o.addProperty("pocket_state", String.valueOf(w.state()));
            o.addProperty("pocket_density", w.density());
            o.addProperty("pocket_until_next", w.untilNext());
        }
        o.addProperty("storm_intensity", Storms.intensity(level));
        o.addProperty("storm_at", Storms.at(level, x, y, z));
        Storms.Forced f = Storms.Forced.get(level);
        JsonObject fo = new JsonObject();
        fo.addProperty("active", f.active);
        fo.addProperty("start", f.start);
        fo.addProperty("end", f.end);
        fo.addProperty("folded", f.folded);
        o.add("storm_forced", fo);
        o.addProperty("deeps_time", Deeps.time(level));
        o.addProperty("deeps_real_time", Deeps.realTime(level));
        return o;
    }

    static JsonObject fields(ServerLevel level, ServerPlayer sp) {
        FieldData d = FieldData.get(level);
        JsonObject o = new JsonObject();
        o.addProperty("deep_seed", d.deepSeed());
        o.addProperty("total", d.fields().size());
        JsonArray a = new JsonArray();
        for (Field f : d.fields()) {
            double dist = Math.sqrt(sp.distanceToSqr(f.x(), f.y(), f.z()));
            if (dist > FIELD_RANGE) continue;
            JsonObject fo = Field.CODEC.encodeStart(JsonOps.INSTANCE, f).result()
                .filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElseGet(JsonObject::new);
            fo.addProperty("dist", dist);
            fo.addProperty("clock", LocalTime.clockOf(level, f.id()));
            fo.addProperty("contains_player", f.containsPoint(sp.getX(), sp.getY(), sp.getZ()));
            a.add(fo);
            if (a.size() >= 64) break;
        }
        o.add("near", a);
        o.addProperty("chunk_clock_here", LocalTime.ChunkClocks.get(level).at(ChunkPos.pack(sp.blockPosition().getX() >> 4, sp.blockPosition().getZ() >> 4)));
        return o;
    }

    static JsonArray towers(ServerPlayer sp) {
        ServerLevel level = sp.level();
        int cx = sp.blockPosition().getX() >> 4, cz = sp.blockPosition().getZ() >> 4;
        JsonArray a = new JsonArray();
        for (int dx = -TOWER_CHUNKS; dx <= TOWER_CHUNKS; dx++)
            for (int dz = -TOWER_CHUNKS; dz <= TOWER_CHUNKS; dz++) {
                LevelChunk ch = level.getChunkSource().getChunkNow(cx + dx, cz + dz);
                if (ch == null) continue;
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    if (!(be instanceof TowerBlockEntity t)) continue;
                    JsonObject o = tower(t);
                    try {
                        if (t.isMaster()) {
                            StasisNet.ConsoleState st = t.consoleState(sp);
                            o.add("console", console(st.v()));
                            o.addProperty("console_text", st.text());
                        }
                    } catch (RuntimeException e) {
                        o.addProperty("console_error", e.toString());
                    }
                    a.add(o);
                    if (a.size() >= 16) return a;
                }
            }
        return a;
    }

    /** A tower's getters (both sides: the client report uses this on its synced copy). */
    public static JsonObject tower(TowerBlockEntity t) {
        JsonObject o = new JsonObject();
        o.addProperty("pos", t.getBlockPos().toShortString());
        try {
            o.addProperty("state", String.valueOf(t.state()));
            o.addProperty("segments", t.segments());
            o.addProperty("radius", t.radius());
            o.addProperty("design_radius", t.designRadius());
            o.addProperty("stretch", String.format("E%.2f S%.2f W%.2f N%.2f", t.stretch.e(), t.stretch.s(), t.stretch.w(), t.stretch.n()));
            o.addProperty("volume_scale", t.volumeScale());
            o.addProperty("min_radius", t.minRadius());
            o.addProperty("max_radius", t.maxRadius());
            o.addProperty("target", t.target());
            o.addProperty("override", t.override());
            o.addProperty("failing", t.failing());
            o.addProperty("retreating", t.retreating());
            o.addProperty("goal_radius", t.goalRadius());
            o.addProperty("carry_radius", t.carryRadius());
            o.addProperty("stored_J", t.storedJ());
            o.addProperty("store_max_J", t.storeMaxJ());
            o.addProperty("field_id", t.fieldId());
            o.addProperty("facing", String.valueOf(t.facing()));
            o.addProperty("master", t.isMaster());
            o.addProperty("locked", t.locked);
            o.addProperty("status_text", t.statusText().getString());
            o.addProperty("structure_status", String.valueOf(t.structure.status));
            o.addProperty("structure_message", t.structure.message);
            TowerBlockEntity.Remnant r = t.remnant();
            if (r != null) {
                JsonObject ro = new JsonObject();
                ro.addProperty("radius", r.radius());
                ro.addProperty("seed", r.seed());
                ro.addProperty("collapse", r.collapse());
                ro.addProperty("dropout", r.dropout());
                ro.addProperty("side", r.side());
                ro.addProperty("froze_at", r.frozeAt());
                ro.addProperty("bolts", r.bolts() == null ? 0 : r.bolts().size());
                o.add("remnant", ro);
            }
        } catch (RuntimeException e) {
            o.addProperty("error", e.toString());
        }
        return o;
    }

    /** ConsoleState.v with its names (TowerBlockEntity.CONSOLE_NAMES). */
    public static JsonObject console(java.util.List<Double> v) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < v.size(); i++) {
            String name = i < TowerBlockEntity.CONSOLE_NAMES.size() ? TowerBlockEntity.CONSOLE_NAMES.get(i) : "v" + i;
            o.addProperty(name, v.get(i));
        }
        return o;
    }

    static JsonObject eln(ServerLevel level, BlockPos look, net.minecraft.core.Direction face) {
        JsonObject o = new JsonObject();
        var coord = new mods.eln.misc.Coordonate(look, level);
        mods.eln.node.NodeBase node = mods.eln.node.NodeManager.instance == null ? null : mods.eln.node.NodeManager.instance.getNodeFromCoordonate(coord);
        if (node == null && mods.eln.Eln.ghostManager != null) {
            var ghost = mods.eln.Eln.ghostManager.getGhost(coord);
            if (ghost != null && ghost.getObservatorCoordonate() != null) {
                BlockPos base = ghost.getObservatorCoordonate().pos();
                o.addProperty("ghost_of", base.toShortString());
                node = mods.eln.node.NodeManager.instance.getNodeFromCoordonate(new mods.eln.misc.Coordonate(base, level));
            }
        }
        if (node == null) {
            o.addProperty("node", (String) null);
            return o;
        }
        o.addProperty("node", node.getClass().getName());
        o.addProperty("added", node.isAdded());
        if (node instanceof mods.eln.node.Node n) o.addProperty("light", n.getLightValue());
        mods.eln.misc.Direction ef = face == null ? null : mods.eln.misc.Direction.fromEnumFacing(face);
        if (node instanceof mods.eln.node.transparent.TransparentNode tn && tn.element != null) {
            JsonObject e = new JsonObject();
            e.addProperty("class", tn.element.getClass().getName());
            e.add("waila", waila(tn.element.getWaila()));
            if (ef != null) {
                e.addProperty("multimeter", safe(() -> tn.element.multiMeterString(ef)));
                e.addProperty("thermometer", safe(() -> tn.element.thermoMeterString(ef)));
            }
            try {
                if (tn.getFluidHandler() instanceof mods.eln.fluid.ElementFluidHandler h)
                    e.addProperty("tank", h.getFluidAmount() + " / " + h.getCapacity() + " mB of " + h.getFluid().getFluid());
            } catch (RuntimeException ex) {
                e.addProperty("tank_error", ex.toString());
            }
            o.add("transparent", e);
        }
        if (node instanceof mods.eln.node.six.SixNode six) {
            JsonArray sides = new JsonArray();
            for (var el : six.sideElementList) {
                if (el == null) continue;
                JsonObject e = new JsonObject();
                e.addProperty("side", String.valueOf(el.side));
                e.addProperty("class", el.getClass().getName());
                e.add("waila", waila(el.getWaila()));
                e.addProperty("multimeter", safe(el::multiMeterString));
                e.addProperty("thermometer", safe(el::thermoMeterString));
                sides.add(e);
            }
            o.add("six", sides);
        }
        return o;
    }

    static JsonElement waila(Map<String, String> m) {
        JsonObject o = new JsonObject();
        if (m == null) return o;
        new TreeMap<>(m).forEach(o::addProperty);
        return o;
    }

    static String safe(Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            return "error: " + e;
        }
    }
}
