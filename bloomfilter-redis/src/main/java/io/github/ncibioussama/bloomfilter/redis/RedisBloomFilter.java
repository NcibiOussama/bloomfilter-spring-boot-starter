package io.github.ncibioussama.bloomfilter.redis;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.BloomFilterStats;
import io.github.ncibioussama.bloomfilter.core.BloomFilterUnavailableException;
import io.github.ncibioussama.bloomfilter.core.FailureMode;
import io.github.ncibioussama.bloomfilter.core.HashStrategy;
import io.github.ncibioussama.bloomfilter.core.KeyEncoder;
import io.github.ncibioussama.bloomfilter.core.KeyTooLargeException;
import io.github.ncibioussama.bloomfilter.core.RebuildSession;
import io.github.ncibioussama.bloomfilter.core.Rebuildable;
import io.github.ncibioussama.bloomfilter.core.Suspendable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Bloom filter stored as a Redis bitmap and shared by every application instance.
 * <p>
 * All bits of a key are read or written by a single Lua script, so each operation is one atomic
 * round-trip instead of {@code k}.
 * <p>
 * Redis keys, all in the same Redis Cluster hash slot thanks to the {@code {...}} hash tag:
 * <ul>
 *     <li>{@code <prefix>{<name>:<fingerprint>}}: the bitmap</li>
 *     <li>{@code ...:ready}: set once a complete load or rebuild finished</li>
 *     <li>{@code ...:next}: the bitmap being rebuilt</li>
 *     <li>{@code ...:rebuild}: rebuild lock and dual-write signal, with an expiry</li>
 *     <li>{@code ...:suspended}: present while the filter is {@link Suspendable suspended} on every instance</li>
 * </ul>
 * The fingerprint covers the sizing and hash configuration, so changing size, algorithm or secret
 * switches to a fresh bitmap instead of reading incompatible bits (which would cause false negatives).
 */
public final class RedisBloomFilter implements BloomFilter, Rebuildable, Suspendable {

    private static final Log log = LogFactory.getLog(RedisBloomFilter.class);
    private static final long WARN_INTERVAL_MILLIS = 30_000;

    /** KEYS: main, rebuild marker, next. ARGV: bit indexes. Dual-writes into next while a rebuild runs. */
    private static final RedisScript<Long> PUT_SCRIPT = new DefaultRedisScript<>("""
            local dual = redis.call('EXISTS', KEYS[2]) == 1
            local added = 0
            for i = 1, #ARGV do
              if redis.call('SETBIT', KEYS[1], ARGV[i], 1) == 0 then added = added + 1 end
              if dual then redis.call('SETBIT', KEYS[3], ARGV[i], 1) end
            end
            return added
            """, Long.class);

    /** KEYS: main, rebuild marker, next. ARGV[1]: k, then k indexes per key. Returns number of new keys. */
    private static final RedisScript<Long> PUT_ALL_SCRIPT = new DefaultRedisScript<>("""
            local dual = redis.call('EXISTS', KEYS[2]) == 1
            local k = tonumber(ARGV[1])
            local newKeys = 0
            local i = 2
            while i <= #ARGV do
              local changed = false
              for j = 0, k - 1 do
                if redis.call('SETBIT', KEYS[1], ARGV[i + j], 1) == 0 then changed = true end
                if dual then redis.call('SETBIT', KEYS[3], ARGV[i + j], 1) end
              end
              if changed then newKeys = newKeys + 1 end
              i = i + k
            end
            return newKeys
            """, Long.class);

    /** KEYS: bitmap. ARGV[1]: k, then k indexes per key. Plain bulk write, used for the rebuild target. */
    private static final RedisScript<Long> TARGET_PUT_ALL_SCRIPT = new DefaultRedisScript<>("""
            local k = tonumber(ARGV[1])
            local newKeys = 0
            local i = 2
            while i <= #ARGV do
              local changed = false
              for j = 0, k - 1 do
                if redis.call('SETBIT', KEYS[1], ARGV[i + j], 1) == 0 then changed = true end
              end
              if changed then newKeys = newKeys + 1 end
              i = i + k
            end
            return newKeys
            """, Long.class);

    /** KEYS: bitmap, optional suspension marker. ARGV: bit indexes. A suspended filter contains everything. */
    private static final RedisScript<Long> CONTAINS_SCRIPT = new DefaultRedisScript<>("""
            if KEYS[2] and redis.call('EXISTS', KEYS[2]) == 1 then return 1 end
            for i = 1, #ARGV do
              if redis.call('GETBIT', KEYS[1], ARGV[i]) == 0 then return 0 end
            end
            return 1
            """, Long.class);

    /** KEYS: rebuild marker, next. ARGV: token, ttl millis. Acquires the lock and clears a stale target. */
    private static final RedisScript<Long> BEGIN_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
              redis.call('DEL', KEYS[2])
              return 1
            end
            return 0
            """, Long.class);

    /** KEYS: main, next, rebuild marker, ready. ARGV: token. Swaps only while the lock is still ours. */
    private static final RedisScript<Long> COMMIT_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[3]) ~= ARGV[1] then return 0 end
            if redis.call('EXISTS', KEYS[2]) == 1 then
              redis.call('RENAME', KEYS[2], KEYS[1])
            else
              redis.call('DEL', KEYS[1])
            end
            redis.call('SET', KEYS[4], '1')
            redis.call('DEL', KEYS[3])
            return 1
            """, Long.class);

    /** KEYS: suspension marker. ARGV: token. Ends the suspension only if it is still this one. */
    private static final RedisScript<Long> RESUME_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);

    /** KEYS: rebuild marker, next. ARGV: token. */
    private static final RedisScript<Long> ABORT_REBUILD_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1], KEYS[2])
              return 1
            end
            return 0
            """, Long.class);

    /** Redis bitmaps are limited to 2^32 bits (512 MiB). */
    public static final long MAX_BITS = 1L << 32;

    private final BloomFilterSpec spec;
    private final HashStrategy hashStrategy;
    private final KeyEncoder keyEncoder;
    private final StringRedisTemplate redis;
    private final FailureMode failureMode;
    private final int batchSize;
    private final String redisKey;
    private final String readyKey;
    private final String nextKey;
    private final String rebuildKey;
    private final String suspendedKey;
    private final LongAdder errors = new LongAdder();
    private final AtomicLong lastWarn = new AtomicLong();

    /**
     * @param spec         the sizing, at most {@link #MAX_BITS} bits
     * @param hashStrategy hash strategy; must be identical on every instance sharing the filter
     * @param keyEncoder   key encoder; must be identical on every instance sharing the filter
     * @param redis        template connected to Redis
     * @param keyPrefix    prefix for the Redis keys, for example {@code "bloom:myapp:"}
     * @param failureMode  behaviour when Redis is unavailable
     * @param batchSize    number of keys per Lua call in {@link #putAll(Iterable)}
     */
    public RedisBloomFilter(BloomFilterSpec spec, HashStrategy hashStrategy, KeyEncoder keyEncoder,
                            StringRedisTemplate redis, String keyPrefix, FailureMode failureMode, int batchSize) {
        if (spec == null || hashStrategy == null || keyEncoder == null || redis == null || failureMode == null) {
            throw new IllegalArgumentException("spec, hashStrategy, keyEncoder, redis and failureMode are required");
        }
        if (spec.bitSize() > MAX_BITS) {
            throw new IllegalArgumentException("Bloom filter '" + spec.name() + "' has " + spec.bitSize()
                    + " bits, but Redis bitmaps support at most " + MAX_BITS);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.spec = spec;
        this.hashStrategy = hashStrategy;
        this.keyEncoder = keyEncoder;
        this.redis = redis;
        this.failureMode = failureMode;
        this.batchSize = batchSize;
        this.redisKey = (keyPrefix == null ? "" : keyPrefix)
                + "{" + spec.name() + ":" + fingerprint(spec, hashStrategy) + "}";
        this.readyKey = redisKey + ":ready";
        this.nextKey = redisKey + ":next";
        this.rebuildKey = redisKey + ":rebuild";
        this.suspendedKey = redisKey + ":suspended";
    }

    /**
     * @return the Redis key holding the bitmap
     */
    public String redisKey() {
        return redisKey;
    }

    @Override
    public boolean mightContain(Object key) {
        List<String> args = indexArgs(key, new ArrayList<>(spec.hashFunctions()));
        if (args == null) {
            return true; // oversized keys are never stored: "might contain" sends the caller to the source of truth
        }
        try {
            Long result = redis.execute(CONTAINS_SCRIPT, List.of(redisKey, suspendedKey), args.toArray());
            return result == null || result != 0;
        } catch (DataAccessException e) {
            onError("mightContain", e);
            return true; // fail open: "maybe present" sends the caller to the source of truth
        }
    }

    @Override
    public boolean put(Object key) {
        List<String> args = indexArgs(key, new ArrayList<>(spec.hashFunctions()));
        if (args == null) {
            return false; // oversized keys are not stored; checks for them always answer "might contain"
        }
        try {
            Long added = redis.execute(PUT_SCRIPT, List.of(redisKey, rebuildKey, nextKey), args.toArray());
            return added != null && added > 0;
        } catch (DataAccessException e) {
            onError("put", e);
            return false;
        }
    }

    @Override
    public long putAll(Iterable<?> keys) {
        try {
            return batchPut(keys, PUT_ALL_SCRIPT, List.of(redisKey, rebuildKey, nextKey));
        } catch (DataAccessException e) {
            onError("putAll", e);
            return 0;
        }
    }

    private long batchPut(Iterable<?> keys, RedisScript<Long> script, List<String> redisKeys) {
        long newKeys = 0;
        List<String> args = new ArrayList<>(1 + batchSize * spec.hashFunctions());
        args.add(Integer.toString(spec.hashFunctions()));
        int inBatch = 0;
        for (Object key : keys) {
            if (indexArgs(key, args) == null) {
                continue; // oversized key: skipped, see KeyTooLargeException
            }
            if (++inBatch == batchSize) {
                newKeys += executeLong(script, redisKeys, args);
                args.subList(1, args.size()).clear();
                inBatch = 0;
            }
        }
        if (inBatch > 0) {
            newKeys += executeLong(script, redisKeys, args);
        }
        return newKeys;
    }

    private long executeLong(RedisScript<Long> script, List<String> redisKeys, List<String> args) {
        Long result = redis.execute(script, redisKeys, args.toArray());
        return result == null ? 0 : result;
    }

    @Override
    public BloomFilterSpec spec() {
        return spec;
    }

    @Override
    public BloomFilterStats stats() {
        return statsOf(redisKey);
    }

    private BloomFilterStats statsOf(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        try {
            Long bits = redis.execute((RedisCallback<Long>) connection ->
                    connection.stringCommands().bitCount(keyBytes));
            return new BloomFilterStats(spec, bits == null ? 0 : Math.min(bits, spec.bitSize()));
        } catch (DataAccessException e) {
            throw unavailable("stats", e);
        }
    }

    @Override
    public boolean isEmpty() {
        try {
            return !Boolean.TRUE.equals(redis.hasKey(redisKey));
        } catch (DataAccessException e) {
            throw unavailable("isEmpty", e);
        }
    }

    /**
     * {@inheritDoc}
     * Uses a separate marker, because another instance may still be loading a non-empty bitmap.
     */
    @Override
    public boolean isPopulated() {
        try {
            return Boolean.TRUE.equals(redis.hasKey(readyKey));
        } catch (DataAccessException e) {
            throw unavailable("isPopulated", e);
        }
    }

    @Override
    public void markPopulated() {
        try {
            redis.opsForValue().set(readyKey, "1");
        } catch (DataAccessException e) {
            throw unavailable("markPopulated", e);
        }
    }

    @Override
    public void clear() {
        try {
            redis.delete(List.of(redisKey, readyKey));
        } catch (DataAccessException e) {
            throw unavailable("clear", e);
        }
    }

    @Override
    public long backendErrors() {
        return errors.sum();
    }

    /**
     * {@inheritDoc}
     * The marker is stored in Redis without expiry, so every instance bypasses the filter until
     * {@link #resume(String)}. Never fails open: an unrecorded suspension would not protect anything.
     */
    @Override
    public String suspend() {
        String token = UUID.randomUUID().toString();
        try {
            redis.opsForValue().set(suspendedKey, token);
            return token;
        } catch (DataAccessException e) {
            throw unavailable("suspend", e);
        }
    }

    @Override
    public String suspension() {
        try {
            return redis.opsForValue().get(suspendedKey);
        } catch (DataAccessException e) {
            throw unavailable("suspension", e);
        }
    }

    @Override
    public boolean resume(String token) {
        if (token == null) {
            return false;
        }
        try {
            Long resumed = redis.execute(RESUME_SCRIPT, List.of(suspendedKey), token);
            return resumed != null && resumed == 1;
        } catch (DataAccessException e) {
            throw unavailable("resume", e);
        }
    }

    @Override
    public RebuildSession beginRebuild(Duration maxDuration) {
        if (maxDuration == null || maxDuration.toMillis() < 1) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
        String token = UUID.randomUUID().toString();
        try {
            Long acquired = redis.execute(BEGIN_REBUILD_SCRIPT, List.of(rebuildKey, nextKey),
                    token, Long.toString(maxDuration.toMillis()));
            return acquired != null && acquired == 1 ? new Session(token) : null;
        } catch (DataAccessException e) {
            throw unavailable("beginRebuild", e);
        }
    }

    /** Appends the key's bit indexes, or returns {@code null} (appending nothing) for an oversized key. */
    private List<String> indexArgs(Object key, List<String> target) {
        byte[] encoded;
        try {
            encoded = keyEncoder.encode(key);
        } catch (KeyTooLargeException e) {
            return null;
        }
        for (long index : hashStrategy.indexes(encoded, spec.hashFunctions(), spec.bitSize())) {
            target.add(Long.toString(index));
        }
        return target;
    }

    private BloomFilterUnavailableException unavailable(String operation, DataAccessException e) {
        errors.increment();
        return new BloomFilterUnavailableException(
                "Redis unavailable for Bloom filter '" + spec.name() + "' during " + operation, e);
    }

    private void onError(String operation, DataAccessException e) {
        errors.increment();
        if (failureMode == FailureMode.THROW) {
            throw new BloomFilterUnavailableException(
                    "Redis unavailable for Bloom filter '" + spec.name() + "' during " + operation, e);
        }
        long now = System.currentTimeMillis();
        long last = lastWarn.get();
        if (now - last >= WARN_INTERVAL_MILLIS && lastWarn.compareAndSet(last, now)) {
            log.warn("Redis unavailable for Bloom filter '" + spec.name() + "' (" + operation
                    + "); failing open. " + errors.sum() + " errors so far. Cause: " + e.getMessage());
        }
    }

    static String fingerprint(BloomFilterSpec spec, HashStrategy hashStrategy) {
        String material = spec.bitSize() + "|" + spec.hashFunctions() + "|" + hashStrategy.fingerprint();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return "RedisBloomFilter[" + spec.name() + " -> " + redisKey + "]";
    }

    private final class Session implements RebuildSession {

        private final String token;
        private final BloomFilter target = new Target();

        Session(String token) {
            this.token = token;
        }

        @Override
        public BloomFilter target() {
            return target;
        }

        @Override
        public boolean commit() {
            try {
                Long swapped = redis.execute(COMMIT_REBUILD_SCRIPT,
                        List.of(redisKey, nextKey, rebuildKey, readyKey), token);
                return swapped != null && swapped == 1;
            } catch (DataAccessException e) {
                throw unavailable("commitRebuild", e);
            }
        }

        @Override
        public void abort() {
            try {
                redis.execute(ABORT_REBUILD_SCRIPT, List.of(rebuildKey, nextKey), token);
            } catch (DataAccessException e) {
                // the lock expires on its own
                errors.increment();
            }
        }
    }

    /** Write view of the bitmap being rebuilt. Never fails open: an incomplete target must abort. */
    private final class Target implements BloomFilter {

        @Override
        public boolean mightContain(Object key) {
            List<String> args = indexArgs(key, new ArrayList<>(spec.hashFunctions()));
            if (args == null) {
                return true;
            }
            try {
                Long result = redis.execute(CONTAINS_SCRIPT, List.of(nextKey), args.toArray());
                return result == null || result != 0;
            } catch (DataAccessException e) {
                throw unavailable("rebuild mightContain", e);
            }
        }

        @Override
        public boolean put(Object key) {
            return putAll(List.of(key)) > 0;
        }

        @Override
        public long putAll(Iterable<?> keys) {
            try {
                return batchPut(keys, TARGET_PUT_ALL_SCRIPT, List.of(nextKey));
            } catch (DataAccessException e) {
                throw unavailable("rebuild putAll", e);
            }
        }

        @Override
        public BloomFilterSpec spec() {
            return spec;
        }

        @Override
        public BloomFilterStats stats() {
            return statsOf(nextKey);
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("A rebuild target cannot be cleared; abort the rebuild instead");
        }
    }
}
