package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilterUnavailableException;
import java.util.List;
import java.util.Locale;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/**
 * Runs {@link BloomFilterLoader} beans at startup. Spring Boot reports the application as ready only after
 * all runners complete, so Kubernetes readiness probes wait for the filters to be loaded.
 * <p>
 * In {@link BloomFilterProperties.WarmUpMode#IF_EMPTY if-empty} mode, loading is skipped only when a
 * previous load <em>completed</em> ({@link io.github.ncibioussama.bloomfilter.core.BloomFilter#isPopulated()}),
 * not merely when the filter is non-empty: another instance may still be loading a shared filter.
 */
public class BloomFilterWarmUpRunner implements ApplicationRunner, Ordered {

    private static final Log log = LogFactory.getLog(BloomFilterWarmUpRunner.class);

    private final BloomFilterRegistry registry;
    private final List<BloomFilterLoader> loaders;
    private final BloomFilterProperties.WarmUpMode mode;

    /**
     * @param registry the registry
     * @param loaders  loaders to run, in order
     * @param mode     when to run a loader
     */
    public BloomFilterWarmUpRunner(BloomFilterRegistry registry, List<BloomFilterLoader> loaders,
                                   BloomFilterProperties.WarmUpMode mode) {
        this.registry = registry;
        this.loaders = loaders;
        this.mode = mode;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (BloomFilterLoader loader : loaders) {
            load(loader);
        }
    }

    private void load(BloomFilterLoader loader) {
        ManagedBloomFilter filter = registry.managed(loader.filterName());
        try {
            if (mode == BloomFilterProperties.WarmUpMode.IF_EMPTY && filter.isPopulated()) {
                log.info("Bloom filter '" + filter.name() + "' already populated, skipping warm-up");
                filter.markReady();
                return;
            }
        } catch (BloomFilterUnavailableException e) {
            filter.markDegraded("its backend was unavailable during warm-up");
            return;
        }

        long start = System.nanoTime();
        try {
            loader.load(filter);
        } catch (Exception e) {
            throw new IllegalStateException("Warm-up of Bloom filter '" + filter.name() + "' failed", e);
        }
        try {
            filter.markPopulated();
        } catch (BloomFilterUnavailableException e) {
            filter.markDegraded("its backend was unavailable during warm-up");
            return;
        }
        filter.markReady();
        log.info(String.format(Locale.ROOT, "Bloom filter '%s' warmed up in %d ms (%d keys)", filter.name(),
                (System.nanoTime() - start) / 1_000_000, filter.putCount()));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }
}
