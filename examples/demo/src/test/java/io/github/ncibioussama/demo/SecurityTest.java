package io.github.ncibioussama.demo;

import static io.github.ncibioussama.demo.InMemoryFilterTest.randomEmail;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterRegistry;
import io.github.ncibioussama.bloomfilter.autoconfigure.ManagedBloomFilter.State;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;

/**
 * Hostile input through the public HTTP API: no wrong answers, no key leaks, no way to switch the filter off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SecurityTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    BloomFilterRegistry registry;

    Api api;

    @BeforeEach
    void setUp() {
        api = new Api(port);
    }

    @Test
    void oversizedKeysCannotDegradeTheFilterOrCauseErrors() {
        String huge = "a".repeat(5_000) + "@example.com";   // above bloom.max-key-bytes (4096)
        long lookups = api.databaseLookups();

        ResponseEntity<String> byEmail = api.raw("/users", Map.of("email", huge));
        assertThat(byEmail.getStatusCode().value()).isEqualTo(404);
        assertThat(api.raw("/users/email-taken", Map.of("email", huge)).getBody()).contains("\"taken\":false");

        // Oversized keys are never stored, so the filter sends them to the database instead of guessing.
        assertThat(api.databaseLookups()).isEqualTo(lookups + 2);
        assertThat(registry.managed("emails").state()).isEqualTo(State.READY);
        assertThat(api.status("/users?email=alice@example.com")).isEqualTo(200);
    }

    @Test
    void keyExpressionsNeverEvaluateUserInput() {
        String[] payloads = {
                "#{T(java.lang.Runtime).getRuntime().exec('calc')}",
                "T(java.lang.System).exit(1)",
                "' + #root + '",
                "${spring.datasource.password}",
                "\u0000￿😀",
        };
        for (String payload : payloads) {
            ResponseEntity<String> response = api.raw("/users", Map.of("email", payload));
            assertThat(response.getStatusCode().value()).as(payload).isEqualTo(404);
            assertThat(response.getBody()).as(payload).doesNotContain(payload);
        }
        assertThat(api.status("/users/1")).as("application still healthy").isEqualTo(200);
    }

    @Test
    void keysNeverLeakIntoResponsesMetricsOrLogs(CapturedOutput output) {
        String email = "leak-check-" + randomEmail();
        long id = api.createUser(email, "Mallory");
        api.status("/users/" + id);
        api.status("/users?email=" + email);
        api.raw("/users", Map.of("email", "unknown-" + email));
        api.raw("/users", Map.of("email", "x".repeat(5_000) + email));
        api.post("/actuator/bloomfilters/emails");

        assertThat(api.raw("/users", Map.of("email", "unknown-" + email)).getBody()).doesNotContain(email);
        assertThat(api.raw("/actuator/bloomfilters", Map.of()).getBody()).doesNotContain(email);
        for (String metric : new String[] {"bloom.filter.checks", "bloom.filter.puts", "bloom.filter.false.positives"}) {
            assertThat(api.raw("/actuator/metrics/" + metric, Map.of()).getBody()).doesNotContain(email);
        }
        assertThat(output.getAll()).doesNotContain(email);
    }

    @Test
    void unknownFiltersAreNotFound() {
        assertThat(api.status("/actuator/bloomfilters/does-not-exist")).isEqualTo(404);
        assertThat(api.raw("/actuator/bloomfilters/..%2F..%2Fetc", Map.of()).getStatusCode().is2xxSuccessful())
                .isFalse();
    }
}
