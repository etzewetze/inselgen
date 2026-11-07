package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandStyle;

import java.util.function.LongSupplier;
import java.util.Locale;

public record GenerationArguments(int size, long seed, IslandStyle style, String biomeKey) {
    public GenerationArguments(int size, long seed, IslandStyle style) { this(size, seed, style, null); }

    public static GenerationArguments parse(String[] args, int minimum, int maximum, LongSupplier randomSeed) {
        if (args.length < 1 || args.length > 4) throw new IllegalArgumentException("Benutzung: /skyinsel <Größe> [Seed] [natuerlich|bergig|flach] [biome:beach]");
        int size;
        try { size = Integer.parseInt(args[0]); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Die Größe muss eine ganze Zahl sein."); }
        if (size < minimum || size > maximum) throw new IllegalArgumentException("Die Größe muss zwischen " + minimum + " und " + maximum + " liegen (Durchmesser, kein Radius).");
        Long seed = null;
        IslandStyle style = null;
        String biome = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].toLowerCase(Locale.ROOT).startsWith("biome:")) {
                if (biome != null) throw new IllegalArgumentException("Bitte nur ein Biom angeben.");
                biome = args[i].substring(6).toLowerCase(Locale.ROOT);
                if (!biome.contains(":")) biome = "minecraft:" + biome;
                if (!biome.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Ungültiges Biom: " + args[i]);
                continue;
            }
            var parsedStyle = IslandStyle.parse(args[i]);
            if (parsedStyle.isPresent()) {
                if (style != null) throw new IllegalArgumentException("Bitte nur einen Inselstil angeben.");
                style = parsedStyle.get();
            } else {
                if (seed != null) throw new IllegalArgumentException("Bitte nur einen Seed angeben.");
                try { seed = Long.parseLong(args[i]); }
                catch (NumberFormatException error) { throw new IllegalArgumentException("Unbekannter Stil oder ungültiger Seed: " + args[i]); }
            }
        }
        return new GenerationArguments(size, seed == null ? randomSeed.getAsLong() : seed,
                style == null ? IslandStyle.NATUERLICH : style, biome);
    }
}
