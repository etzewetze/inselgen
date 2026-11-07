package de.etzewetze.inselgen.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

import static de.etzewetze.inselgen.terrain.IslandPlan.Surface.*;

/** Seed, size, style and vegetation flags fully determine the result, independent of world coordinates. */
public final class IslandGenerator {
    private record Peak(double x, double z, double width, double height) { }

    public IslandPlan generate(int size, int baseY, long seed, IslandStyle style,
                               boolean trees, boolean plants, BooleanSupplier cancelled) {
        return generate(size, baseY, seed, style, trees, plants, BiomeProfile.DEFAULT, cancelled);
    }

    public IslandPlan generate(int size, int baseY, long seed, IslandStyle style,
                               boolean trees, boolean plants, BiomeProfile biome, BooleanSupplier cancelled) {
        return generate(size, baseY, seed, style, biome, GenerationFeatures.legacy(trees, plants), cancelled, ignored -> { });
    }

    public IslandPlan generate(int size, int baseY, long seed, IslandStyle style, BiomeProfile biome,
                               GenerationFeatures features, BooleanSupplier cancelled, DoubleConsumer progress) {
        if (size < 50 || size > 500) throw new IllegalArgumentException("Größe muss zwischen 50 und 500 liegen");
        if (baseY < -4096 || baseY > 4096) throw new IllegalArgumentException("Ungültige Basishöhe");
        SplittableRandom random = new SplittableRandom(seed);
        SeededNoise coast = new SeededNoise(seed ^ 0x43F0B716L);
        SeededNoise terrain = new SeededNoise(seed ^ 0xA385BE6DL);
        SeededNoise underside = new SeededNoise(seed ^ 0x7E1337BAL);
        double rotation = random.nextDouble(Math.PI * 2);
        double aspect = random.nextDouble(0.70, 0.96);
        double phase = random.nextDouble(Math.PI * 2);
        double notchAngle = random.nextDouble(Math.PI * 2);
        double notchWidth = random.nextDouble(0.20, 0.48);
        double notchDepth = random.nextDouble(0.13, 0.32);
        double center = (size - 1) / 2.0;
        double depth = Math.clamp(size * 0.24, 13, 100);
        double hills = Math.clamp(size * style.hills(), 0.8, 22);
        double mountainHeight = Math.min(size * style.mountains(), 90);
        List<Peak> peaks = new ArrayList<>();
        for (int i = 0; i < style.peaks(); i++) {
            double angle = random.nextDouble(Math.PI * 2), distance = random.nextDouble(0.05, 0.48);
            peaks.add(new Peak(Math.cos(angle) * distance, Math.sin(angle) * distance,
                    random.nextDouble(0.14, 0.28), random.nextDouble(0.55, 1.0)));
        }

        // A continuous radial outline keeps the main island connected. Angular notches
        // and an independently rotated ellipse prevent a circular or repeated silhouette.
        double[] outline = new double[2048];
        double largestRadius = 0;
        for (int i = 0; i < outline.length; i++) {
            double angle = i * Math.PI * 2 / outline.length;
            double ellipse = 1 / Math.sqrt(Math.pow(Math.cos(angle - rotation), 2)
                    + Math.pow(Math.sin(angle - rotation) / aspect, 2));
            double delta = Math.atan2(Math.sin(angle - notchAngle), Math.cos(angle - notchAngle));
            double notch = notchDepth * Math.exp(-delta * delta / (2 * notchWidth * notchWidth));
            double irregular = 0.06 * Math.sin(3 * angle + phase) + 0.045 * Math.sin(5 * angle - phase)
                    + 0.10 * coast.fractal(Math.cos(angle) * 2.7 + 8.3, Math.sin(angle) * 2.7 - 4.7, 3);
            outline[i] = ellipse * Math.clamp(0.92 + irregular - notch, 0.48, 1.10);
            largestRadius = Math.max(largestRadius, outline[i]);
        }
        for (int i = 0; i < outline.length; i++) outline[i] *= 0.995 / largestRadius;

        int[] tops = new int[size * size], bottoms = new int[size * size];
        IslandPlan.Surface[] surfaces = new IslandPlan.Surface[size * size];
        Arrays.fill(bottoms, Integer.MIN_VALUE);
        for (int z = 0; z < size; z++) {
            checkCancelled(cancelled);
            progress.accept(0.7 * z / size);
            for (int x = 0; x < size; x++) {
                double nx = (x - center) / center, nz = (z - center) / center;
                double angle = Math.atan2(nz, nx);
                if (angle < 0) angle += Math.PI * 2;
                double sample = angle * outline.length / (Math.PI * 2);
                int a = (int) sample % outline.length;
                double radius = outline[a] + (outline[(a + 1) % outline.length] - outline[a]) * (sample - Math.floor(sample));
                double distance = Math.hypot(nx, nz) / radius;
                if (distance > 1) continue;
                double interior = Math.max(0, 1 - distance * distance);
                double hill = terrain.fractal(nx * 2.5 + 3.7, nz * 2.5 - 8.9, 4) * hills;
                double mountain = 0;
                for (Peak peak : peaks) {
                    double dx = nx - peak.x(), dz = nz - peak.z();
                    mountain = Math.max(mountain, peak.height() * Math.exp(-(dx * dx + dz * dz) / (2 * peak.width() * peak.width())));
                }
                double ridge = 0.80 + 0.20 * terrain.fractal(nx * 6.4 + 10.2, nz * 6.4 + 5.9, 3);
                int top = baseY + (int) Math.round(hill * (0.3 + 0.7 * interior) + mountain * ridge * mountainHeight * Math.sqrt(interior));
                double roughness = underside.fractal(nx * 4.8 - 11.7, nz * 4.8 + 6.1, 3);
                int bottom = baseY - 3 - (int) Math.round(depth * Math.pow(interior, 1.15) * (1 + 0.16 * roughness));
                int index = z * size + x;
                tops[index] = top;
                bottoms[index] = Math.min(bottom, top - 3);
            }
        }
        keepConnectedFootprint(size, bottoms, cancelled);
        int[] water = IslandPlan.emptyWater(size);
        int lakes = LakeCarver.carve(size, tops, bottoms, water, features.lakes(), seed, cancelled);
        progress.accept(0.8);
        for (int z = 0; z < size; z++) {
            checkCancelled(cancelled);
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (bottoms[i] == Integer.MIN_VALUE) continue;
                int slope = 0;
                for (int[] offset : NEIGHBORS) {
                    int xx = x + offset[0], zz = z + offset[1];
                    if (xx >= 0 && zz >= 0 && xx < size && zz < size && bottoms[zz * size + xx] != Integer.MIN_VALUE)
                        slope = Math.max(slope, Math.abs(tops[i] - tops[zz * size + xx]));
                }
                if (water[i] != Integer.MIN_VALUE) surfaces[i] = biome == BiomeProfile.SAND ? SAND : LAKE_BED;
                else if (biome != BiomeProfile.DEFAULT) surfaces[i] = IslandPlan.Surface.valueOf(biome.name());
                else if (style == IslandStyle.BERGIG && tops[i] > baseY + mountainHeight * 0.60 && slope <= 2) surfaces[i] = SNOW;
                else surfaces[i] = slope >= 3 ? ROCK : GRASS;
            }
        }
        progress.accept(0.9);
        List<IslandPlan.Decoration> decorations = VegetationGenerator.generate(size, tops, bottoms, water, surfaces, seed, features, cancelled);
        OreField ores = features.ores() && features.oreDensity() > 0 ? new OreField(seed, features.oreDensity(), baseY) : null;
        progress.accept(1);
        return new IslandPlan(size, tops, bottoms, surfaces, decorations, water, ores, lakes);
    }

    private static final int[][] NEIGHBORS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};

    private static void keepConnectedFootprint(int size, int[] bottoms, BooleanSupplier cancelled) {
        boolean[] visited = new boolean[bottoms.length];
        int[] queue = new int[bottoms.length];
        int first = (size / 2) * size + size / 2;
        int head = 0, tail = 1;
        queue[0] = first;
        visited[first] = true;
        while (head < tail) {
            if ((head & 1023) == 0) checkCancelled(cancelled);
            int current = queue[head++], x = current % size, z = current / size;
            for (int[] offset : NEIGHBORS) {
                int xx = x + offset[0], zz = z + offset[1];
                if (xx < 0 || zz < 0 || xx >= size || zz >= size) continue;
                int neighbor = zz * size + xx;
                if (!visited[neighbor] && bottoms[neighbor] != Integer.MIN_VALUE) {
                    visited[neighbor] = true;
                    queue[tail++] = neighbor;
                }
            }
        }
        for (int i = 0; i < bottoms.length; i++) if (!visited[i]) bottoms[i] = Integer.MIN_VALUE;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Abgebrochen");
    }
}
