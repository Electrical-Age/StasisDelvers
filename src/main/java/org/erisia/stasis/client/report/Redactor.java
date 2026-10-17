package org.erisia.stasis.client.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips private data from bug report text (notes/bugreport/SPEC.md 1.11), in this order: the game directory (to
 * &lt;game&gt;), other absolute paths (&lt;path&gt;, keeping a .jar's file name), the server address (&lt;server&gt;),
 * IP literals (&lt;ip&gt;), the OS user name (&lt;user&gt;), tokens and passwords (&lt;redacted&gt;).
 * Pure Java (no Minecraft classes), so the game tests can check it on the server.
 */
public final class Redactor {
    private final List<Pattern> gameForms = new ArrayList<>();
    private final List<Pattern> serverForms = new ArrayList<>();
    private final Pattern user;

    /**
     * @param gameDirs the game directory as the log may spell it (absolute path, real path...); each also matches
     *                 with / and \ separators and as a file: URL
     * @param serverAddress the server address as typed in the server list (host or host:port), or null
     * @param userName the OS user name, or null; only names of 4 or more characters are redacted
     */
    public Redactor(List<String> gameDirs, String serverAddress, String userName) {
        Set<String> forms = new LinkedHashSet<>();
        for (String d : gameDirs) {
            if (d == null) continue;
            String p = stripTrailing(d);
            if (p.length() < 2) continue;   // never "/" or ""
            String fwd = p.replace('\\', '/'), back = p.replace('/', '\\');
            String noLead = fwd.startsWith("/") ? fwd.substring(1) : fwd;
            forms.add("file:///" + noLead);
            forms.add("file://" + noLead);
            forms.add("file:/" + noLead);
            forms.add("file:" + noLead);
            forms.add(p);
            forms.add(fwd);
            forms.add(back);
        }
        // longest first, so a path doesn't eat the start of a longer form
        forms.stream().sorted(Comparator.comparingInt(String::length).reversed())
            .forEach(f -> gameForms.add(Pattern.compile(Pattern.quote(f), Pattern.CASE_INSENSITIVE)));
        if (serverAddress != null && !serverAddress.isBlank()) {
            String s = serverAddress.strip();
            // (with boundaries: a short server-list name like "home" must not eat "homeward")
            serverForms.add(Pattern.compile("(?<![\\w.\\-])" + Pattern.quote(s) + "(?![\\w\\-])", Pattern.CASE_INSENSITIVE));
            String host = hostOf(s);
            if (host.length() >= 4 && !host.equalsIgnoreCase(s))
                serverForms.add(Pattern.compile("(?<![\\w.\\-])" + Pattern.quote(host) + "(?![\\w\\-])", Pattern.CASE_INSENSITIVE));
        }
        user = userName != null && userName.strip().length() >= 4
            ? Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(userName.strip()) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE) : null;
    }

    static String stripTrailing(String p) {
        String s = p.strip();
        while (s.endsWith("/.") || s.endsWith("\\.")) s = s.substring(0, s.length() - 2);
        while (s.length() > 1 && (s.endsWith("/") || s.endsWith("\\"))) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** "host:port" -> host; "[v6]:port" -> v6; a bare IPv6 stays as it is. */
    static String hostOf(String s) {
        if (s.startsWith("[")) {
            int e = s.indexOf(']');
            return e > 0 ? s.substring(1, e) : s;
        }
        int c = s.indexOf(':');
        return c > 0 && c == s.lastIndexOf(':') ? s.substring(0, c) : s;
    }

    // other absolute paths: file URLs, Windows drive and UNC paths, POSIX paths under the usual private roots
    static final Pattern URL_PATH = Pattern.compile("(?i)(?:jar:)?file:/+[^\\s\"'<>|]*");
    static final Pattern WIN_PATH = Pattern.compile("(?i)(?<![\\w])[A-Z]:[\\\\/][^\\s\"'<>|]*");
    static final Pattern UNC_PATH = Pattern.compile("\\\\\\\\[^\\s\"'<>|]+");
    static final Pattern POSIX_PATH = Pattern.compile(
        "(?<![\\w.<>/\\-])/(?:home|Users|root|var|tmp|opt|mnt|media|run|nix)(?:/[^\\s\"'<>|]*)?(?![\\w])");
    static final Pattern JAR_NAME = Pattern.compile("([^\\\\/!]+\\.jar)(?![\\w])", Pattern.CASE_INSENSITIVE);

    // IPv4: four parts each <= 255, not part of a dotted version (neoforge-26.3.0.75-beta, v1.2.3.4, 1.2.3.4.5)
    static final Pattern IPV4 = Pattern.compile("(?<![\\w.+\\-])((?:\\d{1,3}\\.){3}\\d{1,3})(?::\\d{1,5})?(?![\\w+\\-]|\\.\\d)");
    // IPv6: hex digits and colons, two or more colons; optionally bracketed with a port
    static final Pattern IPV6 = Pattern.compile("(?<![\\w:.])(\\[)?([0-9A-Fa-f]{0,4}(?::[0-9A-Fa-f]{0,4}){2,7})(?:\\](?::\\d{1,5})?)?(?![\\w:])");
    static final Pattern ACCESS_TOKEN = Pattern.compile("(--accessToken\\s+)\\S+");
    static final Pattern SECRET = Pattern.compile("(?i)(token|session|password)([=:]\\s*)\\S+");

    public String redact(String s) {
        if (s == null || s.isEmpty()) return s;
        for (Pattern p : gameForms) s = p.matcher(s).replaceAll("<game>");
        s = replace(URL_PATH, s, Redactor::path);
        s = replace(UNC_PATH, s, Redactor::path);
        s = replace(WIN_PATH, s, Redactor::path);
        s = replace(POSIX_PATH, s, Redactor::path);
        for (Pattern p : serverForms) s = p.matcher(s).replaceAll("<server>");
        s = replace(IPV4, s, m -> validV4(m.group(1)) ? "<ip>" : m.group());
        s = replace(IPV6, s, Redactor::v6);
        if (user != null) s = user.matcher(s).replaceAll("<user>");
        s = ACCESS_TOKEN.matcher(s).replaceAll("$1<redacted>");
        s = SECRET.matcher(s).replaceAll("$1$2<redacted>");
        return s;
    }

    interface Rep {
        String apply(Matcher m);
    }

    static String replace(Pattern p, String s, Rep r) {
        Matcher m = p.matcher(s);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            if (sb == null) sb = new StringBuilder(s.length());
            sb.append(s, last, m.start()).append(r.apply(m));
            last = m.end();
        }
        if (sb == null) return s;
        return sb.append(s, last, s.length()).toString();
    }

    /** &lt;path&gt;, keeping a jar's file name (and what follows it inside the jar: "!/org/x/Y.class"). */
    static String path(Matcher m) {
        String p = m.group();
        if (p.contains("<game>")) {
            // a URL into the game dir: keep it from <game> on
            int g = p.indexOf("<game>");
            return p.substring(g);
        }
        Matcher j = JAR_NAME.matcher(p);
        int start = -1, end = -1;
        String name = null;
        while (j.find()) {
            start = j.start();
            end = j.end();
            name = j.group(1);
        }
        if (name == null) return "<path>";
        // the last .jar in the path: its name, and the rest when that's inside the jar or trailing punctuation
        String rest = p.substring(end);
        if (!rest.isEmpty() && !rest.startsWith("!") && !rest.matches("[)\\],;:.]*")) return "<path>";
        return "<path>/" + name + rest;
    }

    static boolean validV4(String ip) {
        for (String part : ip.split("\\.")) {
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    static String v6(Matcher m) {
        String addr = m.group(2);
        long colons = addr.chars().filter(c -> c == ':').count();
        boolean hasHex = addr.chars().anyMatch(c -> c != ':');
        // a real IPv6 has "::" or all 7 colons; "[12:34:56]" is a log time stamp
        if (!hasHex) return m.group();
        if (addr.contains("::") || colons == 7) return "<ip>";
        return m.group();
    }
}
