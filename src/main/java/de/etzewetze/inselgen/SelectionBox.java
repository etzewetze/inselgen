package de.etzewetze.inselgen;

import java.util.UUID;

record SelectionBox(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    SelectionBox {
        if (
            minX > maxX ||
            minY > maxY ||
            minZ > maxZ ||
            (long) maxX - minX >= 500 ||
            (long) maxZ - minZ >= 500 ||
            (long) maxY - minY >= 1024 ||
            ((long) maxX - minX + 1) * ((long) maxY - minY + 1) * ((long) maxZ - minZ + 1) > SavedStructure.MAX_VOLUME
        ) throw new IllegalArgumentException(
            "Auswahl zu groß: maximal 500×1024×500 und 64 Millionen Blöcke einschließlich Luft."
        );
        if (
            minX < -29_999_984 || minZ < -29_999_984 || maxX >= 29_999_984 || maxZ >= 29_999_984
        ) throw new IllegalArgumentException("Auswahl liegt außerhalb der Weltgrenze.");
    }

    int width() {
        return maxX - minX + 1;
    }

    int height() {
        return maxY - minY + 1;
    }

    int depth() {
        return maxZ - minZ + 1;
    }

    long volume() {
        return (long) width() * height() * depth();
    }

    int index(int x, int y, int z) {
        return ((y - minY) * depth() + z - minZ) * width() + x - minX;
    }
}
