package org.erisia.stasis.towerweb;

import java.io.ByteArrayInputStream;

import org.erisia.stasis.towergen.*;
import org.teavm.jso.JSExport;
import org.teavm.jso.typedarrays.Int8Array;

public final class TowerWeb {
    private static Library lib;
    private static TowerGen gen;

    public static void main(String[] args) {}

    @JSExport
    public static String loadLibraryJson(Int8Array bytes) throws Exception {
        long t0 = System.currentTimeMillis();
        lib = Library.load(new ByteArrayInputStream(bytes.copyToJavaArray()), false);
        gen = new TowerGen(lib);
        return "entries " + lib.entries.size() + " in " + (System.currentTimeMillis() - t0) + " ms";
    }

    @JSExport
    public static String build(String layoutJson, double q) {
        long t0 = System.currentTimeMillis();
        TowerGen.Result r = gen.build(Layout.fromJson(Json.parse(layoutJson)), q);
        long t1 = System.currentTimeMillis();
        Mesh m = new MeshBuilder(gen.c, VisualParams.defaults()).build(r);
        long t2 = System.currentTimeMillis();
        return "tris " + m.tris + " util " + r.analysis.maxUtil + " build " + (t1 - t0) + " mesh " + (t2 - t1);
    }
}
