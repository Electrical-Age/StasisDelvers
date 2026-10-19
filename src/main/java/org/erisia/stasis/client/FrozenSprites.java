package org.erisia.stasis.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.RegisterSpriteSourcesEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import org.erisia.stasis.Field;
import org.erisia.stasis.FrozenTextures;
import org.erisia.stasis.Stasis;
import org.erisia.stasis.StasisDelvers;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Still textures in stopped land (Baughn: lava, water and campfires kept animating). See FrozenTextures.
 * <ul>
 * <li><b>Sprites:</b> a sprite source ({@link Source}, listed in assets/minecraft/atlases/blocks.json) adds the first
 * frame of every animated texture under textures/block (any namespace, resource packs included) and
 * textures/entity/conduit to the block atlas as stasisdelvers:frozen/&lt;ns&gt;/&lt;path&gt;, without animation.</li>
 * <li><b>Meshing:</b> chunk meshing (SectionCompilerMixin, FluidRendererMixin; worker threads) draws a block whose
 * whole block is stopped (rateAtSteady 0: eddies drifting by mustn't re-mesh) with the still copies.</li>
 * <li><b>Re-meshing:</b> when the fields change shape, the sections they cover (before and after) are re-meshed, at
 * most once a second (a collapse changes the field every 5 ticks).</li>
 * </ul>
 */
public final class FrozenSprites {
    private FrozenSprites() {
    }

    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();

    // ---------------------------------------------------------------- the sprite source

    /** Like minecraft:directory, but adds still first frames of the animated textures only, under frozen ids. */
    public record Source(String source, String prefix) implements SpriteSource {
        public static final MapCodec<Source> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.fieldOf("source").forGetter(Source::source),
            Codec.STRING.fieldOf("prefix").forGetter(Source::prefix)
        ).apply(i, Source::new));

        @Override
        public void run(ResourceManager resourceManager, SpriteSource.Output output) {
            FileToIdConverter converter = new FileToIdConverter("textures/" + source, ".png");
            int[] n = {0};
            converter.listMatchingResources(resourceManager).forEach((file, resource) -> {
                boolean animated;
                try {
                    animated = resource.metadata().getSection(AnimationMetadataSection.TYPE).isPresent();
                } catch (Exception e) {
                    return;   // the real sprite logs it
                }
                if (!animated) return;
                Identifier frozen = FrozenTextures.frozenId(converter.fileToId(file).withPrefix(prefix));
                output.add(frozen, (SpriteSource.DiscardableLoader) loader -> loader.loadSprite(frozen, resource, FrozenSprites::firstFrame));
                n[0]++;
            });
            LOG.debug("Frozen textures: {} still copies from textures/{}", n[0], source);
        }

        @Override
        public MapCodec<Source> codec() {
            return MAP_CODEC;
        }
    }

    /** The first frame of the animation (its frame list's first entry), alone and without animation. */
    static SpriteContents firstFrame(Identifier id, FrameSize size, NativeImage image, Optional<AnimationMetadataSection> animation,
                                     List<MetadataSectionType.WithValue<?>> additional, Optional<TextureMetadataSection> texture) {
        try {
            int w = size.width(), h = size.height();
            int perRow = Math.max(1, image.getWidth() / w), count = perRow * Math.max(1, image.getHeight() / h);
            int frame = animation.flatMap(AnimationMetadataSection::frames).filter(l -> !l.isEmpty())
                .map(l -> l.getFirst().index()).orElse(0);
            if (frame < 0 || frame >= count) frame = 0;
            NativeImage still = new NativeImage(w, h, false);
            image.copyRect(still, (frame % perRow) * w, (frame / perRow) * h, 0, 0, w, h, false, false);
            return new SpriteContents(id, new FrameSize(w, h), still, Optional.empty(), additional, texture);
        } catch (Exception e) {
            LOG.warn("Frozen texture {} failed", id, e);
            return null;
        } finally {
            image.close();
        }
    }

    static void registerSource(RegisterSpriteSourcesEvent e) {
        e.register(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "frozen_animated"), Source.MAP_CODEC);
        e.register(Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "water_lines"), WaterLines.Source.MAP_CODEC);
    }

    // ---------------------------------------------------------------- animated sprite -> still copy

    /** The block atlas's animated sprites and their still copies; replaced whole on each stitch, read by mesh workers. */
    private static volatile Map<TextureAtlasSprite, TextureAtlasSprite> frozen = Map.of();
    /** Bumped on each stitch: the per-thread quad caches drop their quads. */
    private static volatile int generation;

    /** Bug reports: how many sprites have a frozen copy, and the atlas generation. */
    static int frozenCount() {
        return frozen.size();
    }

    static int generation() {
        return generation;
    }

    static void stitched(TextureAtlasStitchedEvent e) {
        TextureAtlas atlas = e.getAtlas();
        if (!atlas.location().equals(TextureAtlas.LOCATION_BLOCKS)) return;
        Map<Identifier, TextureAtlasSprite> all = atlas.getTextures();
        Map<TextureAtlasSprite, TextureAtlasSprite> m = new IdentityHashMap<>();
        int missing = 0;
        for (var entry : all.entrySet()) {
            TextureAtlasSprite s = entry.getValue();
            if (!s.contents().isAnimated() || entry.getKey().getNamespace().equals(StasisDelvers.MODID) && entry.getKey().getPath().startsWith("frozen/")) continue;
            TextureAtlasSprite f = all.get(FrozenTextures.frozenId(entry.getKey()));
            if (f == null) missing++;
            else m.put(s, f);
        }
        frozen = java.util.Collections.unmodifiableMap(m);
        WaterLines.stitched(all);
        generation++;
        LOG.info("Frozen textures: {} animated block sprites have still copies{}", m.size(),
            missing > 0 ? " (" + missing + " animated sprites from other atlas sources have none and keep moving)" : "");
    }

    /** The still copy of an animated block sprite, or null (not animated, or no copy). */
    public static TextureAtlasSprite frozen(TextureAtlasSprite sprite) {
        return frozen.get(sprite);
    }

    public static TextureAtlasSprite frozenOr(TextureAtlasSprite sprite) {
        TextureAtlasSprite f = frozen.get(sprite);
        return f != null ? f : sprite;
    }

    private static final class QuadCache {
        final IdentityHashMap<BakedQuad, BakedQuad> map = new IdentityHashMap<>();
        int generation;
    }

    private static final ThreadLocal<QuadCache> QUADS = ThreadLocal.withInitial(QuadCache::new);

    /** The quad with its UVs moved from its sprite onto the still copy f; cached per source quad (per thread). */
    static BakedQuad remapped(BakedQuad quad, TextureAtlasSprite f) {
        QuadCache c = QUADS.get();
        int g = generation;
        if (c.generation != g || c.map.size() > 8192) {   // models can make quads per call: keep the cache bounded
            c.map.clear();
            c.generation = g;
        }
        BakedQuad out = c.map.get(quad);
        if (out == null) {
            TextureAtlasSprite s = quad.materialInfo().sprite();
            float u0 = s.getU0(), v0 = s.getV0(), u1 = s.getU1(), v1 = s.getV1();
            float fu0 = f.getU0(), fv0 = f.getV0(), fu1 = f.getU1(), fv1 = f.getV1();
            out = new BakedQuad(quad.position0(), quad.position1(), quad.position2(), quad.position3(),
                FrozenTextures.remapUV(quad.packedUV0(), u0, v0, u1, v1, fu0, fv0, fu1, fv1),
                FrozenTextures.remapUV(quad.packedUV1(), u0, v0, u1, v1, fu0, fv0, fu1, fv1),
                FrozenTextures.remapUV(quad.packedUV2(), u0, v0, u1, v1, fu0, fv0, fu1, fv1),
                FrozenTextures.remapUV(quad.packedUV3(), u0, v0, u1, v1, fu0, fv0, fu1, fv1),
                quad.direction(), quad.materialInfo(), quad.bakedNormals(), quad.bakedColors());
            c.map.put(quad, out);
        }
        return out;
    }

    // ---------------------------------------------------------------- stopped? (mesh worker threads)

    private static final class SectionCache {
        Level level;
        long section;
        int version;
        int state;
    }

    private static final ThreadLocal<SectionCache> SECTIONS = ThreadLocal.withInitial(SectionCache::new);

    static Level levelOf(BlockAndTintGetter getter) {
        if (getter instanceof RenderSectionRegion r) return r.level;
        return getter instanceof Level l ? l : null;
    }

    /** Stasis.sectionState of pos's section, cached per thread while the fields stay the same. */
    static int sectionState(Level level, BlockPos pos) {
        SectionCache c = SECTIONS.get();
        int v = Stasis.version();   // read before asking: a change after this re-meshes with a new version
        long key = net.minecraft.core.SectionPos.asLong(pos);
        if (c.level != level || c.section != key || c.version != v) {
            c.state = Stasis.sectionState(level, pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
            c.level = level;
            c.section = key;
            c.version = v;
        }
        return c.state;
    }

    /** Is the whole block at pos stopped (rateAtSteady 0)? False where it can't tell. */
    public static boolean stopped(BlockAndTintGetter getter, BlockPos pos) {
        if (frozen.isEmpty()) return false;
        Level level = levelOf(getter);
        if (level == null) return false;
        int s = sectionState(level, pos);
        return s == Stasis.SECTION_STOPPED
            || s == Stasis.SECTION_MIXED && Stasis.rateAtSteady(level, pos.getX(), pos.getY(), pos.getZ()) <= 0;
    }

    /** Wraps a block's quad output: animated sprites on a stopped block go to their still copies. One per thread. */
    static final class FreezingOutput implements BlockQuadOutput {
        BlockQuadOutput out;
        Level level;
        BlockPos pos;
        int stopped;   // -1: not asked yet

        @Override
        public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
            TextureAtlasSprite f = frozen.get(quad.materialInfo().sprite());
            if (f != null) {
                if (stopped < 0) {
                    int s = sectionState(level, pos);
                    stopped = s == Stasis.SECTION_STOPPED
                        || s == Stasis.SECTION_MIXED && Stasis.rateAtSteady(level, pos.getX(), pos.getY(), pos.getZ()) <= 0 ? 1 : 0;
                }
                if (stopped == 1) quad = remapped(quad, f);
            }
            out.put(x, y, z, quad, instance);
        }
    }

    private static final ThreadLocal<FreezingOutput> OUTPUTS = ThreadLocal.withInitial(FreezingOutput::new);

    /** The output to mesh the block at pos with: out itself where nothing can be frozen (cheap), else a FreezingOutput. */
    public static BlockQuadOutput begin(BlockQuadOutput out, BlockAndTintGetter getter, BlockPos pos) {
        if (frozen.isEmpty()) return out;
        Level level = levelOf(getter);
        if (level == null || sectionState(level, pos) == Stasis.SECTION_RUNNING) return out;
        FreezingOutput o = OUTPUTS.get();
        o.out = out;
        o.level = level;
        o.pos = pos;
        o.stopped = -1;
        return o;
    }

    /** After the block: drop the references (the region holds a copy of the chunks). */
    public static void end(BlockQuadOutput used) {
        if (used instanceof FreezingOutput o) {
            o.out = null;
            o.level = null;
            o.pos = null;
        }
    }

    // ---------------------------------------------------------------- re-meshing when the fields change (main thread)

    private static final List<Field> PENDING = new ArrayList<>();
    private static int ticks, lastFlush = -1000;
    /** At most one re-mesh a second: a collapse changes its field every 5 ticks. */
    static final int FLUSH_TICKS = 20;

    /** ClientFields.onChange (main thread: the payload handler). */
    static void fieldsChanged(List<Field> old, List<Field> now) {
        PENDING.addAll(Field.changedShapes(old, now));
    }

    static void tick(Minecraft mc) {
        ticks++;
        if (PENDING.isEmpty()) return;
        ClientLevel level = mc.level;
        if (level == null) {
            PENDING.clear();
            return;
        }
        if (ticks - lastFlush < FLUSH_TICKS) return;
        lastFlush = ticks;
        if (Stasis.stops(level) && !frozen.isEmpty()) {
            int minY = level.getMinSectionY(), maxY = level.getMaxSectionY();
            for (Field f : PENDING) {
                int x0 = (int) Math.floor(f.minX()) >> 4, x1 = (int) Math.floor(f.maxX()) >> 4;
                int z0 = (int) Math.floor(f.minZ()) >> 4, z1 = (int) Math.floor(f.maxZ()) >> 4;
                int y0 = minY, y1 = maxY;
                if (!f.column()) {
                    y0 = Math.max(minY, (int) Math.floor(f.y() - f.radius()) >> 4);
                    y1 = Math.min(maxY, (int) Math.floor(f.y() + f.radius()) >> 4);
                }
                if (y0 <= y1) level.setSectionRangeDirty(x0, y0, z0, x1, y1, z1);
            }
        }
        PENDING.clear();
    }
}
