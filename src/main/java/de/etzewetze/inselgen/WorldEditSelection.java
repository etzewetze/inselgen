package de.etzewetze.inselgen;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.regions.CuboidRegion;
import org.bukkit.entity.Player;

/** Linked only while WorldEdit/FAWE is enabled on a compatible server. */
final class WorldEditSelection {

    static SelectionBox get(Player player) {
        try {
            var actor = BukkitAdapter.adapt(player);
            var region = WorldEdit.getInstance()
                .getSessionManager()
                .get(actor)
                .getSelection(BukkitAdapter.adapt(player.getWorld()));
            if (!(region instanceof CuboidRegion)) throw new IllegalArgumentException(
                "Bitte eine Quaderauswahl verwenden: //sel cuboid."
            );
            var min = region.getMinimumPoint();
            var max = region.getMaximumPoint();
            return new SelectionBox(player.getWorld().getUID(), min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        } catch (com.sk89q.worldedit.IncompleteRegionException error) {
            throw new IllegalArgumentException(
                "WorldEdit-Auswahl unvollständig. //pos1 und //pos2 oder /inselgen pos1 und pos2 setzen."
            );
        }
    }
}
