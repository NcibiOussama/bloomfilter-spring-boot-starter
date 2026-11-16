package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.RebuildSession;
import io.github.ncibioussama.bloomfilter.core.Rebuildable;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.util.StringUtils;

/**
 * Rebuilds filters from their {@link BloomFilterLoader}: on demand, on a per-filter cron schedule
 * ({@code bloom.filters.<name>.rebuild-cron}) and automatically for {@link ManagedBloomFilter.State#DEGRADED
 * degraded} filters.
 * <p>
 * A rebuild loads every existing key into a fresh filter while the current one keeps answering, then swaps
 * it in atomically (see {@link Rebuildable}). This removes deleted keys and repairs filters that missed writes.
 * With a shared backend, only one application instance rebuilds a filter at a time.
 */
public class BloomFilterRebuilder implements SmartLifecycle {

    private static final Log log = LogFactory.getLog(BloomFilterRebuilder.class);

    /** Outcome of a rebuild attempt. */
    public enum Outcome {
        /** The filter was rebuilt and swapped in. */
        COMPLETED,
        /** Another rebuild of this filter is running, on this or another instance. */
        ALREADY_RUNNING,
        /** No {@link BloomFilterLoader} bean exists for the filter. */
        NO_LOADER,
        /** The backend does not support rebuilds. */
        NOT_SUPPORTED,
        /** Loading or swapping failed; the filter kept its previous content. */
        FAILED
    }

    /**
     * Result of a rebuild attempt.
     *
     * @param filter         the filter name
     * @param outcome        what happened
     * @param durationMillis how long it took
     * @param message        human-readable detail
     */
    public record Result(String filter, Outcome outcome, long durationMillis, String message) {
    }

    private final BloomFilterRegistry registry;
    private final Map<String, BloomFilterLoader> loaders;
    private final BloomFilterProperties properties;
    private final Set<String> warnedWithoutLoader = ConcurrentHashMap.newKeySet();
    private volatile ThreadPoolTaskScheduler scheduler;

    /**
     * @param registry   the filters
     * @param loaders    one loader per filter
     * @param properties configuration
     * @throws IllegalStateException if the configuration is invalid
     */
    public BloomFilterRebuilder(BloomFilterRegistry registry, List<BloomFilterLoader> loaders,
                                BloomFilterProperties properties) {
        this.registry = registry;
        this.properties = properties;
        Map<String, BloomFilterLoader> byName = new LinkedHashMap<>();
        for (BloomFilterLoader loader : loaders) {
            if (byName.putIfAbsent(loader.filterName(), loader) != null) {
                throw new IllegalStateException("Two BloomFilterLoader beans target filter '"
                        + loader.filterName() + "'. Use one loader per filter.");
            }
        }
        this.loaders = Map.copyOf(byName);
        properties.getFilters().forEach((name, filter) -> {
            String cron = filter.getRebuildCron();
            if (!StringUtils.hasText(cron)) {
                return;
            }
            if (!CronExpression.isValidExpression(cron)) {
                throw new IllegalStateException("bloom.filters." + name + ".rebuild-cron '" + cron
                        + "' is not a valid cron expression. Use 6 fields, for example \"0 0 3 * * *\".");
            }
            if (!this.loaders.containsKey(name)) {
                throw new IllegalStateException("bloom.filters." + name + ".rebuild-cron is set, but there is no "
                        + "BloomFilterLoader bean for filter '" + name + "' to rebuild it from.");
            }
        });
        Duration timeout = properties.getRebuild().getTimeout();
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("bloom.rebuild.timeout must be positive");
        }
    }

    /**
     * Rebuilds a filter now, in the calling thread.
     *
     * @param name the filter name
     * @return the result
     * @throws IllegalArgumentException if no such filter is configured
     */
    public Result rebuild(String name) {
        ManagedBloomFilter filter = registry.managed(name);
        long start = System.nanoTime();
        BloomFilterLoader loader = loaders.get(name);
        if (loader == null) {
            return result(name, Outcome.NO_LOADER, start, "no BloomFilterLoader bean for this filter");
        }
        if (!(filter.delegate() instanceof Rebuildable rebuildable)) {
            return result(name, Outcome.NOT_SUPPORTED, start,
                    filter.backend() + " backend does not support rebuilds");
        }
        if (!filter.tryStartRebuild()) {
            return result(name, Outcome.ALREADY_RUNNING, start, "a rebuild is already running on this instance");
        }
        Result result = null;
        try {
            result = doRebuild(name, filter, rebuildable, loader, start);
            return result;
        } catch (RuntimeException e) {
            result = result(name, Outcome.FAILED, start, e.getMessage());
            return result;
        } finally {
            if (result == null) {
                filter.finishRebuild(false);
            } else if (result.outcome() == Outcome.ALREADY_RUNNING) {
                filter.cancelRebuild();
                log(result);
            } else {
                filter.finishRebuild(result.outcome() == Outcome.COMPLETED);
                log(result);
            }
        }
    }

    private Result doRebuild(String name, ManagedBloomFilter filter, Rebuildable rebuildable,
                             BloomFilterLoader loader, long start) {
        long errorsBefore = filter.backendErrors();
        RebuildSession session = rebuildable.beginRebuild(properties.getRebuild().getTimeout());
        if (session == null) {
            return result(name, Outcome.ALREADY_RUNNING, start, "another instance is rebuilding this filter");
        }
        try {
            loader.load(session.target());
        } catch (Exception e) {
            session.abort();
            return result(name, Outcome.FAILED, start, "loader failed: " + e.getMessage());
        }
        if (filter.backendErrors() > errorsBefore) {
            session.abort();
            return result(name, Outcome.FAILED, start, "backend errors during the rebuild; writes may be missing");
        }
        if (!session.commit()) {
            return result(name, Outcome.FAILED, start, "the rebuild took longer than bloom.rebuild.timeout or "
                    + "was cancelled; the previous content was kept");
        }
        filter.markRecovered();
        return result(name, Outcome.COMPLETED, start,
                "~" + filter.stats().approximateElementCount() + " keys");
    }

    /**
     * Rebuilds a filter in the background.
     *
     * @param name the filter name
     * @return the future result
     * @throws IllegalArgumentException if no such filter is configured
     */
    public CompletableFuture<Result> rebuildAsync(String name) {
        registry.managed(name); // fail fast on unknown names
        ThreadPoolTaskScheduler current = scheduler;
        if (current == null) {
            return CompletableFuture.completedFuture(rebuild(name));
        }
        return CompletableFuture.supplyAsync(() -> rebuild(name), current);
    }

    void recoverDegradedFilters() {
        for (ManagedBloomFilter filter : registry.managed()) {
            if (filter.state() != ManagedBloomFilter.State.DEGRADED || filter.isRebuilding()) {
                continue;
            }
            if (!loaders.containsKey(filter.name())) {
                if (warnedWithoutLoader.add(filter.name())) {
                    log.warn("Bloom filter '" + filter.name() + "' is degraded and has no BloomFilterLoader, so it "
                            + "cannot recover automatically. Restart the application to reset it.");
                }
                continue;
            }
            rebuild(filter.name());
        }
    }

    private static Result result(String name, Outcome outcome, long startNanos, String message) {
        return new Result(name, outcome, (System.nanoTime() - startNanos) / 1_000_000, message);
    }

    private static void log(Result result) {
        String text = String.format(Locale.ROOT, "Bloom filter '%s' rebuild %s in %d ms: %s", result.filter(),
                result.outcome().name().toLowerCase(Locale.ROOT), result.durationMillis(), result.message());
        switch (result.outcome()) {
            case COMPLETED -> log.info(text);
            case ALREADY_RUNNING -> log.debug(text);
            default -> log.warn(text);
        }
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Bloom filter rebuild task failed", e);
        }
    }

    @Override
    public void start() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(Math.max(1, Math.min(4, registry.names().size())));
        taskScheduler.setThreadNamePrefix("bloom-rebuild-");
        taskScheduler.setDaemon(true);
        taskScheduler.setWaitForTasksToCompleteOnShutdown(false);
        taskScheduler.initialize();
        scheduler = taskScheduler;

        BloomFilterProperties.Rebuild config = properties.getRebuild();
        if (!config.isEnabled()) {
            return;
        }
        properties.getFilters().forEach((name, filter) -> {
            if (StringUtils.hasText(filter.getRebuildCron())) {
                taskScheduler.schedule(() -> runSafely(() -> rebuild(name)), new CronTrigger(filter.getRebuildCron()));
                log.info("Bloom filter '" + name + "' rebuild scheduled: " + filter.getRebuildCron());
            }
        });
        Duration interval = config.getRecoveryInterval();
        if (interval != null && !interval.isZero() && !interval.isNegative()) {
            taskScheduler.scheduleWithFixedDelay(() -> runSafely(this::recoverDegradedFilters),
                    Instant.now().plus(interval), interval);
        }
    }

    @Override
    public void stop() {
        ThreadPoolTaskScheduler current = scheduler;
        scheduler = null;
        if (current != null) {
            current.shutdown();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
