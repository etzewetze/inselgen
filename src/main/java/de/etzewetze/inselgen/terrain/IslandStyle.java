package de.etzewetze.inselgen.terrain;

import java.util.Locale;
import java.util.Optional;

public enum IslandStyle {
    NATUERLICH("natuerlich", 0.045, 0.10, 3),
    BERGIG("bergig", 0.055, 0.22, 5),
    FLACH("flach", 0.008, 0, 0);

    private final String token;
    private final double hills;
    private final double mountains;
    private final int peaks;

    IslandStyle(String token, double hills, double mountains, int peaks) {
        this.token = token;
        this.hills = hills;
        this.mountains = mountains;
        this.peaks = peaks;
    }

    public String token() { return token; }
    double hills() { return hills; }
    double mountains() { return mountains; }
    int peaks() { return peaks; }

    public static Optional<IslandStyle> parse(String token) {
        String normalized = token.toLowerCase(Locale.ROOT).replace("ü", "ue");
        for (IslandStyle style : values()) {
            if (style.token.equals(normalized)) return Optional.of(style);
        }
        return Optional.empty();
    }
}
