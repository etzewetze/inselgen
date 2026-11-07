package de.etzewetze.inselgen;

import org.bukkit.entity.Player;

interface EditBackend {
    @FunctionalInterface interface Prepared { void apply(EditContext context, JobControl job) throws Exception; }
    Prepared prepare(Player player);
    String name();
    String undoCommand();
}
