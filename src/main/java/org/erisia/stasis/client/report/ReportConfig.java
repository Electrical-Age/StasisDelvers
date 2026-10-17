package org.erisia.stasis.client.report;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config for bug reports (stasisdelvers-client.toml). Read at report time; defaults until the file is loaded. */
public final class ReportConfig {
    private ReportConfig() {
    }

    public static final ModConfigSpec SPEC;
    static final ModConfigSpec.BooleanValue INCLUDE_CHAT;
    static final ModConfigSpec.BooleanValue CHAT_SYSTEM_ONLY;
    static final ModConfigSpec.BooleanValue INCLUDE_POST_DATA;

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

    private static boolean get(ModConfigSpec.BooleanValue v, boolean def) {
        try {
            return SPEC.isLoaded() ? v.get() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }
}
