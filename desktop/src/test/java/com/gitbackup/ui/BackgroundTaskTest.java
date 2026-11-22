package com.gitbackup.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BackgroundTaskTest {
    @Test
    void anErrorReachesTheHandlerInsteadOfEndingTheTask() {
        OutOfMemoryError thrown = new OutOfMemoryError("Java heap space");
        AtomicReference<Throwable> seen = new AtomicReference<>();

        Runnable task = BackgroundTask.guarded(() -> {
            throw thrown;
        }, seen::set);

        assertDoesNotThrow(task::run);
        assertSame(thrown, seen.get());
    }

    @Test
    void workThatFinishesNeverCallsTheHandler() {
        AtomicBoolean ran = new AtomicBoolean();
        AtomicReference<Throwable> seen = new AtomicReference<>();

        BackgroundTask.guarded(() -> ran.set(true), seen::set).run();

        assertTrue(ran.get());
        assertNull(seen.get());
    }

    /** The case that left the window busy for good: a run dies on an Error on the backup thread. */
    @Test
    void aRunThatDiesOnAnErrorEndsTheBusyStateAndTheThreadTakesTheNextRun() throws InterruptedException {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            AtomicBoolean busy = new AtomicBoolean(true);
            AtomicReference<Throwable> seen = new AtomicReference<>();
            CountDownLatch failed = new CountDownLatch(1);
            worker.execute(BackgroundTask.guarded(() -> {
                throw new OutOfMemoryError("Java heap space");
            }, error -> {
                seen.set(error);
                busy.set(false);
                failed.countDown();
            }));
            assertTrue(failed.await(5, TimeUnit.SECONDS));
            assertFalse(busy.get());
            assertInstanceOf(OutOfMemoryError.class, seen.get());

            CountDownLatch nextRun = new CountDownLatch(1);
            worker.execute(BackgroundTask.guarded(nextRun::countDown, error -> { }));
            assertTrue(nextRun.await(5, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
        }
    }
}
