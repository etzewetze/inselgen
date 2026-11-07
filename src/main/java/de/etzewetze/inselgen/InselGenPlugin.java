package de.etzewetze.inselgen;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class InselGenPlugin extends JavaPlugin {
    private GenerationSettings settings;
    private GenerationService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = GenerationSettings.load(getConfig());
        service = new GenerationService(this);
        IslandCommand handler = new IslandCommand(this, service);
        PluginCommand command = Objects.requireNonNull(getCommand("skyinsel"), "skyinsel fehlt in plugin.yml");
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        getLogger().info("InselGen ist bereit: /skyinsel <50-500> [Seed] [natuerlich|bergig|flach]");
    }

    @Override
    public void onDisable() {
        if (service != null) service.close();
    }

    GenerationSettings settings() { return settings; }

    void reloadSettings() {
        reloadConfig();
        GenerationSettings replacement = GenerationSettings.load(getConfig());
        settings = replacement;
    }
}
