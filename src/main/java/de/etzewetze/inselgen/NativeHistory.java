package de.etzewetze.inselgen;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Worker-thread-only compressed journals. Blocks and biome cells share the same undo. */
final class NativeHistory {
    record Change(boolean biome, int x, int y, int z, String before, String after) { }
    record Entry(Path directory, UUID world) { }
    private final Path root;

    NativeHistory(Path root) { this.root = root; }

    Path create(UUID owner, UUID world) throws IOException {
        Files.createDirectories(root);
        Path directory = root.resolve(UUID.randomUUID().toString());
        Files.createDirectory(directory);
        Properties metadata = new Properties();
        metadata.setProperty("world", world.toString());
        metadata.setProperty("owner", owner.toString());
        try (var output = Files.newOutputStream(directory.resolve("metadata.properties"))) { metadata.store(output, "InselGen native undo v1"); }
        return directory;
    }

    void select(UUID owner, Path directory) throws IOException {
        Path previous = null;
        try {
            Path pointer = root.resolve(owner + ".last");
            if (Files.exists(pointer)) previous = root.resolve(UUID.fromString(Files.readString(pointer).trim()).toString());
        } catch (IllegalArgumentException | IOException ignored) { /* A damaged older pointer must not hide the new undo. */ }
        Path temp = root.resolve(owner + ".tmp");
        Files.writeString(temp, directory.getFileName().toString());
        Files.move(temp, root.resolve(owner + ".last"), StandardCopyOption.REPLACE_EXISTING);
        if (previous != null && !previous.equals(directory)) {
            try { discard(previous); } catch (IOException ignored) { /* Cleanup is best-effort; the new history is already selected. */ }
        }
    }

    Entry last(UUID owner) throws IOException {
        Path pointer = root.resolve(owner + ".last");
        if (!Files.exists(pointer)) throw new IllegalArgumentException("Keine native Insel-Historie vorhanden. Bei FAWE-Inseln //undo verwenden.");
        Path directory = root.resolve(UUID.fromString(Files.readString(pointer).trim()).toString());
        Properties metadata = new Properties();
        try (var input = Files.newInputStream(directory.resolve("metadata.properties"))) { metadata.load(input); }
        if (!owner.toString().equals(metadata.getProperty("owner"))) throw new IOException("Ungültiger Historienbesitzer");
        return new Entry(directory, UUID.fromString(metadata.getProperty("world")));
    }

    void consumed(UUID owner, Entry entry) throws IOException {
        Files.deleteIfExists(root.resolve(owner + ".last"));
        // Completed journals can be large; remove only this exact, UUID-named job directory.
        discard(entry.directory());
    }

    static void discard(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var paths = Files.list(directory)) { for (Path path : paths.toList()) Files.delete(path); }
        Files.delete(directory);
    }

    static Writer writer(Path directory, int chunkX, int chunkZ) throws IOException { return new Writer(directory.resolve(chunkX + "_" + chunkZ + ".gz")); }
    static Reader reader(Path file) throws IOException { return new Reader(file); }

    static final class Writer implements AutoCloseable {
        private final DataOutputStream stream;
        Writer(Path path) throws IOException {
            stream = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(path)), 65536));
            stream.writeInt(0x49534731);
        }
        void append(List<Change> changes) throws IOException {
            for (Change c : changes) {
                stream.writeByte(c.biome() ? 2 : 1);
                stream.writeInt(c.x()); stream.writeInt(c.y()); stream.writeInt(c.z());
                stream.writeUTF(c.before()); stream.writeUTF(c.after());
            }
        }
        @Override public void close() throws IOException { stream.close(); }
    }

    static final class Reader implements AutoCloseable {
        private final DataInputStream stream;
        private boolean finished;
        Reader(Path path) throws IOException {
            stream = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(path)), 65536));
            if (stream.readInt() != 0x49534731) throw new IOException("Ungültige InselGen-Historie");
        }
        List<Change> next(int limit) throws IOException {
            List<Change> result = new ArrayList<>(limit);
            while (!finished && result.size() < limit) {
                int type = stream.read();
                if (type == -1) { finished = true; break; }
                if (type != 1 && type != 2) throw new IOException("Ungültiger Historieneintrag");
                result.add(new Change(type == 2, stream.readInt(), stream.readInt(), stream.readInt(), stream.readUTF(), stream.readUTF()));
            }
            return result;
        }
        @Override public void close() throws IOException { stream.close(); }
    }
}
