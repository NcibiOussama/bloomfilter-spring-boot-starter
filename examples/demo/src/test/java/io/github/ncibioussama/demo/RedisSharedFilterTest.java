package io.github.ncibioussama.demo;

import static io.github.ncibioussama.demo.InMemoryFilterTest.randomEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterRegistry;
import io.github.ncibioussama.demo.user.UserFilterLoaders.UserIdLoader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Two application instances sharing filters through Redis. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("redis")
@DirtiesContext
class RedisSharedFilterTest {

    static final String SECRET = "demo-test-secret-not-for-production";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        properties.add("bloom.security.secret", () -> SECRET);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    BloomFilterRegistry registry;

    @Autowired
    UserIdLoader idLoader;

    @Autowired
    StringRedisTemplate redis;

    Api api;

    @BeforeEach
    void setUp() {
        api = new Api(port);
    }

    @Test
    void filtersLiveInRedis() {
        assertThat(api.filter("users")).containsEntry("backend", "redis").containsEntry("state", "READY");
        assertThat(redis.keys("bloom:demo:*")).isNotEmpty();

        assertThat(api.get("/users/2")).containsEntry("name", "Bob");
        long lookups = api.databaseLookups();
        assertThat(api.status("/users/987654321")).isEqualTo(404);
        assertThat(api.databaseLookups()).isEqualTo(lookups);
    }

    @Test
    void redisHoldsOnlyBitmapsNeverEmailsOrIds() {
        String email = "plaintext-check-" + randomEmail();
        api.createUser(email, "Heidi");
        for (String key : redis.keys("*")) {
            assertThat(key).doesNotContain("@").doesNotContain("example.com");
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            byte[] value = redis.execute((RedisCallback<byte[]>) c ->
                    "string".equals(c.keyCommands().type(raw).code()) ? c.stringCommands().get(raw) : null);
            if (value != null) {
                assertThat(new String(value, StandardCharsets.ISO_8859_1)).doesNotContain(email).doesNotContain("alice");
            }
        }
    }

    @Test
    void instanceWithADifferentSecretNeverReadsTheSharedFilter() {
        try (ConfigurableApplicationContext other = new SpringApplicationBuilder(DemoApplication.class)
                .profiles("redis")
                .run("--server.port=0",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--bloom.security.secret=a-completely-different-secret-value")) {

            // Different secret = different hash positions = a different Redis key, so it builds its own filter
            // instead of reading bits it would misinterpret (which could produce wrong "not found" answers).
            assertThat(other.getBean(UserIdLoader.class).runs()).isEqualTo(1);
            BloomFilterRegistry otherRegistry = other.getBean(BloomFilterRegistry.class);
            assertThat(otherRegistry.get("users").mightContain(1L)).isTrue();
            assertThat(otherRegistry.get("emails").mightContain("alice@example.com")).isTrue();
        }
    }

    @Test
    void concurrentWritesDuringARebuildAreNeverLost() throws Exception {
        int users = 60;
        long rebuilds = api.rebuildSuccesses("users");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Long>> created = new ArrayList<>();
            for (int i = 0; i < users; i++) {
                created.add(pool.submit(() -> api.createUser(randomEmail(), "Concurrent")));
                if (i == users / 2) {
                    api.post("/actuator/bloomfilters/users");
                }
            }
            List<Long> ids = new ArrayList<>();
            for (Future<Long> future : created) {
                ids.add(future.get(30, TimeUnit.SECONDS));
            }
            Await.until(Duration.ofSeconds(30), "rebuild finished", () -> api.rebuildSuccesses("users") > rebuilds
                    && !registry.managed("users").isRebuilding());

            for (long id : ids) {
                assertThat(api.status("/users/" + id)).as("user %d", id).isEqualTo(200);
                assertThat(registry.get("users").mightContain(id)).as("filter has %d", id).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void secondInstanceSharesTheFilterAndSkipsWarmUp() {
        try (ConfigurableApplicationContext second = new SpringApplicationBuilder(DemoApplication.class)
                .profiles("redis")
                .run("--server.port=0",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--bloom.security.secret=" + SECRET)) {

            // warm-up.mode=if-empty: the shared filter was already loaded by the first instance.
            assertThat(second.getBean(UserIdLoader.class).runs()).isZero();

            // A user created through instance 1 is immediately visible to instance 2's filter...
            String email = randomEmail();
            long id = api.createUser(email, "Frank");
            BloomFilterRegistry secondRegistry = second.getBean(BloomFilterRegistry.class);
            assertThat(secondRegistry.get("users").mightContain(id)).isTrue();
            assertThat(secondRegistry.get("emails").mightContain(email)).isTrue();

            // ...and a key added by instance 2 is visible to instance 1.
            secondRegistry.get("emails").put("added-by-instance-2@example.com");
            assertThat(registry.get("emails").mightContain("added-by-instance-2@example.com")).isTrue();
        }
        assertThat(idLoader.runs()).isEqualTo(1);
    }
}
