package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandPlan;
import org.bukkit.World;
import org.bukkit.block.Biome;

import java.util.UUID;

record EditContext(World world, UUID worldId, UUID owner, int originX, int originZ, IslandPlan plan,
                   GenerationArguments request, GenerationSettings settings, Biome biome, int minY, int maxY) { }
