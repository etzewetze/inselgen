package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.*;
import org.bukkit.block.Biome;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChunkEditsTest {
    @ParameterizedTest @ValueSource(ints = {-37, -16, -1, 0, 13, 32})
    void partitionsEveryBlockExactlyOnceAndKeepsBiomeCellsInTheOwningChunk(int origin) {
        var plan = new IslandGenerator().generate(50, 170, 731, IslandStyle.NATUERLICH, true, true, () -> false);
        var context = new EditContext(null, UUID.randomUUID(), UUID.randomUUID(), origin, origin - 17, plan,
                new GenerationArguments(50, 731, IslandStyle.NATUERLICH, "minecraft:beach"),
                new GenerationSettings(50, 500, 12000000, true, true, true, true), Biome.BEACH, -64, 180);
        Set<String> coordinates = new HashSet<>(), biomes = new HashSet<>();
        for (var part : ChunkEdits.partition(context)) {
            var blocks = part.blocks(context);
            while (blocks.hasNext()) {
                var block = blocks.next();
                assertEquals(part.x(), block.x() >> 4); assertEquals(part.z(), block.z() >> 4);
                assertTrue(coordinates.add(block.x() + "," + block.y() + "," + block.z()));
            }
            for (var cell : part.biomes(context)) {
                assertEquals(part.x(), cell.x() >> 4); assertEquals(part.z(), cell.z() >> 4);
                assertEquals(0, Math.floorMod(cell.x(), 4)); assertEquals(0, Math.floorMod(cell.y(), 4)); assertEquals(0, Math.floorMod(cell.z(), 4));
                assertTrue(cell.y() >= -64 && cell.y() < 180);
                assertTrue(biomes.add(cell.x() + "," + cell.y() + "," + cell.z()));
            }
        }
        assertEquals(plan.blockCount(), coordinates.size());
        assertFalse(biomes.isEmpty());
    }
}
