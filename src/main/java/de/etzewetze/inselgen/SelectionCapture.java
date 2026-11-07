package de.etzewetze.inselgen;

import java.util.Arrays;
import org.bukkit.World;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Leaves;

/** Reads only owned chunks, with bounded region tasks and no live world access on the worker. */
final class SelectionCapture {

    private record Batch(int[] indices, String[] states) {}

    static SavedStructure capture(
        World world,
        SelectionBox box,
        boolean tree,
        long limit,
        PlatformScheduler scheduler,
        JobControl job
    ) throws Exception {
        SavedStructure.Builder builder = new SavedStructure.Builder();
        int centerX = box.minX() + box.width() / 2,
            centerZ = box.minZ() + box.depth() / 2;
        int centerTop = -1,
            highest = -1;
        job.begin(JobControl.Stage.EXPORT, "Auswahl lesen", box.volume());
        for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) for (
            int cx = box.minX() >> 4;
            cx <= box.maxX() >> 4;
            cx++
        ) {
            Cursor cursor = new Cursor(box, cx, cz);
            while (cursor.hasNext()) {
                job.check();
                Batch batch = job.await(scheduler.chunk(world, cx, cz, () -> cursor.read(world, tree, job)));
                for (int i = 0; i < batch.indices.length; i++) if (batch.states[i] != null) {
                    int index = batch.indices[i],
                        x = index % box.width(),
                        z = (index / box.width()) % box.depth(),
                        y = index / (box.width() * box.depth());
                    builder.add(index, batch.states[i]);
                    if (builder.blocks() > limit) throw new IllegalArgumentException(
                        "Auswahl überschreitet das Blocklimit: " + limit
                    );
                    highest = Math.max(highest, y);
                    if (x + box.minX() == centerX && z + box.minZ() == centerZ) centerTop = Math.max(centerTop, y);
                }
            }
        }
        job.check();
        if (builder.blocks() == 0) throw new IllegalArgumentException("Die Auswahl enthält nur Luft.");
        return builder.build(
            box.width(),
            box.height(),
            box.depth(),
            box.width() / 2,
            (centerTop >= 0 ? centerTop : highest) + 1,
            box.depth() / 2
        );
    }

    private static final class Cursor {

        final SelectionBox box;
        final int minX, maxX, minZ, maxZ;
        int x, y, z;

        Cursor(SelectionBox box, int cx, int cz) {
            this.box = box;
            minX = Math.max(box.minX(), cx * 16);
            maxX = Math.min(box.maxX(), cx * 16 + 15);
            minZ = Math.max(box.minZ(), cz * 16);
            maxZ = Math.min(box.maxZ(), cz * 16 + 15);
            x = minX;
            z = minZ;
            y = box.minY();
        }

        boolean hasNext() {
            return y <= box.maxY();
        }

        Batch read(World world, boolean tree, JobControl job) {
            int[] indices = new int[2048];
            String[] states = new String[2048];
            int count = 0;
            long started = System.nanoTime();
            while (count < indices.length && hasNext()) {
                if ((count & 63) == 0) job.check();
                var block = world.getBlockAt(x, y, z);
                indices[count] = box.index(x, y, z);
                if (!block.getType().isAir()) {
                    if (block.getState() instanceof TileState) throw new IllegalArgumentException(
                        "Auswahl enthält einen Block mit Zusatzdaten bei " +
                            x +
                            ", " +
                            y +
                            ", " +
                            z +
                            ". Kisten, Schilder und andere Blockentitäten bitte aus der Auswahl entfernen."
                    );
                    var data = block.getBlockData();
                    if (
                        tree &&
                        (data.getMaterial() == org.bukkit.Material.WATER ||
                            data.getMaterial() == org.bukkit.Material.LAVA)
                    ) throw new IllegalArgumentException("Baumvorlagen dürfen keine Flüssigkeiten enthalten.");
                    if (tree && data instanceof Leaves leaves) leaves.setPersistent(true);
                    states[count] = data.getAsString();
                }
                count++;
                job.done++;
                if (++x > maxX) {
                    x = minX;
                    if (++z > maxZ) {
                        z = minZ;
                        y++;
                    }
                }
                if (System.nanoTime() - started >= 4_000_000) break;
            }
            return new Batch(Arrays.copyOf(indices, count), Arrays.copyOf(states, count));
        }
    }
}
