package de.etzewetze.inselgen;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;

/** Accessed on the owner's entity scheduler; synchronized only for plugin shutdown. */
final class ProgressDisplay implements AutoCloseable {

    private final BossBar bar;
    private boolean closed;

    ProgressDisplay(Player player) {
        bar = Bukkit.createBossBar("InselGen · Insel berechnen · 0 %", BarColor.BLUE, BarStyle.SOLID);
        bar.setProgress(0);
        bar.addPlayer(player);
    }

    synchronized void update(JobControl job) {
        if (closed) return;
        double progress = job.progress();
        bar.setTitle("InselGen · " + job.phase + " · " + (int) Math.floor(progress * 100) + " %");
        bar.setProgress(progress);
        if (progress == 1) bar.setColor(BarColor.GREEN);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        bar.removeAll();
    }
}
