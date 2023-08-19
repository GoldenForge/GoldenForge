package dev.kaiijumc.kaiiju.region;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.goldenforge.GoldenForge;
import org.goldenforge.config.GoldenForgeConfig;

import java.util.Queue;
import java.util.concurrent.*;

public class LinearRegionFileFlusher {
    private final Queue<LinearRegionFile> savingQueue = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder()
            .setNameFormat("linear-flush-scheduler")
            .build()
    );
    private final ExecutorService executor = Executors.newFixedThreadPool(
        GoldenForgeConfig.Server.linearFlushThreads.get(),
        new ThreadFactoryBuilder()
            .setNameFormat("linear-flusher-%d")
            .build()
    );

    public LinearRegionFileFlusher() {
        GoldenForge.LOGGER.info("Using " +  GoldenForgeConfig.Server.linearFlushThreads.get() + " threads for linear region flushing.");
        scheduler.scheduleAtFixedRate(this::pollAndFlush, 0L,  GoldenForgeConfig.Server.linearFlushFrequency.get(), TimeUnit.SECONDS);
    }

    public void scheduleSave(LinearRegionFile regionFile) {
        if (savingQueue.contains(regionFile)) return;
        savingQueue.add(regionFile);
    }

    private void pollAndFlush() {
        while (!savingQueue.isEmpty()) {
            LinearRegionFile regionFile = savingQueue.poll();
            if (!regionFile.closed && regionFile.isMarkedToSave())
                executor.execute(regionFile::flushWrapper);
        }
    }

    public void shutdown() {
        executor.shutdown();
        scheduler.shutdown();
    }
}
