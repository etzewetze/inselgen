package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.IslandStyle;
import de.etzewetze.inselgen.terrain.TreeType;
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
    private final TemplateService templates;

    IslandCommand(InselGenPlugin plugin, GenerationService service, TemplateService templates) {
        this.plugin = plugin;
        this.service = service;
        this.templates = templates;
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
            info(sender, "Vegetation: [tree:auto|palm|birch|…] [trees:0-100] [plants:0-100] [lakes:0-8] [ores:true|false]");
            info(sender, "Die Insel entsteht um deine Position. Fliege vorher in freien Luftraum.");
            info(sender, "/skyinsel status | /skyinsel cancel | /skyinsel biomes | /skyinsel trees | /skyinsel undo | /skyinsel reload");
            info(sender, "/inselgen export <tree|insel> <Name> – WorldEdit-Auswahl oder eigene pos1/pos2 speichern");
            info(sender, "/inselgen import [tree|insel] <Name> – Vorschau; /inselgen templates – gespeicherte Vorlagen");
            info(sender, "/inselgen move <vor|zurueck|links|rechts|hoch|runter> [1–100] | /inselgen confirm | /inselgen cancel");
            return true;
        }
        if (TemplateService.handles(args[0])) {
            if (!(sender instanceof Player player)) error(sender, "Vorlagenbefehle bitte im Spiel ausführen.");
            else try { templates.command(player, args); } catch (IllegalArgumentException | IllegalStateException error) { error(sender, error.getMessage()); }
            return true;
        }
        if (args[0].equalsIgnoreCase("status") && args.length == 1) {
            info(sender, service.status());
            return true;
        }
        if (args[0].equalsIgnoreCase("cancel") && args.length == 1) {
            boolean preview = sender instanceof Player player && templates.cancel(player);
            boolean job = service.cancel(sender);
            if (preview) info(sender, "Vorschau verworfen; nichts gebaut.");
            if (job) info(sender, "Abbruch angefordert. Bereits gesetzte Änderungen nach Abschluss rückgängig: " + service.undoCommand());
            else if (!preview) error(sender, "Keine eigene Vorschau oder kein Auftrag aktiv; für fremde Aufträge ist inselgen.admin nötig.");
            return true;
        }
        if (args[0].equalsIgnoreCase("biomes") && args.length <= 2) {
            String search = args.length == 2 ? args[1].toLowerCase(Locale.ROOT) : "";
            info(sender, String.join(", ", service.biomes().stream().filter(key -> key.contains(search)).toList()));
            return true;
        }
        if (args[0].equalsIgnoreCase("trees") && args.length == 1) {
            info(sender, "Baumarten: " + String.join(", ", treeNames()));
            return true;
        }
        if (args[0].equalsIgnoreCase("undo") && args.length == 1) {
            if (!(sender instanceof Player player)) error(sender, "Undo bitte im Spiel ausführen.");
            else try { templates.cancelForPlacement(player); service.undo(player); } catch (IllegalArgumentException | IllegalStateException error) { error(sender, error.getMessage()); }
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
            var configuration = plugin.configuration();
            GenerationSettings settings = configuration.settings();
            GenerationArguments request = GenerationArguments.parse(args, settings.minimumSize(), settings.maximumSize(),
                    () -> ThreadLocalRandom.current().nextLong());
            templates.cancelForPlacement(player);
            service.start(player, request, settings, configuration.trees());
        } catch (IllegalArgumentException | IllegalStateException error) {
            error(sender, error.getMessage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("inselgen.generate")) return List.of();
        List<String> suggestions = new ArrayList<>();
        if (args.length > 1 && TemplateService.handles(args[0])) return templates.complete(args);
        if (args.length == 1) {
            suggestions.addAll(List.of("50", "100", "150", "200", "300", "500", "help", "status", "cancel", "undo", "biomes", "trees"));
            suggestions.addAll(List.of("export", "import", "confirm", "move", "pos1", "pos2", "selection", "templates"));
            if (sender.hasPermission("inselgen.admin")) suggestions.add("reload");
        } else if (args.length >= 2 && args.length <= 9 && args[0].matches("[0-9]+")) {
            boolean alreadyHasStyle = Arrays.stream(args, 1, args.length - 1).anyMatch(arg -> IslandStyle.parse(arg).isPresent());
            if (!alreadyHasStyle) for (IslandStyle style : IslandStyle.values()) suggestions.add(style.token());
            boolean alreadyHasBiome = Arrays.stream(args, 1, args.length - 1).anyMatch(arg -> arg.toLowerCase(Locale.ROOT).startsWith("biome:"));
            if (!alreadyHasBiome) for (String key : service.biomes()) {
                suggestions.add("biome:" + (key.startsWith("minecraft:") ? key.substring(10) : key));
            }
            for (String name : treeNames()) suggestOption(suggestions, args, "tree:" + name);
            for (int density : new int[]{0, 10, 30, 50, 75, 100}) {
                suggestOption(suggestions, args, "trees:" + density);
                suggestOption(suggestions, args, "plants:" + density);
            }
            for (int count = 0; count <= 8; count++) suggestOption(suggestions, args, "lakes:" + count);
            suggestOption(suggestions, args, "ores:true"); suggestOption(suggestions, args, "ores:false");
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return suggestions.stream().filter(suggestion -> suggestion.startsWith(prefix)).toList();
    }

    private List<String> treeNames() {
        List<String> names = new ArrayList<>(Arrays.stream(TreeType.values()).filter(t -> t != TreeType.CUSTOM).map(TreeType::token).toList());
        names.addAll(plugin.configuration().trees().names());
        return names;
    }

    private static void suggestOption(List<String> suggestions, String[] args, String option) {
        String key = option.substring(0, option.indexOf(':') + 1);
        if (Arrays.stream(args, 1, args.length - 1).noneMatch(a -> a.toLowerCase(Locale.ROOT).startsWith(key))) suggestions.add(option);
    }

    static void info(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.AQUA + "[InselGen] " + ChatColor.GRAY + message);
    }

    static void error(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.RED + "[InselGen] " + message);
    }
}
