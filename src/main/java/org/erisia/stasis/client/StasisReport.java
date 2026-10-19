package org.erisia.stasis.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.Deeps;
import org.erisia.stasis.Field;
import org.erisia.stasis.Gates;
import org.erisia.stasis.LocalTime;
import org.erisia.stasis.Pockets;
import org.erisia.stasis.ReportData;
import org.erisia.stasis.ReportResponder;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StasisConfig;
import org.erisia.stasis.StasisNet;
import org.erisia.stasis.StaticDeeps;
import org.erisia.stasis.Storms;
import org.erisia.stasis.TimeSuit;
import org.erisia.stasis.TowerBlockEntity;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The Stasis Delvers part of a bug report (notes/bugreport/SPEC.md 2). In this package to read the package-private
 * client statics. Client (render) thread only. Each part is caught on its own and records its error.
 */
public final class StasisReport {
    private StasisReport() {
    }

    public static boolean shaderPackInUse() {
        return FlashlightPost.shaderPackInUse();
    }

    static void part(JsonObject o, String name, Supplier<JsonElement> s) {
        try {
            o.add(name, s.get());
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            JsonObject e = new JsonObject();
            e.addProperty("error", t.toString());
            o.add(name, e);
        }
    }

    public static JsonObject collect(Minecraft mc) {
        JsonObject o = new JsonObject();
        part(o, "basics", () -> basics(mc));
        part(o, "fields", () -> fields(mc));
        part(o, "remnants", () -> remnants(mc));
        part(o, "local_clocks", () -> {
            BlockPos bp = mc.player.blockPosition();
            JsonObject c = ReportData.localClocks(bp.getX() >> 4, bp.getZ() >> 4);
            c.add("gutters", ReportData.gutters(bp.getX() >> 4, bp.getZ() >> 4));
            Vec3 p = mc.player.position();
            long gt = mc.level.getOverworldClockTime();
            c.addProperty("local_time_of_day", LocalTime.wrap(LocalTime.Client.time(mc.level, p.x, p.y, p.z, 1f, gt)));
            c.addProperty("local_clock", LocalClock.local(gt, 1f));
            return c;
        });
        part(o, "weather", () -> weather(mc));
        part(o, "suit", () -> suit(mc));
        part(o, "subsystems", () -> subsystems(mc));
        if (org.erisia.stasis.client.report.ReportConfig.includePostData()) part(o, "post_data", StasisReport::postData);
        part(o, "console", () -> console(mc));
        part(o, "towers_client", () -> towers(mc));
        return o;
    }

    // ---- 2.1

    static JsonObject basics(Minecraft mc) {
        ClientLevel level = mc.level;
        BlockPos bp = mc.player.blockPosition();
        JsonObject o = new JsonObject();
        o.addProperty("slow_rate_here", Stasis.rateAt(level, bp.getX(), bp.getY(), bp.getZ()));
        o.addProperty("slow_rate_here_steady", Stasis.rateAtSteady(level, bp.getX(), bp.getY(), bp.getZ()));
        o.addProperty("running_here", Stasis.isRunning(level, bp));
        o.addProperty("running_here_steady", Stasis.isRunningSteady(level, bp));
        o.addProperty("section_state", Stasis.sectionState(level, bp.getX() >> 4, bp.getY() >> 4, bp.getZ() >> 4));
        o.addProperty("world_stopped", Stasis.stops(level));
        int[] sa = Stasis.spawnArea(level);
        if (sa != null) {
            JsonArray a = new JsonArray();
            for (int v : sa) a.add(v);
            o.add("spawn_area", a);
        }
        o.addProperty("index_version", Stasis.version());
        o.addProperty("tickrate", level.tickRateManager().tickrate());
        o.addProperty("freeze_progress", StasisNet.clientFreeze);
        o.addProperty("strain", StasisPost.lastStrain);
        o.addProperty("frozen_entity", Stasis.isFrozen(mc.player));
        JsonObject cfg = new JsonObject();
        for (Map.Entry<String, Object> e : StasisConfig.snapshot().entrySet()) {
            Object v = e.getValue();
            if (v instanceof Number n) cfg.addProperty(e.getKey(), n);
            else if (v instanceof Boolean b) cfg.addProperty(e.getKey(), b);
            else cfg.addProperty(e.getKey(), String.valueOf(v));
        }
        o.add("synced_config", cfg);
        return o;
    }

    // ---- 2.2

    static JsonObject fields(Minecraft mc) {
        Vec3 p = mc.player.position();
        BlockPos look = mc.hitResult instanceof BlockHitResult bh && mc.hitResult.getType() == HitResult.Type.BLOCK ? bh.getBlockPos() : null;
        List<Field> fs = new ArrayList<>(ClientFields.fields());
        fs.sort(Comparator.comparingDouble(f -> p.distanceToSqr(f.x(), f.y(), f.z())));
        JsonObject o = new JsonObject();
        o.addProperty("count", fs.size());
        o.addProperty("deep_seed", ClientFields.deepSeed());
        JsonArray a = new JsonArray();
        for (Field f : fs) {
            if (a.size() >= 64) break;
            JsonObject fo = Field.CODEC.encodeStart(JsonOps.INSTANCE, f).result()
                .filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject).orElseGet(JsonObject::new);
            fo.addProperty("dist", Math.sqrt(p.distanceToSqr(f.x(), f.y(), f.z())));
            fo.addProperty("contains_player", f.containsPoint(p.x, p.y, p.z));
            if (look != null) fo.addProperty("contains_look_block", f.containsBlock(look.getX(), look.getY(), look.getZ()));
            double[] shown = StasisPost.SHOWN.get(f.id());
            if (shown != null && shown.length > 0) fo.addProperty("shown_radius", shown[0]);
            double[] shownC = StasisPost.SHOWN_C.get(f.id());
            if (shownC != null && shownC.length > 0) fo.addProperty("shown_collapse", shownC[0]);
            fo.addProperty("held_collapsing", StasisPost.LAST_COLLAPSING.containsKey(f.id()));
            a.add(fo);
        }
        o.add("fields", a);
        return o;
    }

    // ---- 2.3

    static JsonArray remnants(Minecraft mc) {
        Vec3 p = mc.player.position();
        List<Map.Entry<BlockPos, TowerBlockEntity.Remnant>> all = new ArrayList<>(Remnants.all());
        all.sort(Comparator.comparingDouble(e -> p.distanceToSqr(Vec3.atCenterOf(e.getKey()))));
        JsonArray a = new JsonArray();
        for (var e : all) {
            if (a.size() >= 32) break;
            TowerBlockEntity.Remnant r = e.getValue();
            JsonObject o = new JsonObject();
            o.addProperty("tower", e.getKey().toShortString());
            o.addProperty("dist", Math.sqrt(p.distanceToSqr(Vec3.atCenterOf(e.getKey()))));
            o.addProperty("radius", r.radius());
            o.addProperty("seed", r.seed());
            o.addProperty("collapse", r.collapse());
            o.addProperty("dropout", r.dropout());
            o.addProperty("side", r.side());
            o.addProperty("froze_at", r.frozeAt());
            o.addProperty("bolts", r.bolts() == null ? 0 : r.bolts().size());
            a.add(o);
        }
        return a;
    }

    // ---- 2.5

    static JsonObject weather(Minecraft mc) {
        ClientLevel level = mc.level;
        Vec3 p = mc.player.position();
        BlockPos bp = mc.player.blockPosition();
        int bx = bp.getX(), by = bp.getY(), bz = bp.getZ();
        double t = Double.isNaN(StasisPost.smoothClock) ? Deeps.realTime(level) : StasisPost.smoothClock;
        int seed = ClientFields.deepSeed();
        JsonObject o = new JsonObject();
        o.addProperty("t", t);
        o.addProperty("deeps_time", Deeps.time(level));
        o.addProperty("deeps_real_time", Deeps.realTime(level));
        o.addProperty("seed", seed);
        o.addProperty("debug_deeps", StasisPost.DEBUG_DEEPS);
        JsonObject dd = new JsonObject();
        dd.addProperty("at", Deeps.at(level, bx, by, bz));
        dd.addProperty("wind_angle", Deeps.windAngle(seed));
        double[] drift = Deeps.drift(t, seed);
        JsonArray dr = new JsonArray();
        for (double d : drift) dr.add(d);
        dd.add("drift", dr);
        JsonArray near = new JsonArray();
        for (Deeps.Deep d : Deeps.near((float) p.x, (float) p.z, Deeps.time(level), seed)) {
            if (near.size() >= 32) break;
            JsonObject x = new JsonObject();
            x.addProperty("x", d.x());
            x.addProperty("z", d.z());
            x.addProperty("radius", d.radius());
            x.addProperty("strength", d.strength());
            x.addProperty("id", d.id());
            x.addProperty("puff", d.puff());
            x.addProperty("dist", Math.hypot(d.x() - p.x, d.z() - p.z));
            near.add(x);
        }
        dd.add("near", near);
        o.add("drifting_deeps", dd);
        JsonObject sd = new JsonObject();
        sd.addProperty("zone", StaticDeeps.zone(level, bx, by, bz));
        sd.addProperty("in_rift", StaticDeeps.inRift(level, bx, by, bz));
        sd.addProperty("gate_count", StaticDeeps.gateCount(seed));
        JsonArray rifts = new JsonArray();
        for (StaticDeeps.Deep d : StaticDeeps.near((float) p.x, (float) p.y, (float) p.z, 300, seed)) {
            JsonObject x = new JsonObject();
            x.addProperty("x", d.x());
            x.addProperty("y", d.y());
            x.addProperty("z", d.z());
            x.addProperty("radius", d.radius());
            x.addProperty("id", d.id());
            float[] core = StaticDeeps.core(d);
            if (core != null) {
                JsonArray c = new JsonArray();
                for (float v : core) c.add(v);
                x.add("core", c);
            }
            boolean gate = StaticDeeps.isGate(d);
            x.addProperty("gate", gate);
            if (gate) x.addProperty("destination", Gates.destination(d));
            x.addProperty("dist", Math.sqrt(p.distanceToSqr(d.x(), d.y(), d.z())));
            rifts.add(x);
        }
        sd.add("near", rifts);
        o.add("static_deeps", sd);
        Pockets.Weather w = Pockets.at(level, bx, by, bz);
        JsonObject po = new JsonObject();
        if (w != null) {
            po.addProperty("state", String.valueOf(w.state()));
            po.addProperty("density", w.density());
            po.addProperty("until_next", w.untilNext());
        }
        StaticDeeps.Deep strata = Pockets.strataAt(seed, bx, by, bz);
        po.addProperty("strata", strata == null ? null : "id " + strata.id() + " at " + strata.x() + "," + strata.y() + "," + strata.z());
        o.add("pocket", po);
        JsonObject st = new JsonObject();
        double[] ov = Storms.clientOverrideForClient();
        if (ov != null) {
            JsonArray a = new JsonArray();
            for (double v : ov) a.add(v);
            st.add("override", a);
        }
        st.addProperty("intensity", Storms.intensity(seed, ov, t));
        st.addProperty("warning", Storms.warning(seed, ov, t));
        st.addProperty("tau", Storms.tau(seed, ov, t));
        Storms.Storm next = Storms.next(seed, t);
        if (next != null) {
            st.addProperty("next_in_s", next.start() - t);
            st.addProperty("next_length_s", next.length());
        }
        st.addProperty("at", Storms.at(level, bx, by, bz));
        o.add("storms", st);
        return o;
    }

    // ---- 2.6

    static JsonObject suit(Minecraft mc) {
        Player pl = mc.player;
        JsonObject o = new JsonObject();
        JsonObject pieces = new JsonObject();
        for (EquipmentSlot s : SuitGauge.ARMOR) {
            ItemStack st = pl.getItemBySlot(s);
            JsonObject x = new JsonObject();
            x.addProperty("id", st.isEmpty() ? null : String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem())));
            x.addProperty("time_suit", st.getItem() instanceof TimeSuit);
            if (st.getItem() instanceof mods.eln.item.electricalinterface.IItemEnergyBattery b) {
                double e = b.getEnergy(st), max = b.getEnergyMax(st);
                x.addProperty("energy_J", e);
                x.addProperty("energy_max_J", max);
                x.addProperty("share", max > 0 ? e / max : 0);
            }
            if (st.getItem() instanceof TimeSuit t) {
                x.addProperty("custom_energy_J", TimeSuit.energy(st));
                x.addProperty("draw_W", t.stasisDraw(st));
            }
            pieces.add(s.getName(), x);
        }
        o.add("pieces", pieces);
        double[] sh = SuitGauge.share(pl);
        o.addProperty("suit_complete", sh[1] >= 4);
        o.addProperty("suit_pieces", (int) sh[1]);
        o.addProperty("suit_min_share", sh[1] > 0 ? sh[0] : null);
        o.addProperty("arm_suited", SuitArm.localPlayerSuited());
        return o;
    }

    // ---- 2.7

    static JsonObject subsystems(Minecraft mc) {
        JsonObject o = new JsonObject();
        JsonObject as = new JsonObject();
        as.addProperty("crack_at", AntiSound.crackAt);
        as.addProperty("strain", AntiSound.strain);
        as.addProperty("vol", AntiSound.vol);
        as.addProperty("hf", AntiSound.hf);
        as.addProperty("sag", AntiSound.sag);
        as.addProperty("applied_vol", AntiSound.appliedVol);
        as.addProperty("applied_hf", AntiSound.appliedHf);
        as.addProperty("filter", AntiSound.filter);
        as.addProperty("pitch", AntiSound.pitch());
        o.add("anti_sound", as);
        JsonObject rh = new JsonObject();
        rh.addProperty("playing", RiftHum.playing != null);
        rh.addProperty("target", RiftHum.target);
        rh.addProperty("progress", RiftHum.progress);
        rh.addProperty("source", RiftHum.sx + "," + RiftHum.sy + "," + RiftHum.sz);
        rh.addProperty("wind_target", RiftHum.windTarget);
        o.add("rift_hum", rh);
        JsonObject sm = new JsonObject();
        sm.addProperty("valid", StrataMask.valid);
        sm.addProperty("origin", StrataMask.ox + "," + StrataMask.oy + "," + StrataMask.oz);
        sm.addProperty("job_key", StrataMask.jobKey);
        var job = StrataMask.job;
        sm.addProperty("job", job == null ? "none" : String.valueOf(job.isDone()));
        o.add("strata_mask", sm);
        JsonObject fz = new JsonObject();
        fz.addProperty("frozen_sprites", FrozenSprites.frozenCount());
        fz.addProperty("generation", FrozenSprites.generation());
        o.add("frozen_sprites", fz);
        JsonObject br = new JsonObject();
        synchronized (BoltRenderer.LIVE) {
            br.addProperty("live", BoltRenderer.LIVE.size());
        }
        o.add("bolts", br);
        JsonArray tr = new JsonArray();
        for (String s : TowerRenderer.summary()) {
            if (tr.size() >= 64) break;
            tr.add(s);
        }
        o.add("tower_renderer", tr);
        JsonObject fl = new JsonObject();
        fl.addProperty("holders", FlashlightPost.holders.size());
        fl.addProperty("lights", FlashlightPost.lights.size());
        fl.addProperty("shaderpack", FlashlightPost.shaderPackInUse());
        o.add("flashlight", fl);
        return o;
    }

    // ---- 2.8 the post effect's data texture

    static final int[][] HEADLINE = {
        {16, 6, 0}, {22, 1, 1}, {23, 1, 2}, {24, 1, 3}, {25, 4, 4}, {29, 2, 5}, {31, 1, 6}, {216, 1, 7}, {218, 1, 8},
        {1312, 8, 9}, {1596, 1, 10}, {256, 1, 11}, {200, 8, 12}, {184, 4, 13}, {188, 8, 14}};
    static final String[] HEADLINE_NAMES = {"camera", "stops", "freeze", "count", "spawn_area", "clocks", "drifting_deeps",
        "view_distance", "static_deeps", "storm", "strain", "bolts", "eyes", "strata_mask", "pocket_periods"};

    static JsonObject postData() {
        JsonObject o = new JsonObject();
        if (StasisPost.texture == null || StasisPost.texture.getPixels() == null) {
            o.addProperty("status", "no texture yet");
            return o;
        }
        var img = StasisPost.texture.getPixels();
        int n = StasisPost.SLOTS;
        float[] d = new float[n];
        java.nio.ByteBuffer raw = java.nio.ByteBuffer.allocate(n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            int bits = net.minecraft.util.ARGB.toABGR(img.getPixel(i % StasisPost.WIDTH, i / StasisPost.WIDTH));   // (written with setPixelABGR)
            d[i] = Float.intBitsToFloat(bits);
            raw.putInt(bits);
        }
        for (int[] h : HEADLINE) {
            JsonArray a = new JsonArray();
            for (int k = 0; k < h[1]; k++) a.add(d[h[0] + k]);
            o.add(HEADLINE_NAMES[h[2]], a);
        }
        int count = Math.max(0, Math.min(13, (int) d[24]));
        String[] fieldNames = {"dx", "dy", "dz", "shown_radius", "collapse", "failing", "column", "seed", "age", "remnant_clock"};
        JsonArray fields = new JsonArray();
        for (int k = 0; k < count; k++) {
            JsonObject f = new JsonObject();
            for (int j = 0; j < 10; j++) f.addProperty(fieldNames[j], d[32 + 10 * k + j]);
            f.addProperty("amber", d[StasisPost.SHIELD_BASE + 4 * k]);
            f.addProperty("dropout", d[StasisPost.SHIELD_BASE + 4 * k + 1]);
            f.addProperty("side", d[StasisPost.SHIELD_BASE + 4 * k + 2]);
            JsonArray st = new JsonArray();
            for (int j = 0; j < 4; j++) st.add(d[StasisPost.STRETCH_BASE + 4 * k + j]);
            f.add("shown_stretch_eswn", st);
            fields.add(f);
        }
        o.add("fields", fields);
        int nd = Math.max(0, Math.min(StasisPost.MAX_DEEPS, (int) d[31]));
        JsonArray deeps = new JsonArray();
        for (int i = 0; i < nd; i++) {
            JsonArray a = new JsonArray();
            for (int j = 0; j < StasisPost.DEEP_STRIDE; j++) a.add(d[StasisPost.DEEP_BASE + StasisPost.DEEP_STRIDE * i + j]);
            deeps.add(a);
        }
        o.add("drifting_deep_blocks", deeps);
        int ns = Math.max(0, Math.min(8, (int) d[218]));
        JsonArray statics = new JsonArray();
        for (int i = 0; i < ns; i++) {
            JsonArray a = new JsonArray();
            for (int j = 0; j < 5; j++) a.add(d[220 + 5 * i + j]);
            statics.add(a);
        }
        o.add("static_deep_blocks", statics);
        o.addProperty("raw_b64", Base64.getEncoder().encodeToString(raw.array()));
        o.addProperty("raw_format", "4096 float32 little-endian, slot i = pixel (i % 1024, i / 1024)");
        return o;
    }

    // ---- 2.9 console and nearby towers

    static JsonObject console(Minecraft mc) {
        JsonObject o = new JsonObject();
        if (!(mc.gui.screen() instanceof ConsoleScreen c)) {
            o.addProperty("open", false);
            return o;
        }
        o.addProperty("open", true);
        o.addProperty("pos", c.pos.toShortString());
        o.addProperty("ticks", c.ticks);
        o.addProperty("armed_until", c.armedUntil);
        o.addProperty("pending", c.pending);
        o.addProperty("pending_until", c.pendingUntil);
        if (c.st != null) {
            o.add("state", ReportResponder.console(c.st.v()));
            o.addProperty("text", c.st.text());
        }
        return o;
    }

    static JsonObject towers(Minecraft mc) {
        JsonObject o = new JsonObject();
        o.addProperty("source", "client_synced");
        JsonArray a = new JsonArray();
        BlockPos bp = mc.player.blockPosition();
        int cx = bp.getX() >> 4, cz = bp.getZ() >> 4;
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -4; dz <= 4; dz++) {
                var ch = mc.level.getChunkSource().getChunk(cx + dx, cz + dz, false);
                if (ch == null) continue;
                for (BlockEntity be : ch.getBlockEntities().values()) {
                    if (!(be instanceof TowerBlockEntity t)) continue;
                    if (be.getBlockPos().distSqr(bp) > 64 * 64) continue;
                    a.add(ReportResponder.tower(t));
                    if (a.size() >= 16) break;
                }
            }
        o.add("towers", a);
        return o;
    }
}
