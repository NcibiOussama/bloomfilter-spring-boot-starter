package io.github.ncibioussama.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterRegistry;
import io.github.ncibioussama.bloomfilter.autoconfigure.ManagedBloomFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** End-to-end tests of the starter with the default in-memory backend, through the HTTP API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InMemoryFilterTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    BloomFilterRegistry registry;

    @Autowired
    MeterRegistry meters;

    Api api;

    @BeforeEach
    void setUp() {
        api = new Api(port);
    }

    @Test
    void warmUpLoadsExistingRowsBeforeTheAppIsReady() {
        assertThat(registry.managed("users").state()).isEqualTo(ManagedBloomFilter.State.READY);
        assertThat(registry.managed("emails").state()).isEqualTo(ManagedBloomFilter.State.READY);

        assertThat(api.get("/users/1")).containsEntry("name", "Alice");
        assertThat(api.get("/users?email=bob@example.com")).containsEntry("name", "Bob");
        assertThat(api.status("/actuator/health/readiness")).isEqualTo(200);
    }

    @Test
    void unknownIdIsAnsweredWithoutTouchingTheDatabase() {
        double absentBefore = absentChecks("users");
        long lookups = api.databaseLookups();

        assertThat(api.status("/users/987654321")).isEqualTo(404);

        assertThat(api.databaseLookups()).isEqualTo(lookups);
        assertThat(absentChecks("users")).isEqualTo(absentBefore + 1);
    }

    @Test
    void unknownEmailReturns404AndFreeEmailReturnsFalseWithoutQueries() {
        long lookups = api.databaseLookups();

        assertThat(api.status("/users?email=" + randomEmail())).isEqualTo(404);           // OnMiss.THROW
        assertThat(api.get("/users/email-taken?email=" + randomEmail()))
                .containsEntry("taken", false);                                          // boolean -> false

        assertThat(api.databaseLookups()).isEqualTo(lookups);
    }

    @Test
    void createdUserIsFoundImmediately() {
        String email = randomEmail();
        long id = api.createUser(email, "Dave");

        assertThat(api.get("/users/" + id)).containsEntry("email", email);              // @BloomAdd
        assertThat(api.get("/users/email-taken?email=" + email)).containsEntry("taken", true); // registry.put
        assertThat(api.get("/users?email=" + email)).containsEntry("name", "Dave");
    }

    @Test
    void deletedKeyIsAFalsePositiveUntilARebuildRemovesIt() {
        long id = api.createUser(randomEmail(), "Erin");
        assertThat(api.delete("/users/" + id)).isEqualTo(204);

        // The filter still says "maybe": the database is queried, finds nothing, and it is counted.
        double falsePositivesBefore = meters.get("bloom.filter.false.positives").tag("filter", "users")
                .functionCounter().count();
        long lookups = api.databaseLookups();
        assertThat(api.status("/users/" + id)).isEqualTo(404);
        assertThat(api.databaseLookups()).isEqualTo(lookups + 1);
        assertThat(meters.get("bloom.filter.false.positives").tag("filter", "users").functionCounter().count())
                .isEqualTo(falsePositivesBefore + 1);

        // Rebuild through the actuator: reloads every id from the database and swaps the filter atomically.
        long rebuilds = api.rebuildSuccesses("users");
        assertThat(api.post("/actuator/bloomfilters/users")).containsEntry("status", "started");
        Await.until(Duration.ofSeconds(10), "rebuild of 'users'", () -> api.rebuildSuccesses("users") > rebuilds);

        lookups = api.databaseLookups();
        assertThat(api.status("/users/" + id)).isEqualTo(404);
        assertThat(api.databaseLookups()).isEqualTo(lookups);   // now rejected by the filter itself
        assertThat(api.get("/users/1")).containsEntry("name", "Alice");
    }

    @Test
    void actuatorEndpointAndMetricsDescribeTheFilters() {
        var users = api.filter("users");
        assertThat(users)
                .containsEntry("backend", "memory")
                .containsEntry("state", "READY")
                .containsEntry("expectedInsertions", 100000)
                .containsEntry("hashFunctions", 7)
                .containsKeys("fillRatio", "estimatedFalsePositiveRate", "counters", "rebuild");
        assertThat(users.get("rebuild").toString()).contains("cron=0 0 3 * * *");

        assertThat(api.get("/actuator/metrics/bloom.filter.checks?tag=filter:users"))
                .containsEntry("name", "bloom.filter.checks");
        assertThat(meters.get("bloom.filter.ready").tag("filter", "emails").gauge().value()).isEqualTo(1.0);
        assertThat(api.status("/actuator/bloomfilters/no-such-filter")).isEqualTo(404);
    }

    private double absentChecks(String filter) {
        return meters.get("bloom.filter.checks").tag("filter", filter).tag("result", "absent")
                .functionCounter().count();
    }

    static String randomEmail() {
        return UUID.randomUUID() + "@example.com";
    }
}
