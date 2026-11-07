package de.etzewetze.inselgen.terrain;

import static de.etzewetze.inselgen.terrain.IslandPlan.BlockKind.*;

/** One bounded ellipsoid per 8³ cell: coherent veins without storing millions of voxels. */
public record OreField(long seed, double density, int baseY) {
    public IslandPlan.BlockKind at(int x, int y, int z) {
        int cy = Math.floorDiv(y - baseY, 8);
        long hash = SeededNoise.mix(
            seed ^
                ((long) Math.floorDiv(x, 8) * 0x9E3779B97F4A7C15L) ^
                ((long) cy * 0xC2B2AE3D27D4EB4FL) ^
                ((long) Math.floorDiv(z, 8) * 0x165667B19E3779F9L)
        );
        if ((hash >>> 11) * 0x1.0p-53 * 100 >= density) return STONE;
        double dx = Math.floorMod(x, 8) - (3 + ((hash >>> 3) & 1));
        double dy = Math.floorMod(y - baseY, 8) - (3 + ((hash >>> 4) & 1));
        double dz = Math.floorMod(z, 8) - (3 + ((hash >>> 5) & 1));
        if ((dx * dx) / 6.25 + (dy * dy) / 4.0 + (dz * dz) / 6.25 > 1) return STONE;
        int choice = (int) Math.floorMod(SeededNoise.mix(hash), 100);
        if (choice < 35) return COAL_ORE;
        if (choice < 60) return IRON_ORE;
        if (choice < 75) return COPPER_ORE;
        if (choice < 84) return REDSTONE_ORE;
        if (choice < 90) return LAPIS_ORE;
        if (choice < 95) return GOLD_ORE;
        if (choice < 99) return DIAMOND_ORE;
        return EMERALD_ORE;
    }
}
