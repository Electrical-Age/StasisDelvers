package org.erisia.stasis;

import net.minecraft.resources.Identifier;

/**
 * Frozen block textures (Baughn: lava, water and campfires kept animating in stopped land). Texture animation is per
 * sprite and global, so the client's block atlas gets a still copy of every animated block sprite (its first frame,
 * client/FrozenSprites), and chunk meshing points blocks in stopped land at the copy instead
 * (mixin/client/SectionCompilerMixin, FluidRendererMixin). The pure parts live here so the GameTests can check them.
 */
public final class FrozenTextures {
    private FrozenTextures() {
    }

    /** minecraft:block/lava_still -> stasisdelvers:frozen/minecraft/block/lava_still. */
    public static Identifier frozenId(Identifier sprite) {
        return Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "frozen/" + sprite.getNamespace() + "/" + sprite.getPath());
    }

    /**
     * The fields whose running land changed shape from old to now (both versions of a changed one; added and removed
     * ones): what must be re-meshed. Failing and rate changes don't count (a rate is always above 0).
     */
    public static java.util.List<Field> changed(java.util.List<Field> old, java.util.List<Field> now) {
        java.util.Map<Integer, Field> before = new java.util.HashMap<>();
        for (Field f : old) before.put(f.id(), f);
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Field f : now) {
            Field b = before.remove(f.id());
            if (b == null) out.add(f);
            else if (!Flames.shape(b).equals(Flames.shape(f))) {
                out.add(b);
                out.add(f);
            }
        }
        out.addAll(before.values());
        return out;
    }

    /** A texture coordinate in the span [from0, from1] moved to the same place in [to0, to1]. */
    public static float remap(float t, float from0, float from1, float to0, float to1) {
        float span = from1 - from0;
        if (span == 0) return to0;
        return to0 + (t - from0) / span * (to1 - to0);
    }

    /** Pack a (u, v) pair as UVPair.pack does (client class; same bits): u in the high 32 bits, v in the low. */
    public static long packUV(float u, float v) {
        return (Float.floatToIntBits(u) & 0xFFFFFFFFL) << 32 | (Float.floatToIntBits(v) & 0xFFFFFFFFL);
    }

    public static float unpackU(long uv) {
        return Float.intBitsToFloat((int) (uv >> 32));
    }

    public static float unpackV(long uv) {
        return Float.intBitsToFloat((int) uv);
    }

    /** A packed UV of a quad on a sprite with rect (u0, v0)-(u1, v1), moved onto the sprite with rect (fu0, fv0)-(fu1, fv1). */
    public static long remapUV(long uv, float u0, float v0, float u1, float v1, float fu0, float fv0, float fu1, float fv1) {
        return packUV(remap(unpackU(uv), u0, u1, fu0, fu1), remap(unpackV(uv), v0, v1, fv0, fv1));
    }
}
