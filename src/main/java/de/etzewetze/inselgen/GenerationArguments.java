package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandStyle;
import java.util.Locale;
import java.util.function.LongSupplier;

public record GenerationArguments(int size, long seed, IslandStyle style, String biomeKey, FeatureOverrides features) {
    public GenerationArguments(int size, long seed, IslandStyle style) {
        this(size, seed, style, null);
    }

    public GenerationArguments(int size, long seed, IslandStyle style, String biomeKey) {
        this(size, seed, style, biomeKey, FeatureOverrides.none());
    }

    public static GenerationArguments parse(String[] args, int minimum, int maximum, LongSupplier randomSeed) {
        if (args.length < 1 || args.length > 9) throw new IllegalArgumentException(
            "Benutzung: /skyinsel <Größe> [Seed] [Stil] [biome:beach] [tree:palm] [trees:30] [plants:8] [lakes:1] [ores:true]"
        );
        int size;
        try {
            size = Integer.parseInt(args[0]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Die Größe muss eine ganze Zahl sein.");
        }
        if (size < minimum || size > maximum) throw new IllegalArgumentException(
            "Die Größe muss zwischen " + minimum + " und " + maximum + " liegen (Durchmesser, kein Radius)."
        );
        Long seed = null;
        IslandStyle style = null;
        String biome = null;
        Double trees = null,
            plants = null;
        String tree = null;
        Integer lakes = null;
        Boolean ores = null;
        java.util.Set<String> options = new java.util.HashSet<>();
        for (int i = 1; i < args.length; i++) {
            String token = args[i].toLowerCase(Locale.ROOT);
            if (token.contains(":") && !token.startsWith("biome:")) {
                String key = token.substring(0, token.indexOf(':')),
                    value = token.substring(token.indexOf(':') + 1);
                if (!options.add(key)) throw new IllegalArgumentException("Doppelte Option: " + key);
                try {
                    switch (key) {
                        case "trees" -> trees = percentage(value);
                        case "plants" -> plants = percentage(value);
                        case "tree" -> {
                            if (!value.matches("[a-z][a-z0-9_-]*")) throw new IllegalArgumentException();
                            tree = value;
                        }
                        case "lakes" -> {
                            lakes = Integer.parseInt(value);
                            if (lakes < 0 || lakes > 8) throw new IllegalArgumentException();
                        }
                        case "ores" -> {
                            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException();
                            ores = Boolean.parseBoolean(value);
                        }
                        default -> throw new IllegalArgumentException();
                    }
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException(
                        "Ungültige Option: " + args[i] + " (Dichten 0–100, Seen 0–8, Erze true/false)"
                    );
                }
                continue;
            }
            if (args[i].toLowerCase(Locale.ROOT).startsWith("biome:")) {
                if (biome != null) throw new IllegalArgumentException("Bitte nur ein Biom angeben.");
                biome = args[i].substring(6).toLowerCase(Locale.ROOT);
                if (!biome.contains(":")) biome = "minecraft:" + biome;
                if (!biome.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException(
                    "Ungültiges Biom: " + args[i]
                );
                continue;
            }
            var parsedStyle = IslandStyle.parse(args[i]);
            if (parsedStyle.isPresent()) {
                if (style != null) throw new IllegalArgumentException("Bitte nur einen Inselstil angeben.");
                style = parsedStyle.get();
            } else {
                if (seed != null) throw new IllegalArgumentException("Bitte nur einen Seed angeben.");
                try {
                    seed = Long.parseLong(args[i]);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Unbekannter Stil oder ungültiger Seed: " + args[i]);
                }
            }
        }
        return new GenerationArguments(
            size,
            seed == null ? randomSeed.getAsLong() : seed,
            style == null ? IslandStyle.NATUERLICH : style,
            biome,
            new FeatureOverrides(trees, plants, tree, lakes, ores)
        );
    }

    private static double percentage(String value) {
        double result = Double.parseDouble(value.endsWith("%") ? value.substring(0, value.length() - 1) : value);
        if (!Double.isFinite(result) || result < 0 || result > 100) throw new IllegalArgumentException();
        return result;
    }
}
