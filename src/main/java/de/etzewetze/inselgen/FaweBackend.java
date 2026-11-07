package de.etzewetze.inselgen;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.biome.BiomeTypes;
import com.sk89q.worldedit.world.block.BlockState;
import de.etzewetze.inselgen.terrain.IslandPlan;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Loaded only when a compatible FAWE is enabled on a non-Folia server. */
final class FaweBackend implements EditBackend {
    private final Map<IslandPlan.BlockKind, BlockState> palette = new EnumMap<>(IslandPlan.BlockKind.class);

    FaweBackend() {
        for (var kind : IslandPlan.BlockKind.values()) {
            if (kind == IslandPlan.BlockKind.CUSTOM) continue;
            Material material = Material.valueOf(kind.name());
            palette.put(kind, BukkitAdapter.adapt(kind.name().endsWith("_LEAVES") ? material.createBlockData("[persistent=true]") : material.createBlockData()));
        }
    }

    @Override public String name() { return "FAWE"; }
    @Override public String undoCommand() { return "//undo"; }

    @Override public Prepared prepare(Player player) {
        var actor = BukkitAdapter.adapt(player);
        var world = BukkitAdapter.adapt(player.getWorld());
        LocalSession history = WorldEdit.getInstance().getSessionManager().get(actor);
        return (context, job) -> {
            Map<String, BlockState> custom = new HashMap<>();
            ParserContext parser = new ParserContext();
            parser.setActor(actor);
            parser.setWorld(world);
            for (var decoration : context.plan().decorations()) if (decoration.blockData() != null && !custom.containsKey(decoration.blockData()))
                custom.put(decoration.blockData(), WorldEdit.getInstance().getBlockFactory().parseFromInput(decoration.blockData(), parser).toImmutableState());
            var biome = context.biome() == null ? null : BiomeTypes.get(context.request().biomeKey());
            if (context.biome() != null && biome == null) throw new IllegalArgumentException("FAWE kennt dieses Biom nicht: " + context.request().biomeKey());
            EditSession session = WorldEdit.getInstance().newEditSessionBuilder().world(world).actor(actor)
                    .maxBlocks(context.settings().maximumBlocks()).fastMode(false).build();
            try {
                var parts = ChunkEdits.partition(context);
                if (context.settings().requireEmpty()) {
                    job.begin(JobControl.Stage.PREFLIGHT, "Freien Luftraum prüfen", context.plan().blockCount());
                    for (var part : parts) {
                        var blocks = part.blocks(context);
                        while (blocks.hasNext()) {
                            job.check(); var block = blocks.next();
                            if (!session.getBlock(BlockVector3.at(block.x(), block.y(), block.z())).getBlockType().getMaterial().isAir())
                                throw new IllegalArgumentException("Bereich belegt bei " + block.x() + ", " + block.y() + ", " + block.z() + ". Es wurde nichts gebaut.");
                            job.done++;
                        }
                    }
                }
                job.begin(JobControl.Stage.PLACING, "Blöcke und Biom setzen", context.plan().blockCount());
                for (var part : parts) {
                    var blocks = part.blocks(context);
                    while (blocks.hasNext()) {
                        job.check(); var block = blocks.next();
                        var position = BlockVector3.at(block.x(), block.y(), block.z());
                        var state = block.blockData() == null ? palette.get(block.kind()) : custom.get(block.blockData());
                        if (!session.setBlock(position, state) && !session.getBlock(position).equals(state))
                            throw new IllegalArgumentException("FAWE hat eine Änderung abgelehnt. Teilweise gebaute Insel: //undo.");
                        job.changed++; job.done++;
                    }
                    if (biome != null) for (var cell : part.biomes(context)) {
                        job.check();
                        var position = BlockVector3.at(cell.x(), cell.y(), cell.z());
                        if (!session.setBiome(position, biome) && !session.getBiome(position).equals(biome))
                            throw new IllegalArgumentException("FAWE hat eine Biomänderung abgelehnt. Rückgängig: //undo.");
                    }
                }
                job.begin(JobControl.Stage.COMMITTING, "Änderungen abschließen", 1);
            } finally {
                try { session.close(); }
                finally { history.remember(session); }
            }
        };
    }
}
