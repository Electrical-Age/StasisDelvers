package org.erisia.stasis.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.MapCodec;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.ResourceManager;
import org.erisia.stasis.ClientFields;
import org.erisia.stasis.StasisDelvers;
import org.erisia.stasis.StaticDeeps;
import org.jspecify.annotations.Nullable;

/**
 * Water lines (Baughn and λ-Nero, 2026-10-15; notes/rifts/WATER-LINES-PLAN.md): the frozen surface of water that was
 * there when the world crashed shows fine streaks along the line from the nearest rift core, stronger nearer it, so two
 * ponds triangulate the core. Placed water can't show them (it freezes into stilled water: BucketMixin). Meshing only:
 * FluidRendererMixin asks for the top face's sprite. The sprites: the first frame of water_still with streaks, in
 * ORIENT orientations over 180 degrees (the lines have no head) and two strengths (a sprite source, like FrozenSprites).
 */
public final class WaterLines {
    private WaterLines() {
    }

    static final int ORIENT = 8;
    /** Horizontal reach from a core's edge: strong lines within NEAR, faint ones within FAR. */
    static final float NEAR = 48, FAR = 112;

    static Identifier id(int orient, int strength) {
        return Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "water_lines/" + orient + "_" + strength);
    }

    /** The sprite source: ORIENT x 2 streaked copies of water_still's first frame. */
    public record Source() implements SpriteSource {
        public static final MapCodec<Source> MAP_CODEC = MapCodec.unit(new Source());

        @Override
        public void run(ResourceManager rm, SpriteSource.Output output) {
            var water = rm.getResource(Identifier.withDefaultNamespace("textures/block/water_still.png"));
            if (water.isEmpty()) return;
            for (int o = 0; o < ORIENT; o++)
                for (int s = 0; s < 2; s++) {
                    int oo = o, ss = s;
                    Identifier id = id(o, s);
                    output.add(id, (SpriteSource.DiscardableLoader) loader -> loader.loadSprite(id, water.get(),
                        (i, size, image, anim, extra, tex) -> streaked(i, size, image, anim, extra, tex, oo, ss)));
                }
        }

        @Override
        public MapCodec<Source> codec() {
            return MAP_CODEC;
        }
    }

    static SpriteContents streaked(Identifier id, FrameSize size, NativeImage image, Optional<AnimationMetadataSection> anim,
                                   List<MetadataSectionType.WithValue<?>> extra, Optional<TextureMetadataSection> tex, int orient, int strength) {
        try {
            int w = size.width(), h = size.height();
            NativeImage out = new NativeImage(w, h, false);
            image.copyRect(out, 0, 0, 0, 0, w, h, false, false);
            double a = Math.PI * orient / ORIENT, ca = Math.cos(a), sa = Math.sin(a);
            float lift = strength == 1 ? 0.95f : 0.7f;   // (0.55/0.28 were barely visible: AdventurAgent measured 13-15/255)
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    // across the lines (texture x = east, y = south) and along them; the lines repeat every 4 pixels of a
                    // 16-pixel tile, broken into dashes like filings
                    double fx = (x + 0.5) * 16.0 / w, fy = (y + 0.5) * 16.0 / h;
                    double across = -fx * sa + fy * ca, along = fx * ca + fy * sa;
                    double band = Math.abs(((across % 4) + 4) % 4 - 2) / 2;   // 1 on the line, 0 between
                    int dash = (int) Math.floor((along + 64) / 3);
                    int line = (int) Math.floor((across + 64) / 4);
                    boolean on = band > 0.55 && hash(dash, line) > 0.25;
                    if (!on) continue;
                    int c = out.getPixel(x, y);
                    int al = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                    r = (int) (r + (255 - r) * lift);
                    g = (int) (g + (255 - g) * lift);
                    b = (int) (b + (255 - b) * lift);
                    out.setPixel(x, y, (Math.min(255, al + (strength == 1 ? 140 : 90)) << 24) | (r << 16) | (g << 8) | b);
                }
            return new SpriteContents(id, new FrameSize(w, h), out, Optional.empty(), extra, tex);
        } catch (Exception e) {
            return null;
        } finally {
            image.close();
        }
    }

    static float hash(int a, int b) {
        int h = a * 0x8da6b343 ^ b * 0xd8163841;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        return (h >>> 8) / 16777216f;
    }

    /** The streaked sprite for the still top of water at pos, or null (no core near). Called on mesh workers. */
    public static @Nullable TextureAtlasSprite sprite(BlockPos pos) {
        int seed = ClientFields.deepSeed();
        if (seed == 0) return null;
        float x = pos.getX() + 0.5f, z = pos.getZ() + 0.5f;
        StaticDeeps.Deep best = null;
        float bestD = Float.MAX_VALUE;
        for (StaticDeeps.Deep d : StaticDeeps.near(x, pos.getY(), z, FAR + 64, seed)) {
            float dist = (float) Math.hypot(x - d.x(), z - d.z()) - d.radius();
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        if (best == null || bestD > FAR || bestD < 0) return null;
        double ang = Math.atan2(z - best.z(), x - best.x());
        int orient = Math.floorMod((int) Math.round(ang / Math.PI * ORIENT), ORIENT);
        TextureAtlasSprite[] s = sprites;
        return s == null ? null : s[orient * 2 + (bestD < NEAR ? 1 : 0)];
    }

    /** The streak sprites of the current block atlas (FrozenSprites.stitched), or null before. */
    private static volatile TextureAtlasSprite @Nullable [] sprites;

    static void stitched(java.util.Map<Identifier, TextureAtlasSprite> all) {
        TextureAtlasSprite[] s = new TextureAtlasSprite[ORIENT * 2];
        for (int o = 0; o < ORIENT; o++)
            for (int k = 0; k < 2; k++) {
                s[o * 2 + k] = all.get(id(o, k));
                if (s[o * 2 + k] == null) {
                    sprites = null;
                    return;
                }
            }
        sprites = s;
    }
}
