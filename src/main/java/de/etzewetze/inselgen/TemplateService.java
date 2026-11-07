package de.etzewetze.inselgen;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;

final class TemplateService implements Listener, AutoCloseable {

    private record Pick(UUID world, int x, int y, int z) {}

    private record Picks(Pick first, Pick second) {}

    private record Loaded(SavedStructure template, List<SavedStructure.Point> points, List<BlockData> palette) {}

    private final InselGenPlugin plugin;
    private final GenerationService generation;
    private final PlatformScheduler scheduler;
    private final TemplateStore store;
    private final PreviewService previews;
    private final Map<UUID, Picks> selections = new ConcurrentHashMap<>();

    TemplateService(InselGenPlugin plugin, GenerationService generation) {
        this.plugin = plugin;
        this.generation = generation;
        scheduler = generation.scheduler();
        store = new TemplateStore(plugin.getDataFolder().toPath());
        previews = new PreviewService(plugin, scheduler);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    static boolean handles(String command) {
        return List.of("export", "import", "confirm", "move", "pos1", "pos2", "selection", "templates").contains(
            command.toLowerCase(Locale.ROOT)
        );
    }

    void command(Player player, String[] args) {
        String command = args[0].toLowerCase(Locale.ROOT);
        String permission = List.of("export", "pos1", "pos2", "selection").contains(command)
            ? "inselgen.export"
            : "inselgen.import";
        if (!player.hasPermission(permission)) throw new IllegalArgumentException("Dafür fehlt " + permission + ".");
        switch (command) {
            case "export" -> export(player, args);
            case "import" -> load(player, args);
            case "confirm" -> {
                if (args.length != 1) throw new IllegalArgumentException("Benutzung: /inselgen confirm");
                var preview = previews.require(player);
                if (preview.busy.get() || preview.rendered != preview.revision) throw new IllegalArgumentException(
                    "Vorschau wird noch aktualisiert. Bitte kurz warten und erneut confirm ausführen."
                );
                var at = preview.position;
                generation.place(player, preview.label, preview.template, at.x(), at.y(), at.z());
                previews.cancel(player, true, false);
            }
            case "move" -> {
                if (args.length < 2 || args.length > 3) throw new IllegalArgumentException(
                    "Benutzung: /inselgen move <vor|zurueck|links|rechts|hoch|runter> [1–100]"
                );
                int steps = 1;
                if (args.length == 3) try {
                    steps = Integer.parseInt(args[2]);
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("Schrittweite muss eine ganze Zahl von 1–100 sein.");
                }
                previews.move(player, args[1], steps);
            }
            case "pos1", "pos2" -> position(player, args, command.equals("pos1"));
            case "selection" -> {
                if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
                    selections.remove(player.getUniqueId());
                    IslandCommand.info(
                        player,
                        "Eigene Auswahl gelöscht; WorldEdit-Auswahl kann wieder verwendet werden."
                    );
                } else if (args.length == 1) {
                    var box = selection(player);
                    IslandCommand.info(
                        player,
                        "Auswahl: " +
                            box.width() +
                            "×" +
                            box.height() +
                            "×" +
                            box.depth() +
                            ", " +
                            box.volume() +
                            " Blöcke einschließlich Luft."
                    );
                } else throw new IllegalArgumentException("Benutzung: /inselgen selection [clear]");
            }
            case "templates" -> {
                if (args.length != 1) throw new IllegalArgumentException("Benutzung: /inselgen templates");
                IslandCommand.info(player, "Bäume: " + String.join(", ", plugin.configuration().trees().names()));
                IslandCommand.info(player, "Inseln: " + String.join(", ", store.names()));
            }
            default -> throw new IllegalArgumentException("Unbekannter Vorlagenbefehl.");
        }
    }

    private void export(Player player, String[] args) {
        if (args.length != 3) throw new IllegalArgumentException("Benutzung: /inselgen export <tree|insel> <Name>");
        boolean tree = treeKind(args[1]);
        String id = TemplateStore.name(args[2]);
        SelectionBox box = selection(player);
        World world = player.getWorld();
        if (box.minY() < world.getMinHeight() || box.maxY() >= world.getMaxHeight()) throw new IllegalArgumentException(
            "Auswahl überschreitet die Bauhöhe."
        );
        if (tree && (box.width() > 31 || box.depth() > 31 || box.height() > 64)) throw new IllegalArgumentException(
            "Baumauswahl maximal 31×64×31; nur den Baum ohne Boden markieren."
        );
        long limit = tree ? 4096 : plugin.configuration().settings().maximumBlocks();
        generation.operation(
            player,
            "Export " + id,
            job -> {
                store.checkNew(id, tree);
                SavedStructure captured = SelectionCapture.capture(world, box, tree, limit, scheduler, job);
                job.check();
                job.begin(JobControl.Stage.COMMITTING, "Vorlage speichern", 1);
                if (tree) {
                    var custom = TreeExport.convert(id, captured);
                    store.saveTree(custom);
                    plugin.registerTree(custom);
                } else store.saveIsland(id, captured);
                return captured.blockCount();
            },
            blocks ->
                IslandCommand.info(
                    player,
                    "Export gespeichert: " +
                        (tree ? "trees/" + id + ".yml" : "islands/" + id + ".insel") +
                        ", " +
                        blocks +
                        " Blöcke." +
                        (tree ? " Direkt als tree:" + id + " und per Import verwendbar." : "")
                )
        );
    }

    private void load(Player player, String[] args) {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException(
            "Benutzung: /inselgen import [tree|insel] <Name>"
        );
        boolean tree;
        String id;
        if (args.length == 3) {
            tree = treeKind(args[1]);
            id = TemplateStore.name(args[2]);
        } else {
            id = TemplateStore.name(args[1]);
            boolean isTree = plugin.configuration().trees().get(id) != null,
                isIsland = store.names().contains(id);
            if (isTree && isIsland) throw new IllegalArgumentException(
                "Name ist als Baum und Insel vorhanden. import tree oder import insel verwenden."
            );
            if (!isTree && !isIsland) throw new IllegalArgumentException(
                "Unbekannte Vorlage: " + id + ". Siehe /inselgen templates."
            );
            tree = isTree;
        }
        var custom = tree ? plugin.configuration().trees().get(id) : null;
        if (tree && custom == null) throw new IllegalArgumentException(
            "Unbekannte Baumvorlage: " + id + ". Siehe /inselgen templates."
        );
        long limit = plugin.configuration().settings().maximumBlocks();
        UUID targetWorld = player.getWorld().getUID();
        generation.operation(
            player,
            "Vorlage laden: " + id,
            job -> {
                job.begin(JobControl.Stage.LOADING, "Vorlage lesen", 1);
                SavedStructure template;
                try {
                    template = tree ? SavedStructure.tree(custom) : store.loadIsland(id, limit);
                } catch (java.io.IOException error) {
                    throw new IllegalArgumentException("Inseldatei ist beschädigt oder nicht lesbar: " + id, error);
                }
                if (template.blockCount() > limit) throw new IllegalArgumentException(
                    "Vorlage überschreitet maximum-blocks."
                );
                List<BlockData> palette = new ArrayList<>();
                job.total = template.palette().size();
                for (int offset = 0; offset < template.palette().size(); offset += 64) {
                    job.check();
                    var states = template.palette().subList(offset, Math.min(offset + 64, template.palette().size()));
                    palette.addAll(
                        job.await(
                            scheduler.entity(player, () ->
                                states
                                    .stream()
                                    .map(state -> {
                                        try {
                                            BlockData data = Bukkit.createBlockData(state);
                                            if (data.getMaterial().isAir()) throw new IllegalArgumentException(
                                                "Luft darf nicht Teil der Blockpalette sein."
                                            );
                                            return data;
                                        } catch (IllegalArgumentException error) {
                                            throw new IllegalArgumentException(
                                                "Blockzustand wird auf diesem Server nicht unterstützt: " + state,
                                                error
                                            );
                                        }
                                    })
                                    .toList()
                            )
                        )
                    );
                    job.done = palette.size();
                }
                var points = template.preview(previews.blockLimit());
                job.check();
                return new Loaded(template, points, List.copyOf(palette));
            },
            loaded -> {
                if (!player.getWorld().getUID().equals(targetWorld)) {
                    IslandCommand.error(player, "Welt während des Ladens gewechselt. Bitte erneut importieren.");
                    return;
                }
                try {
                    previews.show(
                        player,
                        (tree ? "Baum " : "Insel ") + id,
                        loaded.template,
                        loaded.points,
                        loaded.palette
                    );
                } catch (IllegalArgumentException error) {
                    IslandCommand.error(player, error.getMessage());
                }
            }
        );
    }

    private SelectionBox selection(Player player) {
        Picks picks = selections.get(player.getUniqueId());
        if (picks != null) {
            if (picks.first == null || picks.second == null) throw new IllegalArgumentException(
                "Eigene Auswahl unvollständig: /inselgen pos1 und pos2 setzen. Mit /inselgen selection clear wieder WorldEdit verwenden."
            );
            var a = picks.first;
            var b = picks.second;
            if (
                !a.world.equals(player.getWorld().getUID()) || !a.world.equals(b.world)
            ) throw new IllegalArgumentException("Auswahl gehört zu einer anderen Welt.");
            return new SelectionBox(
                a.world,
                Math.min(a.x, b.x),
                Math.min(a.y, b.y),
                Math.min(a.z, b.z),
                Math.max(a.x, b.x),
                Math.max(a.y, b.y),
                Math.max(a.z, b.z)
            );
        }
        if (
            !scheduler.isFolia() &&
            (Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit") ||
                Bukkit.getPluginManager().isPluginEnabled("WorldEdit"))
        ) return WorldEditSelection.get(player);
        throw new IllegalArgumentException("Zuerst /inselgen pos1 und /inselgen pos2 setzen, optional mit x y z.");
    }

    private void position(Player player, String[] args, boolean first) {
        if (args.length != 1 && args.length != 4) throw new IllegalArgumentException(
            "Benutzung: /inselgen pos1|pos2 [x y z]"
        );
        Location at = player.getLocation();
        World world = player.getWorld();
        int x = at.getBlockX(),
            y = at.getBlockY(),
            z = at.getBlockZ();
        if (args.length == 4) {
            x = coordinate(args[1], x);
            y = coordinate(args[2], y);
            z = coordinate(args[3], z);
        }
        if (
            y < world.getMinHeight() ||
            y >= world.getMaxHeight() ||
            x < -29_999_984 ||
            x >= 29_999_984 ||
            z < -29_999_984 ||
            z >= 29_999_984
        ) throw new IllegalArgumentException("Position liegt außerhalb der Welt-/Baugrenze.");
        Pick pick = new Pick(world.getUID(), x, y, z);
        selections.compute(player.getUniqueId(), (ignored, old) -> {
            Pick a = old == null ? null : old.first,
                b = old == null ? null : old.second;
            if (a != null && !a.world.equals(pick.world)) a = null;
            if (b != null && !b.world.equals(pick.world)) b = null;
            return first ? new Picks(pick, b) : new Picks(a, pick);
        });
        IslandCommand.info(
            player,
            "Position " +
                (first ? 1 : 2) +
                ": " +
                x +
                ", " +
                y +
                ", " +
                z +
                ". Eigene Auswahl hat Vorrang vor WorldEdit."
        );
    }

    private static int coordinate(String value, int base) {
        try {
            return value.startsWith("~")
                ? Math.addExact(base, value.length() == 1 ? 0 : Integer.parseInt(value.substring(1)))
                : Integer.parseInt(value);
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException("Koordinate muss ganzzahlig sein; auch ~ oder ~5 ist möglich.");
        }
    }

    private static boolean treeKind(String kind) {
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "tree", "baum" -> true;
            case "insel", "island" -> false;
            default -> throw new IllegalArgumentException("Typ muss tree oder insel sein.");
        };
    }

    List<String> complete(String[] args) {
        String command = args[0].toLowerCase(Locale.ROOT),
            prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> values = new ArrayList<>();
        if (args.length == 2) switch (command) {
            case "export", "import" -> {
                values.addAll(List.of("tree", "insel"));
                if (command.equals("import")) {
                    values.addAll(store.names());
                    values.addAll(plugin.configuration().trees().names());
                }
            }
            case "move" -> values.addAll(List.of("vor", "zurueck", "links", "rechts", "hoch", "runter"));
            case "selection" -> values.add("clear");
            case "pos1", "pos2" -> values.add("~");
        }
        if (args.length == 3 && command.equals("import")) {
            try {
                values.addAll(treeKind(args[1]) ? plugin.configuration().trees().names() : store.names());
            } catch (IllegalArgumentException ignored) {}
        }
        if (args.length == 3 && command.equals("move")) values.addAll(List.of("1", "5", "10", "25", "100"));
        if ((command.equals("pos1") || command.equals("pos2")) && args.length >= 3 && args.length <= 4) values.add("~");
        return values
            .stream()
            .filter(v -> v.startsWith(prefix))
            .distinct()
            .toList();
    }

    boolean cancel(Player player) {
        return previews.cancel(player, true);
    }

    void cancelForPlacement(Player player) {
        previews.cancel(player, true, false);
    }

    void refresh() {
        store.refresh();
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        selections.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void close() {
        previews.close();
        selections.clear();
    }
}
