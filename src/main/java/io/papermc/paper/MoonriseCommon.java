package io.papermc.paper;

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedThreadPool;
import org.goldenforge.config.GoldenForgeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoonriseCommon {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoonriseCommon.class);

    // Paper start
    public static PrioritisedThreadPool WORKER_POOL;
    public static int WORKER_THREADS;
    public static void init() {
        // Paper end
        int defaultWorkerThreads = Runtime.getRuntime().availableProcessors() / 2;
        if (defaultWorkerThreads <= 4) {
            defaultWorkerThreads = defaultWorkerThreads <= 3 ? 1 : 2;
        } else {
            defaultWorkerThreads = defaultWorkerThreads / 2;
        }
        defaultWorkerThreads = Integer.getInteger("Paper.WorkerThreadCount", Integer.valueOf(defaultWorkerThreads)); // Paper

        int workerThreads = GoldenForgeConfig.Server.workerThreads.get();

        if (workerThreads <= 0) {
            workerThreads = defaultWorkerThreads;
        }

        WORKER_POOL = new PrioritisedThreadPool(
                "Paper Worker Pool", workerThreads, // Paper
                (final Thread thread, final Integer id) -> {
                    thread.setName("Paper Common Worker #" + id.intValue()); // Paper
                    thread.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                        @Override
                        public void uncaughtException(final Thread thread, final Throwable throwable) {
                            LOGGER.error("Uncaught exception in thread " + thread.getName(), throwable);
                        }
                    });
                }, (long)(20.0e6)); // 20ms
        WORKER_THREADS = workerThreads;
    }

    private MoonriseCommon() {}
}
