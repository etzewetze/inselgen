package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.CustomTree;
import de.etzewetze.inselgen.terrain.IslandPlan.BlockKind;
import java.util.*;

final class TreeExport {

    static CustomTree convert(String id, SavedStructure source) {
        if (source.blockCount() > 4096) throw new IllegalArgumentException(
            "Bäume dürfen höchstens 4096 Blöcke enthalten."
        );
        List<SavedStructure.Point> points = source.preview(4096);
        int floor = points.stream().mapToInt(SavedStructure.Point::y).min().orElseThrow();
        double cx = (source.width - 1) / 2d,
            cz = (source.depth - 1) / 2d;
        Comparator<SavedStructure.Point> roots = Comparator.<SavedStructure.Point>comparingInt(p ->
            stem(source.palette().get(p.state())) ? 0 : 1
        )
            .thenComparingDouble(p -> Math.pow(p.x() - cx, 2) + Math.pow(p.z() - cz, 2))
            .thenComparingInt(SavedStructure.Point::z)
            .thenComparingInt(SavedStructure.Point::x);
        var root = points
            .stream()
            .filter(p -> p.y() == floor)
            .min(roots)
            .orElseThrow();
        List<CustomTree.Block> blocks = points
            .stream()
            .map(p ->
                new CustomTree.Block(p.x() - root.x(), p.y() - floor, p.z() - root.z(), source.palette().get(p.state()))
            )
            .toList();
        try {
            return new CustomTree(
                id,
                blocks,
                Set.of(BlockKind.GRASS_BLOCK, BlockKind.DIRT, BlockKind.SAND, BlockKind.SNOW_BLOCK)
            );
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                "Baum muss um den Stammfuß in X/Z ±15 und Y 0–63 passen. Nur den Baum ohne Boden auswählen.",
                error
            );
        }
    }

    private static boolean stem(String state) {
        String material = state.split("\\[", 2)[0];
        return (
            material.endsWith("_log") ||
            material.endsWith("_wood") ||
            material.endsWith("_stem") ||
            material.endsWith("_roots") ||
            material.endsWith(":bamboo_block")
        );
    }
}
