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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

final class GenerationService implements AutoCloseable {
    private final InselGenPlugin plugin;
    private final PlatformScheduler scheduler;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("InselGen-worker").factory());
    private final AtomicReference<Job> active = new AtomicReference<>();
    private final NativeBackend nativeBackend;
    private final EditBackend backend;
    private final List<String> biomes;
    private volatile boolean closing;

    private record Job(Player player, UUID owner, String name, JobControl control, String description) { }

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
    }

    List<String> biomes() { return biomes; }
    String undoCommand() { return backend.undoCommand(); }

    void start(Player player, GenerationArguments request, GenerationSettings settings) {
        ensureAvailable();
        if (backend == nativeBackend && !player.hasPermission("inselgen.native")) throw new IllegalArgumentException("Die native Platzierung benötigt inselgen.native (standardmäßig OP).");
        var biome = request.biomeKey() == null ? null : Registry.BIOME.get(NamespacedKey.fromString(request.biomeKey()));
        if (request.biomeKey() != null && biome == null) throw new IllegalArgumentException("Unbekanntes Biom auf diesem Server: " + request.biomeKey());
        Location location = player.getLocation(); World world = player.getWorld(); UUID worldId = world.getUID();
        int originX = location.getBlockX() - request.size() / 2, originZ = location.getBlockZ() - request.size() / 2;
        int minY = world.getMinHeight(), maxY = world.getMaxHeight(), baseY = location.getBlockY() - 1;
        EditBackend.Prepared prepared = backend.prepare(player);
        Job job = new Job(player, player.getUniqueId(), player.getName(), new JobControl(), "Größe " + request.size() + ", Seed " + request.seed()
                + (request.biomeKey() == null ? "" : ", Biom " + request.biomeKey()));
        if (!active.compareAndSet(null, job)) throw new IllegalStateException("Es läuft bereits ein Auftrag.");
        IslandCommand.info(player, "Erzeuge Insel: " + job.description() + ". Abbrechen: /skyinsel cancel.");
        worker.execute(() -> {
            long started = System.nanoTime(); IslandPlan plan = null; String failure = null; boolean cancelled = false;
            try {
                job.control().await(scheduler.global(() -> { checkBorder(world, originX, originZ, request.size()); return null; }));
                plan = new IslandGenerator().generate(request.size(), baseY, request.seed(), request.style(), settings.trees(), settings.plants(),
                        BiomeProfile.fromKey(request.biomeKey()), job.control().cancelled::get);
                job.control().check();
                if (plan.minY() < minY || plan.maxY() >= maxY)
                    throw new IllegalArgumentException("Nicht genug Bauhöhe: Insel braucht Y=" + plan.minY() + " bis " + plan.maxY() + "; erlaubt: " + minY + " bis " + (maxY - 1) + ".");
                if (plan.blockCount() > settings.maximumBlocks()) throw new IllegalArgumentException("Die Insel hätte " + plan.blockCount() + " Blöcke; Limit: " + settings.maximumBlocks() + ".");
                prepared.apply(new EditContext(world, worldId, job.owner(), originX, originZ, plan, request, settings, biome, minY, maxY), job.control());
            } catch (CancellationException error) { cancelled = true; }
            catch (Exception error) { failure = describe(error); }
            finally {
                long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                plugin.getLogger().info("Auftrag beendet: " + job.description() + ", " + job.control().changed + " Blöcke, " + duration + " ms");
                IslandPlan resultPlan = plan; String resultFailure = failure; boolean resultCancelled = cancelled;
                active.compareAndSet(job, null);
                if (!closing) scheduler.entity(player, () -> {
                    if (resultFailure != null) IslandCommand.error(player, resultFailure + (job.control().changed > 0 ? " Bereits gesetzte Änderungen: " + undoCommand() : ""));
                    else if (resultCancelled) IslandCommand.info(player, "Abgebrochen; " + job.control().changed + " Blöcke gesetzt." + (job.control().changed > 0 ? " Rückgängig: " + undoCommand() : ""));
                    else {
                        IslandCommand.info(player, "Insel fertig! " + job.description() + ", " + job.control().changed + " Blöcke. Rückgängig: " + undoCommand());
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
        Job job = new Job(player, player.getUniqueId(), player.getName(), new JobControl(), "Undo");
        if (!active.compareAndSet(null, job)) throw new IllegalStateException("Es läuft bereits ein Auftrag.");
        worker.execute(() -> {
            String failure = null;
            try { nativeBackend.undo(job.owner(), job.control()); }
            catch (CancellationException error) { failure = "Undo abgebrochen; mit /skyinsel undo fortsetzen."; }
            catch (Exception error) { failure = describe(error); }
            finally {
                String message = failure;
                active.compareAndSet(job, null);
                if (!closing) scheduler.entity(player, () -> {
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
        return job.name() + ": " + c.phase + (c.total > 0 ? " (" + Math.min(100, c.done * 100 / c.total) + " %)" : "") + ", " + job.description();
    }

    boolean cancel(CommandSender sender) {
        Job job = active.get();
        if (job == null || !(sender.hasPermission("inselgen.admin") || sender instanceof Player player && player.getUniqueId().equals(job.owner()))) return false;
        job.control().cancelled.set(true); return true;
    }

    @Override public void close() {
        closing = true; var job = active.get(); if (job != null) job.control().cancelled.set(true);
        scheduler.close(); worker.shutdown();
        try { if (!worker.awaitTermination(15, TimeUnit.SECONDS)) plugin.getLogger().warning("Ein laufender Auftrag wird noch abgeschlossen."); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }
}
