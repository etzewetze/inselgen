package de.etzewetze.inselgen.terrain;

import java.util.Locale;

public enum TreeType {
    AUTO,
    NONE,
    OAK,
    BIRCH,
    SPRUCE,
    JUNGLE,
    ACACIA,
    DARK_OAK,
    CHERRY,
    MANGROVE,
    PALM,
    CUSTOM;

    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static TreeType forBiome(String key) {
        String biome = key == null ? "plains" : key.replace("minecraft:", "");
        if (biome.equals("beach")) return PALM;
        if (biome.contains("birch")) return BIRCH;
        if (
            biome.contains("taiga") || biome.contains("snow") || biome.contains("frozen") || biome.equals("grove")
        ) return SPRUCE;
        if (biome.contains("jungle")) return JUNGLE;
        if (biome.contains("savanna")) return ACACIA;
        if (biome.contains("cherry")) return CHERRY;
        if (biome.contains("mangrove")) return MANGROVE;
        if (biome.equals("dark_forest")) return DARK_OAK;
        if (
            biome.contains("desert") ||
            biome.contains("badlands") ||
            biome.contains("mushroom") ||
            biome.contains("ocean") ||
            biome.contains("nether") ||
            biome.contains("end") ||
            biome.contains("basalt") ||
            biome.contains("soul_sand") ||
            biome.equals("warped_forest") ||
            biome.equals("crimson_forest") ||
            biome.equals("the_void")
        ) return NONE;
        return OAK;
    }
}
