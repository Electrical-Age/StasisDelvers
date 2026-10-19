package org.erisia.stasis.client.report;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The bug-report Redactor on generated log lines: nothing private survives, and a second pass changes nothing. */
class RedactorTest {
    static final List<String> PHRASES = List.of("Loading", "at", "Found mod file", "Exception in", "[12:34:56] [Render thread/INFO]:",
        "Setting user: Bob", "neoforge-26.3.0.75-beta", "connecting to", "version 1.2.3.400", "from", "(GameRenderer.java:827)", "done.", "", ",");

    /** A private name: one or two capitalised words that occur nowhere else in the line. */
    @Provide
    Arbitrary<String> names() {
        Arbitrary<String> word = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(4).ofMaxLength(8).map(s -> "Q" + s);
        return Arbitraries.oneOf(word, Combinators.combine(word, word).as((a, b) -> a + " " + b));
    }

    /** Where a private name sits in a path, or a secret. %s is the name. */
    @Provide
    Arbitrary<String> shapes() {
        return Arbitraries.of(
            "/home/%s/.minecraft/mods/stasis.jar",
            "/home/%s/notes.txt",
            "/Users/%s/Library/Application Support/minecraft/logs/latest.log",
            "C:\\Users\\%s\\AppData\\Roaming\\.minecraft\\mods\\a.jar",
            "C:\\Users\\%s\\Documents\\x.txt",
            "C:/Users/%s/curseforge/minecraft",
            "file:///C:/Users/%s/x.jar",
            "/srv/%s/minecraft/world",
            "/data/%s/server/logs",
            "/Volumes/%s/Games/minecraft",
            "sessionId=%s",
            "session_id: %s",
            "accessToken=%s",
            "password=%s");
    }

    static Redactor plain() {
        return new Redactor(List.of("/opt/game/instance"), "play.example.org:25565", null);
    }

    @Property(tries = 3000)
    void noPrivateNameSurvives(@ForAll("names") String name, @ForAll("shapes") String shape,
                               @ForAll("phrase") String before, @ForAll("phrase") String after) {
        String secret = shape.contains("=") || shape.contains(": ") ? name.replace(' ', '_') : name;
        String line = (before + " " + String.format(shape, secret) + " " + after).strip();
        String out = plain().redact(line);
        for (String w : secret.split("[ _]"))
            assertFalse(out.contains(w), () -> "'" + w + "' survives in '" + out + "' (from '" + line + "')");
    }

    @Provide
    Arbitrary<String> phrase() {
        return Arbitraries.of(PHRASES);
    }

    @Provide
    Arbitrary<String> lines() {
        Arbitrary<String> bit = Arbitraries.oneOf(phrase(),
            Combinators.combine(shapes(), names()).as((s, n) -> String.format(s, n)),
            Arbitraries.of("192.168.1.10", "/10.0.0.5:25565", "[2001:db8::1]:25565", "fe80::1%eth0", "play.example.org",
                "/opt/game/instance/mods/x.jar", "--accessToken abc.def", "token: t1", "1.2.3.4.5", "999.1.1.1", "/tmp/a b/c"));
        return bit.list().ofMinSize(1).ofMaxSize(6).map(l -> String.join(" ", l));
    }

    @Property(tries = 3000)
    void redactingTwiceChangesNothing(@ForAll("lines") String line, @ForAll("names") String user) {
        Redactor r = new Redactor(List.of("/opt/game/instance"), "play.example.org:25565", user);
        String once = r.redact(line);
        assertEquals(once, r.redact(once), () -> "from '" + line + "'");
    }

    /** The OS user name, also when it has a space (a Windows account "Alice Smith"). */
    @Property(tries = 1000)
    void theUserNameNeverSurvives(@ForAll("names") String user, @ForAll("phrase") String before) {
        Redactor r = new Redactor(List.of(), null, user);
        for (String line : List.of(before + " C:\\Users\\" + user + "\\x.txt", before + " user " + user + " logged in",
            before + " /home/" + user + "/a b"))
            for (String w : user.split(" ")) {
                String out = r.redact(line);
                assertFalse(out.contains(w), () -> "'" + w + "' survives in '" + out + "'");
            }
    }

    @Example
    void knownLeaks() {
        Redactor r = plain();
        assertEquals("<path> loaded", r.redact("C:\\Users\\Alice Smith\\Documents\\x.txt loaded"));
        assertEquals("<path>/eln.jar", r.redact("/home/Alice Smith/mods/eln.jar"));
        assertEquals("<path> and <path>", r.redact("/srv/minecraft/world and /data/alice/logs"));
        assertEquals("sessionId=<redacted> ok", r.redact("sessionId=abc123 ok"));
    }

    static void check(Redactor r, String in, String want) {
        assertEquals(want, r.redact(in), () -> "redact: " + in);
    }

    /** The bugreport_redaction GameTest's table, kept passing. */
    @Example
    void gameTestExamples() {
        var r = new Redactor(java.util.List.of("/home/alice/.local/share/PrismLauncher/instances/Incognito/minecraft/.",
            "/mnt/games/Incognito/minecraft", "C:\\Users\\Alice\\curseforge\\minecraft\\Instances\\Incognito"), "play.example.org:25565", "alice");
        check(r, "Loading /home/alice/.local/share/PrismLauncher/instances/Incognito/minecraft/mods/eln.jar", "Loading <game>/mods/eln.jar");
        check(r, "real path /mnt/games/Incognito/minecraft/logs/latest.log", "real path <game>/logs/latest.log");
        check(r, "C:\\Users\\Alice\\curseforge\\minecraft\\Instances\\Incognito\\config\\x.toml", "<game>\\config\\x.toml");
        check(r, "url file:///C:/Users/Alice/curseforge/minecraft/Instances/Incognito/mods/a.jar", "url <game>/mods/a.jar");
        check(r, "at /home/alice/.gradle/caches/foo-1.2.jar", "at <path>/foo-1.2.jar");
        check(r, "jar:file:/opt/java/lib/rt.jar!/java/lang/Object.class", "<path>/rt.jar!/java/lang/Object.class");
        check(r, "tmp /tmp/hsperf_x/123 done", "tmp <path> done");
        check(r, "D:\\Games\\notes.txt here", "<path> here");
        check(r, "share \\\\nas\\mc\\x", "share <path>");
        check(r, "Connecting to play.example.org, 25565", "Connecting to <server>, 25565");
        check(r, "Connecting to play.example.org:25565", "Connecting to <server>");
        check(r, "from /192.168.1.10:25565 ok", "from /<ip> ok");
        check(r, "peer 10.0.0.5.", "peer <ip>.");
        check(r, "ipv6 [2001:db8::1]:25565 and fe80::1%eth0 and 2001:0db8:85a3:0000:0000:8a2e:0370:7334", "ipv6 <ip> and <ip>%eth0 and <ip>");
        check(r, "neoforge-26.3.0.75-beta, version 1.2.3.400, mod v1.2.3.4", "neoforge-26.3.0.75-beta, version 1.2.3.400, mod v1.2.3.4");
        check(r, "[12:34:56] [Render thread/INFO] [net.minecraft.client.Minecraft/]: Setting user: Bob", "[12:34:56] [Render thread/INFO] [net.minecraft.client.Minecraft/]: Setting user: Bob");
        check(r, "at net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:827)", "at net.minecraft.client.renderer.GameRenderer.render(GameRenderer.java:827)");
        check(r, "user Alice and ALICE but not Alicesmith", "user <user> and <user> but not Alicesmith");
        check(r, "--username Bob --accessToken eyJhbGciOi.xyz --version 26.3", "--username Bob --accessToken <redacted> --version 26.3");
        check(r, "password=hunter2 token: abc session=s1", "password=<redacted> token: <redacted> session=<redacted>");
    }
}
