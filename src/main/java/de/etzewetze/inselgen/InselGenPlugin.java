package de.etzewetze.inselgen;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.nio.file.Files;

public final class InselGenPlugin extends JavaPlugin {
    record Configuration(GenerationSettings settings, TreeLibrary trees) { }
    private volatile Configuration configuration;
    private GenerationService service;
    private TemplateService templates;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (!Files.exists(getDataFolder().toPath().resolve("trees/example_palm.yml"))) saveResource("trees/example_palm.yml", false);
        reloadSnapshot();
        service = new GenerationService(this);
        templates = new TemplateService(this, service);
        IslandCommand handler = new IslandCommand(this, service, templates);
        PluginCommand command = Objects.requireNonNull(getCommand("skyinsel"), "skyinsel fehlt in plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        getLogger().info("InselGen ist bereit: /skyinsel <50-500> [Seed] [natuerlich|bergig|flach] [biome:beach]");
    }

    @Override
    public void onDisable() {
        if (templates != null) templates.close();
        if (service != null) service.close();
    }

    Configuration configuration() { return configuration; }
    synchronized void registerTree(de.etzewetze.inselgen.terrain.CustomTree tree) {
        configuration = new Configuration(configuration.settings(), configuration.trees().with(tree));
    }

    synchronized void reloadSettings() {
        reloadConfig();
        reloadSnapshot();
        if (templates != null) templates.refresh();
    }

    private void reloadSnapshot() {
        GenerationSettings replacement = GenerationSettings.load(getConfig());
        TreeLibrary trees = TreeLibrary.load(getDataFolder().toPath().resolve("trees"));
        // Resolve the configured default before replacing the snapshot, including custom IDs.
        replacement.features().resolve(new GenerationArguments(50, 0, de.etzewetze.inselgen.terrain.IslandStyle.NATUERLICH), replacement.trees(), replacement.plants(), trees);
        configuration = new Configuration(replacement, trees);
    }
}
