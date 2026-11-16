package io.github.ncibioussama.bloomfilter.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.BloomFilterUnavailableException;
import io.github.ncibioussama.bloomfilter.core.DefaultKeyEncoder;
import io.github.ncibioussama.bloomfilter.core.FailureMode;
import io.github.ncibioussama.bloomfilter.core.SipHashStrategy;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against a real Redis in Docker. Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisBloomFilterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    static LettuceConnectionFactory connectionFactory;
    static StringRedisTemplate template;

    final SipHashStrategy hash = SipHashStrategy.withSecret("test-secret-0123456789");

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        template = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void flush() {
        template.execute((org.springframework.data.redis.core.RedisCallback<Void>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }

    private RedisBloomFilter filter(String name, FailureMode mode) {
        return new RedisBloomFilter(BloomFilterSpec.of(name, 10_000, 0.01), hash, new DefaultKeyEncoder(),
                template, "bloom:test:", mode, 100);
    }

    @Test
    void putAndCheck() {
        RedisBloomFilter filter = filter("users", FailureMode.THROW);
        assertThat(filter.isEmpty()).isTrue();
        assertThat(filter.mightContain("alice")).isFalse();
        assertThat(filter.put("alice")).isTrue();
        assertThat(filter.put("alice")).isFalse();
        assertThat(filter.mightContain("alice")).isTrue();
        assertThat(filter.isEmpty()).isFalse();
    }

    @Test
    void putAllInBatchesHasNoFalseNegatives() {
        RedisBloomFilter filter = filter("ids", FailureMode.THROW);
        List<Long> ids = LongStream.range(0, 5_000).boxed().toList();
        assertThat(filter.putAll(ids)).isBetween(4_900L, 5_000L);
        for (Long id : ids) {
            assertThat(filter.mightContain(id)).isTrue();
        }
        assertThat(filter.stats().approximateElementCount()).isBetween(4_750L, 5_250L);
    }

    @Test
    void instancesWithSameConfigurationShareState() {
        filter("shared", FailureMode.THROW).put("bob");
        assertThat(filter("shared", FailureMode.THROW).mightContain("bob")).isTrue();
    }

    @Test
    void differentSecretUsesDifferentRedisKey() {
        RedisBloomFilter a = filter("users", FailureMode.THROW);
        RedisBloomFilter b = new RedisBloomFilter(BloomFilterSpec.of("users", 10_000, 0.01),
                SipHashStrategy.withSecret("another-secret-0123456789"), new DefaultKeyEncoder(),
                template, "bloom:test:", FailureMode.THROW, 100);
        assertThat(a.redisKey()).isNotEqualTo(b.redisKey()).startsWith("bloom:test:users:");
    }

    @Test
    void clearRemovesEverything() {
        RedisBloomFilter filter = filter("tmp", FailureMode.THROW);
        filter.put("x");
        filter.clear();
        assertThat(filter.isEmpty()).isTrue();
        assertThat(filter.mightContain("x")).isFalse();
    }

    @Test
    void failsOpenOrThrowsWhenRedisIsDown() {
        LettuceConnectionFactory dead = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 1));
        dead.afterPropertiesSet();
        dead.start();
        try {
            StringRedisTemplate deadTemplate = new StringRedisTemplate(dead);
            RedisBloomFilter open = new RedisBloomFilter(BloomFilterSpec.of("f", 100, 0.01), hash,
                    new DefaultKeyEncoder(), deadTemplate, "bloom:", FailureMode.FAIL_OPEN, 10);
            assertThat(open.mightContain("anything")).isTrue();
            assertThat(open.put("anything")).isFalse();
            assertThat(open.backendErrors()).isEqualTo(2);

            RedisBloomFilter strict = new RedisBloomFilter(BloomFilterSpec.of("f", 100, 0.01), hash,
                    new DefaultKeyEncoder(), deadTemplate, "bloom:", FailureMode.THROW, 10);
            assertThatThrownBy(() -> strict.mightContain("anything"))
                    .isInstanceOf(BloomFilterUnavailableException.class);
        } finally {
            dead.destroy();
        }
    }
}
