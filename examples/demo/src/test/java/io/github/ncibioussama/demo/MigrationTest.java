package io.github.ncibioussama.demo;

import static io.github.ncibioussama.demo.InMemoryFilterTest.randomEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Rows written behind the application's back (SQL migration, bulk import, another service): the filter does not
 * know them. Suspending the filter around the migration keeps every answer correct, on every instance.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "bloom.security.secret=" + MigrationTest.SECRET,
        "bloom.rebuild.recovery-interval=0"
})
@ActiveProfiles("redis")
@DirtiesContext
class MigrationTest {

    static final String SECRET = "demo-test-secret-not-for-production";

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
    JdbcTemplate jdbc;

    Api api;

    @BeforeEach
    void setUp() {
        api = new Api(port);
    }

    @Test
    void withoutSuspendARowInsertedBySqlIsWronglyNotFound() {
        long id = insertWithSql(randomEmail());

        assertThat(api.status("/users/" + id)).as("the problem this feature solves").isEqualTo(404);

        // A rebuild repairs it, but only afterwards: until then every lookup of the row was wrong.
        long rebuilds = api.rebuildSuccesses("users");
        api.post("/actuator/bloomfilters/users");
        Await.until(Duration.ofSeconds(15), "rebuild", () -> api.rebuildSuccesses("users") > rebuilds);
        assertThat(api.status("/users/" + id)).isEqualTo(200);
    }

    @Test
    void suspendedAroundTheMigrationEveryAnswerStaysCorrectOnEveryInstance() {
        try (ConfigurableApplicationContext other = new SpringApplicationBuilder(DemoApplication.class)
                .profiles("redis")
                .run("--server.port=0",
                        "--spring.data.redis.host=" + REDIS.getHost(),
                        "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                        "--bloom.security.secret=" + SECRET)) {
            BloomFilterRegistry otherInstance = other.getBean(BloomFilterRegistry.class);

            // 1. Before the migration: suspend. The filter is suspended everywhere when the call returns.
            assertThat(api.post("/actuator/bloomfilters/users/suspend")).containsEntry("status", "suspended");
            assertThat(api.filter("users")).containsEntry("suspended", true);

            // 2. The migration writes rows without telling the filter.
            long migrated = insertWithSql(randomEmail());
            assertThat(api.status("/users/" + migrated)).as("found while suspended").isEqualTo(200);
            assertThat(otherInstance.get("users").mightContain(migrated)).as("other instance bypasses too").isTrue();

            // 3. After the migration: resume. It rebuilds from the database, then trusts the filter again.
            assertThat(api.post("/actuator/bloomfilters/users/resume")).containsEntry("status", "resuming");
            Await.until(Duration.ofSeconds(15), "resume", () -> Boolean.FALSE.equals(api.filter("users").get("suspended")));

            assertThat(api.status("/users/" + migrated)).isEqualTo(200);
            assertThat(otherInstance.get("users").mightContain(migrated)).isTrue();
            long lookups = api.databaseLookups();
            assertThat(api.status("/users/987654321")).isEqualTo(404);
            assertThat(api.databaseLookups()).as("the filter answers again").isEqualTo(lookups);
            assertThat(otherInstance.get("users").mightContain(987654321L)).isFalse();
        }
    }

    @Test
    void unknownActionsAreRejected() {
        assertThat(api.raw("/actuator/bloomfilters/users/drop", Map.of()).getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(api.status("/users/1")).isEqualTo(200);
    }

    private long insertWithSql(String email) {
        jdbc.update("insert into app_user (email, name) values (?, ?)", email, "Migrated");
        return jdbc.queryForObject("select id from app_user where email = ?", Long.class, email);
    }
}
