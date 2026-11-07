package de.etzewetze.inselgen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StructurePlacementTest {

    @ParameterizedTest
    @ValueSource(ints = { -37, -16, -1, 0, 15, 32 })
    void splitsLongRunsAcrossRowsAndDestinationChunksWithoutLosingStates(int origin) {
        var builder = new SavedStructure.Builder();
        Map<Integer, String> expected = new HashMap<>();
        for (int i = 0; i < 33 * 3 * 35; i++) if (i % 19 != 0) {
            String state = i % 300 < 150 ? "minecraft:cherry_log[axis=x]" : "minecraft:water[level=0]";
            builder.add(i, state);
            expected.put(i, state);
        }
        var template = builder.build(33, 3, 35, 16, 3, 17);
        var placement = new StructurePlacement(template, 57);
        var context = new EditContext(
            null,
            UUID.randomUUID(),
            UUID.randomUUID(),
            origin,
            origin - 5,
            null,
            null,
            new GenerationSettings(50, 500, 12000000, true, true, true, true),
            null,
            -64,
            320,
            placement
        );
        Set<String> seen = new HashSet<>();
        for (var part : ChunkEdits.partition(context)) {
            assertTrue(part.biomes(context).isEmpty());
            var edits = part.blocks(context);
            while (edits.hasNext()) {
                var edit = edits.next();
                assertEquals(part.x(), edit.x() >> 4);
                assertEquals(part.z(), edit.z() >> 4);
                assertTrue(seen.add(edit.x() + "," + edit.y() + "," + edit.z()));
                int index = ((edit.y() - 57) * 35 + edit.z() - origin + 5) * 33 + edit.x() - origin;
                assertEquals(expected.get(index), edit.blockData());
            }
        }
        assertEquals(expected.size(), seen.size());
        assertEquals(expected.size(), context.blockCount());
    }

    @Test
    void surfacePreviewIsBoundedDeterministicAndContainsOnlyRealSurfaceBlocks() {
        var builder = new SavedStructure.Builder();
        for (int i = 0; i < 80 * 12 * 72; i++) builder.add(i, "minecraft:stone");
        var template = builder.build(80, 12, 72, 40, 12, 36);
        var preview = template.preview(300);
        assertEquals(300, preview.size());
        assertEquals(preview, template.preview(300));
        assertEquals(300, new HashSet<>(preview).size());
        assertTrue(preview.stream().allMatch(p -> p.y() == 0 || p.y() == 11));
        assertTrue(preview.stream().anyMatch(p -> p.z() < 10));
        assertTrue(preview.stream().anyMatch(p -> p.z() > 60));
    }

    @Test
    void movementFollowsHorizontalViewAndProtectsBounds() {
        var p = new PreviewPosition(0, 100, 0);
        assertEquals(new PreviewPosition(0, 100, 5), p.move("vor", 5, 0));
        assertEquals(new PreviewPosition(-5, 100, 0), p.move("rechts", 5, 0));
        assertEquals(new PreviewPosition(5, 100, 0), p.move("links", 5, 0));
        assertEquals(new PreviewPosition(-5, 100, 0), p.move("vor", 5, 90));
        assertEquals(new PreviewPosition(0, 100, -5), p.move("vor", 5, 180));
        assertEquals(new PreviewPosition(5, 100, 0), p.move("vor", 5, -90));
        assertEquals(new PreviewPosition(0, 103, 0), p.move("hoch", 3, 77));
        assertEquals(p, p.move("hoch", 3, 0).move("runter", 3, 0));
        assertThrows(IllegalArgumentException.class, () -> p.move("vor", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> p.move("vor", 101, 0));
        assertThrows(IllegalArgumentException.class, () -> p.move("???", 1, 0));
        var builder = new SavedStructure.Builder();
        builder.add(0, "minecraft:stone");
        var template = builder.build(1, 1, 1, 0, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> new PreviewPosition(0, 320, 0).check(template, -64, 320));
        assertThrows(IllegalArgumentException.class, () ->
            new PreviewPosition(-29_999_985, 0, 0).check(template, -64, 320)
        );
    }

    @Test
    void denseObjectsWithFewerSurfacePointsThanTheLimitStillShowTheirWholeSurface() {
        var builder = new SavedStructure.Builder();
        for (int i = 0; i < 22 * 10 * 22; i++) builder.add(i, "minecraft:stone");
        var template = builder.build(22, 10, 22, 11, 10, 11);
        var preview = template.preview(4096);
        assertEquals(22 * 22 * 2, preview.size());
        assertTrue(preview.contains(new SavedStructure.Point(0, 0, 0, 0)));
        assertTrue(preview.contains(new SavedStructure.Point(21, 9, 21, 0)));
    }
}
