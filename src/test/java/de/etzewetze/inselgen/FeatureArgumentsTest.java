package de.etzewetze.inselgen;

import static org.junit.jupiter.api.Assertions.*;

import de.etzewetze.inselgen.terrain.TreeType;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FeatureArgumentsTest {

    private GenerationArguments parse(String... args) {
        return GenerationArguments.parse(args, 50, 500, () -> 32);
    }

    @Test
    void acceptsAllOptionsInAnyOrderAndKeepsTheSeed() {
        var a = parse(
            "300",
            "32",
            "bergig",
            "biome:beach",
            "tree:palm",
            "trees:50",
            "plants:12.5%",
            "lakes:2",
            "ores:true"
        );
        var b = parse(
            "300",
            "ORES:TRUE",
            "trees:50%",
            "lakes:2",
            "plants:12.5",
            "TREE:PALM",
            "biome:beach",
            "bergig",
            "32"
        );
        assertEquals(a, b);
        assertEquals(new FeatureOverrides(50d, 12.5, "palm", 2, true), a.features());
        assertEquals("my_palm", parse("50", "tree:my_palm").features().tree());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "trees:-1",
            "trees:101",
            "trees:nan",
            "trees:infinity",
            "trees:5%0",
            "trees:",
            "plants:-0.1",
            "plants:101",
            "lakes:-1",
            "lakes:9",
            "lakes:1.5",
            "ores:yes",
            "tree:../oak",
            "unknown:1",
        }
    )
    void rejectsInvalidOptions(String option) {
        assertThrows(IllegalArgumentException.class, () -> parse("100", option));
    }

    @Test
    void rejectsEveryDuplicateFeature() {
        for (String option : new String[] { "trees:10", "plants:5", "tree:oak", "lakes:1", "ores:true" })
            assertThrows(IllegalArgumentException.class, () -> parse("100", option, option));
    }

    @Test
    void defaultsAndOverridesResolveBeforeStarting(@TempDir Path directory) {
        TreeLibrary library = TreeLibrary.load(directory);
        var config = new YamlConfiguration();
        var settings = FeatureSettings.load(config);
        var defaults = settings.resolve(parse("100", "biome:beach"), true, true, library);
        assertEquals(TreeType.PALM, defaults.treeType());
        assertEquals(30, defaults.trees());
        assertEquals(8, defaults.plants());
        assertEquals(1, defaults.lakes());
        assertTrue(defaults.ores());
        var disabled = settings.resolve(
            parse("100", "tree:none", "trees:0", "plants:0", "lakes:0", "ores:false"),
            true,
            true,
            library
        );
        assertEquals(TreeType.NONE, disabled.treeType());
        assertEquals(0, disabled.trees());
        assertEquals(0, disabled.plants());
        assertEquals(0, disabled.lakes());
        assertFalse(disabled.ores());
        assertThrows(IllegalArgumentException.class, () ->
            settings.resolve(parse("100", "tree:missing"), true, true, library)
        );
        config.set("vegetation.tree-density", 101);
        assertThrows(IllegalArgumentException.class, () -> FeatureSettings.load(config));
    }
}
