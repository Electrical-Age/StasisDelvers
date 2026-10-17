package org.erisia.stasis.client.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The Electrical Age part of a bug report (notes/bugreport/SPEC.md 3): what the client knows about the looked-at node
 * block and an open EA GUI. Values the renders hold are read by reflection (best effort). Client thread only.
 */
final class ElnReport {
    private ElnReport() {
    }

    static final int MAX_FIELDS = 64, MAX_STRING = 256;

    static JsonObject collect(Minecraft mc) {
        JsonObject o = new JsonObject();
        o.addProperty("version", ClientCollector.modVersion("eln"));
        o.addProperty("client_node_entities", mods.eln.node.NodeBlockEntity.clientList.size());
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            BlockEntity be = mc.level.getBlockEntity(bh.getBlockPos());
            if (be != null && be.getClass().getName().startsWith("mods.eln.")) o.add("look", look(be, bh));
        }
        Screen s = mc.gui.screen();
        if (s instanceof mods.eln.client.gui.ElnScreen es) o.add("gui", gui(es));
        return o;
    }

    static JsonObject look(BlockEntity be, BlockHitResult bh) {
        JsonObject o = new JsonObject();
        o.addProperty("class", be.getClass().getName());
        o.addProperty("hit_face", bh.getDirection().getName());
        if (be instanceof mods.eln.node.NodeBlockEntity nbe) {
            o.addProperty("redstone", String.valueOf(field(nbe, mods.eln.node.NodeBlockEntity.class, "redstone")));
            o.addProperty("last_light", String.valueOf(field(nbe, mods.eln.node.NodeBlockEntity.class, "lastLight")));
        }
        if (be instanceof mods.eln.node.six.SixNodeEntity six) {
            o.addProperty("side_mask", six.getSideMask());
            JsonArray faces = new JsonArray();
            int hitFace = -1;
            try {
                hitFace = mods.eln.misc.Direction.fromEnumFacing(bh.getDirection()).ordinal();
            } catch (RuntimeException e) {
                // (left -1)
            }
            for (int i = 0; i < 6; i++) {
                JsonObject f = new JsonObject();
                f.addProperty("face", i);
                short id = six.elementRenderIdList[i];
                f.addProperty("id", id);
                var r = six.elementRenderList[i];
                if (i == hitFace) f.addProperty("crosshair", true);
                if (id == 0 && r == null) {
                    faces.add(f);
                    continue;
                }
                if (r == null) {
                    // serverPublishUnserialize stops at the first render that isn't ported: a porting bug signal
                    f.addProperty("render", "missing");
                } else {
                    f.addProperty("render", r.getClass().getSimpleName());
                    f.addProperty("descriptor", r.sixNodeDescriptor == null ? null : r.sixNodeDescriptor.name);
                    f.addProperty("side", String.valueOf(r.side));
                    f.addProperty("front", String.valueOf(r.front));
                    f.addProperty("burnt", r.burnt);
                    f.addProperty("connected_side", String.valueOf(r.connectedSide));
                    f.add("values", values(r, mods.eln.node.six.SixNodeElementRender.class));
                }
                faces.add(f);
            }
            o.add("six_faces", faces);
        }
        if (be instanceof mods.eln.node.transparent.TransparentNodeEntity tn) {
            o.addProperty("render_id", tn.elementRenderId);
            var r = tn.elementRender;
            if (r == null) o.addProperty("render", tn.elementRenderId == 0 ? null : "missing");
            else {
                o.addProperty("render", r.getClass().getSimpleName());
                o.addProperty("descriptor", r.transparentNodedescriptor == null ? null : r.transparentNodedescriptor.name);
                o.addProperty("front", String.valueOf(r.front));
                o.addProperty("grounded", r.grounded);
                o.addProperty("burnt", r.burnt);
                o.add("values", values(r, mods.eln.node.transparent.TransparentNodeElementRender.class));
            }
        }
        return o;
    }

    /** An open EA screen: its class and menu, and the render it reads from (a field holding one). */
    static JsonObject gui(mods.eln.client.gui.ElnScreen s) {
        JsonObject o = new JsonObject();
        o.addProperty("screen", s.getClass().getName());
        o.addProperty("menu", s.getMenu().getClass().getName());
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        JsonArray renders = new JsonArray();
        for (Object holder : new Object[]{s, s.getMenu()}) {
            for (Class<?> c = holder.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (var f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(holder);
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        continue;
                    }
                    if (v == null || seen.containsKey(v)) continue;
                    Class<?> stop = v instanceof mods.eln.node.six.SixNodeElementRender ? mods.eln.node.six.SixNodeElementRender.class
                        : v instanceof mods.eln.node.transparent.TransparentNodeElementRender ? mods.eln.node.transparent.TransparentNodeElementRender.class : null;
                    if (stop == null) continue;
                    seen.put(v, true);
                    JsonObject r = new JsonObject();
                    r.addProperty("field", c.getSimpleName() + "." + f.getName());
                    r.addProperty("class", v.getClass().getName());
                    r.add("values", values(v, stop));
                    renders.add(r);
                }
            }
        }
        o.add("renders", renders);
        return o;
    }

    /** Every non-static primitive, String or enum field of o's class, up to (not including) stop. */
    static JsonObject values(Object o, Class<?> stop) {
        JsonObject out = new JsonObject();
        int n = 0;
        for (Class<?> c = o.getClass(); c != null && c != stop && c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (!(t.isPrimitive() || t == String.class || t.isEnum())) continue;
                if (n++ >= MAX_FIELDS) {
                    out.addProperty("_truncated", true);
                    return out;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    String key = out.has(f.getName()) ? c.getSimpleName() + "." + f.getName() : f.getName();
                    if (v instanceof Number num) out.addProperty(key, num);
                    else if (v instanceof Boolean b) out.addProperty(key, b);
                    else out.addProperty(key, v == null ? null : ClientCollector.cap(String.valueOf(v), MAX_STRING));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    out.addProperty(f.getName(), "error: " + e.getClass().getSimpleName());
                }
            }
        }
        return out;
    }

    static Object field(Object o, Class<?> owner, String name) {
        try {
            var f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "error: " + e.getClass().getSimpleName();
        }
    }
}
