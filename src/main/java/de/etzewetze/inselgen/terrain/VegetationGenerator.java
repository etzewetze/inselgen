package de.etzewetze.inselgen.terrain;

import static de.etzewetze.inselgen.terrain.IslandPlan.BlockKind.*;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Seeded candidates, portable procedural species and validated custom block templates. */
final class VegetationGenerator {

    private record Position(int x, int y, int z) {}

    private record Value(IslandPlan.BlockKind kind, String state) {}

    static List<IslandPlan.Decoration> generate(
        int size,
        int[] ground,
        int[] bottoms,
        int[] water,
        IslandPlan.Surface[] surfaces,
        long seed,
        GenerationFeatures features,
        BooleanSupplier cancelled
    ) {
        Map<Position, Value> blocks = new LinkedHashMap<>();
        TreeType type = features.treeType();
        int radius = switch (type) {
            case PALM, CHERRY -> 6;
            case DARK_OAK, ACACIA, JUNGLE, MANGROVE, SPRUCE -> 3;
            case CUSTOM -> features.customTree().radius();
            default -> 2;
        };
        int spacing = Math.max(6, radius * 2 + 1),
            margin = radius + 2;
        int trees = 0;
        if (features.trees() > 0 && type != TreeType.NONE) {
            for (int z = margin; z < size - margin; z += spacing) {
                check(cancelled);
                for (int x = margin; x < size - margin; x += spacing) {
                    var random = new SplittableRandom(
                        SeededNoise.mix(seed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL))
                    );
                    int tx = x + random.nextInt(-1, 2),
                        tz = z + random.nextInt(-1, 2);
                    if (random.nextDouble() * 100 >= features.trees()) continue;
                    if (plant(size, tx, tz, ground, bottoms, water, surfaces, features, random, blocks)) trees++;
                }
            }
            // Small/hilly islands should still get a tree when at least one suitable spot exists.
            if (trees == 0) {
                var random = new SplittableRandom(seed ^ 0x52EEDL);
                int start = random.nextInt(size * size);
                for (int n = 0; n < size * size; n++) {
                    if ((n & 255) == 0) check(cancelled);
                    int index = (start + n) % (size * size);
                    if (
                        plant(
                            size,
                            index % size,
                            index / size,
                            ground,
                            bottoms,
                            water,
                            surfaces,
                            features,
                            random,
                            blocks
                        )
                    ) break;
                }
            }
        }
        for (int z = 0; z < size; z++) {
            check(cancelled);
            for (int x = 0; x < size; x++) {
                int i = z * size + x;
                if (
                    features.plants() <= 0 ||
                    bottoms[i] == Integer.MIN_VALUE ||
                    water[i] != Integer.MIN_VALUE ||
                    surfaces[i] != IslandPlan.Surface.GRASS ||
                    Math.hypot(x - size / 2, z - size / 2) < 2
                ) continue;
                long hash = SeededNoise.mix(
                    seed ^ 0xF10AAL ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL)
                );
                if ((hash >>> 11) * 0x1.0p-53 * 100 >= features.plants()) continue;
                IslandPlan.BlockKind kind = Math.floorMod(hash, 23) == 0
                    ? POPPY
                    : (Math.floorMod(hash, 29) == 0
                              ? DANDELION
                              : ((type == TreeType.SPRUCE || type == TreeType.JUNGLE) && Math.floorMod(hash, 4) == 0
                                        ? FERN
                                        : SHORT_GRASS));
                blocks.putIfAbsent(new Position(x, ground[i] + 1, z), new Value(kind, null));
            }
        }
        List<IslandPlan.Decoration> result = new ArrayList<>(blocks.size());
        blocks.forEach((p, v) -> result.add(new IslandPlan.Decoration(p.x, p.y, p.z, v.kind, v.state)));
        return result;
    }

    private static boolean plant(
        int size,
        int x,
        int z,
        int[] ground,
        int[] bottoms,
        int[] water,
        IslandPlan.Surface[] surfaces,
        GenerationFeatures features,
        SplittableRandom random,
        Map<Position, Value> occupied
    ) {
        if (
            x < 3 || z < 3 || x >= size - 3 || z >= size - 3 || Math.hypot(x - size / 2, z - size / 2) < 7
        ) return false;
        int index = z * size + x;
        if (bottoms[index] == Integer.MIN_VALUE || water[index] != Integer.MIN_VALUE) return false;
        TreeType type = features.treeType();
        if (type == TreeType.OAK && surfaces[index] != IslandPlan.Surface.GRASS) return false;
        int base = ground[index] + 1;
        Map<Position, Value> tree = new LinkedHashMap<>();
        if (type == TreeType.CUSTOM) {
            var custom = features.customTree();
            var root = new IslandPlan.Column(bottoms[index], ground[index], surfaces[index]).blockAt(ground[index]);
            if (!custom.soil().contains(root)) return false;
            for (var block : custom.blocks())
                tree.put(new Position(block.x(), block.y(), block.z()), new Value(CUSTOM, block.state()));
        } else build(tree, type, random);
        for (var entry : tree.entrySet()) {
            Position p = entry.getKey();
            int xx = x + p.x,
                zz = z + p.z;
            if (xx < 0 || zz < 0 || xx >= size || zz >= size) return false;
            int i = zz * size + xx;
            if (bottoms[i] == Integer.MIN_VALUE || water[i] != Integer.MIN_VALUE) return false;
            boolean leaf = entry.getValue().kind.name().endsWith("_LEAVES");
            if (base + p.y <= ground[i] && (!leaf || type == TreeType.CUSTOM)) return false;
            if (!leaf && occupied.containsKey(new Position(xx, base + p.y, zz))) return false;
        }
        for (var entry : tree.entrySet()) {
            Position p = entry.getKey();
            int xx = x + p.x,
                zz = z + p.z,
                yy = base + p.y;
            if (yy <= ground[zz * size + xx]) continue;
            occupied.putIfAbsent(new Position(xx, yy, zz), entry.getValue());
        }
        return true;
    }

    private static void build(Map<Position, Value> tree, TreeType type, SplittableRandom random) {
        String family = type == TreeType.PALM ? "JUNGLE" : type.name();
        IslandPlan.BlockKind log = IslandPlan.BlockKind.valueOf(family + "_LOG"),
            leaves = IslandPlan.BlockKind.valueOf(family + "_LEAVES");
        int height = random.nextInt(5, 8);
        if (type == TreeType.PALM) {
            height = random.nextInt(8, 12);
            int direction = random.nextBoolean() ? 1 : -1;
            for (int y = 0; y < height; y++) put(tree, y < 4 ? 0 : (direction * (y - 3)) / 3, y, 0, log);
            int tip = (direction * (height - 4)) / 3;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) put(tree, tip + dx, height, dz, leaves);
            for (int[] d : new int[][] {
                { 1, 0 },
                { -1, 0 },
                { 0, 1 },
                { 0, -1 },
                { 1, 1 },
                { -1, 1 },
                { 1, -1 },
                { -1, -1 },
            }) {
                int length = d[0] * d[1] == 0 ? 4 : 3;
                for (int step = 1; step <= length; step++) put(
                    tree,
                    tip + d[0] * step,
                    height - (step >= 3 ? step - 2 : 0),
                    d[1] * step,
                    leaves
                );
            }
            return;
        }
        if (type == TreeType.SPRUCE) {
            height = random.nextInt(7, 11);
            for (int y = 2; y <= height + 1; y++) {
                int radius = Math.max(0, Math.min(3, (height - y + 2) / 2));
                disk(tree, 0, y, 0, radius, leaves);
            }
        } else if (type == TreeType.ACACIA) {
            int direction = random.nextBoolean() ? 1 : -1;
            for (int y = 0; y < height; y++) put(tree, y > 2 ? (direction * (y - 2)) / 2 : 0, y, 0, log);
            int tip = (direction * (height - 3)) / 2;
            disk(tree, tip, height, 0, 3, leaves);
            disk(tree, tip, height + 1, 0, 2, leaves);
            return;
        } else if (type == TreeType.CHERRY) {
            for (int dx : new int[] { -2, 2 }) {
                for (int step = 1; step <= 2; step++) put(tree, (dx / 2) * step, height - 3 + step, 0, log);
                crown(tree, dx, height, 0, 2, leaves);
            }
            crown(tree, 0, height + 1, 0, 2, leaves);
        } else {
            if (type == TreeType.JUNGLE || type == TreeType.MANGROVE) height += 3;
            crown(tree, 0, height - 1, 0, type == TreeType.DARK_OAK || type == TreeType.JUNGLE ? 3 : 2, leaves);
        }
        int width = type == TreeType.DARK_OAK ? 2 : 1;
        for (int y = 0; y < height; y++) for (int dx = 0; dx < width; dx++) for (int dz = 0; dz < width; dz++) put(
            tree,
            dx,
            y,
            dz,
            log
        );
        if (type == TreeType.MANGROVE) for (int[] d : new int[][] { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } })
            for (int y = 0; y < 3; y++) put(tree, d[0], y, d[1], MANGROVE_ROOTS);
    }

    private static void crown(Map<Position, Value> tree, int x, int y, int z, int radius, IslandPlan.BlockKind leaves) {
        for (int dy = -2; dy <= 2; dy++) disk(
            tree,
            x,
            y + dy,
            z,
            Math.max(1, radius - (Math.abs(dy) == 2 ? 1 : 0)),
            leaves
        );
    }

    private static void disk(Map<Position, Value> tree, int x, int y, int z, int radius, IslandPlan.BlockKind kind) {
        for (int dz = -radius; dz <= radius; dz++) for (int dx = -radius; dx <= radius; dx++) if (
            dx * dx + dz * dz <= radius * radius + 1
        ) put(tree, x + dx, y, z + dz, kind);
    }

    private static void put(Map<Position, Value> tree, int x, int y, int z, IslandPlan.BlockKind kind) {
        tree.put(new Position(x, y, z), new Value(kind, null));
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
