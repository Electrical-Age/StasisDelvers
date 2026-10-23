package org.erisia.stasis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.energy.SimpleEnergyHandler;

import java.util.ArrayDeque;

/**
 * A chronal accelerator's room (λ-Nero, variant B; notes/timestop/ACCEL-DESIGN.md). Every 2 s (and when its setting
 * changes) it flood-fills from each of its sides through everything that isn't chronal casing (the block tag
 * stasisdelvers:chronal_casing: riftstone, refined riftstone and accelerators), 6-connected, within a 16-block span;
 * the side that stays sealed is the room. A room may hold MAX_PLAIN blocks walled in plain riftstone, up to
 * MAX_REFINED all refined (in proportion to the refined share of its walls). It runs while the accelerator can pay
 * W_PER_BLOCK x volume x rate² (refined walls take off up to half) W from its FE store: at its setting, or the highest half
 * step the store carries, or not at all (Rooms).
 */
public class ChronalAcceleratorBlockEntity extends BlockEntity {
    public static final TagKey<Block> CASING = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(StasisDelvers.MODID, "chronal_casing"));
    static final int MAX_PLAIN = 512, MAX_REFINED = 1728, SPAN = 16;
    /** Draw per block of room at 1x (W); FE is 4/3 J as EA's. */
    static final double W_PER_BLOCK = 2, FE_PER_J = 4.0 / 3.0;
    /** The store holds this many seconds of the draw at 10x. */
    static final double STORE_SECONDS = 10;

    /** The last fill: the room (null if none), or why not, and the refined share of its walls. */
    Rooms.Room shape;
    String why = "not checked yet";
    double refined;
    /** The rate it runs at now (0: not running). */
    double running;
    private int ticks;

    final Store store = new Store();

    final class Store extends SimpleEnergyHandler {
        Store() {
            super(1, Integer.MAX_VALUE, 0);
        }

        /** Size the store to the room (never below what it holds: a room that hasn't been filled yet has no size). */
        void size() {
            capacity = Math.max(Math.max(1000, energy), (int) Math.ceil(drawW(10) * STORE_SECONDS * FE_PER_J));
        }

        void load(int fe) {
            energy = Math.max(0, fe);
            capacity = Math.max(capacity, energy);
        }

        int amount() {
            return energy;
        }

        boolean take(int fe) {
            if (energy < fe) return false;
            energy -= fe;
            return true;
        }

        @Override
        public int insert(int amount, net.neoforged.neoforge.transfer.transaction.TransactionContext transaction) {
            size();
            return super.insert(amount, transaction);
        }

        @Override
        protected void onEnergyChanged(int previousAmount) {
            setChanged();
        }
    }

    public ChronalAcceleratorBlockEntity(BlockPos pos, BlockState state) {
        super(StasisRegistry.CHRONAL_ACCELERATOR_ENTITY.get(), pos, state);
    }

    public SimpleEnergyHandler energy() {
        return store;
    }

    /** The setting's rate. */
    double want() {
        return ChronalAcceleratorBlock.rate(getBlockState().getValue(ChronalAcceleratorBlock.STEP));
    }

    /** The room's draw (W) at a rate. */
    public double drawW(double rate) {
        if (shape == null) return 0;
        return W_PER_BLOCK * shape.volume() * rate * rate * (1 - 0.5 * refined);
    }

    void serverTick(ServerLevel sl) {
        if (ticks++ % 40 == 0) refill(sl);
        double next = 0;
        if (shape != null) {
            for (double r = want(); r >= 1; r -= 0.5) {
                if (store.take((int) Math.ceil(drawW(r) / 20 * FE_PER_J))) {
                    next = r;
                    break;
                }
            }
        }
        if (next != running) {
            running = next;
            publish(sl);
        }
    }

    /** Fill again (and publish if the room changed). */
    void refill(ServerLevel sl) {
        Rooms.Room before = shape;
        fill(sl);
        store.size();
        if (shape == null || before == null || !shape.sameShape(before)) publish(sl);
    }

    void publish(ServerLevel sl) {
        boolean lit = running > 0 && shape != null;
        Rooms.put(sl, worldPosition.asLong(), lit ? withRate(shape, running) : null);
        BlockState st = getBlockState();
        if (st.getValue(ChronalAcceleratorBlock.LIT) != lit) sl.setBlock(worldPosition, st.setValue(ChronalAcceleratorBlock.LIT, lit), Block.UPDATE_CLIENTS);
    }

    static Rooms.Room withRate(Rooms.Room r, double rate) {
        return new Rooms.Room(r.key(), r.x0(), r.y0(), r.z0(), r.sx(), r.sy(), r.sz(), r.bits(), rate);
    }

    /** What a click shows: the room and its draw, or why there's none. */
    public String status() {
        if (shape == null) return why;
        return String.format("Room of %d blocks (%.0f%% refined walls, max %d): %s, %.0f W at %.1fx", shape.volume(), refined * 100,
            maxVolume(refined), running > 0 ? String.format("running at %.1fx", running) : "no power", drawW(want()), want());
    }

    public static int maxVolumeForTests(double refined) {
        return maxVolume(refined);
    }

    static int maxVolume(double refined) {
        return (int) Math.round(MAX_PLAIN + (MAX_REFINED - MAX_PLAIN) * refined);
    }

    /** Flood-fill from each side; the first that stays sealed is the room. */
    void fill(ServerLevel sl) {
        shape = null;
        why = "No sealed riftstone room against it";
        for (Direction d : Direction.values()) {
            BlockPos start = worldPosition.relative(d);
            if (casing(sl, start)) continue;
            String err = fillFrom(sl, start);
            if (err == null) return;
            if (!err.startsWith("Not sealed")) why = err;
        }
    }

    static boolean casing(ServerLevel sl, BlockPos p) {
        return sl.getBlockState(p).is(CASING);
    }

    /** Fill from start; null when it found a sealed room (shape set), else why not. */
    private String fillFrom(ServerLevel sl, BlockPos start) {
        int n = 2 * SPAN - 1, base = SPAN - 1;   // (a box round start that any room within the span fits)
        java.util.BitSet seen = new java.util.BitSet(n * n * n);
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        q.add(start);
        seen.set(idx(base, base, base, n));
        int count = 0, walls = 0, refinedWalls = 0;
        int minX = start.getX(), minY = start.getY(), minZ = start.getZ(), maxX = minX, maxY = minY, maxZ = minZ;
        java.util.BitSet wallSeen = new java.util.BitSet();
        java.util.List<BlockPos> cells = new java.util.ArrayList<>();
        while (!q.isEmpty()) {
            BlockPos p = q.poll();
            if (!sl.isLoaded(p)) return "Not sealed: it reaches unloaded land";
            if (++count > MAX_REFINED) return "Not sealed (or over " + MAX_REFINED + " blocks)";
            cells.add(p);
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
            if (maxX - minX >= SPAN || maxY - minY >= SPAN || maxZ - minZ >= SPAN) return "Not sealed (or wider than " + SPAN + " blocks)";
            for (Direction d : Direction.values()) {
                BlockPos o = p.relative(d);
                int lx = o.getX() - start.getX() + base, ly = o.getY() - start.getY() + base, lz = o.getZ() - start.getZ() + base;
                if (lx < 0 || ly < 0 || lz < 0 || lx >= n || ly >= n || lz >= n) return "Not sealed (or wider than " + SPAN + " blocks)";
                int i = idx(lx, ly, lz, n);
                BlockState s = sl.getBlockState(o);
                if (s.is(CASING)) {
                    if (!wallSeen.get(i)) {
                        wallSeen.set(i);
                        walls++;
                        if (s.is(StasisRegistry.REFINED_RIFTSTONE.get())) refinedWalls++;
                    }
                    continue;
                }
                if (seen.get(i)) continue;
                seen.set(i);
                q.add(o);
            }
        }
        double share = walls == 0 ? 0 : refinedWalls / (double) walls;
        int max = maxVolume(share);
        if (count > max) return String.format("Room of %d blocks: too big for its walls (max %d; refined riftstone allows more)", count, max);
        int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
        long[] bits = new long[(sx * sy * sz + 63) >> 6];
        for (BlockPos c : cells) {
            int i = ((c.getY() - minY) * sz + (c.getZ() - minZ)) * sx + (c.getX() - minX);
            bits[i >> 6] |= 1L << (i & 63);
        }
        refined = share;
        shape = new Rooms.Room(worldPosition.asLong(), minX, minY, minZ, sx, sy, sz, bits, 1);
        return null;
    }

    private static int idx(int x, int y, int z, int n) {
        return (y * n + z) * n + x;
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel sl) Rooms.put(sl, worldPosition.asLong(), null);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        out.putInt("energy", store.amount());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        store.load(in.getIntOr("energy", 0));
    }
}
