package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.CustomTree;
import java.util.*;

/** Non-air voxels as sorted runs: start (32 bits), length (20), palette index (12). */
final class SavedStructure {

    static final int MAX_RUNS = 4_000_000,
        MAX_PALETTE = 4096,
        MAX_RUN_LENGTH = 0xfffff;
    static final long MAX_VOLUME = 64_000_000;

    record Point(int x, int y, int z, int state) {}

    final int width, height, depth, anchorX, anchorY, anchorZ;
    private final List<String> palette;
    private final long[] runs;
    private final long blocks;

    SavedStructure(
        int width,
        int height,
        int depth,
        int anchorX,
        int anchorY,
        int anchorZ,
        List<String> palette,
        long[] runs
    ) {
        if (
            width < 1 ||
            width > 500 ||
            depth < 1 ||
            depth > 500 ||
            height < 1 ||
            height > 1024 ||
            (long) width * height * depth > MAX_VOLUME
        ) throw new IllegalArgumentException("Vorlage überschreitet 500×1024×500 oder 64 Millionen Auswahlblöcke.");
        if (
            anchorX < 0 || anchorX >= width || anchorZ < 0 || anchorZ >= depth || anchorY < 0 || anchorY > height
        ) throw new IllegalArgumentException("Ungültiger Vorlagenanker.");
        if (
            palette.isEmpty() || palette.size() > MAX_PALETTE || new HashSet<>(palette).size() != palette.size()
        ) throw new IllegalArgumentException("Ungültige Blockpalette.");
        for (String state : palette)
            if (
                state == null ||
                state.length() > 512 ||
                !state.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(?:\\[[a-z0-9_=,.-]+\\])?")
            ) throw new IllegalArgumentException("Ungültiger Blockzustand in der Vorlage.");
        if (runs.length < 1 || runs.length > MAX_RUNS) throw new IllegalArgumentException(
            "Leere oder zu komplexe Vorlage."
        );
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.palette = List.copyOf(palette);
        this.runs = runs.clone();
        Arrays.sort(this.runs);
        long count = 0,
            end = 0,
            volume = (long) width * height * depth;
        for (long run : this.runs) {
            int start = start(run),
                length = length(run);
            if (
                start < end || length < 1 || (long) start + length > volume || state(run) >= palette.size()
            ) throw new IllegalArgumentException("Überlappende oder ungültige Vorlagenblöcke.");
            end = (long) start + length;
            count += length;
        }
        blocks = count;
    }

    long blockCount() {
        return blocks;
    }

    List<String> palette() {
        return palette;
    }

    int runCount() {
        return runs.length;
    }

    long run(int i) {
        return runs[i];
    }

    Point point(int index, int state) {
        return new Point(index % width, index / (width * depth), (index / width) % depth, state);
    }

    static int start(long run) {
        return (int) (run >>> 32);
    }

    static int length(long run) {
        return (int) (run >>> 12) & MAX_RUN_LENGTH;
    }

    static int state(long run) {
        return (int) run & 4095;
    }

    static long pack(int start, int length, int state) {
        if (
            start < 0 || length < 1 || length > MAX_RUN_LENGTH || state < 0 || state >= MAX_PALETTE
        ) throw new IllegalArgumentException("Ungültiger Blocklauf.");
        return ((long) start << 32) | ((long) length << 12) | state;
    }

    /** Full small objects, evenly sampled upper/lower surfaces for large islands. */
    List<Point> preview(int limit) {
        List<Point> result = new ArrayList<>(limit);
        if (blocks <= limit) {
            for (long run : runs) for (int n = 0; n < length(run); n++) result.add(point(start(run) + n, state(run)));
            return List.copyOf(result);
        }
        long[] top = new long[width * depth],
            bottom = new long[top.length];
        Arrays.fill(top, -1);
        Arrays.fill(bottom, -1);
        for (long run : runs)
            for (int n = 0; n < length(run); n++) {
                int index = start(run) + n,
                    column = index % top.length;
                long value = ((long) index << 12) | state(run);
                if (bottom[column] == -1) bottom[column] = value;
                top[column] = value;
            }
        int candidates = 0;
        for (int i = 0; i < top.length; i++) if (top[i] != -1) candidates += top[i] == bottom[i] ? 1 : 2;
        int slots = Math.min(limit, candidates);
        int ordinal = 0,
            chosen = 0;
        for (int i = 0; i < top.length; i++) if (top[i] != -1) {
            for (long value : top[i] == bottom[i] ? new long[] { top[i] } : new long[] { top[i], bottom[i] }) {
                if (chosen < slots && ordinal == ((long) chosen * candidates) / slots) {
                    result.add(point((int) (value >>> 12), (int) value & 4095));
                    chosen++;
                }
                ordinal++;
            }
        }
        return List.copyOf(result);
    }

    static SavedStructure tree(CustomTree tree) {
        int minX = tree.blocks().stream().mapToInt(CustomTree.Block::x).min().orElseThrow();
        int maxX = tree.blocks().stream().mapToInt(CustomTree.Block::x).max().orElseThrow();
        int minZ = tree.blocks().stream().mapToInt(CustomTree.Block::z).min().orElseThrow();
        int maxZ = tree.blocks().stream().mapToInt(CustomTree.Block::z).max().orElseThrow();
        int width = maxX - minX + 1,
            depth = maxZ - minZ + 1;
        int height = tree.blocks().stream().mapToInt(CustomTree.Block::y).max().orElseThrow() + 1;
        Builder builder = new Builder();
        for (var block : tree.blocks())
            builder.add((block.y() * depth + block.z() - minZ) * width + block.x() - minX, block.state());
        return builder.build(width, height, depth, -minX, 0, -minZ);
    }

    static final class Runs {

        private long[] values = new long[256];
        private int size;

        void add(int index, int length, int state) {
            while (length > 0) {
                int take = Math.min(length, MAX_RUN_LENGTH);
                if (
                    size > 0 &&
                    state(values[size - 1]) == state &&
                    start(values[size - 1]) + length(values[size - 1]) == index &&
                    length(values[size - 1]) + take <= MAX_RUN_LENGTH
                ) {
                    values[size - 1] = pack(start(values[size - 1]), length(values[size - 1]) + take, state);
                } else {
                    if (size >= MAX_RUNS) throw new IllegalArgumentException(
                        "Vorlage enthält zu viele unterschiedliche Blockläufe."
                    );
                    if (size == values.length) values = Arrays.copyOf(values, Math.min(MAX_RUNS, size * 2));
                    values[size++] = pack(index, take, state);
                }
                index += take;
                length -= take;
            }
        }

        long[] array() {
            return Arrays.copyOf(values, size);
        }
    }

    static final class Builder {

        private final Map<String, Integer> ids = new LinkedHashMap<>();
        private final Runs runs = new Runs();
        private long blocks;

        long blocks() {
            return blocks;
        }

        void add(int index, String state) {
            Integer id = ids.get(state);
            if (id == null) {
                id = ids.size();
                if (id >= MAX_PALETTE) throw new IllegalArgumentException("Mehr als 4096 verschiedene Blockzustände.");
                ids.put(state, id);
            }
            runs.add(index, 1, id);
            blocks++;
        }

        SavedStructure build(int width, int height, int depth, int ax, int ay, int az) {
            return new SavedStructure(width, height, depth, ax, ay, az, new ArrayList<>(ids.keySet()), runs.array());
        }
    }
}
