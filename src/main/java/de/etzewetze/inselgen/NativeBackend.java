package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandPlan;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Uses only Bukkit 1.21 calls, always on the owning chunk's scheduler. Disk I/O stays on the worker. */
final class NativeBackend implements EditBackend {
    private static final int BATCH = 2048;
    private static final long BUDGET_NANOS = 4_000_000;
    private final PlatformScheduler scheduler;
    private final NativeHistory history;
    private final Map<IslandPlan.BlockKind, BlockData> palette = new EnumMap<>(IslandPlan.BlockKind.class);
    private record BatchResult(List<NativeHistory.Change> changes, RuntimeException failure) { }

    NativeBackend(InselGenPlugin plugin, PlatformScheduler scheduler) {
        this.scheduler = scheduler;
        history = new NativeHistory(plugin.getDataFolder().toPath().resolve("native-history"));
        for (var kind : IslandPlan.BlockKind.values()) {
            if (kind == IslandPlan.BlockKind.CUSTOM) continue;
            Material material = Material.valueOf(kind.name());
            palette.put(kind, kind.name().endsWith("_LEAVES") ? material.createBlockData("[persistent=true]") : material.createBlockData());
        }
    }

    @Override public Prepared prepare(Player player) { return this::apply; }
    @Override public String name() { return "native"; }
    @Override public String undoCommand() { return "/skyinsel undo"; }

    private void apply(EditContext context, JobControl job) throws Exception {
        if (!context.settings().requireEmpty()) throw new IllegalArgumentException("Die native Platzierung benötigt require-empty-space: true. Zum Überschreiben bestehender Blöcke FAWE verwenden.");
        List<ChunkEdits.Part> parts = ChunkEdits.partition(context);
        Map<String, BlockData> custom = new HashMap<>();
        job.begin(JobControl.Stage.PREFLIGHT, "Freien Luftraum prüfen", context.plan().blockCount());
        for (var part : parts) {
            Iterator<ChunkEdits.BlockEdit> blocks = part.blocks(context);
            while (blocks.hasNext()) {
                job.check();
                job.await(scheduler.chunk(context.world(), part.x(), part.z(), () -> {
                    job.check();
                    long start = System.nanoTime();
                    for (int n = 0; n < BATCH && blocks.hasNext(); n++) {
                        var edit = blocks.next();
                        if (!context.world().getBlockAt(edit.x(), edit.y(), edit.z()).getType().isAir())
                            throw new IllegalArgumentException("Bereich belegt bei " + edit.x() + ", " + edit.y() + ", " + edit.z() + ". Es wurde nichts gebaut.");
                        job.done++;
                        if (System.nanoTime() - start >= BUDGET_NANOS) break;
                    }
                    return null;
                }));
            }
        }
        job.check();
        Path directory = history.create(context.owner(), context.worldId());
        job.begin(JobControl.Stage.PLACING, "Blöcke und Biom setzen", context.plan().blockCount());
        boolean selected = false;
        for (var part : parts) {
            Iterator<ChunkEdits.BlockEdit> blocks = part.blocks(context);
            if (!blocks.hasNext()) continue;
            try (NativeHistory.Writer writer = NativeHistory.writer(directory, part.x(), part.z())) {
                while (blocks.hasNext()) {
                    job.check();
                    BatchResult batch = job.await(scheduler.chunk(context.world(), part.x(), part.z(), () -> placeBatch(context, job, blocks, custom)));
                    writer.append(batch.changes());
                    if (!selected && !batch.changes().isEmpty()) { history.select(context.owner(), directory); selected = true; }
                    if (batch.failure() != null) throw batch.failure();
                }
                List<ChunkEdits.BiomeCell> cells = part.biomes(context);
                for (int offset = 0; offset < cells.size(); offset += BATCH) {
                    job.check();
                    var batchCells = cells.subList(offset, Math.min(cells.size(), offset + BATCH));
                    BatchResult batch = job.await(scheduler.chunk(context.world(), part.x(), part.z(), () -> biomeBatch(context, job, batchCells)));
                    writer.append(batch.changes());
                    if (batch.failure() != null) throw batch.failure();
                }
            }
            if (context.biome() != null) job.await(scheduler.chunk(context.world(), part.x(), part.z(), () -> {
                job.check(); context.world().refreshChunk(part.x(), part.z()); return null;
            }));
        }
        job.begin(JobControl.Stage.COMMITTING, "Historie abschließen", 1);
    }

    private BatchResult placeBatch(EditContext context, JobControl job, Iterator<ChunkEdits.BlockEdit> edits, Map<String, BlockData> custom) {
        List<NativeHistory.Change> changes = new ArrayList<>(BATCH);
        try {
            job.check();
            long start = System.nanoTime();
            for (int n = 0; n < BATCH && edits.hasNext(); n++) {
                var edit = edits.next();
                var block = context.world().getBlockAt(edit.x(), edit.y(), edit.z());
                if (!block.getType().isAir()) throw new IllegalArgumentException("Der Bereich wurde während des Bauens verändert. Teilweise gebaute Insel: /skyinsel undo.");
                BlockData data = edit.blockData() == null ? palette.get(edit.kind()) : custom.computeIfAbsent(edit.blockData(), Bukkit::createBlockData);
                String before = block.getBlockData().getAsString();
                block.setBlockData(data, false);
                changes.add(new NativeHistory.Change(false, edit.x(), edit.y(), edit.z(), before, data.getAsString()));
                job.changed++; job.done++;
                if (System.nanoTime() - start >= BUDGET_NANOS) break;
            }
            return new BatchResult(changes, null);
        } catch (RuntimeException error) { return new BatchResult(changes, error); }
    }

    private BatchResult biomeBatch(EditContext context, JobControl job, List<ChunkEdits.BiomeCell> cells) {
        List<NativeHistory.Change> changes = new ArrayList<>();
        try {
            job.check();
            for (var cell : cells) {
                String before = ((Keyed) context.world().getBiome(cell.x(), cell.y(), cell.z())).getKey().toString();
                if (before.equals(context.request().biomeKey())) continue;
                context.world().setBiome(cell.x(), cell.y(), cell.z(), context.biome());
                changes.add(new NativeHistory.Change(true, cell.x(), cell.y(), cell.z(), before, context.request().biomeKey()));
            }
            return new BatchResult(changes, null);
        } catch (RuntimeException error) { return new BatchResult(changes, error); }
    }

    void undo(UUID owner, JobControl job) throws Exception {
        NativeHistory.Entry entry = history.last(owner);
        World world = job.await(scheduler.global(() -> Bukkit.getWorld(entry.world())));
        if (world == null) throw new IllegalArgumentException("Die Welt der letzten Insel ist nicht geladen.");
        List<Path> files;
        try (var stream = Files.list(entry.directory())) { files = stream.filter(path -> path.getFileName().toString().endsWith(".gz")).sorted().toList(); }
        job.begin(JobControl.Stage.UNDO, "Insel rückgängig machen", files.size());
        Map<String, BlockData> states = new HashMap<>();
        for (Path file : files) {
            String[] coordinates = file.getFileName().toString().replace(".gz", "").split("_");
            int cx = Integer.parseInt(coordinates[0]), cz = Integer.parseInt(coordinates[1]);
            try (NativeHistory.Reader reader = NativeHistory.reader(file)) {
                List<NativeHistory.Change> batch;
                while (!(batch = reader.next(BATCH)).isEmpty()) {
                    job.check();
                    List<NativeHistory.Change> changes = batch;
                    job.await(scheduler.chunk(world, cx, cz, () -> {
                        job.check();
                        for (var change : changes) {
                            if (change.biome()) {
                                String current = ((Keyed) world.getBiome(change.x(), change.y(), change.z())).getKey().toString();
                                if (matchesGenerated(current, change.after())) {
                                    var biome = Registry.BIOME.get(NamespacedKey.fromString(change.before()));
                                    if (biome == null) throw new IllegalArgumentException("Biom aus der Historie fehlt: " + change.before());
                                    world.setBiome(change.x(), change.y(), change.z(), biome);
                                    job.changed++;
                                } else if (!current.equals(change.before())) job.skipped++;
                            } else {
                                var block = world.getBlockAt(change.x(), change.y(), change.z());
                                String current = block.getBlockData().getAsString();
                                if (matchesGenerated(current, change.after())) {
                                    block.setBlockData(states.computeIfAbsent(change.before(), Bukkit::createBlockData), false);
                                    job.changed++;
                                } else if (!current.equals(change.before())) job.skipped++;
                            }
                        }
                        return null;
                    }));
                }
            }
            job.done++;
            job.await(scheduler.chunk(world, cx, cz, () -> { world.refreshChunk(cx, cz); return null; }));
        }
        history.consumed(owner, entry);
    }

    static boolean matchesGenerated(String current, String expected) {
        if (current.equals(expected)) return true;
        // Leaves update distance and grass updates snowy naturally, without a player edit.
        if (expected.matches("minecraft:[a-z_]+_leaves\\[.*") && current.startsWith(expected.substring(0, expected.indexOf('[') + 1)))
            return current.replaceAll("distance=[0-9]+", "distance=*").equals(expected.replaceAll("distance=[0-9]+", "distance=*"));
        if (expected.startsWith("minecraft:grass_block[") && current.startsWith("minecraft:grass_block["))
            return current.replaceAll("snowy=(true|false)", "snowy=*").equals(expected.replaceAll("snowy=(true|false)", "snowy=*"));
        return false;
    }
}
