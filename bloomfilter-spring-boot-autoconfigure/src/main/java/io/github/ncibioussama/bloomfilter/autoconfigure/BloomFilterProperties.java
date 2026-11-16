package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.DefaultKeyEncoder;
import io.github.ncibioussama.bloomfilter.core.FailureMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration under the {@code bloom.*} prefix.
 *
 * <pre>
 * bloom:
 *   filters:
 *     users:
 *       expected-insertions: 1000000
 *       false-positive-rate: 0.01
 *       backend: redis
 * </pre>
 */
@ConfigurationProperties(prefix = "bloom")
public class BloomFilterProperties {

    /**
     * Whether to enable Bloom filter auto-configuration.
     */
    private boolean enabled = true;

    /**
     * Backend used by filters that do not set their own.
     */
    private Backend defaultBackend = Backend.MEMORY;

    /**
     * Maximum size of an encoded key in bytes. Larger keys are rejected.
     */
    private int maxKeyBytes = DefaultKeyEncoder.DEFAULT_MAX_KEY_BYTES;

    /**
     * Refuse to create a filter needing more bits than this. Protects against misconfiguration.
     * Defaults to 2^32 bits (512 MiB), which is also the Redis bitmap limit.
     */
    private long maxBitsPerFilter = BloomFilterSpec.DEFAULT_MAX_BITS;

    /**
     * Filters by name. Names may contain letters, digits, '.', '_' and '-'.
     */
    private Map<String, Filter> filters = new LinkedHashMap<>();

    private final Security security = new Security();

    private final Redis redis = new Redis();

    private final WarmUp warmUp = new WarmUp();

    private final Rebuild rebuild = new Rebuild();

    public Rebuild getRebuild() {
        return rebuild;
    }

    /**
     * Rebuilds: replacing a filter's content with a fresh load from its BloomFilterLoader.
     */
    public static class Rebuild {

        /**
         * Whether to run scheduled rebuilds (per-filter 'rebuild-cron') and automatic recovery.
         */
        private boolean enabled = true;

        /**
         * How often to retry rebuilding degraded filters (lost writes, failed warm-up). Zero disables
         * automatic recovery.
         */
        private Duration recoveryInterval = Duration.ofMinutes(1);

        /**
         * Maximum duration of one rebuild. For shared backends this is the lock lifetime; a rebuild that takes
         * longer is cancelled instead of risking lost writes.
         */
        private Duration timeout = Duration.ofHours(1);

        /**
         * Whether POST /actuator/bloomfilters/{name} may trigger a rebuild. A rebuild reloads every key from
         * the source of truth, so only enable this behind actuator security.
         */
        private boolean endpointEnabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getRecoveryInterval() {
            return recoveryInterval;
        }

        public void setRecoveryInterval(Duration recoveryInterval) {
            this.recoveryInterval = recoveryInterval;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public boolean isEndpointEnabled() {
            return endpointEnabled;
        }

        public void setEndpointEnabled(boolean endpointEnabled) {
            this.endpointEnabled = endpointEnabled;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Backend getDefaultBackend() {
        return defaultBackend;
    }

    public void setDefaultBackend(Backend defaultBackend) {
        this.defaultBackend = defaultBackend;
    }

    public int getMaxKeyBytes() {
        return maxKeyBytes;
    }

    public void setMaxKeyBytes(int maxKeyBytes) {
        this.maxKeyBytes = maxKeyBytes;
    }

    public long getMaxBitsPerFilter() {
        return maxBitsPerFilter;
    }

    public void setMaxBitsPerFilter(long maxBitsPerFilter) {
        this.maxBitsPerFilter = maxBitsPerFilter;
    }

    public Map<String, Filter> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, Filter> filters) {
        this.filters = filters;
    }

    public Security getSecurity() {
        return security;
    }

    public Redis getRedis() {
        return redis;
    }

    public WarmUp getWarmUp() {
        return warmUp;
    }

    /**
     * Storage backend of a filter.
     */
    public enum Backend {
        /** Local to each JVM, lost on restart. Fastest. */
        MEMORY,
        /** Shared by all instances through a Redis bitmap. Requires spring-boot-starter-data-redis. */
        REDIS
    }

    /**
     * Hash algorithm selection.
     */
    public enum HashAlgorithm {
        /**
         * SipHash when a secret is set or the backend is in-memory (random key per JVM),
         * otherwise Murmur3 with a startup warning.
         */
        AUTO,
        /** Keyed SipHash-2-4. Resists hash-flooding attacks. Shared backends need a secret. */
        SIPHASH,
        /** MurmurHash3. Fast but predictable; only for keys an attacker cannot choose. */
        MURMUR3
    }

    /**
     * Configuration of one filter.
     */
    public static class Filter {

        /**
         * Number of keys the filter is sized for. Adding more raises the false-positive rate.
         */
        private long expectedInsertions = 1_000_000;

        /**
         * Target false-positive probability, for example 0.01 for 1%.
         */
        private double falsePositiveRate = 0.01;

        /**
         * Backend of this filter. Defaults to 'bloom.default-backend'.
         */
        private Backend backend;

        /**
         * Behaviour when a remote backend is unavailable.
         */
        private FailureMode failureMode = FailureMode.FAIL_OPEN;

        /**
         * Cron expression (Spring format, 6 fields) for scheduled rebuilds from the filter's
         * BloomFilterLoader, for example "0 0 3 * * *" for every night at 3:00. Rebuilding removes deleted
         * keys. Requires a BloomFilterLoader for this filter.
         */
        private String rebuildCron;

        public String getRebuildCron() {
            return rebuildCron;
        }

        public void setRebuildCron(String rebuildCron) {
            this.rebuildCron = rebuildCron;
        }

        public long getExpectedInsertions() {
            return expectedInsertions;
        }

        public void setExpectedInsertions(long expectedInsertions) {
            this.expectedInsertions = expectedInsertions;
        }

        public double getFalsePositiveRate() {
            return falsePositiveRate;
        }

        public void setFalsePositiveRate(double falsePositiveRate) {
            this.falsePositiveRate = falsePositiveRate;
        }

        public Backend getBackend() {
            return backend;
        }

        public void setBackend(Backend backend) {
            this.backend = backend;
        }

        public FailureMode getFailureMode() {
            return failureMode;
        }

        public void setFailureMode(FailureMode failureMode) {
            this.failureMode = failureMode;
        }
    }

    /**
     * Hashing security.
     */
    public static class Security {

        /**
         * Hash algorithm.
         */
        private HashAlgorithm hashAlgorithm = HashAlgorithm.AUTO;

        /**
         * Secret key for SipHash, at least 16 characters. Must be identical on every instance sharing a
         * filter. Inject it from an environment variable or a secret store, never commit it.
         */
        private String secret;

        public HashAlgorithm getHashAlgorithm() {
            return hashAlgorithm;
        }

        public void setHashAlgorithm(HashAlgorithm hashAlgorithm) {
            this.hashAlgorithm = hashAlgorithm;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }
    }

    /**
     * Redis backend settings.
     */
    public static class Redis {

        /**
         * Prefix of Redis keys. Use a per-application prefix when several applications share a Redis.
         */
        private String keyPrefix = "bloom:";

        /**
         * Number of keys sent per Redis call by bulk inserts.
         */
        private int batchSize = 500;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    /**
     * Startup loading through BloomFilterLoader beans.
     */
    public static class WarmUp {

        /**
         * Whether to run BloomFilterLoader beans at startup.
         */
        private boolean enabled = true;

        /**
         * When to run a loader.
         */
        private WarmUpMode mode = WarmUpMode.IF_EMPTY;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public WarmUpMode getMode() {
            return mode;
        }

        public void setMode(WarmUpMode mode) {
            this.mode = mode;
        }
    }

    /**
     * Warm-up mode.
     */
    public enum WarmUpMode {
        /** Load only if the filter is empty. Avoids reloading a shared Redis filter on every instance. */
        IF_EMPTY,
        /** Always load at startup. */
        ALWAYS
    }
}
