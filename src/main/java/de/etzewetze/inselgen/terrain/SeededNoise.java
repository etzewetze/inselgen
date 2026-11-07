package de.etzewetze.inselgen.terrain;

/** Immutable, continuous two-dimensional gradient noise. No server API calls. */
final class SeededNoise {
    private final long seed;

    SeededNoise(long seed) { this.seed = seed; }

    double fractal(double x, double z, int octaves) {
        double value = 0, weight = 1, total = 0;
        for (int octave = 0; octave < octaves; octave++) {
            value += noise(x, z) * weight;
            total += weight;
            weight *= 0.5;
            x = x * 2.03 + 17.1;
            z = z * 2.03 - 9.2;
        }
        return Math.clamp(value / total * 1.45, -1, 1);
    }

    private double noise(double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double dx = x - ix, dz = z - iz;
        double u = fade(dx), v = fade(dz);
        return lerp(lerp(gradient(ix, iz, dx, dz), gradient(ix + 1, iz, dx - 1, dz), u),
                lerp(gradient(ix, iz + 1, dx, dz - 1), gradient(ix + 1, iz + 1, dx - 1, dz - 1), u), v);
    }

    private double gradient(int x, int z, double dx, double dz) {
        return switch ((int) mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL)) & 7) {
            case 0 -> dx;
            case 1 -> -dx;
            case 2 -> dz;
            case 3 -> -dz;
            case 4 -> (dx + dz) * 0.7071067811865476;
            case 5 -> (dx - dz) * 0.7071067811865476;
            case 6 -> (-dx + dz) * 0.7071067811865476;
            default -> (-dx - dz) * 0.7071067811865476;
        };
    }

    static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
