package de.etzewetze.inselgen;

public record FeatureOverrides(Double trees, Double plants, String tree, Integer lakes, Boolean ores) {
    public static FeatureOverrides none() {
        return new FeatureOverrides(null, null, null, null, null);
    }
}
