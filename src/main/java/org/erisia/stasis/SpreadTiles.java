package org.erisia.stasis;

import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's spread map: the rank tiles the server sent around the player (StasisNet.SpreadTile, Spread.Sync), read
 * through SpreadMap.rank1024 exactly as the server reads its SpreadMap, so both sides give the same answer for every
 * column the tiles cover (stopped-water collision, frozen textures). A tile is TILE x TILE cells (1024 blocks square),
 * tile (tx, tz) holding cells 32tx..32tx+31 (global cell indices, SpreadMap.CELL blocks each), row by row (z outer).
 * <p>
 * A cell no tile covers reads MISSING: above every P, so it runs (the client's "no map yet" answer, kept per cell).
 * The server sends the tiles a player's loaded chunks need before they matter (Sync.RANGE) and tells the client to
 * forget the far ones, so a missing cell is only ever far away or for a moment after login. Thread-safe: mesh workers
 * read it while the main thread adds and drops tiles.
 */
public final class SpreadTiles implements SpreadRanks {
    /** Cells per tile side. */
    public static final int TILE = 32;
    public static final int TILE_CELLS = TILE * TILE;
    /** A cell no tile covers: runs at every P (P is at most N). */
    public static final int MISSING = Integer.MAX_VALUE;

    private final ConcurrentHashMap<Long, int[]> tiles = new ConcurrentHashMap<>();
    /** Bumped on every add, drop or clear: readers that cache answers (the shader's window) compare it. */
    private volatile int generation;

    public static long key(int tx, int tz) {
        return (long) tx << 32 | (tz & 0xFFFFFFFFL);
    }

    public static int keyX(long key) {
        return (int) (key >> 32);
    }

    public static int keyZ(long key) {
        return (int) key;
    }

    @Override
    public int rank(int cellX, int cellZ) {
        int[] t = tiles.get(key(Math.floorDiv(cellX, TILE), Math.floorDiv(cellZ, TILE)));
        return t == null ? MISSING : t[Math.floorMod(cellX, TILE) + Math.floorMod(cellZ, TILE) * TILE];
    }

    public void put(int tx, int tz, int[] ranks) {
        if (ranks.length != TILE_CELLS) throw new IllegalArgumentException("spread tile: " + ranks.length + " cells");
        tiles.put(key(tx, tz), ranks);
        generation++;
    }

    public void remove(long key) {
        if (tiles.remove(key) != null) generation++;
    }

    public void clear() {
        tiles.clear();
        generation++;
    }

    public boolean has(int tx, int tz) {
        return tiles.containsKey(key(tx, tz));
    }

    public int size() {
        return tiles.size();
    }

    public int generation() {
        return generation;
    }

    /** The cells of tile (tx, tz) of any map (the server cuts its SpreadMap into these). */
    public static int[] cut(SpreadRanks src, int tx, int tz) {
        int[] out = new int[TILE_CELLS];
        for (int k = 0; k < TILE; k++)
            for (int i = 0; i < TILE; i++) out[i + k * TILE] = src.rank(tx * TILE + i, tz * TILE + k);
        return out;
    }

    /**
     * The tiles whose cells the columns x0..x1 (blocks) need: rank1024 reads cell floor((x - 16) / 32) and the next one.
     * Returns {first tile, last tile}.
     */
    public static int[] tilesFor(int x0, int x1) {
        int c0 = Math.floorDiv(x0 - SpreadMap.HALF, SpreadMap.CELL), c1 = Math.floorDiv(x1 - SpreadMap.HALF, SpreadMap.CELL) + 1;
        return new int[]{Math.floorDiv(c0, TILE), Math.floorDiv(c1, TILE)};
    }
}
