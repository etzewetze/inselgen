package de.etzewetze.inselgen;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Client-only blocks and particles. Actual placement is delegated only by confirm. */
final class PreviewService implements Listener, AutoCloseable {

    private record Position(int x, int y, int z) {}

    private record Rendered(List<BlockState> packets, Map<Position, BlockData> originals, List<BlockState> ghosts) {}

    private final InselGenPlugin plugin;
    private final PlatformScheduler scheduler;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("InselGen-preview-clock").factory()
    );
    private final ExecutorService renderer = Executors.newSingleThreadExecutor(
        Thread.ofPlatform().daemon(true).name("InselGen-preview").factory()
    );
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean closing;
    private final int blockLimit, timeout;

    static final class Session {

        final Player player;
        final World world;
        final UUID worldId;
        final String label;
        final SavedStructure template;
        final List<SavedStructure.Point> points;
        final List<BlockData> palette;
        final int minY, maxY;
        final AtomicBoolean busy = new AtomicBoolean(),
            tickPending = new AtomicBoolean();
        volatile PreviewPosition position;
        volatile long revision,
            rendered = -1,
            expires;
        volatile int viewerX, viewerZ;
        volatile Map<Position, BlockData> originals = Map.of();
        volatile List<BlockState> ghosts = List.of();
        boolean announced;

        Session(
            Player player,
            String label,
            SavedStructure template,
            List<SavedStructure.Point> points,
            List<BlockData> palette,
            int timeout
        ) {
            this.player = player;
            this.label = label;
            this.template = template;
            this.points = points;
            this.palette = palette;
            world = player.getWorld();
            worldId = world.getUID();
            minY = world.getMinHeight();
            maxY = world.getMaxHeight();
            if (template.height > maxY - minY) throw new IllegalArgumentException("Vorlage ist höher als die Welt.");
            Location at = player.getLocation();
            int[] forward = PreviewPosition.forward(at.getYaw());
            position = new PreviewPosition(
                at.getBlockX() + forward[0] * 8 - template.anchorX,
                Math.clamp(at.getBlockY() - template.anchorY, minY, maxY - template.height),
                at.getBlockZ() + forward[1] * 8 - template.anchorZ
            );
            position.check(template, minY, maxY);
            viewerX = at.getBlockX();
            viewerZ = at.getBlockZ();
            expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        }
    }

    PreviewService(InselGenPlugin plugin, PlatformScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        blockLimit = plugin.getConfig().getInt("preview.maximum-blocks", 4096);
        timeout = plugin.getConfig().getInt("preview.timeout-seconds", 300);
        if (
            blockLimit < 128 || blockLimit > 16384 || timeout < 30 || timeout > 1800
        ) throw new IllegalArgumentException("preview.maximum-blocks: 128–16384; preview.timeout-seconds: 30–1800.");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        timer.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    int blockLimit() {
        return blockLimit;
    }

    void show(
        Player player,
        String label,
        SavedStructure template,
        List<SavedStructure.Point> points,
        List<BlockData> palette
    ) {
        if (closing) return;
        if (!sessions.containsKey(player.getUniqueId()) && sessions.size() >= 4) throw new IllegalArgumentException(
            "Es können höchstens vier Vorschauen gleichzeitig aktiv sein."
        );
        Session next = new Session(player, label, template, points, palette, timeout);
        cancel(player, true);
        sessions.put(player.getUniqueId(), next);
        IslandCommand.info(
            player,
            "Vorschau wird geladen: " +
                label +
                " (" +
                template.width +
                "×" +
                template.height +
                "×" +
                template.depth +
                ", " +
                template.blockCount() +
                " Blöcke). Noch nichts gebaut."
        );
        if (template.blockCount() > blockLimit) IslandCommand.info(
            player,
            "Große Vorlage: vereinfachte Oberflächenvorschau mit Begrenzungsrahmen. confirm setzt alle Blöcke."
        );
        request(next);
    }

    void move(Player player, String direction, int steps) {
        Session session = require(player);
        PreviewPosition next = session.position.move(direction, steps, player.getLocation().getYaw());
        next.check(session.template, session.minY, session.maxY);
        session.position = next;
        session.revision++;
        session.expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        IslandCommand.info(
            player,
            "Vorschau verschoben. Anker: " +
                (next.x() + session.template.anchorX) +
                ", " +
                (next.y() + session.template.anchorY) +
                ", " +
                (next.z() + session.template.anchorZ)
        );
        request(session);
    }

    Session require(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) throw new IllegalArgumentException(
            "Keine Vorschau aktiv. Zuerst /inselgen import tree|insel <Name>."
        );
        if (!player.getWorld().getUID().equals(session.worldId)) {
            cancel(player, false);
            throw new IllegalArgumentException("Vorschau gehört zu einer anderen Welt.");
        }
        return session;
    }

    boolean cancel(Player player, boolean restore) {
        return cancel(player, restore, restore);
    }

    boolean cancel(Player player, boolean restore, boolean fresh) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) return false;
        var old = session.originals;
        if (restore) {
            restoreCached(session);
            if (fresh && !closing && !old.isEmpty()) refreshReal(session, old.keySet());
        }
        return true;
    }

    private void refreshReal(Session session, Set<Position> positions) {
        renderer.execute(() -> {
            try {
                if (closing) return;
                Map<Long, List<Position>> chunks = new HashMap<>();
                for (Position p : positions)
                    if (near(p, session.viewerX, session.viewerZ)) chunks
                        .computeIfAbsent(((long) (p.x >> 4) << 32) | ((p.z >> 4) & 0xffffffffL), ignored ->
                            new ArrayList<>()
                        )
                        .add(p);
                List<CompletableFuture<List<BlockState>>> futures = new ArrayList<>();
                for (var entry : chunks.entrySet()) {
                    int cx = (int) (entry.getKey() >> 32),
                        cz = (int) (long) entry.getKey();
                    CompletableFuture<List<BlockState>> future = CompletableFuture.completedFuture(new ArrayList<>());
                    for (int i = 0; i < entry.getValue().size(); i += 256) {
                        var batch = entry.getValue().subList(i, Math.min(i + 256, entry.getValue().size()));
                        future = future.thenCompose(states ->
                            scheduler.chunk(session.world, cx, cz, () -> {
                                if (session.world.isChunkLoaded(cx, cz)) for (Position p : batch)
                                    states.add(session.world.getBlockAt(p.x, p.y, p.z).getState());
                                return states;
                            })
                        );
                    }
                    futures.add(future);
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
                scheduler
                    .entity(session.player, () -> {
                        if (session.player.getWorld().getUID().equals(session.worldId)) for (var future : futures)
                            session.player.sendBlockChanges(future.join());
                        return null;
                    })
                    .get(15, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                /* Cached restoration already ran; unloaded client chunks reset normally. */
            }
        });
    }

    private void restoreCached(Session session) {
        session.originals.forEach((p, data) ->
            session.player.sendBlockChange(new Location(session.world, p.x, p.y, p.z), data)
        );
        session.originals = Map.of();
        session.ghosts = List.of();
    }

    private void request(Session session) {
        if (closing || !session.busy.compareAndSet(false, true)) return;
        renderer.execute(() -> {
            long revision = session.revision;
            try {
                if (!current(session)) return;
                var origin = session.position;
                int vx = session.viewerX,
                    vz = session.viewerZ;
                Map<Position, Integer> wanted = new HashMap<>();
                for (var p : session.points) {
                    var position = new Position(origin.x() + p.x(), origin.y() + p.y(), origin.z() + p.z());
                    if (near(position, vx, vz)) wanted.put(position, p.state());
                }
                Set<Position> positions = new HashSet<>(wanted.keySet());
                session.originals
                    .keySet()
                    .stream()
                    .filter(p -> near(p, vx, vz))
                    .forEach(positions::add);
                Map<Long, List<Position>> chunks = new HashMap<>();
                for (Position p : positions)
                    chunks
                        .computeIfAbsent(((long) (p.x >> 4) << 32) | ((p.z >> 4) & 0xffffffffL), ignored ->
                            new ArrayList<>()
                        )
                        .add(p);
                List<CompletableFuture<Rendered>> futures = new ArrayList<>();
                for (var entry : chunks.entrySet()) {
                    int cx = (int) (entry.getKey() >> 32),
                        cz = (int) (long) entry.getKey();
                    List<Position> values = entry.getValue();
                    CompletableFuture<Rendered> future = CompletableFuture.completedFuture(
                        new Rendered(new ArrayList<>(), new HashMap<>(), new ArrayList<>())
                    );
                    for (int i = 0; i < values.size(); i += 256) {
                        var batch = values.subList(i, Math.min(i + 256, values.size()));
                        future = future.thenCompose(result ->
                            scheduler.chunk(session.world, cx, cz, () -> {
                                if (
                                    !current(session) ||
                                    session.revision != revision ||
                                    !session.world.isChunkLoaded(cx, cz)
                                ) return result;
                                for (Position p : batch) {
                                    BlockState state = session.world.getBlockAt(p.x, p.y, p.z).getState();
                                    Integer id = wanted.get(p);
                                    if (id != null) {
                                        result.originals.put(p, state.getBlockData().clone());
                                        state.setBlockData(session.palette.get(id));
                                        result.ghosts.add(state);
                                    }
                                    result.packets.add(state);
                                }
                                return result;
                            })
                        );
                    }
                    futures.add(future);
                }
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
                scheduler
                    .entity(session.player, () -> {
                        if (!current(session) || session.revision != revision) return null;
                        session.originals.forEach((p, data) -> {
                            if (!near(p, vx, vz)) session.player.sendBlockChange(
                                new Location(session.world, p.x, p.y, p.z),
                                data
                            );
                        });
                        List<BlockState> ghosts = new ArrayList<>();
                        Map<Position, BlockData> originals = new HashMap<>();
                        for (var future : futures) {
                            var result = future.join();
                            session.player.sendBlockChanges(result.packets);
                            ghosts.addAll(result.ghosts);
                            originals.putAll(result.originals);
                        }
                        session.ghosts = List.copyOf(ghosts);
                        session.originals = Map.copyOf(originals);
                        session.rendered = revision;
                        if (!session.announced) {
                            session.announced = true;
                            IslandCommand.info(
                                session.player,
                                "Vorschau bereit. Verschieben, dann /inselgen confirm; verwerfen: /inselgen cancel."
                            );
                            buttons(session.player);
                        }
                        return null;
                    })
                    .get(15, TimeUnit.SECONDS);
            } catch (CancellationException ignored) {} catch (Exception error) {
                if (!closing && current(session)) {
                    plugin.getLogger().log(Level.WARNING, "Vorschau fehlgeschlagen", error);
                    scheduler.entity(session.player, () -> {
                        if (current(session)) {
                            cancel(session.player, true);
                            IslandCommand.error(
                                session.player,
                                "Vorschau konnte nicht geladen werden. Bitte erneut importieren."
                            );
                        }
                        return null;
                    });
                }
            } finally {
                session.busy.set(false);
                if (!closing && current(session) && session.revision != revision) request(session);
            }
        });
    }

    private boolean current(Session s) {
        return sessions.get(s.player.getUniqueId()) == s;
    }

    private static boolean near(Position p, int x, int z) {
        return Math.abs((long) p.x - x) <= 48 && Math.abs((long) p.z - z) <= 48;
    }

    private void tick() {
        if (closing) return;
        for (Session session : sessions.values())
            if (session.tickPending.compareAndSet(false, true)) {
                scheduler
                    .entity(session.player, () -> {
                        if (!current(session)) return null;
                        if (System.nanoTime() > session.expires) {
                            cancel(session.player, true);
                            IslandCommand.info(session.player, "Vorschau abgelaufen; nichts gebaut.");
                            return null;
                        }
                        Location at = session.player.getLocation();
                        if (
                            (session.viewerX >> 4) != (at.getBlockX() >> 4) ||
                            (session.viewerZ >> 4) != (at.getBlockZ() >> 4)
                        ) {
                            session.viewerX = at.getBlockX();
                            session.viewerZ = at.getBlockZ();
                            session.revision++;
                            request(session);
                        } else if (!session.busy.get()) session.player.sendBlockChanges(session.ghosts);
                        outline(session);
                        return null;
                    })
                    .whenComplete((v, e) -> session.tickPending.set(false));
            }
    }

    private static void buttons(Player player) {
        TextComponent line = new TextComponent("");
        for (String[] item : new String[][] {
            { "Vor", "move vor" },
            { "Zurück", "move zurueck" },
            { "Links", "move links" },
            { "Rechts", "move rechts" },
            { "Hoch", "move hoch" },
            { "Runter", "move runter" },
            { "Setzen", "confirm" },
            { "Verwerfen", "cancel" },
        }) {
            TextComponent button = new TextComponent("[" + item[0] + "] ");
            button.setColor(net.md_5.bungee.api.ChatColor.AQUA);
            button.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/inselgen " + item[1]));
            line.addExtra(button);
        }
        player.spigot().sendMessage(line);
    }

    private static void outline(Session session) {
        var o = session.position;
        var t = session.template;
        double[] min = { o.x(), o.y(), o.z() },
            size = { t.width, t.height, t.depth };
        var dust = new Particle.DustOptions(Color.LIME, 1.2f);
        for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 4; side++) for (
            int step = 0;
            step <= 10;
            step++
        ) {
            double[] p = min.clone();
            int a = (axis + 1) % 3,
                b = (axis + 2) % 3;
            p[axis] += (size[axis] * step) / 10;
            p[a] += (side & 1) * size[a];
            p[b] += (side >> 1) * size[b];
            session.player.spawnParticle(Particle.DUST, p[0], p[1], p[2], 1, 0, 0, 0, 0, dust);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), false);
    }

    @EventHandler
    public void changedWorld(PlayerChangedWorldEvent event) {
        cancel(event.getPlayer(), false);
    }

    @Override
    public void close() {
        closing = true;
        timer.shutdownNow();
        sessions.values().forEach(this::restoreCached);
        sessions.clear();
        renderer.shutdownNow();
    }
}
