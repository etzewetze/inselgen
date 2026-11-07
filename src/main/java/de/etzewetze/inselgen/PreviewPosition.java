package de.etzewetze.inselgen;

import java.util.Locale;

record PreviewPosition(int x, int y, int z) {
    static int[] forward(float yaw) {
        return switch (Math.floorMod(Math.round(yaw / 90), 4)) {
            case 0 -> new int[] { 0, 1 };
            case 1 -> new int[] { -1, 0 };
            case 2 -> new int[] { 0, -1 };
            default -> new int[] { 1, 0 };
        };
    }

    PreviewPosition move(String direction, int steps, float yaw) {
        if (steps < 1 || steps > 100) throw new IllegalArgumentException(
            "Schrittweite muss zwischen 1 und 100 liegen."
        );
        int[] f = forward(yaw);
        int dx = 0,
            dy = 0,
            dz = 0;
        switch (direction.toLowerCase(Locale.ROOT)) {
            case "vor", "forward" -> {
                dx = f[0];
                dz = f[1];
            }
            case "zurueck", "zurück", "back" -> {
                dx = -f[0];
                dz = -f[1];
            }
            case "links", "left" -> {
                dx = f[1];
                dz = -f[0];
            }
            case "rechts", "right" -> {
                dx = -f[1];
                dz = f[0];
            }
            case "hoch", "up" -> dy = 1;
            case "runter", "down" -> dy = -1;
            default -> throw new IllegalArgumentException("Richtung: vor, zurueck, links, rechts, hoch oder runter.");
        }
        return new PreviewPosition(
            Math.addExact(x, dx * steps),
            Math.addExact(y, dy * steps),
            Math.addExact(z, dz * steps)
        );
    }

    void check(SavedStructure template, int minY, int maxY) {
        if (
            x < -29_999_984 ||
            z < -29_999_984 ||
            (long) x + template.width > 29_999_984 ||
            (long) z + template.depth > 29_999_984
        ) throw new IllegalArgumentException("Vorschau überschreitet die Weltgrenze.");
        if (y < minY || (long) y + template.height > maxY) throw new IllegalArgumentException(
            "Vorschau überschreitet die Bauhöhe."
        );
    }
}
