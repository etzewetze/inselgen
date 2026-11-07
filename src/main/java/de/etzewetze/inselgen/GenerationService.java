package de.etzewetze.inselgen;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BlockState;
import de.etzewetze.inselgen.terrain.IslandGenerator;
import de.etzewetze.inselgen.terrain.IslandPlan;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/** All Bukkit player/world access happens on the server thread; FAWE work runs on one worker. */
final class GenerationService implements AutoCloseable {
    private final InselGenPlugin plugin;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("InselGen-worker").factory());
    private final AtomicReference<Job> active = new AtomicReference<>();
    private final Map<IslandPlan.BlockKind, BlockState> palette = new EnumMap<>(IslandPlan.BlockKind.class);
    private final BukkitTask progress;
    private volatile boolean closing;

    private static final class Job {
        final UUID owner;
        final String ownerName;
        final GenerationArguments request;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile String phase = "Insel berechnen";
        volatile long done;
        volatile long total;

        Job(Player player, GenerationArguments request) {
            owner = player.getUniqueId();
            ownerName = player.getName();
            this.request = request;
        }

        void checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Abgebrochen");
        }

        String status() {
            return ownerName + ": " + phase + (total > 0 ? " (" + Math.min(100, done * 100 / total) + " %)" : "")
                    + ", Größe " + request.size() + ", Seed " + request.seed();
        }
    }

    GenerationService(InselGenPlugin plugin) {
        this.plugin = plugin;
        for (IslandPlan.BlockKind kind : IslandPlan.BlockKind.values()) {
            palette.put(kind, BukkitAdapter.adapt(Material.valueOf(kind.name()).createBlockData()));
        }
        // Persistent leaves prevent a freshly generated canopy from decaying.
        palette.put(IslandPlan.BlockKind.OAK_LEAVES, BukkitAdapter.adapt(Material.OAK_LEAVES.createBlockData("[persistent=true]")));
        progress = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Job job = active.get();
            if (job == null) return;
            Player player = Bukkit.getPlayer(job.owner);
            if (player != null) player.sendActionBar(Component.text(job.status(), NamedTextColor.AQUA));
        }, 20, 40);
    }

    void start(Player player, GenerationArguments request, GenerationSettings settings) {
        if (closing) throw new IllegalStateException("Das Plugin wird gerade beendet.");
        if (active.get() != null) throw new IllegalStateException("Es läuft bereits ein Auftrag. Fortschritt: /skyinsel status");
        Location location = player.getLocation();
        World world = player.getWorld();
        int originX = location.getBlockX() - request.size() / 2;
        int originZ = location.getBlockZ() - request.size() / 2;
        int baseY = location.getBlockY() - 1;
        int worldMin = world.getMinHeight(), worldMax = world.getMaxHeight();
        checkBorder(world.getWorldBorder(), originX, originZ, request.size());
        com.sk89q.worldedit.entity.Player actor = BukkitAdapter.adapt(player);
        com.sk89q.worldedit.world.World editWorld = BukkitAdapter.adapt(world);
        LocalSession history = WorldEdit.getInstance().getSessionManager().get(actor);
        UUID worldId = world.getUID();
        Job job = new Job(player, request);
        if (!active.compareAndSet(null, job)) throw new IllegalStateException("Es läuft bereits ein Auftrag.");
        IslandCommand.info(player, "Erzeuge " + request.style().token() + "e Insel, Größe " + request.size() + ", Seed " + request.seed() + ".");
        IslandCommand.info(player, "Abbrechen: /skyinsel cancel. Währenddessen keine FAWE-Edits oder Undo im selben Bereich starten.");
        worker.execute(() -> run(job, settings, actor, editWorld, history, worldId, originX, originZ, baseY, worldMin, worldMax,
                location.getYaw(), location.getPitch()));
    }

    private static void checkBorder(WorldBorder border, int x, int z, int size) {
        Location center = border.getCenter();
        double half = border.getSize() / 2;
        if (x < center.getX() - half || z < center.getZ() - half || x + size > center.getX() + half || z + size > center.getZ() + half
                || x < -29_999_984 || z < -29_999_984 || (long) x + size > 29_999_984 || (long) z + size > 29_999_984)
            throw new IllegalArgumentException("Die Insel würde die Weltgrenze überschreiten. Wähle eine andere Position oder kleinere Größe.");
    }

    private void run(Job job, GenerationSettings settings, com.sk89q.worldedit.entity.Player actor,
                     com.sk89q.worldedit.world.World world, LocalSession history, UUID worldId,
                     int originX, int originZ, int baseY, int worldMin, int worldMax, float yaw, float pitch) {
        EditSession session = null;
        IslandPlan plan = null;
        String failure = null;
        boolean cancelled = false;
        long placed = 0;
        long started = System.nanoTime();
        try {
            plan = new IslandGenerator().generate(job.request.size(), baseY, job.request.seed(), job.request.style(),
                    settings.trees(), settings.plants(), job.cancelled::get);
            job.checkCancelled();
            if (plan.minY() < worldMin || plan.maxY() >= worldMax)
                throw new IllegalArgumentException("Nicht genug Bauhöhe: Die Insel braucht Y=" + plan.minY() + " bis " + plan.maxY()
                        + "; erlaubt ist " + worldMin + " bis " + (worldMax - 1) + ". Wähle eine andere Höhe oder kleinere Größe.");
            if (plan.blockCount() > settings.maximumBlocks())
                throw new IllegalArgumentException("Die Insel hätte " + plan.blockCount() + " Blöcke; das Limit ist " + settings.maximumBlocks() + ".");
            session = WorldEdit.getInstance().newEditSessionBuilder().world(world).actor(actor)
                    .maxBlocks(settings.maximumBlocks()).fastMode(false)
                    .command("/skyinsel " + job.request.size() + " " + job.request.seed() + " " + job.request.style().token()).build();
            if (settings.requireEmpty()) preflight(job, session, plan, originX, originZ);
            job.checkCancelled();
            job.phase = "Blöcke setzen";
            job.done = 0;
            job.total = plan.blockCount();
            for (int z = 0; z < plan.size(); z++) {
                for (int x = 0; x < plan.size(); x++) {
                    job.checkCancelled();
                    IslandPlan.Column column = plan.column(x, z);
                    if (column == null) continue;
                    for (int y = column.bottom(); y <= column.top(); y++) {
                        place(session, originX + x, y, originZ + z, column.blockAt(y));
                        placed++;
                    }
                    job.done = placed;
                }
            }
            for (IslandPlan.Decoration decoration : plan.decorations()) {
                job.checkCancelled();
                place(session, originX + decoration.x(), decoration.y(), originZ + decoration.z(), decoration.kind());
                job.done = ++placed;
            }
        } catch (CancellationException error) {
            cancelled = true;
        } catch (IllegalArgumentException error) {
            failure = error.getMessage();
        } catch (Exception error) {
            failure = "FAWE konnte den Auftrag nicht abschließen: " + error.getClass().getSimpleName() + ". Details stehen im Serverlog.";
            plugin.getLogger().log(Level.SEVERE, "Fehler beim Erzeugen der Insel (Seed " + job.request.seed() + ")", error);
        } finally {
            if (session != null) {
                job.phase = "Änderungen abschließen";
                try {
                    session.close(); // Blocking FAWE queue flush: keep it off the server thread.
                } catch (Exception error) {
                    failure = "FAWE konnte die Änderungen nicht vollständig abschließen. Bitte Serverlog prüfen.";
                    plugin.getLogger().log(Level.SEVERE, "FAWE-Queue konnte nicht abgeschlossen werden", error);
                }
                try {
                    // LocalSession synchronizes its history; remember also flushes, so do this on the worker.
                    history.remember(session);
                } catch (Exception error) {
                    failure = "Änderungen wurden gesetzt, aber die Undo-Historie konnte nicht gespeichert werden. Bitte Serverlog prüfen.";
                    plugin.getLogger().log(Level.SEVERE, "Undo-Historie konnte nicht gespeichert werden", error);
                }
            }
            long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            String resultFailure = failure;
            boolean resultCancelled = cancelled;
            long resultPlaced = placed;
            IslandPlan resultPlan = plan;
            active.compareAndSet(job, null);
            if (!closing && plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, () -> finish(job, settings, resultPlan, resultFailure, resultCancelled,
                        resultPlaced, duration, worldId, originX, originZ, yaw, pitch));
            }
        }
    }

    private void preflight(Job job, EditSession session, IslandPlan plan, int originX, int originZ) {
        job.phase = "Freien Luftraum prüfen";
        job.done = 0;
        job.total = plan.blockCount();
        for (int z = 0; z < plan.size(); z++) {
            for (int x = 0; x < plan.size(); x++) {
                job.checkCancelled();
                IslandPlan.Column column = plan.column(x, z);
                if (column == null) continue;
                for (int y = column.bottom(); y <= column.top(); y++) requireAir(session, originX + x, y, originZ + z);
                job.done += column.height();
            }
        }
        for (IslandPlan.Decoration decoration : plan.decorations()) {
            job.checkCancelled();
            requireAir(session, originX + decoration.x(), decoration.y(), originZ + decoration.z());
            job.done++;
        }
    }

    private static void requireAir(EditSession session, int x, int y, int z) {
        if (!session.getBlock(BlockVector3.at(x, y, z)).getBlockType().getMaterial().isAir())
            throw new IllegalArgumentException("Bereich belegt bei " + x + ", " + y + ", " + z + ". Es wurde nichts gebaut. Fliege höher oder wähle freien Luftraum.");
    }

    private void place(EditSession session, int x, int y, int z, IslandPlan.BlockKind kind) throws WorldEditException {
        BlockVector3 position = BlockVector3.at(x, y, z);
        BlockState state = palette.get(kind);
        // Identical blocks are fine when replacement was explicitly enabled in config.
        if (!session.setBlock(position, state) && !session.getBlock(position).equals(state))
            throw new IllegalArgumentException("FAWE hat eine Änderung bei " + x + ", " + y + ", " + z
                    + " abgelehnt (z. B. Gebietsschutz oder Limit). Bereits gesetzte Blöcke sind über //undo rückgängig zu machen.");
    }

    private void finish(Job job, GenerationSettings settings, IslandPlan plan, String failure, boolean cancelled,
                        long placed, long duration, UUID worldId, int originX, int originZ, float yaw, float pitch) {
        Player player = Bukkit.getPlayer(job.owner);
        String description = "Seed " + job.request.seed() + ", Größe " + job.request.size() + ", " + placed + " Blöcke, " + duration + " ms";
        plugin.getLogger().info((failure == null && !cancelled ? "Insel fertig: " : "Auftrag beendet: ") + description);
        if (player == null) return;
        if (failure != null) IslandCommand.error(player, failure);
        else if (cancelled) IslandCommand.info(player, placed == 0 ? "Auftrag abgebrochen. Es wurden keine Blöcke gesetzt."
                : "Auftrag abgebrochen. " + placed + " Blöcke wurden bereits gesetzt; rückgängig mit //undo.");
        else {
            IslandCommand.info(player, "Insel fertig! " + description + ". Rückgängig: //undo.");
            if (settings.teleport() && plan != null && player.getWorld().getUID().equals(worldId)) {
                int center = plan.size() / 2;
                Location destination = new Location(player.getWorld(), originX + center + 0.5,
                        plan.centerTop() + 1, originZ + center + 0.5, yaw, pitch);
                player.teleport(destination);
            }
        }
    }

    String status() {
        Job job = active.get();
        return job == null ? "Kein Auftrag aktiv." : job.status();
    }

    boolean cancel(CommandSender sender) {
        Job job = active.get();
        if (job == null) return false;
        if (sender instanceof Player player && player.getUniqueId().equals(job.owner) || sender.hasPermission("inselgen.admin")) {
            job.cancelled.set(true);
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        closing = true;
        progress.cancel();
        Job job = active.get();
        if (job != null) job.cancelled.set(true);
        worker.shutdown();
        try {
            if (!worker.awaitTermination(15, TimeUnit.SECONDS))
                plugin.getLogger().warning("FAWE schließt noch einen laufenden Auftrag ab. Keine Plugin-Hot-Reloads verwenden.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
