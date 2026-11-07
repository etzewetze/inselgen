package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.CustomTree;
import de.etzewetze.inselgen.terrain.TreeType;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class TemplateStore {

    private static final int MAGIC = 0x494e534c,
        VERSION = 1;
    private final Path islands, trees;
    private volatile List<String> names = List.of();

    TemplateStore(Path dataFolder) {
        islands = dataFolder.resolve("islands");
        trees = dataFolder.resolve("trees");
        refresh();
    }

    static String name(String name) {
        if (name == null || !name.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException(
            "Name: 1–32 kleine Buchstaben, Ziffern, _ oder -, beginnend mit einem Buchstaben."
        );
        return name;
    }

    List<String> names() {
        return names;
    }

    void refresh() {
        try {
            Files.createDirectories(islands);
            Files.createDirectories(trees);
            try (var files = Files.list(islands)) {
                names = files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("[a-z][a-z0-9_-]{0,31}\\.insel"))
                    .map(n -> n.substring(0, n.length() - 6))
                    .sorted()
                    .toList();
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Vorlagenordner konnte nicht gelesen werden.", error);
        }
    }

    void checkNew(String id, boolean tree) {
        Path path = (tree ? trees : islands).resolve(name(id) + (tree ? ".yml" : ".insel"));
        if (Files.exists(path)) throw new IllegalArgumentException(
            "Vorlage existiert bereits: " + id + ". Bitte einen anderen Namen wählen."
        );
        if (
            tree && Arrays.stream(TreeType.values()).anyMatch(type -> type.token().equals(id))
        ) throw new IllegalArgumentException("Reservierter Baumname: " + id);
        try (var files = Files.list(tree ? trees : islands)) {
            if (
                files.filter(p -> p.getFileName().toString().endsWith(tree ? ".yml" : ".insel")).count() >=
                (tree ? 32 : 128)
            ) throw new IllegalArgumentException(tree ? "Höchstens 32 Baumvorlagen." : "Höchstens 128 Inselvorlagen.");
        } catch (IOException error) {
            throw new IllegalArgumentException("Vorlagenordner konnte nicht gelesen werden.", error);
        }
    }

    void saveIsland(String id, SavedStructure template) throws IOException {
        checkNew(id, false);
        Path temporary = Files.createTempFile(islands, ".export-", ".tmp");
        try {
            try (
                var out = new DataOutputStream(
                    new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)))
                )
            ) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(template.width);
                out.writeInt(template.height);
                out.writeInt(template.depth);
                out.writeInt(template.anchorX);
                out.writeInt(template.anchorY);
                out.writeInt(template.anchorZ);
                out.writeInt(template.palette().size());
                for (String state : template.palette()) out.writeUTF(state);
                out.writeInt(template.runCount());
                for (int i = 0; i < template.runCount(); i++) out.writeLong(template.run(i));
            }
            Files.move(temporary, islands.resolve(name(id) + ".insel"));
            refresh();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    SavedStructure loadIsland(String id, long maximumBlocks) throws IOException {
        Path path = islands.resolve(name(id) + ".insel");
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Unbekannte Inselvorlage: " + id);
        if (Files.size(path) > 64 * 1024 * 1024) throw new IllegalArgumentException(
            "Inseldatei ist größer als 64 MiB."
        );
        try (var in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path))))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IllegalArgumentException(
                "Unbekanntes Inseldateiformat."
            );
            int width = in.readInt(),
                height = in.readInt(),
                depth = in.readInt();
            // Validate dimensions before allocating anything from an untrusted header.
            new SelectionBox(new UUID(0, 0), 0, 0, 0, width - 1, height - 1, depth - 1);
            int ax = in.readInt(),
                ay = in.readInt(),
                az = in.readInt(),
                count = in.readInt();
            if (count < 1 || count > SavedStructure.MAX_PALETTE) throw new IllegalArgumentException(
                "Ungültige Blockpalette."
            );
            List<String> palette = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String state = in.readUTF();
                if (state.length() > 512) throw new IllegalArgumentException("Blockzustand ist zu lang.");
                palette.add(state);
            }
            int runs = in.readInt();
            if (runs < 1 || runs > SavedStructure.MAX_RUNS || runs > maximumBlocks) throw new IllegalArgumentException(
                "Vorlage ist leer oder überschreitet das Block-/Komplexitätslimit."
            );
            long[] data = new long[runs];
            long blocks = 0;
            for (int i = 0; i < runs; i++) {
                data[i] = in.readLong();
                blocks += SavedStructure.length(data[i]);
                if (blocks > maximumBlocks) throw new IllegalArgumentException("Vorlage überschreitet maximum-blocks.");
            }
            if (in.read() != -1) throw new IllegalArgumentException("Unerwartete Daten am Dateiende.");
            return new SavedStructure(width, height, depth, ax, ay, az, palette, data);
        }
    }

    void saveTree(CustomTree tree) throws IOException {
        checkNew(tree.id(), true);
        StringBuilder yaml = new StringBuilder(
            "# Mit /inselgen export tree gespeichert. Koordinaten relativ zum Stammfuß.\nsoil: [grass_block, dirt, sand, snow_block]\nblocks:\n"
        );
        for (var b : tree.blocks())
            yaml
                .append("  - {x: ")
                .append(b.x())
                .append(", y: ")
                .append(b.y())
                .append(", z: ")
                .append(b.z())
                .append(", block: '")
                .append(b.state().replace("'", "''"))
                .append("'}\n");
        byte[] bytes = yaml.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > TreeLibrary.MAX_FILE_SIZE) throw new IllegalArgumentException("Baumdatei ist zu groß.");
        Path temporary = Files.createTempFile(trees, ".export-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, trees.resolve(tree.id() + ".yml"));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
