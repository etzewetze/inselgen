package de.etzewetze.inselgen.terrain;

import static de.etzewetze.inselgen.terrain.IslandPlan.BlockKind.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class GenerationFeaturesTest {

    private IslandPlan plan(int size, long seed, BiomeProfile biome, GenerationFeatures features) {
        return new IslandGenerator().generate(
            size,
            170,
            seed,
            IslandStyle.NATUERLICH,
            biome,
            features,
            () -> false,
            ignored -> {}
        );
    }

    private GenerationFeatures features(double trees, double plants, TreeType type, int lakes, boolean ores) {
        return new GenerationFeatures(trees, plants, type, null, lakes, ores, 50);
    }

    @Test
    void densitiesChangeTreeAndPlantCountsWithoutChangingTerrain() {
        var empty = plan(150, 32, BiomeProfile.DEFAULT, features(0, 0, TreeType.OAK, 0, false));
        var sparse = plan(150, 32, BiomeProfile.DEFAULT, features(10, 5, TreeType.OAK, 0, false));
        var dense = plan(150, 32, BiomeProfile.DEFAULT, features(100, 80, TreeType.OAK, 0, false));
        assertTrue(empty.decorations().isEmpty());
        assertTrue(count(dense, OAK_LOG) > count(sparse, OAK_LOG) * 2);
        assertTrue(count(dense, SHORT_GRASS) > count(sparse, SHORT_GRASS) * 2);
        for (int z = 0; z < 150; z++) for (int x = 0; x < 150; x++) assertEquals(
            empty.column(x, z),
            dense.column(x, z)
        );
        validate(dense);
    }

    @ParameterizedTest
    @EnumSource(value = TreeType.class, names = { "AUTO", "NONE", "CUSTOM" }, mode = EnumSource.Mode.EXCLUDE)
    void everyTreeSpeciesIsReproducibleAndFitsTheTerrain(TreeType type) {
        BiomeProfile biome = type == TreeType.PALM ? BiomeProfile.SAND : BiomeProfile.DEFAULT;
        var a = plan(100, 32, biome, features(100, 0, type, 0, false));
        var b = plan(100, 32, biome, features(100, 0, type, 0, false));
        assertFalse(a.decorations().isEmpty(), type.name());
        assertEquals(a.decorations(), b.decorations());
        String family = type == TreeType.PALM ? "JUNGLE" : type.name();
        assertTrue(
            a
                .decorations()
                .stream()
                .anyMatch(d -> d.kind().name().equals(family + "_LOG"))
        );
        validate(a);
    }

    @Test
    void automaticTreeSpeciesFollowBiomes() {
        Map<String, TreeType> expected = Map.of(
            "beach",
            TreeType.PALM,
            "birch_forest",
            TreeType.BIRCH,
            "snowy_taiga",
            TreeType.SPRUCE,
            "jungle",
            TreeType.JUNGLE,
            "savanna",
            TreeType.ACACIA,
            "dark_forest",
            TreeType.DARK_OAK,
            "cherry_grove",
            TreeType.CHERRY,
            "mangrove_swamp",
            TreeType.MANGROVE,
            "desert",
            TreeType.NONE
        );
        expected.forEach((biome, tree) -> assertEquals(tree, TreeType.forBiome("minecraft:" + biome)));
    }

    @ParameterizedTest
    @ValueSource(ints = { 50, 100, 500 })
    void lakesHaveSolidBedsAndClosedBanksAndLeaveSpawnDry(int size) {
        for (long seed : new long[] { 32, 731, -17 }) {
            var a = plan(size, seed, BiomeProfile.DEFAULT, features(30, 8, TreeType.OAK, 3, true));
            assertTrue(a.lakes() > 0 && a.lakes() <= 3, "size=" + size + " seed=" + seed);
            assertFalse(a.column(size / 2, size / 2).wet());
            for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
                var c = a.column(x, z);
                if (c == null || !c.wet()) continue;
                assertEquals(WATER, c.blockAt(c.top()));
                assertNotEquals(WATER, c.blockAt(c.groundTop()));
                assertTrue(c.groundTop() - c.bottom() >= 3);
                for (int[] d : new int[][] { { -1, 0 }, { 1, 0 }, { 0, -1 }, { 0, 1 } }) {
                    var n = a.column(x + d[0], z + d[1]);
                    assertNotNull(n);
                    if (n.wet()) assertEquals(c.top(), n.top());
                    else assertTrue(n.groundTop() > c.top(), "Water must have a raised bank");
                }
            }
            validate(a);
        }
    }

    @Test
    void oresAreSeededVeinsInsideTheStoneCore() {
        var a = plan(100, 32, BiomeProfile.DEFAULT, features(0, 0, TreeType.NONE, 0, true));
        var b = plan(100, 32, BiomeProfile.DEFAULT, features(0, 0, TreeType.NONE, 0, false));
        var repeat = plan(100, 32, BiomeProfile.DEFAULT, features(0, 0, TreeType.NONE, 0, true));
        Set<IslandPlan.BlockKind> ores = new HashSet<>();
        int count = 0;
        for (int z = 0; z < 100; z++) for (int x = 0; x < 100; x++) {
            var c = a.column(x, z);
            if (c == null) continue;
            var plain = b.column(x, z);
            var same = repeat.column(x, z);
            for (int y = c.bottom(); y <= c.top(); y++) {
                var block = c.blockAt(y);
                assertEquals(block, same.blockAt(y));
                if (block.name().endsWith("_ORE")) {
                    count++;
                    ores.add(block);
                    assertEquals(STONE, plain.blockAt(y));
                    assertTrue(y < c.groundTop() - 5 && y > c.bottom() + 1);
                } else assertEquals(plain.blockAt(y), block);
            }
        }
        assertTrue(count > 100);
        assertTrue(ores.size() >= 6);
    }

    @Test
    void customTemplatesKeepTheirStatesAndRejectMalformedTrees() {
        var root = new CustomTree.Block(0, 0, 0, "minecraft:jungle_log[axis=y]");
        var custom = new CustomTree(
            "test_palm",
            List.of(root, new CustomTree.Block(0, 1, 0, "minecraft:jungle_log[axis=x]")),
            Set.of(SAND)
        );
        var features = new GenerationFeatures(100, 0, TreeType.CUSTOM, custom, 0, false, 0);
        var beach = plan(100, 32, BiomeProfile.SAND, features);
        assertFalse(beach.decorations().isEmpty());
        assertTrue(
            beach
                .decorations()
                .stream()
                .allMatch(d -> d.kind() == CUSTOM && d.blockData().startsWith("minecraft:jungle_log[axis="))
        );
        assertTrue(
            plan(100, 32, BiomeProfile.DEFAULT, features).decorations().isEmpty(),
            "Custom soil constraints apply"
        );
        validate(beach);
        assertThrows(IllegalArgumentException.class, () ->
            new CustomTree("duplicate", List.of(root, root), Set.of(SAND))
        );
        assertThrows(IllegalArgumentException.class, () ->
            new CustomTree(
                "outside",
                List.of(root, new CustomTree.Block(Integer.MIN_VALUE, 2, 0, "minecraft:stone")),
                Set.of(SAND)
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new CustomTree("no_root", List.of(new CustomTree.Block(0, 1, 0, "minecraft:stone")), Set.of(SAND))
        );
    }

    private long count(IslandPlan plan, IslandPlan.BlockKind kind) {
        return plan
            .decorations()
            .stream()
            .filter(d -> d.kind() == kind)
            .count();
    }

    private void validate(IslandPlan plan) {
        Set<String> seen = new HashSet<>();
        long blocks = 0;
        for (int z = 0; z < plan.size(); z++) for (int x = 0; x < plan.size(); x++) {
            var c = plan.column(x, z);
            if (c != null) blocks += c.height();
        }
        for (var d : plan.decorations()) {
            var c = plan.column(d.x(), d.z());
            assertNotNull(c);
            assertTrue(d.y() > c.top());
            assertFalse(c.wet());
            assertTrue(seen.add(d.x() + "," + d.y() + "," + d.z()));
            assertFalse(d.x() == plan.size() / 2 && d.z() == plan.size() / 2);
            blocks++;
        }
        assertEquals(blocks, plan.blockCount());
    }
}
