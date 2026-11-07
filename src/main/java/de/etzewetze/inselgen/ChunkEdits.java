package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandPlan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** Chunk partitions contain height maps and a few decorations, not millions of block objects. */
final class ChunkEdits {
    record BlockEdit(int x, int y, int z, IslandPlan.BlockKind kind) { }
    record BiomeCell(int x, int y, int z) { }
    record Part(int x, int z, int minX, int minZ, int maxX, int maxZ, List<IslandPlan.Decoration> decorations) {
        Iterator<BlockEdit> blocks(EditContext context) { return new Cursor(context, this); }

        List<BiomeCell> biomes(EditContext context) {
            if (context.biome() == null) return List.of();
            Set<BiomeCell> cells = new HashSet<>();
            for (int z = minZ; z < maxZ; z++) for (int x = minX; x < maxX; x++) {
                var column = context.plan().column(x, z);
                if (column == null) continue;
                int bx = Math.floorDiv(context.originX() + x, 4) * 4;
                int bz = Math.floorDiv(context.originZ() + z, 4) * 4;
                for (int y = Math.max(context.minY(), Math.floorDiv(column.bottom(), 4) * 4); y <= Math.min(context.maxY() - 1, column.top() + 8); y += 4)
                    cells.add(new BiomeCell(bx, y, bz));
            }
            return cells.stream().sorted(Comparator.comparingInt(BiomeCell::z).thenComparingInt(BiomeCell::x).thenComparingInt(BiomeCell::y)).toList();
        }
    }

    static List<Part> partition(EditContext context) {
        int size = context.plan().size(), ox = context.originX(), oz = context.originZ();
        Map<Long, List<IslandPlan.Decoration>> decorations = new HashMap<>();
        for (var d : context.plan().decorations()) decorations.computeIfAbsent(key((ox + d.x()) >> 4, (oz + d.z()) >> 4), ignored -> new ArrayList<>()).add(d);
        List<Part> result = new ArrayList<>();
        for (int cz = oz >> 4; cz <= (oz + size - 1) >> 4; cz++) for (int cx = ox >> 4; cx <= (ox + size - 1) >> 4; cx++) {
            result.add(new Part(cx, cz, Math.max(0, cx * 16 - ox), Math.max(0, cz * 16 - oz),
                    Math.min(size, cx * 16 + 16 - ox), Math.min(size, cz * 16 + 16 - oz), decorations.getOrDefault(key(cx, cz), List.of())));
        }
        return result;
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static final class Cursor implements Iterator<BlockEdit> {
        private final EditContext context;
        private final Part part;
        private int x, z, y, decoration;
        private IslandPlan.Column column;
        private BlockEdit next;

        Cursor(EditContext context, Part part) { this.context = context; this.part = part; x = part.minX(); z = part.minZ(); advance(); }
        @Override public boolean hasNext() { return next != null; }
        @Override public BlockEdit next() { if (next == null) throw new NoSuchElementException(); var value = next; advance(); return value; }

        private void advance() {
            while (z < part.maxZ()) {
                if (column == null) { column = context.plan().column(x, z); if (column != null) y = column.bottom(); }
                if (column != null && y <= column.top()) { next = new BlockEdit(context.originX() + x, y, context.originZ() + z, column.blockAt(y++)); return; }
                column = null;
                if (++x >= part.maxX()) { x = part.minX(); z++; }
            }
            if (decoration < part.decorations().size()) {
                var d = part.decorations().get(decoration++);
                next = new BlockEdit(context.originX() + d.x(), d.y(), context.originZ() + d.z(), d.kind());
            } else next = null;
        }
    }
}
