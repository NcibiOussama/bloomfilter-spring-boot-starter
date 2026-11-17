package io.github.ncibioussama.demo;

import static io.github.ncibioussama.demo.InMemoryFilterTest.randomEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterRegistry;
import io.github.ncibioussama.bloomfilter.autoconfigure.ManagedBloomFilter.State;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis goes away while the application keeps serving: no request gets a wrong "not found", and the filter
 * repairs itself once Redis is back.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bloom.security.secret=demo-test-secret-not-for-production",
        "spring.data.redis.timeout=300ms",
        "bloom.rebuild.recovery-interval=1s"
})
@ActiveProfiles("redis")
@DirtiesContext
class RedisOutageTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    BloomFilterRegistry registry;

    @Test
    void failsOpenDuringAnOutageAndSelfHealsAfterwards() {
        Api api = new Api(port);
        assertThat(registry.managed("users").state()).isEqualTo(State.READY);

        long id;
        pauseRedis();
        try {
            // Writes still succeed: the database is the source of truth. The lost filter write degrades the filter.
            id = api.createUser(randomEmail(), "Grace");
            assertThat(registry.managed("users").state()).isEqualTo(State.DEGRADED);

            // Degraded filters are bypassed, so the new user is found and unknown ids are still a correct 404.
            assertThat(api.get("/users/" + id)).containsEntry("name", "Grace");
            long lookups = api.databaseLookups();
            assertThat(api.status("/users/987654321")).isEqualTo(404);
            assertThat(api.databaseLookups()).isEqualTo(lookups + 1);   // answered by the database
            assertThat(api.filter("users")).containsEntry("state", "DEGRADED");
        } finally {
            unpauseRedis();
        }

        // Automatic recovery rebuilds the filter from the database, including the user created during the outage.
        Await.until(Duration.ofSeconds(30), "'users' filter recovered",
                () -> registry.managed("users").state() == State.READY);
        Await.until(Duration.ofSeconds(30), "'emails' filter recovered",
                () -> registry.managed("emails").state() == State.READY);

        assertThat(api.get("/users/" + id)).containsEntry("name", "Grace");
        long lookups = api.databaseLookups();
        assertThat(api.status("/users/987654321")).isEqualTo(404);
        assertThat(api.databaseLookups()).isEqualTo(lookups);           // the filter answers again
    }

    private static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    private static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }
}
