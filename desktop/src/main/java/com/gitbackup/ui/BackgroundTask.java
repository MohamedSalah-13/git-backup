package com.gitbackup.ui;

import java.util.function.Consumer;

/**
 * Work for a background thread whose failures are never lost. An executor keeps what a task
 * throws to itself, so a task that dies on an {@link Error} (out of memory on a large
 * repository, say) would skip the code that ends the busy state: the window would stay "busy"
 * and the schedule, which waits for it, would never start another backup.
 */
final class BackgroundTask {
    private BackgroundTask() {
    }

    /** {@code work}, with anything it throws, {@link Error}s included, handed to {@code onFailure}. */
    static Runnable guarded(Runnable work, Consumer<Throwable> onFailure) {
        return () -> {
            try {
                work.run();
            } catch (Throwable error) {
                onFailure.accept(error);
            }
        };
    }
}
