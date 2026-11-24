package io.github.artemdbondarenko.crawdad.service;

import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

/** Schedulers for tests that drive a {@link CrawdadServer.Endpoint} by hand. */
final class Schedulers {

    private static final Scheduler IDLE = started();

    private Schedulers() {}

    /**
     * A running scheduler shared by such tests; with {@link CrawdadServer.Heartbeat#DEFAULT} nothing it is
     * given runs before the test ends.
     */
    static Scheduler idle() {
        return IDLE;
    }

    private static Scheduler started() {
        ScheduledExecutorScheduler scheduler = new ScheduledExecutorScheduler("test-scheduler", true);
        try {
            scheduler.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return scheduler;
    }
}
