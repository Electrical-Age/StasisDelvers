package org.erisia.stasis.client.report;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config (stasisdelvers-client.toml): bug reports, and how far rifts are drawn. Read at report time; defaults until the file is loaded. */
public final class ReportConfig {
    private ReportConfig() {
    }

    public static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue INCLUDE_CHAT;
    static final ModConfigSpec.BooleanValue CHAT_SYSTEM_ONLY;
    static final ModConfigSpec.BooleanValue INCLUDE_POST_DATA;
    static final ModConfigSpec.IntValue DISTANT_RIFTS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("bugReport");
        INCLUDE_CHAT = b.comment("Bug reports (F7) include the last 50 chat lines. They can hold other players' messages, whispers too.")
            .define("includeChat", true);
        CHAT_SYSTEM_ONLY = b.comment("With includeChat: only system lines (server and client messages), no player chat.")
            .define("chatSystemOnly", false);
        INCLUDE_POST_DATA = b.comment("Bug reports include a summary of the stasis effect's data texture (small).")
            .define("includePostData", true);
        b.pop();
        b.push("view");
        DISTANT_RIFTS = b.comment("The farthest (blocks) rifts are drawn: they show as far as terrain does (the render distance, or",
                "Distant Horizons' if installed), at least 200 and at most this. Lower it on a weak machine.")
            .defineInRange("distantRifts", 500, 0, 500);
        b.pop();
        SPEC = b.build();
    }

    static boolean includeChat() {
        return get(INCLUDE_CHAT, true);
    }

    static boolean chatSystemOnly() {
        return get(CHAT_SYSTEM_ONLY, false);
    }

    public static boolean includePostData() {
        return get(INCLUDE_POST_DATA, true);
    }

    /** How far rifts are drawn (blocks): the config with Distant Horizons (Baughn: they vanished at the vanilla fog line
     *  over DH's terrain), 200 without it. */
    public static int riftRange() {
        // Baughn: rifts are visible where terrain is, at most 500 blocks: Distant Horizons' terrain distance if it's
        // installed, else the render distance; never under 200 (the sky's rifts before), and the config may lower the cap
        int cap;
        try {
            cap = SPEC.isLoaded() ? DISTANT_RIFTS.get() : 500;
        } catch (RuntimeException e) {
            cap = 500;
        }
        int terrain = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        if (net.neoforged.fml.ModList.get().isLoaded("distanthorizons")) {
            int dh = dhRange();
            if (dh > 0) terrain = Math.max(terrain, dh);
        }
        return Math.max(200, Math.min(cap, terrain));
    }

    private static long dhAsked;
    private static int dhBlocks = -1;

    /** Distant Horizons' LOD render distance in blocks (its API, by reflection: no build dependency), -1 if unknown;
     *  asked once a second. */
    static int dhRange() {
        long now = System.currentTimeMillis();
        if (now - dhAsked < 1000) return dhBlocks;
        dhAsked = now;
        try {
            Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
            Object configs = delayed.getField("configs").get(null);
            if (configs == null) return dhBlocks = -1;
            Object graphics = configs.getClass().getMethod("graphics").invoke(configs);
            Object dist = graphics.getClass().getMethod("chunkRenderDistance").invoke(graphics);
            Object v = dist.getClass().getMethod("getValue").invoke(dist);
            int was = dhBlocks;
            dhBlocks = v instanceof Number n ? n.intValue() * 16 : -1;
            if (dhBlocks != was) org.erisia.stasis.StasisDelvers.LOG.info("Distant Horizons draws terrain to {} blocks ({}): rifts to {}", dhBlocks, v,
                Math.min(SPEC.isLoaded() ? DISTANT_RIFTS.get() : 500, dhBlocks));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            dhBlocks = -1;
        }
        return dhBlocks;
    }

    private static boolean get(ModConfigSpec.BooleanValue v, boolean def) {
        try {
            return SPEC.isLoaded() ? v.get() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
