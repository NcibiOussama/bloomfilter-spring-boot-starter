package io.github.ncibioussama.demo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With {@code failure-mode: throw}, an outage is reported as an error, never as "not found". */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bloom.security.secret=demo-test-secret-not-for-production",
        "bloom.filters.users.failure-mode=throw",
        "spring.data.redis.timeout=300ms",
        "bloom.rebuild.recovery-interval=0"
})
@ActiveProfiles("redis")
@DirtiesContext
class RedisFailClosedTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void outageIsAnErrorNotANotFound() {
        Api api = new Api(port);
        assertThat(api.status("/users/1")).isEqualTo(200);

        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            int existing = api.status("/users/1");
            int unknown = api.status("/users/987654321");
            assertThat(existing).as("existing user during outage").isGreaterThanOrEqualTo(500);
            assertThat(unknown).as("unknown user during outage").isGreaterThanOrEqualTo(500);
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }

        Await.until(java.time.Duration.ofSeconds(15), "Redis answers again", () -> api.status("/users/1") == 200);
        assertThat(api.status("/users/987654321")).isEqualTo(404);
    }
}
