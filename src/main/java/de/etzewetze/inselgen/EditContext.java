package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandPlan;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.UUID;

record EditContext(World world, UUID worldId, UUID owner, int originX, int originZ, IslandPlan plan,
                   GenerationArguments request, GenerationSettings settings, Biome biome, int minY, int maxY, StructurePlacement structure) {
    EditContext(World world, UUID worldId, UUID owner, int originX, int originZ, IslandPlan plan,
                GenerationArguments request, GenerationSettings settings, Biome biome, int minY, int maxY) {
        this(world, worldId, owner, originX, originZ, plan, request, settings, biome, minY, maxY, null);
    }
    long blockCount() { return structure == null ? plan.blockCount() : structure.template().blockCount(); }
    java.util.List<String> customStates() {
        return structure == null ? plan.decorations().stream().map(IslandPlan.Decoration::blockData).filter(java.util.Objects::nonNull).distinct().toList()
                : structure.template().palette();
    }
}
