package de.etzewetze.inselgen;

import org.bukkit.configuration.file.FileConfiguration;

public record GenerationSettings(int minimumSize, int maximumSize, int maximumBlocks,
                                 boolean requireEmpty, boolean trees, boolean plants, boolean teleport, FeatureSettings features) {
    public GenerationSettings(int minimumSize,int maximumSize,int maximumBlocks,boolean requireEmpty,boolean trees,boolean plants,boolean teleport) {
        this(minimumSize,maximumSize,maximumBlocks,requireEmpty,trees,plants,teleport,FeatureSettings.defaults());
    }
    public GenerationSettings {
        if (minimumSize < 50 || maximumSize > 500 || minimumSize > maximumSize)
            throw new IllegalArgumentException("minimum-size/maximum-size müssen einen Bereich innerhalb 50–500 bilden.");
        if (maximumBlocks < 1) throw new IllegalArgumentException("maximum-blocks muss positiv und höchstens 2147483647 sein.");
    }

    public static GenerationSettings load(FileConfiguration config) {
        long limit = config.getLong("maximum-blocks", 12_000_000);
        if (limit > Integer.MAX_VALUE || limit < 1) throw new IllegalArgumentException("Ungültiges maximum-blocks.");
        return new GenerationSettings(config.getInt("minimum-size", 50), config.getInt("maximum-size", 500),
                (int) limit, config.getBoolean("require-empty-space", true),
                config.getBoolean("vegetation.trees", true), config.getBoolean("vegetation.plants", true),
                config.getBoolean("teleport-after-generation", true),FeatureSettings.load(config));
    }
}
