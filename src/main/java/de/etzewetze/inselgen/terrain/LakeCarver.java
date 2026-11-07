package de.etzewetze.inselgen.terrain;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class LakeCarver {

    private static final int[][] DIRECTIONS = { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } };

    static int carve(
        int size,
        int[] ground,
        int[] bottoms,
        int[] water,
        int requested,
        long seed,
        BooleanSupplier cancelled
    ) {
        if (requested == 0) return 0;
        SplittableRandom random = new SplittableRandom(seed ^ 0x7CE05EEDL);
        SeededNoise noise = new SeededNoise(seed ^ 0x7A7E12L);
        int made = 0,
            center = size / 2;
        for (int attempt = 0; attempt < requested * 160 && made < requested; attempt++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            int radius = Math.clamp((int) Math.round(size * random.nextDouble(.035, .065)), 3, 20);
            int cx = random.nextInt(radius + 3, size - radius - 3),
                cz = random.nextInt(radius + 3, size - radius - 3);
            if (Math.hypot(cx - center, cz - center) < radius + 8) continue;
            Set<Integer> cells = new HashSet<>();
            boolean invalid = false;
            for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) {
                double edge = radius * (.88 + .10 * noise.fractal((cx + dx) * .17, (cz + dz) * .17, 2));
                if (Math.hypot(dx, dz) > edge) continue;
                int index = (cz + dz) * size + cx + dx;
                if (bottoms[index] == Integer.MIN_VALUE || water[index] != Integer.MIN_VALUE) invalid = true;
                cells.add(index);
            }
            if (invalid || cells.isEmpty()) continue;
            int level = Integer.MAX_VALUE,
                highest = Integer.MIN_VALUE;
            for (int index : cells) {
                highest = Math.max(highest, ground[index]);
                int x = index % size,
                    z = index / size;
                for (int[] d : DIRECTIONS) {
                    int neighbor = (z + d[1]) * size + x + d[0];
                    if (!cells.contains(neighbor)) {
                        if (bottoms[neighbor] == Integer.MIN_VALUE || water[neighbor] != Integer.MIN_VALUE) invalid =
                            true;
                        level = Math.min(level, ground[neighbor] - 1);
                    }
                }
            }
            if (invalid || highest - level > 8) continue;
            for (int index : cells) {
                double distance = Math.hypot((index % size) - cx, index / size - cz) / radius;
                int depth = 1 + (int) Math.round(3 * Math.max(0, 1 - distance * distance));
                int bed = Math.min(ground[index], level - depth);
                if (bed - bottoms[index] < 3) {
                    invalid = true;
                    break;
                }
            }
            if (invalid) continue;
            for (int index : cells) {
                double distance = Math.hypot((index % size) - cx, index / size - cz) / radius;
                int depth = 1 + (int) Math.round(3 * Math.max(0, 1 - distance * distance));
                ground[index] = Math.min(ground[index], level - depth);
                water[index] = level;
            }
            made++;
        }
        return made;
    }
}
