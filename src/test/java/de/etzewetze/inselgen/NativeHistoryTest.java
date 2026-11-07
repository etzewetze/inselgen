package de.etzewetze.inselgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NativeHistoryTest {
    @TempDir Path root;

    @Test void restoresBlockAndBiomeRecordsAcrossReopeningAndRetainsOnlyTheOwnersPointer() throws Exception {
        var history = new NativeHistory(root);
        UUID owner = UUID.randomUUID(), world = UUID.randomUUID();
        Path directory = history.create(owner, world);
        List<NativeHistory.Change> changes = List.of(new NativeHistory.Change(false, -17, -30, 18, "minecraft:cave_air", "minecraft:stone"),
                new NativeHistory.Change(true, -20, -32, 16, "minecraft:plains", "minecraft:beach"));
        try (var writer = NativeHistory.writer(directory, -2, 1)) { writer.append(changes); }
        history.select(owner, directory);
        NativeHistory reopened = new NativeHistory(root);
        var entry = reopened.last(owner);
        assertEquals(world, entry.world());
        List<NativeHistory.Change> read = new ArrayList<>();
        try (var reader = NativeHistory.reader(directory.resolve("-2_1.gz"))) {
            read.addAll(reader.next(1)); read.addAll(reader.next(1)); assertTrue(reader.next(1).isEmpty());
        }
        assertEquals(changes, read);
        assertThrows(IllegalArgumentException.class, () -> reopened.last(UUID.randomUUID()));
        reopened.consumed(owner, entry);
        assertFalse(Files.exists(directory));
        assertThrows(IllegalArgumentException.class, () -> reopened.last(owner));
    }

    @Test void preservesPlayerChangesButAcceptsNaturalLeafDistanceUpdates() {
        String generated = "minecraft:oak_leaves[distance=7,persistent=true,waterlogged=false]";
        assertTrue(NativeBackend.matchesGenerated("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]", generated));
        assertFalse(NativeBackend.matchesGenerated("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=true]", generated));
        assertFalse(NativeBackend.matchesGenerated("minecraft:diamond_block", "minecraft:stone"));
    }

    @Test void selectingNewHistoryRemovesOnlyThatPlayersPreviousJournal() throws Exception {
        var history = new NativeHistory(root);
        UUID owner = UUID.randomUUID(), other = UUID.randomUUID(), world = UUID.randomUUID();
        Path first = history.create(owner, world), second = history.create(owner, world), foreign = history.create(other, world);
        history.select(owner, first); history.select(other, foreign); history.select(owner, second);
        assertFalse(Files.exists(first)); assertTrue(Files.exists(second)); assertTrue(Files.exists(foreign));
        assertEquals(second, history.last(owner).directory()); assertEquals(foreign, history.last(other).directory());
    }
}
