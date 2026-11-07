package de.etzewetze.inselgen.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeProfileTest {
    @Test void beachChangesSurfaceAndVegetationWhilePreservingShapeAndHeights() {
        var generator = new IslandGenerator();
        var plain = generator.generate(100, 170, 32, IslandStyle.BERGIG, true, true, () -> false);
        var beach = generator.generate(100, 170, 32, IslandStyle.BERGIG, true, true, BiomeProfile.fromKey("minecraft:beach"), () -> false);
        assertTrue(beach.decorations().isEmpty());
        assertEquals(plain.footprint(), beach.footprint());
        for (int z = 0; z < 100; z++) for (int x = 0; x < 100; x++) {
            var a = plain.column(x, z); var b = beach.column(x, z);
            if (a == null) { assertNull(b); continue; }
            assertEquals(a.top(), b.top()); assertEquals(a.bottom(), b.bottom());
            assertEquals(IslandPlan.BlockKind.SAND, b.blockAt(b.top()));
            assertEquals(IslandPlan.BlockKind.SANDSTONE, b.blockAt(b.top() - 3));
        }
    }
}
