package de.etzewetze.inselgen;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Only this bridge knows about Folia; no Paper-only classes are linked on Spigot. */
final class PlatformScheduler implements AutoCloseable {
    private final Plugin plugin;
    private final boolean folia;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> running = ConcurrentHashMap.newKeySet();
    private Object regions, global;
    private Method regionExecute, globalExecute, entityScheduler, entityExecute, teleport;
    private volatile boolean closed;

    PlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
        boolean detected;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); detected = true; }
        catch (ClassNotFoundException ignored) { detected = false; }
        folia = detected;
        if (folia) {
            try {
                regions = Bukkit.getServer().getClass().getMethod("getRegionScheduler").invoke(Bukkit.getServer());
                global = Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer());
                regionExecute = Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler")
                        .getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
                globalExecute = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler")
                        .getMethod("execute", Plugin.class, Runnable.class);
                entityScheduler = Entity.class.getMethod("getScheduler");
                entityExecute = Class.forName("io.papermc.paper.threadedregions.scheduler.EntityScheduler")
                        .getMethod("execute", Plugin.class, Runnable.class, Runnable.class, long.class);
                teleport = Entity.class.getMethod("teleportAsync", Location.class);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Folia-Scheduler konnte nicht initialisiert werden", error); }
        }
    }

    boolean isFolia() { return folia; }

    <T> CompletableFuture<T> global(Supplier<T> task) { return submit(null, 0, 0, null, task); }
    <T> CompletableFuture<T> chunk(World world, int x, int z, Supplier<T> task) { return submit(world, x, z, null, task); }
    <T> CompletableFuture<T> entity(Player player, Supplier<T> task) { return submit(null, 0, 0, player, task); }

    void teleport(Player player, Location location) {
        if (folia) invoke(teleport, player, location);
        else player.teleport(location);
    }

    private <T> CompletableFuture<T> submit(World world, int x, int z, Player player, Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        pending.add(result);
        result.whenComplete((value, error) -> { pending.remove(result); running.remove(result); });
        Runnable run = () -> {
            synchronized (result) {
                if (closed || result.isCancelled()) { result.cancel(false); return; }
                running.add(result);
            }
            try { result.complete(task.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        };
        if (closed) { result.cancel(false); return result; }
        try {
            if (!folia) Bukkit.getScheduler().runTask(plugin, run);
            else if (player != null) {
                Object scheduler = invoke(entityScheduler, player);
                boolean scheduled = (boolean) invoke(entityExecute, scheduler, plugin, run, (Runnable) () -> result.cancel(false), 1L);
                if (!scheduled) result.cancel(false);
            } else if (world != null) invoke(regionExecute, regions, plugin, world, x, z, run);
            else invoke(globalExecute, global, plugin, run);
        } catch (RuntimeException error) { result.completeExceptionally(error); }
        return result;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    @Override public void close() {
        closed = true;
        pending.forEach(future -> { synchronized (future) { if (!running.contains(future)) future.cancel(false); } });
    }
}
