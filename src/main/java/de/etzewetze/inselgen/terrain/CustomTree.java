package de.etzewetze.inselgen.terrain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record CustomTree(String id, List<Block> blocks, Set<IslandPlan.BlockKind> soil) {
    public record Block(int x, int y, int z, String state) {}

    public CustomTree {
        if (id == null || !id.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException(
            "Ungültiger Baumname"
        );
        blocks = List.copyOf(blocks);
        soil = Set.copyOf(soil);
        if (blocks.isEmpty() || blocks.size() > 4096) throw new IllegalArgumentException(
            "Ein eigener Baum benötigt 1–4096 Blöcke"
        );
        Set<String> positions = new HashSet<>();
        boolean root = false;
        for (Block b : blocks) {
            if (
                b.x() < -15 ||
                b.x() > 15 ||
                b.z() < -15 ||
                b.z() > 15 ||
                b.y() < 0 ||
                b.y() > 63 ||
                b.state() == null ||
                b.state().isBlank()
            ) throw new IllegalArgumentException("Baumblöcke müssen innerhalb X/Z ±15 und Y 0–63 liegen");
            if (!positions.add(b.x() + "," + b.y() + "," + b.z())) throw new IllegalArgumentException(
                "Doppelte Baumposition"
            );
            root |= b.x() == 0 && b.y() == 0 && b.z() == 0;
        }
        if (!root) throw new IllegalArgumentException("Der Stammfuß bei 0,0,0 fehlt");
    }

    public int radius() {
        return blocks
            .stream()
            .mapToInt(b -> Math.max(Math.abs(b.x()), Math.abs(b.z())))
            .max()
            .orElse(1);
    }
}
