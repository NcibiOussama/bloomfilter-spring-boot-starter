package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterProperties.Backend;
import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.DefaultKeyEncoder;
import io.github.ncibioussama.bloomfilter.core.KeyEncoder;
import io.github.ncibioussama.bloomfilter.redis.RedisBloomFilter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Auto-configuration for Bloom filters declared under {@code bloom.filters.*}.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"})
@ConditionalOnProperty(prefix = "bloom", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BloomFilterProperties.class)
public class BloomFilterAutoConfiguration {

    private static final Log log = LogFactory.getLog(BloomFilterAutoConfiguration.class);

    /** Runs before transactions and caching (both default to the lowest precedence). */
    static final int ADVISOR_ORDER = Ordered.LOWEST_PRECEDENCE - 1000;

    @Bean
    @ConditionalOnMissingBean
    KeyEncoder bloomFilterKeyEncoder(BloomFilterProperties properties) {
        return new DefaultKeyEncoder(properties.getMaxKeyBytes());
    }

    @Bean
    MemoryBloomFilterBackend memoryBloomFilterBackend(KeyEncoder keyEncoder, BloomFilterProperties properties) {
        return new MemoryBloomFilterBackend(keyEncoder, properties.getSecurity());
    }

    @Bean
    @ConditionalOnMissingBean
    BloomFilterRegistry bloomFilterRegistry(BloomFilterProperties properties,
                                            ObjectProvider<BloomFilterBackend> backends,
                                            ObjectProvider<BloomFilterLoader> loaders) {
        Map<Backend, BloomFilterBackend> byType = new EnumMap<>(Backend.class);
        backends.orderedStream().forEach(b -> byType.putIfAbsent(b.type(), b));

        Set<String> loaded = properties.getWarmUp().isEnabled()
                ? loaders.orderedStream().map(BloomFilterLoader::filterName).collect(Collectors.toSet())
                : Set.of();
        for (String name : loaded) {
            if (!properties.getFilters().containsKey(name)) {
                throw new IllegalStateException("A BloomFilterLoader targets filter '" + name
                        + "', which is not configured. Configured filters: " + properties.getFilters().keySet());
            }
        }

        Map<String, ManagedBloomFilter> filters = new LinkedHashMap<>();
        properties.getFilters().forEach((name, config) -> {
            BloomFilterSpec spec = BloomFilterSpec.of(name, config.getExpectedInsertions(),
                    config.getFalsePositiveRate(), properties.getMaxBitsPerFilter());
            Backend type = config.getBackend() != null ? config.getBackend() : properties.getDefaultBackend();
            BloomFilterBackend backend = byType.get(type);
            if (backend == null) {
                throw new IllegalStateException(missingBackendMessage(name, type));
            }
            BloomFilter filter = backend.create(spec, config);
            ManagedBloomFilter.State state = loaded.contains(name)
                    ? ManagedBloomFilter.State.WARMING_UP : ManagedBloomFilter.State.READY;
            filters.put(name, new ManagedBloomFilter(filter, type.name().toLowerCase(), state));
            log.info(String.format(Locale.ROOT, "Bloom filter '%s': backend=%s, expected-insertions=%d, "
                            + "false-positive-rate=%s, size=%s, hash-functions=%d", name,
                    type.name().toLowerCase(Locale.ROOT), spec.expectedInsertions(), spec.falsePositiveRate(),
                    formatSize(spec.bitSize() / 8), spec.hashFunctions()));
        });
        return new BloomFilterRegistry(filters);
    }

    static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.2f MiB", bytes / 1024.0 / 1024.0);
    }

    private static String missingBackendMessage(String name, Backend type) {
        if (type == Backend.REDIS) {
            return "Bloom filter '" + name + "' uses backend 'redis', but no Redis connection is available. "
                    + "Add 'org.springframework.boot:spring-boot-starter-data-redis' and configure spring.data.redis.*";
        }
        return "Bloom filter '" + name + "' uses backend '" + type.name().toLowerCase()
                + "', but no BloomFilterBackend bean supports it.";
    }

    @Bean
    @ConditionalOnMissingBean
    BloomFilterWarmUpRunner bloomFilterWarmUpRunner(BloomFilterRegistry registry,
                                                    ObjectProvider<BloomFilterLoader> loaders,
                                                    BloomFilterProperties properties) {
        List<BloomFilterLoader> toRun = properties.getWarmUp().isEnabled()
                ? loaders.orderedStream().toList() : List.of();
        return new BloomFilterWarmUpRunner(registry, toRun, properties.getWarmUp().getMode());
    }

    @Bean
    @ConditionalOnMissingBean
    BloomFilterRebuilder bloomFilterRebuilder(BloomFilterRegistry registry,
                                              ObjectProvider<BloomFilterLoader> loaders,
                                              BloomFilterProperties properties) {
        return new BloomFilterRebuilder(registry, loaders.orderedStream().toList(), properties);
    }

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor bloomFilterAnnotationAdvisor(ObjectProvider<BloomFilterRegistry> registry) {
        DefaultPointcutAdvisor advisor = new DefaultPointcutAdvisor(
                new BloomFilterAnnotationPointcut(), new BloomFilterMethodInterceptor(registry));
        advisor.setOrder(ADVISOR_ORDER);
        return advisor;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({StringRedisTemplate.class, RedisBloomFilter.class})
    static class RedisBackendConfiguration {

        @Bean
        @ConditionalOnBean(RedisConnectionFactory.class)
        RedisBloomFilterBackend redisBloomFilterBackend(RedisConnectionFactory connectionFactory,
                                                        KeyEncoder keyEncoder, BloomFilterProperties properties) {
            return new RedisBloomFilterBackend(new StringRedisTemplate(connectionFactory), keyEncoder, properties);
        }
    }
}
