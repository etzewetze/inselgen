package de.etzewetze.inselgen.terrain;

public record GenerationFeatures(
    double trees,
    double plants,
    TreeType treeType,
    CustomTree customTree,
    int lakes,
    boolean ores,
    double oreDensity
) {
    public GenerationFeatures {
        for (double value : new double[] { trees, plants, oreDensity }) {
            if (!Double.isFinite(value) || value < 0 || value > 100) throw new IllegalArgumentException(
                "Dichten müssen zwischen 0 und 100 liegen"
            );
        }
        if (lakes < 0 || lakes > 8) throw new IllegalArgumentException("Es sind 0–8 Seen möglich");
        if (treeType == null || treeType == TreeType.AUTO) throw new IllegalArgumentException(
            "Baumart wurde nicht aufgelöst"
        );
        if (treeType == TreeType.CUSTOM && customTree == null) throw new IllegalArgumentException("Eigener Baum fehlt");
    }

    public static GenerationFeatures legacy(boolean trees, boolean plants) {
        return new GenerationFeatures(trees ? 15 : 0, plants ? 5 : 0, TreeType.OAK, null, 0, false, 35);
    }
}
