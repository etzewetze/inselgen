package de.etzewetze.inselgen.terrain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IslandGeneratorTest {
    private final IslandGenerator generator = new IslandGenerator();

    private IslandPlan generate(int size, long seed, IslandStyle style, boolean vegetation) {
        return generator.generate(size, 170, seed, style, vegetation, vegetation, () -> false);
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 100, 500})
    void respectsBoundsAndCountsEveryBlockIncludingUniqueVegetation(int size) {
        IslandPlan plan = generate(size, 731, IslandStyle.NATUERLICH, true);
        long blocks = 0;
        int footprint = 0, min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        double radius = (size - 1) / 2.0;
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                IslandPlan.Column column = plan.column(x, z);
                if (column == null) continue;
                assertTrue(Math.hypot(x - radius, z - radius) <= radius, "Body exceeds maximum diameter");
                assertTrue(column.height() >= 4);
                assertNotNull(column.surface());
                blocks += column.height();
                footprint++;
                min = Math.min(min, column.bottom());
                max = Math.max(max, column.top());
            }
        }
        Set<String> occupied = new HashSet<>();
        for (IslandPlan.Decoration decoration : plan.decorations()) {
            assertTrue(decoration.x() >= 0 && decoration.x() < size && decoration.z() >= 0 && decoration.z() < size);
            IslandPlan.Column column = plan.column(decoration.x(), decoration.z());
            assertNotNull(column, "Vegetation must have island below it");
            assertTrue(decoration.y() > column.top(), "Vegetation must not replace terrain");
            assertTrue(occupied.add(decoration.x() + "," + decoration.y() + "," + decoration.z()), "Duplicate decoration");
            min = Math.min(min, decoration.y());
            max = Math.max(max, decoration.y());
            blocks++;
        }
        assertEquals(blocks, plan.blockCount());
        assertEquals(footprint, plan.footprint());
        assertEquals(min, plan.minY());
        assertEquals(max, plan.maxY());
        assertTrue(plan.blockCount() < 12_000_000);
        assertNotNull(plan.column(size / 2, size / 2));
        assertTrue(footprint > size * size / 4 && footprint < size * size * 0.8);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 731, Long.MIN_VALUE, Long.MAX_VALUE})
    void sameSeedReproducesTerrainAndDecorations(long seed) {
        assertEquals(fingerprint(generate(100, seed, IslandStyle.NATUERLICH, true)),
                fingerprint(generate(100, seed, IslandStyle.NATUERLICH, true)));
    }

    @Test
    void differentSeedsChangeBothOutlineAndTerrain() {
        IslandPlan a = generate(100, 731, IslandStyle.NATUERLICH, false);
        IslandPlan b = generate(100, 732, IslandStyle.NATUERLICH, false);
        assertNotEquals(fingerprint(a), fingerprint(b));
        int changedFootprint = 0;
        for (int z = 0; z < 100; z++) for (int x = 0; x < 100; x++)
            if ((a.column(x, z) == null) != (b.column(x, z) == null)) changedFootprint++;
        assertTrue(changedFootprint > 100, "A new seed must change the silhouette, not just the blocks");
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 42, -731, 99999})
    void footprintRemainsConnectedAndUndersideTapers(long seed) {
        IslandPlan plan = generate(150, seed, IslandStyle.BERGIG, false);
        Set<Integer> reached = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(75 * 150 + 75);
        reached.add(queue.peek());
        while (!queue.isEmpty()) {
            int p = queue.removeFirst(), x = p % 150, z = p / 150;
            for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int nx = x + d[0], nz = z + d[1];
                if (plan.column(nx, nz) != null && reached.add(nz * 150 + nx)) queue.add(nz * 150 + nx);
            }
        }
        assertEquals(plan.footprint(), reached.size(), "Do not leave detached one-block islands");
        double edgeBottom = 0;
        int edges = 0;
        for (int z = 0; z < 150; z++) for (int x = 0; x < 150; x++) {
            var c = plan.column(x, z);
            if (c != null && (plan.column(x - 1, z) == null || plan.column(x + 1, z) == null
                    || plan.column(x, z - 1) == null || plan.column(x, z + 1) == null)) {
                edges++;
                edgeBottom += c.bottom();
            }
        }
        assertTrue(plan.column(75, 75).bottom() < edgeBottom / edges - 15);
    }

    @Test
    void mountainStyleHasMoreReliefThanFlatStyle() {
        for (long seed : new long[] {1, 42, 731}) {
            IslandPlan flat = generate(150, seed, IslandStyle.FLACH, false);
            IslandPlan mountains = generate(150, seed, IslandStyle.BERGIG, false);
            assertTrue(relief(flat) <= 5);
            assertTrue(relief(mountains) >= 15);
            assertTrue(relief(mountains) > relief(flat) + 10);
        }
    }

    @Test
    void vegetationDoesNotChangeTerrainOrObstructSpawn() {
        IslandPlan plain = generate(150, 12345, IslandStyle.NATUERLICH, false);
        IslandPlan green = generate(150, 12345, IslandStyle.NATUERLICH, true);
        for (int z = 0; z < 150; z++) for (int x = 0; x < 150; x++) assertEquals(plain.column(x, z), green.column(x, z));
        assertTrue(plain.decorations().isEmpty());
        assertFalse(green.decorations().isEmpty());
        assertTrue(green.decorations().stream().noneMatch(d -> d.x() == 75 && d.z() == 75));
    }

    @Test
    void cancellationStopsBeforeReturningAnIsland() {
        assertThrows(CancellationException.class, () -> generator.generate(500, 170, 1, IslandStyle.BERGIG, true, true, () -> true));
        AtomicInteger checkpoints = new AtomicInteger();
        assertThrows(CancellationException.class, () -> generator.generate(500, 170, 1, IslandStyle.BERGIG, true, true,
                () -> checkpoints.incrementAndGet() > 60));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 49, 501, 5000})
    void rejectsUnsupportedSizes(int size) {
        assertThrows(IllegalArgumentException.class, () -> generate(size, 1, IslandStyle.NATUERLICH, false));
    }

    @Test
    void grassColumnsHaveCorrectLayersAndRejectOutOfColumnReads() {
        var column = new IslandPlan.Column(100, 110, IslandPlan.Surface.GRASS);
        assertEquals(IslandPlan.BlockKind.GRASS_BLOCK, column.blockAt(110));
        assertEquals(IslandPlan.BlockKind.DIRT, column.blockAt(107));
        assertEquals(IslandPlan.BlockKind.STONE, column.blockAt(106));
        assertThrows(IllegalArgumentException.class, () -> column.blockAt(111));
        assertThrows(IllegalArgumentException.class, () -> column.blockAt(99));
    }

    private static int relief(IslandPlan plan) {
        int low = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
        for (int z = 0; z < plan.size(); z++) for (int x = 0; x < plan.size(); x++) {
            var c = plan.column(x, z);
            if (c != null) { low = Math.min(low, c.top()); high = Math.max(high, c.top()); }
        }
        return high - low;
    }

    private static long fingerprint(IslandPlan plan) {
        long hash = 1;
        for (int z = 0; z < plan.size(); z++) for (int x = 0; x < plan.size(); x++) {
            var c = plan.column(x, z);
            hash = hash * 31 + (c == null ? 0 : c.hashCode());
        }
        return hash * 31 + plan.decorations().hashCode();
    }
}
