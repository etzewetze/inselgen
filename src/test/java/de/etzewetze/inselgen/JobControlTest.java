package de.etzewetze.inselgen;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JobControlTest {

    @Test
    void progressCoversAllStagesAndOnlyCompletesAfterCommit() {
        var job = new JobControl();
        double previous = 0;
        for (var stage : new JobControl.Stage[] {
            JobControl.Stage.PLANNING,
            JobControl.Stage.PREFLIGHT,
            JobControl.Stage.PLACING,
            JobControl.Stage.COMMITTING,
        }) {
            job.begin(stage, stage.name(), 100);
            for (int i = 0; i <= 100; i++) {
                job.done = i;
                assertTrue(job.progress() >= previous);
                assertTrue(job.progress() < 1);
                previous = job.progress();
            }
        }
        job.complete();
        assertEquals(1, job.progress());
    }

    @Test
    void clampsCountersAndSupportsUndo() {
        var job = new JobControl();
        job.begin(JobControl.Stage.UNDO, "Undo", 0);
        assertEquals(0, job.progress());
        job.begin(JobControl.Stage.UNDO, "Undo", 10);
        job.done = -1;
        assertEquals(0, job.progress());
        job.done = 100;
        assertEquals(.99, job.progress());
        job.complete();
        assertEquals(1, job.progress());
    }

    @Test
    void undoRecognizesNaturalUpdatesForEveryLeafSpecies() {
        for (String type : new String[] {
            "oak",
            "jungle",
            "birch",
            "cherry",
            "mangrove",
            "spruce",
            "dark_oak",
            "acacia",
        }) {
            String prefix = "minecraft:" + type + "_leaves[distance=";
            assertTrue(
                NativeBackend.matchesGenerated(
                    prefix + "2,persistent=true,waterlogged=false]",
                    prefix + "7,persistent=true,waterlogged=false]"
                )
            );
            assertFalse(
                NativeBackend.matchesGenerated(
                    prefix + "2,persistent=true,waterlogged=true]",
                    prefix + "7,persistent=true,waterlogged=false]"
                )
            );
        }
    }
}
