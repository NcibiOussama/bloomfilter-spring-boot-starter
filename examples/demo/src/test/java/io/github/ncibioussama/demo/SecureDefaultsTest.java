package io.github.ncibioussama.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;

/** The starter's defaults, without the demo's convenience settings. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "bloom.rebuild.endpoint-enabled=false")
class SecureDefaultsTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    BloomFilterRegistry registry;

    @Test
    void rebuildThroughTheActuatorIsDisabledByDefault() {
        Api api = new Api(port);
        long rebuilds = api.rebuildSuccesses("users");

        assertThat(api.post("/actuator/bloomfilters/users")).containsEntry("status", "disabled");

        assertThat(api.rebuildSuccesses("users")).isEqualTo(rebuilds);
        assertThat(registry.managed("users").isRebuilding()).isFalse();
    }

    @Test
    void misconfiguredSecretsFailStartup() {
        assertStartupFails("bloom.security.secret=too-short", "at least 16 bytes");
        assertStartupFails("bloom.security.hash-algorithm=siphash", "bloom.filters.redis-only.backend=redis",
                "siphash");
    }

    private static void assertStartupFails(String... propertiesThenExpectedMessage) {
        int last = propertiesThenExpectedMessage.length - 1;
        String expected = propertiesThenExpectedMessage[last];
        String[] args = new String[last + 2];
        args[0] = "--server.port=0";
        for (int i = 0; i < last; i++) {
            args[i + 1] = "--" + propertiesThenExpectedMessage[i];
        }
        args[last + 1] = "--spring.main.banner-mode=off";
        Throwable failure = null;
        try (var ignored = new SpringApplicationBuilder(DemoApplication.class).run(args)) {
            // started: wrong
        } catch (RuntimeException e) {
            failure = e;
        }
        assertThat(failure).as("startup with %s", String.join(" ", args)).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertThat(root.getMessage()).containsIgnoringCase(expected);
    }
}
