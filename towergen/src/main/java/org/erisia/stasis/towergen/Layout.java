package org.erisia.stasis.towergen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tower layout: mast height H above the core, booms as (y, direction, length). Directions E (+x), S (+z), W (-x),
 * N (-z), numbered 0..3 as p2lib.DIRS; rot(x, y, z) = (-z, y, x) turns E into S. JSON:
 * {@code {"H": 40, "booms": [[10, "E", 16], ...]}}.
 */
public record Layout(int H, List<Boom> booms) {
    public record Boom(int y, int dir, int length) {
        public char dirChar() { return "ESWN".charAt(dir); }
    }

    public Layout {
        booms = List.copyOf(booms);
    }

    public static int dirIndex(char c) {
        int i = "ESWN".indexOf(Character.toUpperCase(c));
        if (i < 0) throw new IllegalArgumentException("direction must be one of E S W N: " + c);
        return i;
    }

    public static Layout fromJson(Object tree) {
        Map<String, Object> m = Json.obj(tree);
        int H = Json.inum(m.get("H"));
        List<Boom> bs = new ArrayList<>();
        Object b = m.get("booms");
        if (b != null) {
            for (Object o : Json.arr(b)) {
                List<Object> e = Json.arr(o);
                Object d = e.get(1);
                int dir = d instanceof String s ? dirIndex(s.charAt(0)) : Json.inum(d);
                bs.add(new Boom(Json.inum(e.get(0)), dir, Json.inum(e.get(2))));
            }
        }
        return new Layout(H, bs);
    }

    public static Layout read(Path p) throws IOException {
        return fromJson(Json.parse(Files.readString(p)));
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("H", (double) H);
        List<Object> bs = new ArrayList<>();
        for (Boom b : booms) bs.add(List.of((double) b.y, String.valueOf(b.dirChar()), (double) b.length));
        m.put("booms", bs);
        return m;
    }

    /** Game rules (TOWER-DESIGN.md): H 4..100, booms at 2..H-2 in E/S/W/N, length 2..(H - y), one per (y, dir). */
    public List<String> violations() {
        List<String> out = new ArrayList<>();
        if (H < 4 || H > 100) out.add("H " + H + " outside 4..100");
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Boom b : booms) {
            if (b.y < 2 || b.y > H - 2) out.add("boom y " + b.y + " outside 2..H-2");
            if (b.length < 2 || b.length > H - b.y) out.add("boom length " + b.length + " at y " + b.y + " outside 2..H-y");
            if (!seen.add(((long) b.y << 4) | b.dir)) out.add("two booms at y " + b.y + " dir " + b.dirChar());
        }
        return out;
    }

    @Override public String toString() { return Json.write(toJson()); }
}
