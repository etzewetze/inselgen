package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandStyle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BiomeArgumentsTest {
    private GenerationArguments parse(String... args) { return GenerationArguments.parse(args, 50, 500, () -> 731); }

    @Test void supportsRequestedSyntaxAndArbitraryOptionOrder() {
        assertEquals(new GenerationArguments(300, 731, IslandStyle.NATUERLICH, "minecraft:beach"), parse("300", "biome:beach"));
        assertEquals(new GenerationArguments(300, 32, IslandStyle.BERGIG, "minecraft:beach"), parse("300", "32", "bergig", "biome:beach"));
        assertEquals(parse("300", "32", "bergig", "biome:beach"), parse("300", "biome:minecraft:beach", "bergig", "32"));
        assertEquals("custom:some/biome", parse("300", "biome:custom:some/biome").biomeKey());
        assertEquals("minecraft:beach", parse("300", "BIOME:BEACH").biomeKey());
    }

    @Test void rejectsMissingMalformedAndDuplicateBiomes() {
        for (String option : new String[] {"biome:", "biome:minecraft:", "biome:a:b:c", "biome:be ach"}) {
            assertThrows(IllegalArgumentException.class, () -> parse("100", option));
        }
        assertThrows(IllegalArgumentException.class, () -> parse("100", "biome:beach", "biome:desert"));
    }
}
