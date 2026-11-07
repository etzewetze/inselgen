package de.etzewetze.inselgen;

import static org.junit.jupiter.api.Assertions.*;

import de.etzewetze.inselgen.terrain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateStoreTest {

    @TempDir
    Path directory;

    private SavedStructure sample() {
        var builder = new SavedStructure.Builder();
        for (int i = 0; i < 240; i++) if (i % 7 != 0) builder.add(
            i,
            i % 3 == 0 ? "minecraft:oak_log[axis=x]" : "minecraft:water[level=0]"
        );
        return builder.build(10, 3, 8, 5, 3, 4);
    }

    @Test
    void islandRoundTripRetainsSparseVoxelsStatesAnchorAndSurvivesReopening() throws Exception {
        var store = new TemplateStore(directory);
        var source = sample();
        store.saveIsland("test_island", source);
        assertEquals(List.of("test_island"), new TemplateStore(directory).names());
        var loaded = store.loadIsland("test_island", 1000);
        assertEquals(source.width, loaded.width);
        assertEquals(source.height, loaded.height);
        assertEquals(source.depth, loaded.depth);
        assertEquals(source.anchorX, loaded.anchorX);
        assertEquals(source.anchorY, loaded.anchorY);
        assertEquals(source.anchorZ, loaded.anchorZ);
        assertEquals(source.palette(), loaded.palette());
        assertEquals(source.preview(4096), loaded.preview(4096));
        assertThrows(IllegalArgumentException.class, () -> store.loadIsland("test_island", 100));
        byte[] before = Files.readAllBytes(directory.resolve("islands/test_island.insel"));
        assertThrows(IllegalArgumentException.class, () -> store.saveIsland("test_island", source));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("islands/test_island.insel")));
        try (var files = Files.list(directory.resolve("islands"))) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void treeExportFindsStemRootAndProducesTheExistingYamlFormat() throws Exception {
        var builder = new SavedStructure.Builder();
        builder.add((3 * 5 + 2) * 5 + 2, "minecraft:jungle_log[axis=y]");
        builder.add((4 * 5 + 2) * 5 + 2, "minecraft:jungle_log[axis=x]");
        builder.add((5 * 5 + 3) * 5 + 1, "minecraft:jungle_leaves[distance=7,persistent=true,waterlogged=false]");
        var tree = TreeExport.convert("my_palm", builder.build(5, 7, 5, 2, 6, 2));
        assertTrue(tree.blocks().contains(new CustomTree.Block(0, 0, 0, "minecraft:jungle_log[axis=y]")));
        assertTrue(tree.blocks().contains(new CustomTree.Block(0, 1, 0, "minecraft:jungle_log[axis=x]")));
        var store = new TemplateStore(directory);
        store.saveTree(tree);
        var yaml = YamlConfiguration.loadConfiguration(directory.resolve("trees/my_palm.yml").toFile());
        assertEquals(3, yaml.getMapList("blocks").size());
        assertEquals(Set.of("grass_block", "dirt", "sand", "snow_block"), new HashSet<>(yaml.getStringList("soil")));
        assertThrows(IllegalArgumentException.class, () -> store.saveTree(tree));
        var imported = SavedStructure.tree(tree);
        assertEquals(2, imported.width);
        assertEquals(3, imported.height);
        assertEquals(2, imported.depth);
        assertTrue(
            imported
                .preview(4096)
                .stream()
                .anyMatch(
                    p ->
                        p.x() == imported.anchorX &&
                        p.z() == imported.anchorZ &&
                        p.y() == 0 &&
                        imported.palette().get(p.state()).equals("minecraft:jungle_log[axis=y]")
                )
        );
    }

    @Test
    void rejectsPathTraversalReservedNamesAndUnsupportedHeadersBeforeAllocation() throws Exception {
        var store = new TemplateStore(directory);
        for (String name : List.of("../test", "Test", "a/b", "", ".", "a".repeat(33)))
            assertThrows(IllegalArgumentException.class, () -> TemplateStore.name(name));
        assertThrows(IllegalArgumentException.class, () -> store.checkNew("palm", true));
        writeHeader("bad_version", 0x494e534c, 999, 1, 1, 1);
        writeHeader("bad_magic", 0, 1, 1, 1, 1);
        writeHeader("overflow", 0x494e534c, 1, Integer.MIN_VALUE, 2, 3);
        for (String name : List.of("bad_version", "bad_magic", "overflow"))
            assertThrows(IllegalArgumentException.class, () -> store.loadIsland(name, 1000));
        store.saveIsland("truncated", sample());
        Path file = directory.resolve("islands/truncated.insel");
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length / 2));
        assertThrows(IOException.class, () -> store.loadIsland("truncated", 1000));
    }

    private void writeHeader(String name, int magic, int version, int width, int height, int depth) throws Exception {
        try (
            var out = new DataOutputStream(
                new GZIPOutputStream(Files.newOutputStream(directory.resolve("islands/" + name + ".insel")))
            )
        ) {
            out.writeInt(magic);
            out.writeInt(version);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(depth);
        }
    }

    @Test
    void rejectsOverlapsOutOfBoundsAndInvalidPalettes() {
        assertThrows(IllegalArgumentException.class, () ->
            new SavedStructure(
                10,
                2,
                10,
                5,
                2,
                5,
                List.of("minecraft:stone"),
                new long[] { SavedStructure.pack(0, 5, 0), SavedStructure.pack(3, 2, 0) }
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SavedStructure(
                10,
                2,
                10,
                5,
                2,
                5,
                List.of("minecraft:stone"),
                new long[] { SavedStructure.pack(199, 2, 0) }
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SavedStructure(
                10,
                2,
                10,
                5,
                2,
                5,
                List.of("minecraft:stone"),
                new long[] { SavedStructure.pack(0, 1, 1) }
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SavedStructure(
                10,
                2,
                10,
                5,
                2,
                5,
                List.of("minecraft:stone", "minecraft:stone"),
                new long[] { SavedStructure.pack(0, 1, 0) }
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SavedStructure(
                10,
                2,
                10,
                5,
                2,
                5,
                List.of("minecraft:stone{bad:nbt}"),
                new long[] { SavedStructure.pack(0, 1, 0) }
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new SelectionBox(UUID.randomUUID(), 0, 0, 0, 499, 1023, 499)
        );
    }
}
