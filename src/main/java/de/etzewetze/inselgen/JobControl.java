package de.etzewetze.inselgen;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

final class JobControl {
    final AtomicBoolean cancelled = new AtomicBoolean();
    volatile String phase = "Insel berechnen";
    volatile long done, total, changed, skipped;
    enum Stage {
        PLANNING(0, .10), EXPORT(0, .90), LOADING(0, .90), PREFLIGHT(.10, .40), PLACING(.40, .95), COMMITTING(.95, .99), UNDO(0, .99), COMPLETE(1, 1);
        final double start, end;
        Stage(double start, double end) { this.start = start; this.end = end; }
    }
    private volatile Stage stage = Stage.PLANNING;

    synchronized void begin(Stage next, String label, long total) {
        this.done = 0;
        this.total = total;
        this.phase = label;
        this.stage = next;
    }

    void planning(double fraction) { done = Math.round(Math.clamp(fraction, 0, 1) * 1000); total = 1000; }

    synchronized double progress() {
        double fraction = total > 0 ? Math.clamp((double) done / total, 0, 1) : 0;
        return stage.start + (stage.end - stage.start) * fraction;
    }

    void complete() { begin(Stage.COMPLETE, "Fertig", 1); done = 1; }

    void check() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException("Abgebrochen");
    }

    <T> T await(CompletableFuture<T> future) throws Exception {
        while (true) {
            try { return future.get(100, TimeUnit.MILLISECONDS); }
            // An already running region batch must return its undo records even if cancelled.
            catch (TimeoutException ignored) { }
            catch (ExecutionException error) {
                if (error.getCause() instanceof Exception exception) throw exception;
                throw new IllegalStateException(error.getCause());
            }
        }
    }
}
