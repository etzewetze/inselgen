package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandPlan;
import java.util.*;

record StructurePlacement(SavedStructure template, int originY) {
    List<ChunkEdits.Part> partition(int originX, int originZ) {
        Map<Long, SavedStructure.Runs> chunks = new TreeMap<>();
        for (int i = 0; i < template.runCount(); i++) {
            long run = template.run(i);
            int index = SavedStructure.start(run),
                remaining = SavedStructure.length(run),
                state = SavedStructure.state(run);
            while (remaining > 0) {
                int x = index % template.width,
                    z = (index / template.width) % template.depth;
                int wx = originX + x,
                    wz = originZ + z;
                int take = Math.min(remaining, Math.min(template.width - x, 16 - Math.floorMod(wx, 16)));
                long key = ((long) (wx >> 4) << 32) | ((wz >> 4) & 0xffffffffL);
                chunks.computeIfAbsent(key, ignored -> new SavedStructure.Runs()).add(index, take, state);
                index += take;
                remaining -= take;
            }
        }
        List<ChunkEdits.Part> result = new ArrayList<>(chunks.size());
        chunks.forEach((key, runs) ->
            result.add(new ChunkEdits.Part((int) (key >> 32), (int) (long) key, 0, 0, 0, 0, List.of(), runs.array()))
        );
        return result;
    }

    Iterator<ChunkEdits.BlockEdit> blocks(long[] runs, int originX, int originZ) {
        return new Iterator<>() {
            int runIndex, offset;

            public boolean hasNext() {
                return runIndex < runs.length;
            }

            public ChunkEdits.BlockEdit next() {
                if (!hasNext()) throw new NoSuchElementException();
                long run = runs[runIndex];
                var p = template.point(SavedStructure.start(run) + offset, SavedStructure.state(run));
                if (++offset == SavedStructure.length(run)) {
                    offset = 0;
                    runIndex++;
                }
                return new ChunkEdits.BlockEdit(
                    originX + p.x(),
                    originY + p.y(),
                    originZ + p.z(),
                    IslandPlan.BlockKind.CUSTOM,
                    template.palette().get(p.state())
                );
            }
        };
    }
}
