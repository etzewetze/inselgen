package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandStyle;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

final class IslandCommand implements TabExecutor {
    private final InselGenPlugin plugin;
    private final GenerationService service;

    IslandCommand(InselGenPlugin plugin, GenerationService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("inselgen.generate")) {
            error(sender, "Dafür fehlt dir die Berechtigung inselgen.generate.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("hilfe")) {
            info(sender, "/skyinsel <50-500> [Seed] [natuerlich|bergig|flach] [biome:beach]");
            info(sender, "Beispiele: /skyinsel 300 biome:beach | /skyinsel 300 32 bergig biome:beach");
            info(sender, "Die Insel entsteht um deine Position. Fliege vorher in freien Luftraum.");
            info(sender, "/skyinsel status | /skyinsel cancel | /skyinsel biomes | /skyinsel undo | /skyinsel reload");
            return true;
        }
        if (args[0].equalsIgnoreCase("status") && args.length == 1) {
            info(sender, service.status());
            return true;
        }
        if (args[0].equalsIgnoreCase("cancel") && args.length == 1) {
            if (!service.cancel(sender)) error(sender, "Kein eigener Auftrag aktiv; für fremde Aufträge ist inselgen.admin nötig.");
            else info(sender, "Abbruch angefordert. Bereits gesetzte Änderungen nach Abschluss rückgängig: " + service.undoCommand());
            return true;
        }
        if (args[0].equalsIgnoreCase("biomes") && args.length <= 2) {
            String search = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "";
            info(sender, String.join(", ", service.biomes().stream().filter(key -> key.contains(search)).toList()));
            return true;
        }
        if (args[0].equalsIgnoreCase("undo") && args.length == 1) {
            if (!(sender instanceof Player player)) error(sender, "Undo bitte im Spiel ausführen.");
            else try { service.undo(player); } catch (IllegalArgumentException | IllegalStateException error) { error(sender, error.getMessage()); }
            return true;
        }
        if (args[0].equalsIgnoreCase("reload") && args.length == 1) {
            if (!sender.hasPermission("inselgen.admin")) error(sender, "Dafür fehlt dir inselgen.admin.");
            else {
                try { plugin.reloadSettings(); info(sender, "Konfiguration neu geladen. Laufende Aufträge behalten ihre Einstellungen."); }
                catch (IllegalArgumentException error) { error(sender, "Konfiguration ungültig: " + error.getMessage()); }
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            error(sender, "Inseln bitte im Spiel erzeugen; deine Position legt den Mittelpunkt und die Höhe fest.");
            return true;
        }
        try {
            GenerationSettings settings = plugin.settings();
            GenerationArguments request = GenerationArguments.parse(args, settings.minimumSize(), settings.maximumSize(),
                    () -> ThreadLocalRandom.current().nextLong());
            service.start(player, request, settings);
        } catch (IllegalArgumentException | IllegalStateException error) {
            error(sender, error.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("inselgen.generate")) return List.of();
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            suggestions.addAll(List.of("50", "100", "150", "200", "300", "500", "help", "status", "cancel", "undo", "biomes"));
            if (sender.hasPermission("inselgen.admin")) suggestions.add("reload");
        } else if (args.length >= 2 && args.length <= 4) {
            boolean alreadyHasStyle = Arrays.stream(args, 1, args.length - 1).anyMatch(arg -> IslandStyle.parse(arg).isPresent());
            if (!alreadyHasStyle) for (IslandStyle style : IslandStyle.values()) suggestions.add(style.token());
            boolean alreadyHasBiome = Arrays.stream(args, 1, args.length - 1).anyMatch(arg -> arg.toLowerCase(Locale.ROOT).startsWith("biome:"));
            if (!alreadyHasBiome) for (String key : service.biomes()) {
                suggestions.add("biome:" + (key.startsWith("minecraft:") ? key.substring(10) : key));
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream().filter(suggestion -> suggestion.startsWith(prefix)).toList();
    }

    static void info(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.AQUA + "[InselGen] " + ChatColor.GRAY + message);
    }

    static void error(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.RED + "[InselGen] " + message);
    }
}
