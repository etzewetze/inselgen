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
