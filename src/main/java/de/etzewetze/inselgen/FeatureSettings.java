package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.*;
import java.util.Locale;
import org.bukkit.configuration.file.FileConfiguration;

public record FeatureSettings(double trees, double plants, String tree, int lakes, boolean ores, double oreDensity) {
    public FeatureSettings {
        for (double d : new double[] { trees, plants, oreDensity })
            if (!Double.isFinite(d) || d < 0 || d > 100) throw new IllegalArgumentException(
                "Vegetations- und Erzdichten müssen zwischen 0 und 100 liegen"
            );
        if (lakes < 0 || lakes > 8) throw new IllegalArgumentException("lakes.count muss zwischen 0 und 8 liegen");
        if (tree == null || !tree.matches("[a-z][a-z0-9_-]*")) throw new IllegalArgumentException("Ungültige Baumart");
    }

    public static FeatureSettings defaults() {
        return new FeatureSettings(30, 8, "auto", 1, true, 35);
    }

    public static FeatureSettings load(FileConfiguration config) {
        return new FeatureSettings(
            config.getDouble("vegetation.tree-density", 30),
            config.getDouble("vegetation.plant-density", 8),
            config.getString("vegetation.tree-type", "auto").toLowerCase(Locale.ROOT),
            config.getInt("lakes.count", 1),
            config.getBoolean("ores.enabled", true),
            config.getDouble("ores.density", 35)
        );
    }

    GenerationFeatures resolve(
        GenerationArguments request,
        boolean treesEnabled,
        boolean plantsEnabled,
        TreeLibrary library
    ) {
        var o = request.features();
        String selected = o.tree() == null ? tree : o.tree();
        TreeType type;
        CustomTree custom = null;
        try {
            type = TreeType.valueOf(selected.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            custom = library.get(selected);
            if (custom == null) throw new IllegalArgumentException(
                "Unbekannte Baumart: " + selected + ". Siehe /skyinsel trees"
            );
            type = TreeType.CUSTOM;
        }
        if (type == TreeType.CUSTOM && custom == null) throw new IllegalArgumentException(
            "Bitte den Dateinamen des eigenen Baumes verwenden"
        );
        if (type == TreeType.AUTO) type = TreeType.forBiome(request.biomeKey());
        return new GenerationFeatures(
            o.trees() == null ? (treesEnabled ? trees : 0) : o.trees(),
            o.plants() == null ? (plantsEnabled ? plants : 0) : o.plants(),
            type,
            custom,
            o.lakes() == null ? lakes : o.lakes(),
            o.ores() == null ? ores : o.ores(),
            oreDensity
        );
    }
}
