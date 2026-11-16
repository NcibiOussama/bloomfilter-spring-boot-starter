package io.github.ncibioussama.bloomfilter.redis;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.BloomFilterStats;
import io.github.ncibioussama.bloomfilter.core.BloomFilterUnavailableException;
import io.github.ncibioussama.bloomfilter.core.FailureMode;
import io.github.ncibioussama.bloomfilter.core.HashStrategy;
import io.github.ncibioussama.bloomfilter.core.KeyEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
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
 * The Redis key contains a fingerprint of the sizing and hash configuration
 * ({@code <prefix><name>:<fingerprint>}). Changing the size, the algorithm or the secret therefore
 * switches to a fresh, empty bitmap instead of silently reading incompatible bits (which would cause
 * false negatives). The warm-up loader then repopulates it.
 */
public final class RedisBloomFilter implements BloomFilter {

    private static final Log log = LogFactory.getLog(RedisBloomFilter.class);
    private static final long WARN_INTERVAL_MILLIS = 30_000;

    private static final RedisScript<Long> PUT_SCRIPT = new DefaultRedisScript<>("""
            local added = 0
            for i = 1, #ARGV do
              if redis.call('SETBIT', KEYS[1], ARGV[i], 1) == 0 then added = added + 1 end
            end
            return added
            """, Long.class);

    private static final RedisScript<Long> PUT_ALL_SCRIPT = new DefaultRedisScript<>("""
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

    private static final RedisScript<Long> CONTAINS_SCRIPT = new DefaultRedisScript<>("""
            for i = 1, #ARGV do
              if redis.call('GETBIT', KEYS[1], ARGV[i]) == 0 then return 0 end
            end
            return 1
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
    private final byte[] redisKeyBytes;
    private final LongAdder errors = new LongAdder();
    private final AtomicLong lastWarn = new AtomicLong();

    /**
     * @param spec         the sizing, at most {@link #MAX_BITS} bits
     * @param hashStrategy hash strategy; must be identical on every instance sharing the filter
     * @param keyEncoder   key encoder; must be identical on every instance sharing the filter
     * @param redis        template connected to Redis
     * @param keyPrefix    prefix for the Redis key, for example {@code "bloom:myapp:"}
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
        this.redisKey = (keyPrefix == null ? "" : keyPrefix) + spec.name() + ":" + fingerprint(spec, hashStrategy);
        this.redisKeyBytes = redisKey.getBytes(StandardCharsets.UTF_8);
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
        try {
            Long result = redis.execute(CONTAINS_SCRIPT, List.of(redisKey), args.toArray());
            return result == null || result != 0;
        } catch (DataAccessException e) {
            onError("mightContain", e);
            return true; // fail open: "maybe present" sends the caller to the source of truth
        }
    }

    @Override
    public boolean put(Object key) {
        List<String> args = indexArgs(key, new ArrayList<>(spec.hashFunctions()));
        try {
            Long added = redis.execute(PUT_SCRIPT, List.of(redisKey), args.toArray());
            return added != null && added > 0;
        } catch (DataAccessException e) {
            onError("put", e);
            return false;
        }
    }

    @Override
    public long putAll(Iterable<?> keys) {
        long newKeys = 0;
        List<String> args = new ArrayList<>(1 + batchSize * spec.hashFunctions());
        args.add(Integer.toString(spec.hashFunctions()));
        int inBatch = 0;
        for (Object key : keys) {
            indexArgs(key, args);
            if (++inBatch == batchSize) {
                newKeys += flush(args);
                args.subList(1, args.size()).clear();
                inBatch = 0;
            }
        }
        if (inBatch > 0) {
            newKeys += flush(args);
        }
        return newKeys;
    }

    private long flush(List<String> args) {
        try {
            Long added = redis.execute(PUT_ALL_SCRIPT, List.of(redisKey), args.toArray());
            return added == null ? 0 : added;
        } catch (DataAccessException e) {
            onError("putAll", e);
            return 0;
        }
    }

    @Override
    public BloomFilterSpec spec() {
        return spec;
    }

    @Override
    public BloomFilterStats stats() {
        try {
            Long bits = redis.execute((RedisCallback<Long>) (RedisConnection connection) ->
                    connection.stringCommands().bitCount(redisKeyBytes));
            return new BloomFilterStats(spec, bits == null ? 0 : Math.min(bits, spec.bitSize()));
        } catch (DataAccessException e) {
            errors.increment();
            throw new BloomFilterUnavailableException("Redis unavailable for Bloom filter '" + spec.name() + "'", e);
        }
    }

    @Override
    public boolean isEmpty() {
        try {
            return !Boolean.TRUE.equals(redis.hasKey(redisKey));
        } catch (DataAccessException e) {
            errors.increment();
            throw new BloomFilterUnavailableException("Redis unavailable for Bloom filter '" + spec.name() + "'", e);
        }
    }

    @Override
    public void clear() {
        try {
            redis.delete(redisKey);
        } catch (DataAccessException e) {
            errors.increment();
            throw new BloomFilterUnavailableException("Redis unavailable for Bloom filter '" + spec.name() + "'", e);
        }
    }

    @Override
    public long backendErrors() {
        return errors.sum();
    }

    private List<String> indexArgs(Object key, List<String> target) {
        for (long index : hashStrategy.indexes(keyEncoder.encode(key), spec.hashFunctions(), spec.bitSize())) {
            target.add(Long.toString(index));
        }
        return target;
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
}
