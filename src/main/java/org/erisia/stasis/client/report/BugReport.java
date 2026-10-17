package org.erisia.stasis.client.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.StasisNet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The bug-report key (notes/bugreport/SPEC.md): F7 (Controls: "Send bug report") takes a screenshot, collects the
 * client's state, asks the server for its view (StasisNet.ReportQuery, 3 s), then packages a zip in
 * &lt;game&gt;/bugreports/pending off-thread and says so in chat. Shift+F7 asks for a one-line note first. Uploading is
 * manual for now (Uploader.NONE: post the zip in Discord, then /bugreport sent). One report at a time; 5 s between.
 * Every collector is caught on its own and lands in "errors" instead of breaking the report or the game.
 */
public final class BugReport {
    private BugReport() {
    }

    public static final KeyMapping.Category CAT = new KeyMapping.Category(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "keys"));
    public static final KeyMapping KEY = new KeyMapping("key.stasisdelvers.bug_report", InputConstants.KEY_F7, CAT);

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();
    static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    static final long SERVER_WAIT_MS = 3000, SHOT_WAIT_MS = 6000, COOLDOWN_MS = 5000;
    static final int REPORT_SECTION_CAP = 256 * 1024;
    /** When this game session started (the class loads at mod construction): crash reports newer than this are included. */
    static final long SESSION_START = System.currentTimeMillis();

    /** Option B/A later: set an uploader here. */
    public static volatile Uploader uploader = Uploader.NONE;

    /** The report in flight (client thread), or null. */
    private static Report cur;
    private static long cooldownUntil;
    private static int nonces = ThreadLocalRandom.current().nextInt(1 << 20);
    private static boolean tmpCleaned;
    /** The server answers one query per player per 10 s (ReportResponder): don't ask sooner, say so instead. */
    private static long lastQuery = Long.MIN_VALUE / 2;

    static final class Report {
        final String id;
        final Path root, tmp;
        final Redactor redactor;
        final JsonObject json = new JsonObject();
        final JsonArray errors = new JsonArray();
        final int nonce;
        final long started;
        String f3, chat;
        boolean chatIncluded;
        String serverStatus;
        volatile String serverJson;
        /** 0: being taken, 1: written, 2: failed or timed out */
        volatile int shot;
        volatile String shotNote = "pending";
        boolean wantNote, noteOpened, noteDone;
        String note;
        boolean packaging;
        boolean shotIncluded;

        Report(String id, Path root, Redactor redactor, int nonce) {
            this.id = id;
            this.root = root;
            this.tmp = Packager.tmp(root).resolve(id);
            this.redactor = redactor;
            this.nonce = nonce;
            this.started = Util.getMillis();
        }
    }

    /** From StasisClient: the key, its handlers, the frame-time ring, the client command and the server's replies. */
    public static void init(IEventBus modBus) {
        modBus.addListener(RegisterKeyMappingsEvent.class, e -> {
            e.registerCategory(CAT);
            e.register(KEY);
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, BugReport::tick);
        // vanilla only feeds key mappings with no screen open; reports are wanted most in GUIs (Pre: an EA screen's text
        // field ate F7 before Post: AdventurAgent)
        NeoForge.EVENT_BUS.addListener(ScreenEvent.KeyPressed.Pre.class, e -> {
            if (e.getScreen() instanceof NoteScreen) return;
            if (KEY.matches(e.getKeyEvent())) {
                request(Minecraft.getInstance().hasShiftDown());
                e.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, e -> FrameTimes.frame());
        NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, e -> registerCommand(e));
        StasisNet.reportSink = BugReport::reply;
    }

    // ---- trigger

    static void tick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        while (KEY.consumeClick()) request(mc.hasShiftDown());
        Report r = cur;
        if (r == null || r.packaging) return;
        try {
            poll(mc, r);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            fail(mc, r, t);
        }
    }

    static void request(boolean withNote) {
        Minecraft mc = Minecraft.getInstance();
        if (cur != null) return;
        if (Util.getMillis() < cooldownUntil) {
            actionBar(mc, "Bug report: just made one, wait a few seconds");
            return;
        }
        if (mc.level == null || mc.player == null) {
            say(mc, Component.literal("Bug report: join a world first.").withStyle(ChatFormatting.YELLOW));
            return;
        }
        Report r = null;
        try {
            r = start(mc, withNote);
            cur = r;
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            StasisDelvers.LOG.error("Bug report failed to start", t);
            if (r != null) Packager.deleteTree(r.tmp);
            cur = null;
            cooldownUntil = Util.getMillis() + COOLDOWN_MS;
            say(mc, Component.literal("Bug report failed: " + t).withStyle(ChatFormatting.RED));
        }
    }

    static String id(Minecraft mc) {
        String name = mc.player.getName().getString().replaceAll("[^A-Za-z0-9_]", "");
        if (name.isEmpty()) name = "player";
        return "incognito-" + FILE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)) + "-" + name + "-"
            + String.format("%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }

    static List<String> gameDirs(Minecraft mc) {
        List<String> l = new ArrayList<>();
        l.add(mc.gameDirectory.getAbsolutePath());
        l.add(mc.gameDirectory.toPath().toAbsolutePath().normalize().toString());
        try {
            l.add(mc.gameDirectory.toPath().toRealPath().toString());
        } catch (IOException | RuntimeException e) {
            // (no real path: the absolute forms only)
        }
        return l;
    }

    static Redactor redactor(Minecraft mc) {
        var server = mc.getCurrentServer();
        return new Redactor(gameDirs(mc), server == null ? null : server.ip, System.getProperty("user.name"));
    }

    static Report start(Minecraft mc, boolean withNote) throws IOException {
        Path root = mc.gameDirectory.toPath().resolve("bugreports");
        if (!tmpCleaned) {
            tmpCleaned = true;
            Packager.cleanTmp(root);
        }
        Report r = new Report(id(mc), root, redactor(mc), ++nonces);
        Files.createDirectories(r.tmp);
        r.wantNote = withNote;
        // 1. the screenshot first (the frame as the player sees it, GUI included; before any message about the report)
        screenshot(mc, r);
        // 2. the client's state, now (each part on its own)
        JsonObject o = r.json;
        o.addProperty("schema", 1);
        o.addProperty("created_utc", DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        o.addProperty("report_id", r.id);
        o.add("player_note", null);
        JsonArray mods = new JsonArray();
        try {
            mods.addAll(ClientCollector.mods());
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            r.errors.add("mods: " + t);
        }
        section(r, "versions", () -> ClientCollector.versions(mc, mods));
        section(r, "mods", () -> mods);
        section(r, "client", () -> ClientCollector.client(mc));
        section(r, "player", () -> ClientCollector.player(mc));
        section(r, "world", () -> ClientCollector.world(mc));
        section(r, "look", () -> ClientCollector.look(mc));
        section(r, "screen", () -> ClientCollector.screen(mc));
        section(r, "inventory", () -> ClientCollector.inventory(mc));
        section(r, "stasis", () -> org.erisia.stasis.client.StasisReport.collect(mc));
        section(r, "eln", () -> ElnReport.collect(mc));
        try {
            r.f3 = ClientCollector.f3(mc);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            r.errors.add("f3: " + t);
        }
        if (ReportConfig.includeChat()) {
            try {
                r.chat = ClientCollector.chat(mc, ReportConfig.chatSystemOnly());
                r.chatIncluded = true;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError v) throw v;
                r.errors.add("chat: " + t);
            }
        }
        // 3. the server's view
        try {
            var conn = mc.getConnection();
            if (conn != null && conn.hasChannel(StasisNet.ReportQuery.TYPE) && Util.getMillis() - lastQuery < 10_500)
                r.serverStatus = "rate_limited (one server query per 10 s)";
            else if (conn != null && conn.hasChannel(StasisNet.ReportQuery.TYPE)) {
                lastQuery = Util.getMillis();
                Optional<net.minecraft.core.BlockPos> look = Optional.empty();
                byte face = -1;
                if (mc.hitResult instanceof BlockHitResult bh && mc.hitResult.getType() == HitResult.Type.BLOCK) {
                    look = Optional.of(bh.getBlockPos());
                    face = (byte) bh.getDirection().get3DDataValue();
                }
                ClientPacketDistributor.sendToServer(new StasisNet.ReportQuery(r.nonce, look, face));
                r.serverStatus = "pending";
            } else r.serverStatus = "no_channel";
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            r.serverStatus = "error: " + t;
        }
        return r;
    }

    static void section(Report r, String name, Supplier<JsonElement> s) {
        try {
            JsonElement e = s.get();
            String str = e == null ? "null" : e.toString();
            if (str.length() > REPORT_SECTION_CAP) {
                JsonObject t = new JsonObject();
                t.addProperty("truncated", true);
                t.addProperty("chars", str.length());
                t.addProperty("head", str.substring(0, REPORT_SECTION_CAP));
                e = t;
            }
            r.json.add(name, e);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            StasisDelvers.LOG.warn("Bug report section {} failed", name, t);
            r.errors.add(name + ": " + t);
            JsonObject err = new JsonObject();
            err.addProperty("error", t.toString());
            r.json.add(name, err);
        }
    }

    /** Pick a downscale that keeps the width near 1920 and divides both sides (takeScreenshot throws otherwise). */
    static int downscale(int w, int h) {
        if (w <= 1920) return 1;
        for (int f = 2; f <= 4; f++) if (w % f == 0 && h % f == 0 && w / f <= 2200) return f;
        for (int f = 2; f <= 4; f++) if (w % f == 0 && h % f == 0) return f;
        return 1;
    }

    static void screenshot(Minecraft mc, Report r) {
        try {
            RenderTarget target = mc.gameRenderer.mainRenderTarget();
            int f = downscale(target.width, target.height);
            Path file = r.tmp.resolve("screenshot.png");
            Screenshot.takeScreenshot(target, f, img -> {
                try {
                    Util.ioPool().execute(() -> {
                        try (img) {
                            img.writeToFile(file);
                            r.shotNote = "ok";
                            r.shot = 1;
                        } catch (Throwable t) {
                            r.shotNote = "failed: " + t;
                            r.shot = 2;
                        }
                    });
                } catch (Throwable t) {
                    img.close();
                    r.shotNote = "failed: " + t;
                    r.shot = 2;
                }
            });
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError v) throw v;
            r.shotNote = "failed: " + t;
            r.shot = 2;
        }
    }

    /** The server's answer (client thread: payload handlers run on the main thread). */
    static void reply(StasisNet.ReportState s) {
        Report r = cur;
        if (r != null && s.nonce() == r.nonce && !r.packaging) r.serverJson = s.json();
    }

    // ---- waiting: screenshot, note, server reply

    static void poll(Minecraft mc, Report r) {
        long now = Util.getMillis();
        if (r.shot == 0) {
            if (now - r.started < SHOT_WAIT_MS) return;
            r.shotNote = "timeout";
            r.shot = 2;
        }
        // the screenshot is taken: the note screen may show now
        if (r.wantNote && !r.noteOpened) {
            r.noteOpened = true;
            NoteScreen ns = new NoteScreen(text -> {
                r.note = text;
                r.noteDone = true;
            });
            if (mc.gui.screen() == null) mc.gui.setScreen(ns);
            else mc.gui.pushScreenLayer(ns);   // over the open GUI (the console, an EA screen), back to it after
        }
        if (r.wantNote && !r.noteDone) return;
        boolean serverDone = r.serverJson != null || !"pending".equals(r.serverStatus) || now - r.started > SERVER_WAIT_MS;
        if (!serverDone) return;
        finish(mc, r);
    }

    static void finish(Minecraft mc, Report r) {
        r.packaging = true;
        JsonObject o = r.json;
        // the server part
        JsonElement server;
        String status;
        if (r.serverJson != null) {
            status = "ok";
            try {
                server = JsonParser.parseString(r.serverJson);
            } catch (RuntimeException e) {
                JsonObject s = new JsonObject();
                s.addProperty("status", "bad_json");
                s.addProperty("raw", ClientCollector.cap(r.serverJson, 65536));
                server = s;
            }
        } else {
            status = "pending".equals(r.serverStatus) ? "timeout" : r.serverStatus;
            JsonObject s = new JsonObject();
            s.addProperty("status", status);
            server = s;
        }
        o.add("server", server);
        o.addProperty("screenshot", r.shotNote);
        if (r.note != null) o.addProperty("player_note", r.note.length() > NoteScreen.MAX ? r.note.substring(0, NoteScreen.MAX) : r.note);
        o.addProperty("chat_included", r.chatIncluded);
        o.add("errors", r.errors);
        // redact every string in the report (mod-provided strings are untrusted too), then the texts
        Redactor red = r.redactor;
        String json = GSON.toJson(redact(o, red));
        String f3 = r.f3 == null ? null : red.redact(r.f3);
        String chat = r.chat == null ? null : red.redact(r.chat);
        boolean shotOk = r.shot == 1;
        r.shotIncluded = shotOk;
        String serverStatus = status;
        Path gameDir = mc.gameDirectory.toPath();
        Util.ioPool().execute(() -> {
            Path zip = null;
            String warn = null;
            Throwable err = null;
            try {
                Map<String, byte[]> e = Packager.entries();
                e.put("report.json", json.getBytes(StandardCharsets.UTF_8));
                if (f3 != null) e.put("f3.txt", f3.getBytes(StandardCharsets.UTF_8));
                try {
                    e.put("log.txt", Packager.log(gameDir, red).getBytes(StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    e.put("log.txt", ("(could not read the log: " + ex + ")\n").getBytes(StandardCharsets.UTF_8));
                }
                if (chat != null) e.put("chat.txt", chat.getBytes(StandardCharsets.UTF_8));
                try {
                    String crash = Packager.crash(gameDir, SESSION_START, red);
                    if (crash != null) e.put("crash.txt", crash.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    // (no crash report then)
                }
                Path shot = shotOk ? r.tmp.resolve("screenshot.png") : null;
                Path z = Packager.zip(r.root, r.id, e, shot);
                // over 8 MB (Discord's limit is 10): the screenshot at half size, twice at most
                for (int i = 0; i < 2 && shot != null && Files.size(z) > Packager.ZIP_MAX; i++) {
                    halve(shot);
                    z = Packager.zip(r.root, r.id, e, shot);
                }
                zip = Packager.publish(r.root, z);
                warn = Packager.retain(r.root);
            } catch (Throwable t) {
                err = t;
            } finally {
                Packager.deleteTree(r.tmp);
            }
            Path fz = zip;
            String fw = warn;
            Throwable fe = err;
            mc.execute(() -> done(mc, r, fz, fw, fe, serverStatus));
        });
    }

    static void halve(Path png) throws IOException {
        try (var in = Files.newInputStream(png); var img = com.mojang.blaze3d.platform.NativeImage.read(in)) {
            int w = Math.max(1, img.getWidth() / 2), h = Math.max(1, img.getHeight() / 2);
            try (var small = new com.mojang.blaze3d.platform.NativeImage(w, h, false)) {
                img.resizeSubRectTo(0, 0, img.getWidth(), img.getHeight(), small);
                small.writeToFile(png);
            }
        }
    }

    static JsonElement redact(JsonElement e, Redactor r) {
        if (e == null || e.isJsonNull()) return e;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            return p.isString() ? new JsonPrimitive(r.redact(p.getAsString())) : p;
        }
        if (e.isJsonArray()) {
            JsonArray a = new JsonArray();
            for (JsonElement x : e.getAsJsonArray()) a.add(redact(x, r));
            return a;
        }
        JsonObject o = new JsonObject();
        for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) o.add(r.redact(x.getKey()), redact(x.getValue(), r));
        return o;
    }

    // ---- done

    static void done(Minecraft mc, Report r, Path zip, String warn, Throwable err, String serverStatus) {
        if (cur == r) cur = null;
        cooldownUntil = Util.getMillis() + COOLDOWN_MS;
        if (err != null || zip == null) {
            StasisDelvers.LOG.error("Bug report {} failed", r.id, err);
            say(mc, Component.literal("Bug report failed: " + err).withStyle(ChatFormatting.RED));
            return;
        }
        StasisDelvers.LOG.info("Bug report saved: {}", zip.getFileName());
        String name = zip.getFileName().toString();
        MutableComponent file = Component.literal(name).withStyle(ChatFormatting.UNDERLINE)
            .withStyle(s -> s.withClickEvent(new ClickEvent.OpenFile(zip.toAbsolutePath())));
        MutableComponent folder = Component.literal("(open folder)").withStyle(ChatFormatting.UNDERLINE)
            .withStyle(s -> s.withClickEvent(new ClickEvent.OpenFile(Packager.pending(r.root).toAbsolutePath())));
        MutableComponent sent = Component.literal("/bugreport sent").withStyle(ChatFormatting.UNDERLINE)
            .withStyle(s -> s.withClickEvent(new ClickEvent.SuggestCommand("/bugreport sent " + name)));
        String parts = (r.shotIncluded ? "screenshot, " : "") + "game log tail, " + (r.chatIncluded ? "last 50 chat lines, " : "") + "inventory";
        MutableComponent msg = Component.literal("Bug report saved: ").append(file).append(" ").append(folder)
            .append(Component.literal(" - includes " + parts + "."));
        if (!"ok".equals(serverStatus)) msg.append(Component.literal(" (server info unavailable: " + serverStatus + ")").withStyle(ChatFormatting.GRAY));
        say(mc, msg);
        if (uploader.enabled()) upload(mc, r, zip);
        else say(mc, Component.literal("Please post it in the Discord bug channel, then run ").append(sent).append(Component.literal(".")));
        if (warn != null) say(mc, Component.literal(warn).withStyle(ChatFormatting.YELLOW));
    }

    /** (Not used yet: Uploader.NONE.) Upload, then move the zip to sent/ with its receipt. */
    static void upload(Minecraft mc, Report r, Path zip) {
        try {
            uploader.upload(zip, r.id, res -> {
                String msg;
                if (res.receiptId() != null) {
                    try {
                        Path dest = Packager.sent(r.root).resolve(zip.getFileName());
                        Files.createDirectories(dest.getParent());
                        Files.move(zip, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        Files.writeString(dest.resolveSibling(r.id + ".receipt.txt"), res.receiptId() + "\n");
                    } catch (IOException e) {
                        StasisDelvers.LOG.warn("Bug report {}: uploaded but not moved to sent/", r.id, e);
                    }
                    msg = "Bug report uploaded (id " + res.receiptId() + ")";
                } else msg = "Upload failed: " + res.failure() + " (kept in bugreports/pending)";
                mc.execute(() -> say(mc, Component.literal(msg)));
            });
        } catch (RuntimeException e) {
            say(mc, Component.literal("Upload failed: " + e + " (kept in bugreports/pending)"));
        }
    }

    static void fail(Minecraft mc, Report r, Throwable t) {
        StasisDelvers.LOG.error("Bug report {} failed", r.id, t);
        if (cur == r) cur = null;
        cooldownUntil = Util.getMillis() + COOLDOWN_MS;
        Packager.deleteTree(r.tmp);
        say(mc, Component.literal("Bug report failed: " + t).withStyle(ChatFormatting.RED));
    }

    static void say(Minecraft mc, Component c) {
        try {
            mc.gui.hud.getChat().addClientSystemMessage(c);
        } catch (RuntimeException e) {
            StasisDelvers.LOG.info("{}", c.getString());
        }
    }

    static void actionBar(Minecraft mc, String s) {
        try {
            mc.gui.hud.setOverlayMessage(Component.literal(s), false);
        } catch (RuntimeException e) {
            // (no HUD)
        }
    }

    // ---- /bugreport sent <name|all>, /bugreport list

    static void registerCommand(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("bugreport")
            .then(Commands.literal("sent")
                .executes(c -> sent(c.getSource(), "all"))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .suggests((c, b) -> {
                        b.suggest("all");
                        for (String n : Packager.pendingNames(root())) b.suggest(n);
                        return b.buildFuture();
                    })
                    .executes(c -> sent(c.getSource(), StringArgumentType.getString(c, "name").strip()))))
            .then(Commands.literal("list").executes(c -> {
                List<String> p = Packager.pendingNames(root());
                c.getSource().sendSuccess(() -> Component.literal(p.isEmpty() ? "No bug reports waiting in bugreports/pending."
                    : p.size() + " waiting in bugreports/pending: " + String.join(", ", p)), false);
                return p.size();
            })));
    }

    static Path root() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("bugreports");
    }

    static int sent(CommandSourceStack src, String which) {
        try {
            List<String> moved = Packager.markSent(root(), which);
            if (moved.isEmpty()) {
                src.sendFailure(Component.literal("No pending bug report " + ("all".equals(which) ? "" : "named " + which + " ") + "in bugreports/pending."));
                return 0;
            }
            src.sendSuccess(() -> Component.literal("Marked as sent: " + String.join(", ", moved) + ". Thanks!"), false);
            return moved.size();
        } catch (IOException | RuntimeException ex) {
            src.sendFailure(Component.literal("Could not move the report: " + ex));
            return 0;
        }
    }
}
