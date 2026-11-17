package io.github.ncibioussama.bloomfilter.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ncibioussama.bloomfilter.core.BloomFilterSpec;
import io.github.ncibioussama.bloomfilter.core.BloomFilterUnavailableException;
import io.github.ncibioussama.bloomfilter.core.DefaultKeyEncoder;
import io.github.ncibioussama.bloomfilter.core.FailureMode;
import io.github.ncibioussama.bloomfilter.core.RebuildSession;
import io.github.ncibioussama.bloomfilter.core.SipHashStrategy;
import java.time.Duration;
import org.springframework.data.redis.core.RedisCallback;
import java.nio.charset.StandardCharsets;
import java.util.Set;
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
    void oversizedKeysAreNeverStoredAndAlwaysMightContain() {
        RedisBloomFilter filter = new RedisBloomFilter(BloomFilterSpec.of("big", 10_000, 0.01), hash,
                new DefaultKeyEncoder(64), template, "bloom:test:", FailureMode.THROW, 2);
        String oversized = "x".repeat(65);

        assertThat(filter.put(oversized)).isFalse();
        assertThat(filter.isEmpty()).isTrue();
        assertThat(filter.mightContain(oversized)).isTrue();
        assertThat(filter.putAll(List.of("a", oversized, "b", "c"))).isEqualTo(3);   // batch size 2
        assertThat(filter.mightContain("a")).isTrue();
        assertThat(filter.mightContain("c")).isTrue();
        assertThat(filter.backendErrors()).isZero();

        RebuildSession session = filter.beginRebuild(Duration.ofMinutes(1));
        session.target().putAll(List.of("d", oversized));
        assertThat(session.target().put(oversized)).isFalse();
        assertThat(session.target().mightContain(oversized)).isTrue();
        assertThat(session.commit()).isTrue();
        assertThat(filter.mightContain("d")).isTrue();
        assertThat(filter.mightContain(oversized)).isTrue();
    }

    @Test
    void suspensionIsSharedByEveryInstanceAndSurvivesRebuilds() {
        RedisBloomFilter a = filter("users", FailureMode.THROW);
        RedisBloomFilter b = filter("users", FailureMode.THROW);   // another application instance
        a.put("alice");
        assertThat(b.mightContain("bob")).isFalse();

        String token = a.suspend();
        assertThat(b.suspension()).isEqualTo(token);
        assertThat(b.mightContain("bob")).as("suspended on every instance").isTrue();
        b.put("carol");

        RebuildSession session = b.beginRebuild(Duration.ofMinutes(1));
        session.target().putAll(List.of("alice", "carol"));
        assertThat(session.target().mightContain("bob")).as("rebuild target is not suspended").isFalse();
        assertThat(session.commit()).isTrue();
        assertThat(a.mightContain("bob")).as("a rebuild does not end a suspension").isTrue();

        assertThat(b.resume("someone-else")).isFalse();
        assertThat(b.resume(token)).isTrue();
        assertThat(a.suspension()).isNull();
        assertThat(a.mightContain("bob")).isFalse();
        assertThat(a.mightContain("carol")).isTrue();
    }

    @Test
    void redisNeverStoresKeysInPlainText() {
        RedisBloomFilter filter = filter("emails", FailureMode.THROW);
        filter.putAll(List.of("alice@example.com", "bob@example.com"));
        RebuildSession session = filter.beginRebuild(Duration.ofMinutes(1));
        session.target().put("carol@example.com");

        Set<String> keys = template.keys("*");
        assertThat(keys).isNotEmpty().allSatisfy(key -> assertThat(key).doesNotContain("example.com"));
        for (String key : keys) {
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            byte[] value = template.execute((RedisCallback<byte[]>) c ->
                    "string".equals(c.keyCommands().type(raw).code()) ? c.stringCommands().get(raw) : null);
            String text = value == null ? "" : new String(value, StandardCharsets.ISO_8859_1);
            assertThat(text).doesNotContain("alice").doesNotContain("bob").doesNotContain("carol");
        }
        session.abort();
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
        assertThat(a.redisKey()).isNotEqualTo(b.redisKey()).startsWith("bloom:test:{users:").endsWith("}");
    }

    @Test
    void populatedMarkerIsSeparateFromContent() {
        RedisBloomFilter filter = filter("users", FailureMode.THROW);
        filter.put("alice"); // another instance is still loading
        assertThat(filter.isEmpty()).isFalse();
        assertThat(filter.isPopulated()).isFalse();
        filter.markPopulated();
        assertThat(filter("users", FailureMode.THROW).isPopulated()).isTrue();
    }

    @Test
    void rebuildDropsDeletedKeysAndKeepsWritesFromOtherInstances() {
        RedisBloomFilter instanceA = filter("users", FailureMode.THROW);
        RedisBloomFilter instanceB = filter("users", FailureMode.THROW);
        instanceA.put("deleted-user");
        instanceA.put("alice");

        RebuildSession session = instanceA.beginRebuild(Duration.ofMinutes(1));
        assertThat(instanceB.beginRebuild(Duration.ofMinutes(1))).as("lock held by instance A").isNull();
        instanceB.put("created-on-b-during-rebuild");                    // dual-written by the Lua script
        instanceB.putAll(List.of("bulk-1", "bulk-2"));                     // also dual-written
        session.target().putAll(List.of("alice"));                        // loader reads the source of truth
        assertThat(instanceB.mightContain("deleted-user")).as("old content serves until commit").isTrue();

        assertThat(session.commit()).isTrue();

        for (RedisBloomFilter instance : List.of(instanceA, instanceB)) {
            assertThat(instance.mightContain("alice")).isTrue();
            assertThat(instance.mightContain("created-on-b-during-rebuild")).isTrue();
            assertThat(instance.mightContain("bulk-2")).isTrue();
            assertThat(instance.mightContain("deleted-user")).isFalse();
            assertThat(instance.isPopulated()).isTrue();
        }
        assertThat(template.keys("bloom:test:{users:*")).containsExactlyInAnyOrder(
                instanceA.redisKey(), instanceA.redisKey() + ":ready");
    }

    @Test
    void expiredLockCancelsTheSwapInsteadOfLosingWrites() throws InterruptedException {
        RedisBloomFilter filter = filter("users", FailureMode.THROW);
        filter.put("alice");
        RebuildSession session = filter.beginRebuild(Duration.ofMillis(200));
        Thread.sleep(400); // lock expired: other instances stopped dual-writing
        filter.put("written-after-expiry");
        assertThat(session.commit()).isFalse();
        assertThat(filter.mightContain("alice")).isTrue();
        assertThat(filter.mightContain("written-after-expiry")).isTrue();
        assertThat(filter.beginRebuild(Duration.ofMinutes(1))).as("a new rebuild can start").isNotNull();
    }

    @Test
    void abortReleasesTheLockAndKeepsContent() {
        RedisBloomFilter filter = filter("users", FailureMode.THROW);
        filter.put("alice");
        RebuildSession session = filter.beginRebuild(Duration.ofMinutes(1));
        session.target().put("other");
        session.abort();
        assertThat(filter.mightContain("alice")).isTrue();
        assertThat(template.hasKey(filter.redisKey() + ":next")).isFalse();
        assertThat(filter.beginRebuild(Duration.ofMinutes(1))).isNotNull();
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
            // An unrecorded suspension would protect nothing, so suspend never fails open.
            assertThatThrownBy(open::suspend).isInstanceOf(BloomFilterUnavailableException.class);

            RedisBloomFilter strict = new RedisBloomFilter(BloomFilterSpec.of("f", 100, 0.01), hash,
                    new DefaultKeyEncoder(), deadTemplate, "bloom:", FailureMode.THROW, 10);
            assertThatThrownBy(() -> strict.mightContain("anything"))
                    .isInstanceOf(BloomFilterUnavailableException.class);
        } finally {
            dead.destroy();
        }
    }
}
