package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.file.YamlConfiguration;

/** Portable YAML trees; no WorldEdit classes, schematics or resource pack required. */
final class TreeLibrary {
    static final int MAX_FILE_SIZE = 1024 * 1024;

    private final Map<String, CustomTree> trees;

    private TreeLibrary(Map<String, CustomTree> trees) {
        this.trees = Map.copyOf(trees);
    }

    CustomTree get(String name) {
        return trees.get(name);
    }

    List<String> names() {
        return trees.keySet().stream().sorted().toList();
    }

    TreeLibrary with(CustomTree tree) {
        Map<String, CustomTree> replacement = new HashMap<>(trees);
        replacement.put(tree.id(), tree);
        return new TreeLibrary(replacement);
    }

    static TreeLibrary load(Path directory) {
        try {
            Files.createDirectories(directory);
            Map<String, CustomTree> result = new HashMap<>();
            List<Path> files;
            try (var stream = Files.list(directory)) {
                files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
            }
            if (files.size() > 32) throw new IllegalArgumentException("Höchstens 32 eigene Baumdateien sind möglich");
            for (Path path : files) {
                if (Files.size(path) > MAX_FILE_SIZE) throw new IllegalArgumentException(
                    "Baumdatei ist zu groß: " + path.getFileName()
                );
                String id = path.getFileName().toString().replaceFirst("\\.yml$", "");
                if (
                    Arrays.stream(TreeType.values()).anyMatch(t -> t.token().equals(id))
                ) throw new IllegalArgumentException("Reservierter Baumname: " + id);
                var yaml = new YamlConfiguration();
                try {
                    yaml.load(path.toFile());
                } catch (Exception error) {
                    throw new IllegalArgumentException("Ungültige Baumdatei: " + id, error);
                }
                List<CustomTree.Block> blocks = new ArrayList<>();
                if (
                    !yaml.isList("blocks") || yaml.getList("blocks").size() != yaml.getMapList("blocks").size()
                ) throw new IllegalArgumentException("blocks muss eine Liste von Blockeinträgen sein: " + id);
                for (var map : yaml.getMapList("blocks")) {
                    if (
                        !(map.get("x") instanceof Number x) ||
                        !(map.get("y") instanceof Number y) ||
                        !(map.get("z") instanceof Number z) ||
                        !(map.get("block") instanceof String state)
                    ) throw new IllegalArgumentException("Baumblock benötigt x, y, z und block: " + id);
                    if (
                        x.doubleValue() != x.intValue() ||
                        y.doubleValue() != y.intValue() ||
                        z.doubleValue() != z.intValue()
                    ) throw new IllegalArgumentException("Baumkoordinaten müssen ganzzahlig sein: " + id);
                    var data = Bukkit.createBlockData(state);
                    if (
                        data.getMaterial().isAir() ||
                        data.getMaterial() == Material.WATER ||
                        data.getMaterial() == Material.LAVA
                    ) throw new IllegalArgumentException(
                        "Eigene Bäume dürfen keine Luft oder Flüssigkeiten enthalten: " + id
                    );
                    if (data instanceof Leaves leaves) leaves.setPersistent(true);
                    blocks.add(new CustomTree.Block(x.intValue(), y.intValue(), z.intValue(), data.getAsString()));
                }
                Set<IslandPlan.BlockKind> soil = new HashSet<>();
                for (String kind : yaml.getStringList("soil"))
                    soil.add(IslandPlan.BlockKind.valueOf(kind.replace("minecraft:", "").toUpperCase(Locale.ROOT)));
                if (soil.isEmpty()) soil.addAll(
                    Set.of(
                        IslandPlan.BlockKind.GRASS_BLOCK,
                        IslandPlan.BlockKind.DIRT,
                        IslandPlan.BlockKind.SAND,
                        IslandPlan.BlockKind.SNOW_BLOCK
                    )
                );
                result.put(id, new CustomTree(id, blocks, soil));
            }
            return new TreeLibrary(result);
        } catch (IOException error) {
            throw new IllegalArgumentException("Eigene Baumdateien konnten nicht geladen werden", error);
        }
    }
}
