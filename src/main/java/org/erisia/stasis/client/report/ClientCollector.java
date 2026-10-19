package org.erisia.stasis.client.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generic client state for bug reports (notes/bugreport/SPEC.md 1). Client thread only. */
final class ClientCollector {
    private ClientCollector() {
    }

    static final int SNBT_CAP = 16 * 1024;
    static final int STACK_CAP = 4 * 1024;

    static JsonArray vec(Vec3 v) {
        JsonArray a = new JsonArray();
        a.add(v.x);
        a.add(v.y);
        a.add(v.z);
        return a;
    }

    static JsonArray pos(BlockPos p) {
        JsonArray a = new JsonArray();
        a.add(p.getX());
        a.add(p.getY());
        a.add(p.getZ());
        return a;
    }

    static String cap(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max) + "...[truncated " + (s.length() - max) + " chars]";
    }

    static String modVersion(String id) {
        return ModList.get().getModContainerById(id).map(c -> c.getModInfo().getVersion().toString()).orElse(null);
    }

    // ---- 1.1 versions and mods

    static JsonObject versions(Minecraft mc, JsonArray mods) {
        JsonObject o = new JsonObject();
        var v = SharedConstants.getCurrentVersion();
        o.addProperty("minecraft", v.name());
        o.addProperty("minecraft_id", v.id());
        o.addProperty("launched", mc.getLaunchedVersion());
        o.addProperty("neoforge", net.neoforged.neoforge.common.NeoForgeVersion.getVersion());
        o.addProperty("stasisdelvers", modVersion("stasisdelvers"));
        o.addProperty("eln", modVersion("eln"));
        o.addProperty("java", System.getProperty("java.version"));
        List<String> ids = new ArrayList<>();
        for (JsonElement e : mods) {
            JsonObject m = e.getAsJsonObject();
            ids.add(m.get("id").getAsString() + "@" + m.get("version").getAsString());
        }
        ids.sort(null);
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-1");
            o.addProperty("modlist_sha1", HexFormat.of().formatHex(d.digest(String.join("\n", ids).getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            o.addProperty("modlist_sha1", "error: " + e);
        }
        try {
            var marker = mc.gameDirectory.toPath().resolve("config/incognito-pack.txt");
            if (java.nio.file.Files.isRegularFile(marker) && java.nio.file.Files.size(marker) < 4096)
                o.addProperty("pack", java.nio.file.Files.readString(marker).strip());
        } catch (Exception e) {
            o.addProperty("pack", "error: " + e);
        }
        return o;
    }

    static JsonArray mods() {
        JsonArray a = new JsonArray();
        for (IModInfo m : ModList.get().getMods()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.getModId());
            o.addProperty("name", m.getDisplayName());
            o.addProperty("version", m.getVersion().toString());
            String jar = null;
            try {
                // the file name only (never getFilePath: absolute)
                jar = m.getOwningFile().getFile().getFileName();
            } catch (RuntimeException e) {
                jar = "?";
            }
            o.addProperty("jar", jar);
            a.add(o);
        }
        return a;
    }

    // ---- 1.4, 1.5 client, performance, options

    static JsonObject client(Minecraft mc) {
        JsonObject o = new JsonObject();
        o.addProperty("fps", mc.getFps());
        o.addProperty("frame_ns", mc.getFrameTimeNs());
        o.add("frame_pct", FrameTimes.percentiles());
        o.addProperty("gpu_util", mc.getGpuUtilization());
        Runtime rt = Runtime.getRuntime();
        JsonObject mem = new JsonObject();
        mem.addProperty("free_MB", rt.freeMemory() >> 20);
        mem.addProperty("total_MB", rt.totalMemory() >> 20);
        mem.addProperty("max_MB", rt.maxMemory() >> 20);
        o.add("memory", mem);
        var op = mc.options;
        JsonObject opts = new JsonObject();
        opts.addProperty("render_distance", op.renderDistance().get());
        opts.addProperty("effective_render_distance", op.getEffectiveRenderDistance());
        opts.addProperty("simulation_distance", op.simulationDistance().get());
        opts.addProperty("graphics_preset", String.valueOf(op.graphicsPreset().get()));
        opts.addProperty("framerate_limit", op.framerateLimit().get());
        opts.addProperty("vsync", op.enableVsync().get());
        opts.addProperty("fov", op.fov().get());
        opts.addProperty("gui_scale", op.guiScale().get());
        opts.addProperty("fullscreen", op.fullscreen().get());
        opts.addProperty("reduced_debug_info", op.reducedDebugInfo().get());
        opts.addProperty("window_width", mc.getWindow().getWidth());
        opts.addProperty("window_height", mc.getWindow().getHeight());
        o.add("options", opts);
        o.addProperty("language", op.languageCode);
        o.addProperty("singleplayer", mc.hasSingleplayerServer());
        o.addProperty("local_server", mc.isLocalServer());
        var sp = mc.getSingleplayerServer();
        if (sp != null) {
            o.addProperty("integrated_smoothed_tick_ms", sp.getCurrentSmoothedTickTime());
            o.addProperty("integrated_mspt", sp.getAverageTickTimeNanos() / 1e6);
        }
        JsonArray packs = new JsonArray();
        for (String p : op.resourcePacks) packs.add(p.startsWith("file/") ? p.substring(5) : p);
        o.add("resourcepacks", packs);
        try {
            o.addProperty("shaderpack", org.erisia.stasis.client.StasisReport.shaderPackInUse());
        } catch (Throwable t) {
            o.addProperty("shaderpack", "error: " + t);
        }
        return o;
    }

    // ---- 1.2, 1.6 player

    static JsonObject player(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getName().getString());
        o.addProperty("uuid", p.getUUID().toString());
        BlockPos bp = p.blockPosition();
        o.add("pos", vec(p.position()));
        o.add("block_pos", pos(bp));
        JsonArray rot = new JsonArray();
        rot.add(p.getYRot());
        rot.add(p.getXRot());
        o.add("rot", rot);
        o.addProperty("facing", p.getDirection().getName());
        o.add("motion", vec(p.getDeltaMovement()));
        o.addProperty("on_ground", p.onGround());
        if (p.isPassenger() && p.getVehicle() != null)
            o.addProperty("vehicle", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(p.getVehicle().getType())));
        o.addProperty("spectator", p.isSpectator());
        o.addProperty("creative", p.isCreative());
        Entity cam = mc.getCameraEntity();
        if (cam != null && cam != p) o.addProperty("camera", cam.getType() + " " + cam.getUUID());
        o.addProperty("dimension", level.dimension().identifier().toString());
        ChunkPos cp = ChunkPos.containing(bp);
        JsonObject chunk = new JsonObject();
        chunk.addProperty("x", cp.x());
        chunk.addProperty("z", cp.z());
        chunk.addProperty("section_y", SectionPos.blockToSectionCoord(bp.getY()));
        chunk.add("in_chunk", pos(new BlockPos(bp.getX() & 15, bp.getY() & 15, bp.getZ() & 15)));
        o.add("chunk", chunk);
        o.addProperty("biome", level.getBiome(bp).getRegisteredName());
        JsonObject light = new JsonObject();
        light.addProperty("sky", level.getBrightness(LightLayer.SKY, bp));
        light.addProperty("block", level.getBrightness(LightLayer.BLOCK, bp));
        var le = level.getChunkSource().getLightEngine();
        light.addProperty("sky_raw", le.getLayerListener(LightLayer.SKY).getLightValue(bp));
        light.addProperty("block_raw", le.getLayerListener(LightLayer.BLOCK).getLightValue(bp));
        light.addProperty("combined", level.getMaxLocalRawBrightness(bp));
        o.add("light", light);
        if (mc.gameMode != null) {
            o.addProperty("gamemode", String.valueOf(mc.gameMode.getPlayerMode()));
            o.addProperty("previous_gamemode", String.valueOf(mc.gameMode.getPreviousPlayerMode()));
        }
        JsonObject vit = new JsonObject();
        vit.addProperty("health", p.getHealth());
        vit.addProperty("max_health", p.getMaxHealth());
        vit.addProperty("absorption", p.getAbsorptionAmount());
        vit.addProperty("food", p.getFoodData().getFoodLevel());
        vit.addProperty("saturation", p.getFoodData().getSaturationLevel());
        vit.addProperty("air", p.getAirSupply());
        vit.addProperty("on_fire", p.isOnFire());
        vit.addProperty("powder_snow_frozen", p.isFullyFrozen());
        vit.addProperty("powder_snow_ticks", p.getTicksFrozen());
        vit.addProperty("xp_level", p.experienceLevel);
        o.add("vitals", vit);
        JsonArray eff = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject eo = new JsonObject();
            eo.addProperty("id", e.getEffect().getRegisteredName());
            eo.addProperty("amplifier", e.getAmplifier());
            eo.addProperty("duration", e.getDuration());
            eo.addProperty("ambient", e.isAmbient());
            eff.add(eo);
        }
        o.add("effects", eff);
        o.addProperty("tickrate", level.tickRateManager().tickrate());
        o.addProperty("tick_frozen", level.tickRateManager().isFrozen());
        return o;
    }

    // ---- 1.7 time and weather

    static JsonObject world(Minecraft mc) {
        ClientLevel level = mc.level;
        JsonObject o = new JsonObject();
        o.addProperty("game_time", level.getGameTime());
        JsonObject clock = new JsonObject();
        clock.addProperty("overworld", level.getOverworldClockTime());
        clock.addProperty("default", level.getDefaultClockTime());
        try {
            level.registryAccess().get(net.minecraft.world.timeline.Timelines.OVERWORLD_DAY)
                .ifPresent(t -> clock.addProperty("day", t.value().getPeriodCount(level.clockManager())));
        } catch (RuntimeException e) {
            clock.addProperty("day", "error: " + e);
        }
        o.add("clock", clock);
        JsonObject w = new JsonObject();
        w.addProperty("rain_level", level.getRainLevel(1f));
        w.addProperty("thunder_level", level.getThunderLevel(1f));
        w.addProperty("raining", level.isRaining());
        w.addProperty("thundering", level.isThundering());
        if (mc.player != null) w.addProperty("precipitation_here", String.valueOf(level.getPrecipitationAt(mc.player.blockPosition())));
        o.add("weather", w);
        return o;
    }

    // ---- 1.2 looked-at target

    static JsonObject look(Minecraft mc) {
        ClientLevel level = mc.level;
        HitResult hit = mc.hitResult;
        JsonObject o = new JsonObject();
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            o.addProperty("type", "block");
            BlockPos p = bh.getBlockPos();
            o.add("pos", pos(p));
            o.addProperty("face", bh.getDirection().getName());
            o.add("location", vec(bh.getLocation()));
            BlockState st = level.getBlockState(p);
            o.addProperty("state", st.toString());
            o.addProperty("block", String.valueOf(BuiltInRegistries.BLOCK.getKey(st.getBlock())));
            JsonObject props = new JsonObject();
            for (Property<?> pr : st.getProperties()) props.addProperty(pr.getName(), String.valueOf(st.getValue(pr)));
            o.add("properties", props);
            JsonArray tags = new JsonArray();
            st.typeHolder().tags().limit(32).forEach(t -> tags.add(t.location().toString()));
            o.add("tags", tags);
            o.addProperty("light_emission", st.getLightEmission());
            BlockEntity be = level.getBlockEntity(p);
            if (be != null) {
                JsonObject b = new JsonObject();
                b.addProperty("type", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
                b.addProperty("class", be.getClass().getName());
                try {
                    b.addProperty("snbt", cap(be.saveWithoutMetadata(level.registryAccess()).toString(), SNBT_CAP));
                } catch (RuntimeException e) {
                    b.addProperty("snbt_error", e.toString());
                }
                o.add("block_entity", b);
            }
        } else if (hit instanceof EntityHitResult eh) {
            o.addProperty("type", "entity");
            o.add("entity", entity(mc, eh.getEntity()));
        } else {
            o.addProperty("type", "miss");
        }
        if (!o.has("entity") && mc.crosshairPickEntity != null) o.add("crosshair_entity", entity(mc, mc.crosshairPickEntity));
        Entity cam = mc.getCameraEntity();
        if (cam != null) {
            HitResult fl = cam.pick(20.0, 0f, true);
            if (fl instanceof BlockHitResult fb && fl.getType() == HitResult.Type.BLOCK) {
                var fs = level.getFluidState(fb.getBlockPos());
                if (!fs.isEmpty()) {
                    JsonObject f = new JsonObject();
                    f.add("pos", pos(fb.getBlockPos()));
                    f.addProperty("state", fs.toString());
                    o.add("fluid", f);
                }
            }
        }
        return o;
    }

    static JsonObject entity(Minecraft mc, Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("type", String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())));
        o.addProperty("uuid", e.getUUID().toString());
        o.add("pos", vec(e.position()));
        if (e.getCustomName() != null) o.addProperty("custom_name", e.getCustomName().getString());
        o.addProperty("string", e.toString());
        if (e instanceof LivingEntity le) {
            o.addProperty("health", le.getHealth());
            o.addProperty("max_health", le.getMaxHealth());
        }
        if (e instanceof org.erisia.stasis.Statue s) {
            o.addProperty("statue_owner_name", s.syncedOwnerName());
            o.addProperty("statue_owner_id", s.syncedOwnerId());
        }
        // not other players' data (their inventory isn't ours to ship)
        if (!(e instanceof Player)) {
            try {
                TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, mc.level.registryAccess());
                e.saveWithoutId(out);
                o.addProperty("snbt", cap(out.buildResult().toString(), SNBT_CAP));
            } catch (RuntimeException ex) {
                o.addProperty("snbt_error", ex.toString());
            }
        }
        return o;
    }

    // ---- 1.9 open screen

    static JsonObject screen(Minecraft mc) {
        Screen s = mc.gui.screen();
        JsonObject o = new JsonObject();
        if (s == null) {
            o.addProperty("class", (String) null);
            return o;
        }
        o.addProperty("class", s.getClass().getName());
        o.addProperty("title", s.getTitle().getString());
        if (s instanceof AbstractContainerScreen<?> cs) {
            var menu = cs.getMenu();
            o.addProperty("menu_class", menu.getClass().getName());
            o.addProperty("container_id", menu.containerId);
            try {
                o.addProperty("menu_type", String.valueOf(BuiltInRegistries.MENU.getKey(menu.getType())));
            } catch (RuntimeException e) {
                o.addProperty("menu_type", "(none)");   // the player inventory menu has no type
            }
        }
        return o;
    }

    // ---- 1.8 inventory

    static JsonObject inventory(Minecraft mc) {
        LocalPlayer p = mc.player;
        Inventory inv = p.getInventory();
        JsonObject o = new JsonObject();
        o.addProperty("selected", inv.getSelectedSlot());
        JsonArray items = new JsonArray();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty()) items.add(stack(mc, String.valueOf(i), st));
        }
        o.add("items", items);
        JsonObject armor = new JsonObject();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.BODY}) {
            ItemStack st = p.getItemBySlot(s);
            if (!st.isEmpty()) armor.add(s.getName(), stack(mc, s.getName(), st));
        }
        o.add("armor", armor);
        ItemStack off = p.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!off.isEmpty()) o.add("offhand", stack(mc, "offhand", off));
        ItemStack carried = p.containerMenu == null ? ItemStack.EMPTY : p.containerMenu.getCarried();
        if (!carried.isEmpty()) o.add("carried", stack(mc, "carried", carried));
        if (p.containerMenu != null && p.containerMenu != p.inventoryMenu) {
            JsonObject m = new JsonObject();
            m.addProperty("class", p.containerMenu.getClass().getName());
            m.addProperty("container_id", p.containerMenu.containerId);
            o.add("open_menu", m);
        }
        return o;
    }

    static JsonObject stack(Minecraft mc, String slot, ItemStack st) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", slot);
        o.addProperty("id", String.valueOf(BuiltInRegistries.ITEM.getKey(st.getItem())));
        o.addProperty("count", st.getCount());
        JsonElement comp;
        try {
            comp = ItemStack.OPTIONAL_CODEC.encodeStart(mc.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), st)
                .getOrThrow(IllegalStateException::new);
        } catch (RuntimeException e) {
            comp = new com.google.gson.JsonPrimitive(String.valueOf(st.getComponentsPatch()));
        }
        String s = comp.toString();
        if (s.length() > STACK_CAP) {
            o.addProperty("components", s.substring(0, STACK_CAP));
            o.addProperty("truncated", true);
        } else o.add("components", comp);
        return o;
    }

    // ---- 1.3 F3 text

    static String f3(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        boolean reduced = mc.showOnlyReducedInfo();
        ClientLevel level = mc.level;
        BlockPos bp = mc.player.blockPosition();
        var chunk = level.getChunk(bp.getX() >> 4, bp.getZ() >> 4);
        for (Map.Entry<Identifier, DebugScreenEntry> e : DebugScreenEntries.allEntries().entrySet()) {
            DebugScreenEntry entry = e.getValue();
            if (entry == null || entry.getClass().getSimpleName().equals("DebugEntryNoop")) continue;
            List<String> lines = new ArrayList<>();
            try {
                if (!entry.isAllowed(reduced)) continue;
                Map<Identifier, List<String>> groups = new LinkedHashMap<>();
                entry.display(new DebugScreenDisplayer() {
                    @Override
                    public void addPriorityLine(String line) {
                        lines.add(line);
                    }

                    @Override
                    public void addLine(String line) {
                        lines.add(line);
                    }

                    @Override
                    public void addToGroup(Identifier group, Collection<String> ls) {
                        groups.computeIfAbsent(group, k -> new ArrayList<>()).addAll(ls);
                    }

                    @Override
                    public void addToGroup(Identifier group, String line) {
                        groups.computeIfAbsent(group, k -> new ArrayList<>()).add(line);
                    }
                }, level, chunk, null);
                groups.forEach((g, ls) -> {
                    lines.add("(" + g + ")");
                    lines.addAll(ls);
                });
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError v) throw v;
                lines.add("(error: " + t + ")");
            }
            if (lines.isEmpty()) continue;
            sb.append('[').append(e.getKey()).append("]\n");
            for (String l : lines) sb.append(l).append('\n');
            if (sb.length() > 64 * 1024) {
                sb.setLength(64 * 1024);
                sb.append("\n...[truncated]\n");
                break;
            }
        }
        return sb.toString();
    }

    // ---- 1.10 chat

    static String chat(Minecraft mc, boolean systemOnly) {
        List<GuiMessage> all = mc.gui.hud.getChat().allMessages;
        List<String> lines = new ArrayList<>();
        // newest first: the first 50 (those we may include), written oldest first
        for (GuiMessage m : List.copyOf(all)) {
            if (lines.size() >= 50) break;
            if (systemOnly && m.source() == GuiMessageSource.PLAYER) continue;
            lines.add("[" + m.source() + "] " + m.content().getString());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = lines.size() - 1; i >= 0; i--) sb.append(lines.get(i)).append('\n');
        String s = sb.toString();
        return s.length() > 32 * 1024 ? s.substring(s.length() - 32 * 1024) : s;
    }
}
