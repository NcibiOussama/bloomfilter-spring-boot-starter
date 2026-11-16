package io.github.ncibioussama.bloomfilter.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.ncibioussama.bloomfilter.annotation.BloomAdd;
import io.github.ncibioussama.bloomfilter.annotation.BloomCheck;
import io.github.ncibioussama.bloomfilter.annotation.BloomFilterMissException;
import io.github.ncibioussama.bloomfilter.annotation.OnMiss;
import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import io.github.ncibioussama.bloomfilter.redis.RedisBloomFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

class BloomFilterAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InfrastructureProxyConfig.class)
            .withConfiguration(AutoConfigurations.of(BloomFilterAutoConfiguration.class,
                    BloomFilterMetricsAutoConfiguration.class))
            .withPropertyValues(
                    "bloom.filters.users.expected-insertions=1000",
                    "bloom.filters.users.false-positive-rate=0.01");

    @Test
    void createsConfiguredFilters() {
        runner.run(context -> {
            BloomFilterRegistry registry = context.getBean(BloomFilterRegistry.class);
            assertThat(registry.names()).containsExactly("users");
            BloomFilter users = registry.get("users");
            assertThat(users.spec().expectedInsertions()).isEqualTo(1000);
            users.put(42L);
            assertThat(users.mightContain("42")).isTrue();
        });
    }

    @Test
    void unknownFilterGivesHelpfulError() {
        runner.run(context -> assertThatThrownBy(() -> context.getBean(BloomFilterRegistry.class).get("orders"))
                .hasMessageContaining("No Bloom filter named 'orders'")
                .hasMessageContaining("[users]"));
    }

    @Test
    void redisBackendWithoutRedisFailsWithClearMessage() {
        runner.withPropertyValues("bloom.filters.users.backend=redis")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("spring-boot-starter-data-redis"));
    }

    @Test
    void oversizedFilterIsRejected() {
        runner.withPropertyValues("bloom.filters.users.expected-insertions=100000000000")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("exceeds the limit"));
    }

    @Test
    void shortSecretIsRejected() {
        runner.withPropertyValues("bloom.security.secret=tooshort")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("at least 16 bytes"));
    }

    @Test
    void redisBackendIsWiredWhenAConnectionFactoryExists() {
        runner.withPropertyValues("bloom.filters.users.backend=redis",
                        "bloom.security.secret=0123456789abcdef-test",
                        "bloom.redis.key-prefix=bloom:test:")
                .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
                .run(context -> {
                    ManagedBloomFilter users = context.getBean(BloomFilterRegistry.class).managed("users");
                    assertThat(users.backend()).isEqualTo("redis");
                    assertThat(users.delegate()).isInstanceOf(RedisBloomFilter.class);
                    assertThat(((RedisBloomFilter) users.delegate()).redisKey()).startsWith("bloom:test:users:");
                });
    }

    @Test
    void actuatorEndpointIsRegisteredWhenExposed() {
        runner.withConfiguration(AutoConfigurations.of(BloomFilterEndpointAutoConfiguration.class))
                .withPropertyValues("management.endpoints.web.exposure.include=bloomfilters")
                .run(context -> assertThat(context).hasSingleBean(BloomFiltersEndpoint.class));
    }

    @Test
    void sizesAreFormattedReadably() {
        assertThat(BloomFilterAutoConfiguration.formatSize(1_200)).isEqualTo("1.2 KiB");
        assertThat(BloomFilterAutoConfiguration.formatSize(1_198_136)).isEqualTo("1.14 MiB");
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("bloom.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BloomFilterRegistry.class));
    }

    @Test
    void annotationsCheckAndAdd() {
        runner.withUserConfiguration(UserServiceConfig.class).run(context -> {
            UserService service = context.getBean(UserService.class);

            assertThat(service.findById(1L)).isEmpty();
            assertThat(service.calls()).as("rejected by the filter, method not called").isZero();

            service.create(1L);
            assertThat(service.findById(1L)).contains("user-1");
            assertThat(service.calls()).isEqualTo(1);

            assertThatThrownBy(() -> service.getOrThrow(2L)).isInstanceOf(BloomFilterMissException.class);
            assertThat(service.exists(2L)).isFalse();
            assertThat(service.list(2L)).isEmpty();
        });
    }

    @Test
    void warmUpBypassesChecksUntilLoaded() {
        runner.withUserConfiguration(UserServiceConfig.class, LoaderConfig.class).run(context -> {
            ManagedBloomFilter users = context.getBean(BloomFilterRegistry.class).managed("users");
            assertThat(users.state()).isEqualTo(ManagedBloomFilter.State.WARMING_UP);
            assertThat(users.mightContain(999L)).as("bypassed while warming up").isTrue();

            context.getBean(BloomFilterWarmUpRunner.class).run(new DefaultApplicationArguments());

            assertThat(users.state()).isEqualTo(ManagedBloomFilter.State.READY);
            assertThat(users.mightContain(7L)).isTrue();
            assertThat(users.mightContain(999L)).isFalse();
        });
    }

    @Test
    void loaderForUnknownFilterFailsStartup() {
        runner.withBean(BloomFilterLoader.class, () -> new BloomFilterLoader() {
                    public String filterName() {
                        return "nope";
                    }

                    public void load(BloomFilter filter) {
                    }
                })
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("filter 'nope', which is not configured"));
    }

    @Test
    void publishesMetrics() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            MeterRegistry meters = context.getBean(MeterRegistry.class);
            context.getBean(BloomFilterMeterBinder.class).bindTo(meters);
            BloomFilter users = context.getBean(BloomFilterRegistry.class).get("users");
            users.put("a");
            users.mightContain("a");
            users.mightContain("b");

            assertThat(meters.get("bloom.filter.puts").tag("filter", "users").functionCounter().count())
                    .isEqualTo(1);
            assertThat(meters.get("bloom.filter.checks").tags("filter", "users", "result", "absent")
                    .functionCounter().count()).isEqualTo(1);
            assertThat(meters.get("bloom.filter.fill.ratio").gauge().value()).isGreaterThan(0);
        });
    }

    @Test
    void endpointDescribesFiltersWithoutKeys() {
        runner.run(context -> {
            BloomFiltersEndpoint endpoint = new BloomFiltersEndpoint(context.getBean(BloomFilterRegistry.class));
            context.getBean(BloomFilterRegistry.class).get("users").put("secret@example.com");
            Map<String, Object> users = endpoint.filter("users");
            assertThat(users).containsEntry("state", "READY").containsEntry("hashFunctions", 7);
            assertThat(users.toString()).doesNotContain("secret@example.com");
            assertThat(endpoint.filter("missing")).isNull();
        });
    }

    /**
     * The proxy creator Spring Boot registers when AspectJ is absent (same as for @Transactional).
     * It only applies infrastructure-role advisors, so this also verifies our advisor's @Role.
     */
    @Configuration(proxyBeanMethods = false)
    static class InfrastructureProxyConfig {
        @Bean
        static InfrastructureAdvisorAutoProxyCreator infrastructureAdvisorAutoProxyCreator() {
            InfrastructureAdvisorAutoProxyCreator creator = new InfrastructureAdvisorAutoProxyCreator();
            creator.setProxyTargetClass(true);
            return creator;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserServiceConfig {
        @Bean
        UserService userService() {
            return new UserService();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LoaderConfig {
        @Bean
        BloomFilterLoader usersLoader() {
            return new BloomFilterLoader() {
                @Override
                public String filterName() {
                    return "users";
                }

                @Override
                public void load(BloomFilter filter) {
                    filter.putAll(List.of(5L, 6L, 7L));
                }
            };
        }
    }

    static class UserService {

        private final AtomicInteger calls = new AtomicInteger();

        public int calls() {
            return calls.get();
        }

        @BloomCheck(filter = "users", key = "#id")
        public Optional<String> findById(Long id) {
            calls.incrementAndGet();
            return Optional.of("user-" + id);
        }

        @BloomCheck(filter = "users", key = "#id", onMiss = OnMiss.THROW)
        public String getOrThrow(Long id) {
            return "user-" + id;
        }

        @BloomCheck(filter = "users", key = "#p0")
        public boolean exists(Long id) {
            return true;
        }

        @BloomCheck(filter = "users", key = "#id")
        public List<String> list(Long id) {
            return List.of("x");
        }

        @BloomAdd(filter = "users", key = "#result")
        public Long create(Long id) {
            return id;
        }
    }
}
