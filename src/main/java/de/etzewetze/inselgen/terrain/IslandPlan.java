package de.etzewetze.inselgen.terrain;

import java.util.List;

/** Compact height maps: even a 500-block island does not keep millions of block objects. */
public final class IslandPlan {
    public enum BlockKind { STONE, ANDESITE, DIRT, GRASS_BLOCK, SNOW_BLOCK, SAND, SANDSTONE, RED_SAND, TERRACOTTA, MYCELIUM, OAK_LOG, OAK_LEAVES, SHORT_GRASS, POPPY }
    public enum Surface { GRASS, ROCK, SNOW, SAND, RED_SAND, MYCELIUM }
    public record Decoration(int x, int y, int z, BlockKind kind) { }
    public record Column(int bottom, int top, Surface surface) {
        public int height() { return top - bottom + 1; }

        public BlockKind blockAt(int y) {
            if (y < bottom || y > top) throw new IllegalArgumentException("Block außerhalb der Säule");
            if (surface == Surface.ROCK) return y >= top - 1 ? BlockKind.ANDESITE : BlockKind.STONE;
            if (surface == Surface.SAND || surface == Surface.RED_SAND) {
                if (y >= top - 2) return surface == Surface.SAND ? BlockKind.SAND : BlockKind.RED_SAND;
                if (y >= top - 5) return surface == Surface.SAND ? BlockKind.SANDSTONE : BlockKind.TERRACOTTA;
                return BlockKind.STONE;
            }
            if (surface == Surface.MYCELIUM && y == top) return BlockKind.MYCELIUM;
            if (y == top) return surface == Surface.SNOW ? BlockKind.SNOW_BLOCK : BlockKind.GRASS_BLOCK;
            return y >= top - 3 ? BlockKind.DIRT : BlockKind.STONE;
        }
    }

    private final int size;
    private final int[] tops;
    private final int[] bottoms;
    private final Surface[] surfaces;
    private final List<Decoration> decorations;
    private final long blockCount;
    private final int footprint;
    private final int minY;
    private final int maxY;

    IslandPlan(int size, int[] tops, int[] bottoms, Surface[] surfaces, List<Decoration> decorations) {
        this.size = size;
        this.tops = tops;
        this.bottoms = bottoms;
        this.surfaces = surfaces;
        this.decorations = List.copyOf(decorations);
        long blocks = decorations.size();
        int columns = 0, low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int i = 0; i < bottoms.length; i++) {
            if (bottoms[i] == Integer.MIN_VALUE) continue;
            columns++;
            blocks += tops[i] - bottoms[i] + 1L;
            low = Math.min(low, bottoms[i]);
            high = Math.max(high, tops[i]);
        }
        for (Decoration decoration : decorations) {
            low = Math.min(low, decoration.y());
            high = Math.max(high, decoration.y());
        }
        blockCount = blocks;
        footprint = columns;
        minY = low;
        maxY = high;
    }

    public int size() { return size; }
    public long blockCount() { return blockCount; }
    public int footprint() { return footprint; }
    public int minY() { return minY; }
    public int maxY() { return maxY; }
    public List<Decoration> decorations() { return decorations; }
    public int centerTop() { return column(size / 2, size / 2).top(); }

    public Column column(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) return null;
        int index = z * size + x;
        return bottoms[index] == Integer.MIN_VALUE ? null : new Column(bottoms[index], tops[index], surfaces[index]);
    }
}
