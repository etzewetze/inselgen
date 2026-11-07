package de.etzewetze.inselgen.terrain;

import java.util.List;

/** Compact height maps: even a 500-block island does not keep millions of block objects. */
public final class IslandPlan {

    public enum BlockKind {
        STONE,
        ANDESITE,
        DIRT,
        GRASS_BLOCK,
        SNOW_BLOCK,
        SAND,
        SANDSTONE,
        RED_SAND,
        TERRACOTTA,
        MYCELIUM,
        CLAY,
        WATER,
        OAK_LOG,
        OAK_LEAVES,
        BIRCH_LOG,
        BIRCH_LEAVES,
        SPRUCE_LOG,
        SPRUCE_LEAVES,
        JUNGLE_LOG,
        JUNGLE_LEAVES,
        ACACIA_LOG,
        ACACIA_LEAVES,
        DARK_OAK_LOG,
        DARK_OAK_LEAVES,
        CHERRY_LOG,
        CHERRY_LEAVES,
        MANGROVE_LOG,
        MANGROVE_LEAVES,
        MANGROVE_ROOTS,
        SHORT_GRASS,
        FERN,
        POPPY,
        DANDELION,
        COAL_ORE,
        IRON_ORE,
        COPPER_ORE,
        REDSTONE_ORE,
        LAPIS_ORE,
        GOLD_ORE,
        DIAMOND_ORE,
        EMERALD_ORE,
        CUSTOM,
    }

    public enum Surface {
        GRASS,
        ROCK,
        SNOW,
        SAND,
        RED_SAND,
        MYCELIUM,
        LAKE_BED,
    }

    public record Decoration(int x, int y, int z, BlockKind kind, String blockData) {
        public Decoration(int x, int y, int z, BlockKind kind) {
            this(x, y, z, kind, null);
        }
    }

    public record Column(int bottom, int top, Surface surface, int groundTop, OreField ores, int x, int z) {
        public Column(int bottom, int top, Surface surface) {
            this(bottom, top, surface, top, null, 0, 0);
        }

        public int height() {
            return top - bottom + 1;
        }

        public boolean wet() {
            return top > groundTop;
        }

        public BlockKind blockAt(int y) {
            if (y < bottom || y > top) throw new IllegalArgumentException("Block außerhalb der Säule");
            if (y > groundTop) return BlockKind.WATER;
            if (y < groundTop - 5 && y > bottom + 1 && ores != null) return ores.at(x, y, z);
            if (surface == Surface.LAKE_BED) return y == groundTop
                ? BlockKind.CLAY
                : (y >= groundTop - 2 ? BlockKind.DIRT : BlockKind.STONE);
            if (surface == Surface.ROCK) return y >= groundTop - 1 ? BlockKind.ANDESITE : BlockKind.STONE;
            if (surface == Surface.SAND || surface == Surface.RED_SAND) {
                if (y >= groundTop - 2) return surface == Surface.SAND ? BlockKind.SAND : BlockKind.RED_SAND;
                if (y >= groundTop - 5) return surface == Surface.SAND ? BlockKind.SANDSTONE : BlockKind.TERRACOTTA;
                return BlockKind.STONE;
            }
            if (surface == Surface.MYCELIUM && y == groundTop) return BlockKind.MYCELIUM;
            if (y == groundTop) return surface == Surface.SNOW ? BlockKind.SNOW_BLOCK : BlockKind.GRASS_BLOCK;
            return y >= groundTop - 3 ? BlockKind.DIRT : BlockKind.STONE;
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
    private final int[] water;
    private final OreField ores;
    private final int lakes;

    IslandPlan(int size, int[] tops, int[] bottoms, Surface[] surfaces, List<Decoration> decorations) {
        this(size, tops, bottoms, surfaces, decorations, emptyWater(size), null, 0);
    }

    IslandPlan(
        int size,
        int[] tops,
        int[] bottoms,
        Surface[] surfaces,
        List<Decoration> decorations,
        int[] water,
        OreField ores,
        int lakes
    ) {
        this.size = size;
        this.tops = tops;
        this.bottoms = bottoms;
        this.surfaces = surfaces;
        this.decorations = List.copyOf(decorations);
        this.water = water;
        this.ores = ores;
        this.lakes = lakes;
        long blocks = decorations.size();
        int columns = 0,
            low = Integer.MAX_VALUE,
            high = Integer.MIN_VALUE;
        for (int i = 0; i < bottoms.length; i++) {
            if (bottoms[i] == Integer.MIN_VALUE) continue;
            columns++;
            int highBlock = Math.max(tops[i], water[i]);
            blocks += highBlock - bottoms[i] + 1L;
            low = Math.min(low, bottoms[i]);
            high = Math.max(high, highBlock);
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

    public int size() {
        return size;
    }

    public long blockCount() {
        return blockCount;
    }

    public int footprint() {
        return footprint;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public int lakes() {
        return lakes;
    }

    public List<Decoration> decorations() {
        return decorations;
    }

    public int centerTop() {
        return column(size / 2, size / 2).top();
    }

    public Column column(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) return null;
        int index = z * size + x;
        return bottoms[index] == Integer.MIN_VALUE
            ? null
            : new Column(bottoms[index], Math.max(tops[index], water[index]), surfaces[index], tops[index], ores, x, z);
    }

    static int[] emptyWater(int size) {
        int[] values = new int[size * size];
        java.util.Arrays.fill(values, Integer.MIN_VALUE);
        return values;
    }
}
