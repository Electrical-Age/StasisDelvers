package org.erisia.stasis;

/**
 * The spread map's cell ranks by global cell coordinate (cell i covers blocks 32i..32i+31; SpreadMap.CELL), -1 where
 * the land is stilled from the start or unknown. The server's is the SpreadMap itself; CLIENT STEP: the client's will be
 * a cache of the rank tiles the server sends around each player. SpreadMap.rank1024 reads either the same way.
 */
public interface SpreadRanks {
    int rank(int cellX, int cellZ);
}
