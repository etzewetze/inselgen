package de.etzewetze.inselgen;

import de.etzewetze.inselgen.terrain.BiomeProfile;
import de.etzewetze.inselgen.terrain.IslandGenerator;
import de.etzewetze.inselgen.terrain.IslandPlan;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

final class GenerationService implements AutoCloseable, Listener {
    private final InselGenPlugin plugin;
    private final PlatformScheduler scheduler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("InselGen-worker").factory());
    private final AtomicReference<Job> active = new AtomicReference<>();
    private final ConcurrentMap<UUID, ProgressDisplay> displays = new ConcurrentHashMap<>();
    private final ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("InselGen-progress").factory());
    private final NativeBackend nativeBackend;
    private final EditBackend backend;
    private final List<String> biomes;
    private volatile boolean closing;

    private record Job(Player player, UUID owner, String name, JobControl control, String description, ProgressDisplay display, AtomicBoolean updatePending) { }

    GenerationService(InselGenPlugin plugin) {
        this.plugin = plugin;
        scheduler = new PlatformScheduler(plugin);
        nativeBackend = new NativeBackend(plugin, scheduler);
        String mode = plugin.getConfig().getString("placement-backend", "auto");
        if (!List.of("auto", "native", "fawe").contains(mode)) throw new IllegalArgumentException("placement-backend muss auto, native oder fawe sein.");
        boolean fawe = !scheduler.isFolia() && Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit") && !mode.equals("native");
        if (mode.equals("fawe") && !fawe) throw new IllegalArgumentException("FAWE ist auf diesem Server nicht verfügbar. Auf Folia auto oder native verwenden.");
        backend = fawe ? new FaweBackend() : nativeBackend;
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        Registry.BIOME.forEach(biome -> keys.add(((Keyed) biome).getKey().toString()));
        biomes = keys.stream().sorted().toList();
        plugin.getLogger().info("Platzierung: " + backend.name() + (scheduler.isFolia() ? " (Folia-Regionscheduler)" : "") + "; Undo: " + backend.undoCommand());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        progress.scheduleAtFixedRate(this::refreshProgress, 250, 250, TimeUnit.MILLISECONDS);
    }

    List<String> biomes() { return biomes; }
    String undoCommand() { return backend.undoCommand(); }

    void start(Player player, GenerationArguments request, GenerationSettings settings, TreeLibrary trees) {
        ensureAvailable();
        if (backend == nativeBackend && !player.hasPermission("inselgen.native")) throw new IllegalArgumentException("Die native Platzierung benötigt inselgen.native (standardmäßig OP).");
        var biome = request.biomeKey() == null ? null : Registry.BIOME.get(NamespacedKey.fromString(request.biomeKey()));
        if (request.biomeKey() != null && biome == null) throw new IllegalArgumentException("Unbekanntes Biom auf diesem Server: " + request.biomeKey());
        var features = settings.features().resolve(request, settings.trees(), settings.plants(), trees);
        Location location = player.getLocation(); World world = player.getWorld(); UUID worldId = world.getUID();
        int originX = location.getBlockX() - request.size() / 2, originZ = location.getBlockZ() - request.size() / 2;
        int minY = world.getMinHeight(), maxY = world.getMaxHeight(), baseY = location.getBlockY() - 1;
        EditBackend.Prepared prepared = backend.prepare(player);
        Job job = activate(player, "Größe " + request.size() + ", Seed " + request.seed()
                + (request.biomeKey() == null ? "" : ", Biom " + request.biomeKey()));
        IslandCommand.info(player, "Erzeuge Insel: " + job.description() + ". Abbrechen: /skyinsel cancel.");
        worker.execute(() -> {
            long started = System.nanoTime(); IslandPlan plan = null; String failure = null; boolean cancelled = false;
            try {
                job.control().await(scheduler.global(() -> { checkBorder(world, originX, originZ, request.size()); return null; }));
                plan = new IslandGenerator().generate(request.size(), baseY, request.seed(), request.style(),
                        BiomeProfile.fromKey(request.biomeKey()), features, job.control().cancelled::get, job.control()::planning);
                job.control().check();
                if (plan.minY() < minY || plan.maxY() >= maxY)
                    throw new IllegalArgumentException("Nicht genug Bauhöhe: Insel braucht Y=" + plan.minY() + " bis " + plan.maxY() + "; erlaubt: " + minY + " bis " + (maxY - 1) + ".");
                if (plan.blockCount() > settings.maximumBlocks()) throw new IllegalArgumentException("Die Insel hätte " + plan.blockCount() + " Blöcke; Limit: " + settings.maximumBlocks() + ".");
                prepared.apply(new EditContext(world, worldId, job.owner(), originX, originZ, plan, request, settings, biome, minY, maxY), job.control());
                job.control().complete();
            } catch (CancellationException error) { cancelled = true; }
            catch (Exception error) { failure = describe(error); }
            finally {
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                plugin.getLogger().info("Auftrag beendet: " + job.description() + ", " + job.control().changed + " Blöcke, " + duration + " ms");
                IslandPlan resultPlan = plan; String resultFailure = failure; boolean resultCancelled = cancelled;
                active.compareAndSet(job, null);
                if (!closing) scheduler.entity(player, () -> {
                    finishDisplay(job, resultFailure == null && !resultCancelled);
                    if (resultFailure != null) IslandCommand.error(player, resultFailure + (job.control().changed > 0 ? " Bereits gesetzte Änderungen: " + undoCommand() : ""));
                    else if (resultCancelled) IslandCommand.info(player, "Abgebrochen; " + job.control().changed + " Blöcke gesetzt." + (job.control().changed > 0 ? " Rückgängig: " + undoCommand() : ""));
                    else {
                        IslandCommand.info(player, "Insel fertig! " + job.description() + ", " + job.control().changed + " Blöcke, " + resultPlan.lakes() + " See(n). Rückgängig: " + undoCommand());
                        if (settings.teleport() && resultPlan != null && player.getWorld().getUID().equals(worldId)) {
                            int center = resultPlan.size() / 2;
                            scheduler.teleport(player, new Location(world, originX + center + 0.5, resultPlan.centerTop() + 1, originZ + center + 0.5, location.getYaw(), location.getPitch()));
                        }
                    }
                    return null;
                });
            }
        });
    }

    void undo(Player player) {
        ensureAvailable();
        if (backend != nativeBackend) { player.performCommand("/undo"); return; }
        if (!player.hasPermission("inselgen.native")) throw new IllegalArgumentException("Dafür fehlt inselgen.native.");
        Job job = activate(player, "Undo");
        worker.execute(() -> {
            String failure = null;
            try { nativeBackend.undo(job.owner(), job.control()); job.control().complete(); }
            catch (CancellationException error) { failure = "Undo abgebrochen; mit /skyinsel undo fortsetzen."; }
            catch (Exception error) { failure = describe(error); }
            finally {
                String message = failure;
                active.compareAndSet(job, null);
                if (!closing) scheduler.entity(player, () -> {
                    finishDisplay(job, message == null);
                    if (message != null) IslandCommand.error(player, message);
                    else IslandCommand.info(player, "Undo fertig: " + job.control().changed + " Änderungen zurückgesetzt, " + job.control().skipped + " inzwischen veränderte Positionen übersprungen.");
                    return null;
                });
            }
        });
    }

    private String describe(Exception error) {
        if (error instanceof IllegalArgumentException) return error.getMessage();
        plugin.getLogger().log(Level.SEVERE, "InselGen-Auftrag fehlgeschlagen", error);
        return "Auftrag fehlgeschlagen: " + error.getClass().getSimpleName() + ". Details im Serverlog.";
    }

    private void ensureAvailable() {
        if (closing) throw new IllegalStateException("Das Plugin wird beendet.");
        if (active.get() != null) throw new IllegalStateException("Es läuft bereits ein Auftrag. Fortschritt: /skyinsel status");
    }

    private static void checkBorder(World world, int x, int z, int size) {
        var border = world.getWorldBorder(); var center = border.getCenter(); double half = border.getSize() / 2;
        if (x < center.getX() - half || z < center.getZ() - half || (long) x + size > center.getX() + half || (long) z + size > center.getZ() + half
                || x < -29_999_984 || z < -29_999_984 || (long) x + size > 29_999_984 || (long) z + size > 29_999_984)
            throw new IllegalArgumentException("Die Insel würde die Weltgrenze überschreiten.");
    }

    String status() {
        Job job = active.get();
        if (job == null) return "Kein Auftrag aktiv.";
        var c = job.control();
        return job.name() + ": " + c.phase + " (" + (int) Math.floor(c.progress() * 100) + " %), " + job.description();
    }

    boolean cancel(CommandSender sender) {
        Job job = active.get();
        if (job == null || !(sender.hasPermission("inselgen.admin") || sender instanceof Player player && player.getUniqueId().equals(job.owner()))) return false;
        job.control().cancelled.set(true); return true;
    }

    private Job activate(Player player, String description) {
        ProgressDisplay display = new ProgressDisplay(player);
        Job job = new Job(player, player.getUniqueId(), player.getName(), new JobControl(), description, display, new AtomicBoolean());
        if (!active.compareAndSet(null, job)) { display.close(); throw new IllegalStateException("Es läuft bereits ein Auftrag."); }
        ProgressDisplay previous = displays.put(job.owner(), display);
        if (previous != null) previous.close();
        return job;
    }

    private void refreshProgress() {
        Job job = active.get();
        if (closing || job == null || !job.updatePending().compareAndSet(false, true)) return;
        scheduler.entity(job.player(), () -> {
            if (active.get() == job) job.display().update(job.control());
            return null;
        }).whenComplete((ignored, error) -> job.updatePending().set(false));
    }

    private void finishDisplay(Job job, boolean success) {
        if (success) {
            job.display().update(job.control());
            scheduler.entityLater(job.player(), () -> { removeDisplay(job); return null; }, 30);
        } else removeDisplay(job);
    }

    private void removeDisplay(Job job) { displays.remove(job.owner(), job.display()); job.display().close(); }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        ProgressDisplay display = displays.remove(event.getPlayer().getUniqueId());
        if (display != null) display.close();
    }

    @Override public void close() {
        closing = true; var job = active.get(); if (job != null) job.control().cancelled.set(true);
        progress.shutdownNow(); displays.values().forEach(ProgressDisplay::close); displays.clear();
        scheduler.close(); worker.shutdown();
        try { if (!worker.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("Ein laufender Auftrag wird noch abgeschlossen."); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
