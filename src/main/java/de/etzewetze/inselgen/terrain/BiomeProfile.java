package de.etzewetze.inselgen.terrain;

/** Surface families; the actual biome is independently resolved from the server registry. */
public enum BiomeProfile {
    DEFAULT, SAND, RED_SAND, SNOW, MYCELIUM;

    public static BiomeProfile fromKey(String key) {
        if (key == null || !key.startsWith("minecraft:")) return DEFAULT;
        String name = key.substring(10);
        if (name.equals("beach") || name.equals("desert")) return SAND;
        if (name.contains("badlands")) return RED_SAND;
        if (name.equals("mushroom_fields")) return MYCELIUM;
        if (name.startsWith("snowy_") || name.startsWith("frozen_") || name.equals("ice_spikes")) return SNOW;
        return DEFAULT;
    }
}
