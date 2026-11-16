package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.KeyEncoder;
import io.github.ncibioussama.bloomfilter.redis.RedisBloomFilter;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Creates {@link RedisBloomFilter} instances.
 */
public class RedisBloomFilterBackend implements BloomFilterBackend {

    private final StringRedisTemplate redis;
    private final KeyEncoder keyEncoder;
    private final BloomFilterProperties properties;

    /**
     * @param redis      template used for all filters
     * @param keyEncoder key encoder
     * @param properties configuration
     */
    public RedisBloomFilterBackend(StringRedisTemplate redis, KeyEncoder keyEncoder, BloomFilterProperties properties) {
        this.redis = redis;
        this.keyEncoder = keyEncoder;
        this.properties = properties;
    }

    @Override
    public BloomFilterProperties.Backend type() {
        return BloomFilterProperties.Backend.REDIS;
    }

    @Override
    public BloomFilter create(BloomFilterSpec spec, BloomFilterProperties.Filter config) {
        return new RedisBloomFilter(spec, HashStrategies.forBackend(type(), properties.getSecurity()), keyEncoder,
                redis, properties.getRedis().getKeyPrefix(), config.getFailureMode(),
                properties.getRedis().getBatchSize());
    }
}
