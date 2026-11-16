package io.github.ncibioussama.bloomfilter.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterRebuilder.Outcome;
import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class BloomFilterRebuildTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BloomFilterAutoConfiguration.class))
            .withPropertyValues("bloom.filters.users.expected-insertions=1000");

    private final ApplicationContextRunner withLoader = runner.withUserConfiguration(UsersTable.class);

    @Test
    void rebuildRemovesDeletedKeys() {
        withLoader.run(context -> {
            warmUp(context.getBean(BloomFilterWarmUpRunner.class));
            UsersTable table = context.getBean(UsersTable.class);
            BloomFilter users = context.getBean(BloomFilterRegistry.class).get("users");
            assertThat(users.mightContain(2L)).isTrue();

            table.ids.remove(2L);
            BloomFilterRebuilder.Result result = context.getBean(BloomFilterRebuilder.class).rebuild("users");

            assertThat(result.outcome()).isEqualTo(Outcome.COMPLETED);
            assertThat(users.mightContain(1L)).isTrue();
            assertThat(users.mightContain(2L)).as("deleted key gone after rebuild").isFalse();
        });
    }

    @Test
    void degradedFilterRecoversAutomatically() {
        withLoader.run(context -> {
            warmUp(context.getBean(BloomFilterWarmUpRunner.class));
            ManagedBloomFilter users = context.getBean(BloomFilterRegistry.class).managed("users");
            users.markDegraded("test");
            assertThat(users.mightContain(999L)).as("bypassed while degraded").isTrue();

            context.getBean(BloomFilterRebuilder.class).recoverDegradedFilters();

            assertThat(users.state()).isEqualTo(ManagedBloomFilter.State.READY);
            assertThat(users.mightContain(999L)).isFalse();
            assertThat(users.rebuildSuccessCount()).isEqualTo(1);
            assertThat(users.lastRebuild()).isNotNull();
        });
    }

    @Test
    void filterWithoutLoaderCannotBeRebuilt() {
        runner.run(context -> {
            BloomFilterRebuilder rebuilder = context.getBean(BloomFilterRebuilder.class);
            assertThat(rebuilder.rebuild("users").outcome()).isEqualTo(Outcome.NO_LOADER);
            ManagedBloomFilter users = context.getBean(BloomFilterRegistry.class).managed("users");
            users.markDegraded("test");
            rebuilder.recoverDegradedFilters();
            assertThat(users.state()).isEqualTo(ManagedBloomFilter.State.DEGRADED);
        });
    }

    @Test
    void failingLoaderKeepsPreviousContent() {
        withLoader.run(context -> {
            warmUp(context.getBean(BloomFilterWarmUpRunner.class));
            context.getBean(UsersTable.class).failing = true;
            ManagedBloomFilter users = context.getBean(BloomFilterRegistry.class).managed("users");

            BloomFilterRebuilder.Result result = context.getBean(BloomFilterRebuilder.class).rebuild("users");

            assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
            assertThat(result.message()).contains("database down");
            assertThat(users.mightContain(1L)).isTrue();
            assertThat(users.rebuildFailureCount()).isEqualTo(1);
            assertThat(users.isRebuilding()).isFalse();
        });
    }

    @Test
    void cronScheduleRunsRebuilds() {
        withLoader.withPropertyValues("bloom.filters.users.rebuild-cron=* * * * * *").run(context -> {
            ManagedBloomFilter users = context.getBean(BloomFilterRegistry.class).managed("users");
            awaitTrue(() -> users.rebuildSuccessCount() > 0);
            assertThat(users.mightContain(1L)).isTrue();
        });
    }

    @Test
    void invalidCronFailsStartup() {
        withLoader.withPropertyValues("bloom.filters.users.rebuild-cron=every night")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("bloom.filters.users.rebuild-cron 'every night' is not a valid cron"));
    }

    @Test
    void cronWithoutLoaderFailsStartup() {
        runner.withPropertyValues("bloom.filters.users.rebuild-cron=0 0 3 * * *")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining("no BloomFilterLoader bean for filter 'users'"));
    }

    @Test
    void endpointRebuildIsDisabledByDefault() {
        withLoader.run(context -> {
            BloomFiltersEndpoint endpoint = endpoint(context.getBean(BloomFilterRegistry.class),
                    context.getBean(BloomFilterRebuilder.class), context.getBean(BloomFilterProperties.class));
            assertThat(endpoint.rebuild("users")).containsEntry("status", "disabled");
            assertThat(endpoint.rebuild("unknown")).isNull();
        });
    }

    @Test
    void endpointCanTriggerRebuildWhenEnabled() {
        withLoader.withPropertyValues("bloom.rebuild.endpoint-enabled=true").run(context -> {
            BloomFilterRegistry registry = context.getBean(BloomFilterRegistry.class);
            BloomFiltersEndpoint endpoint = endpoint(registry, context.getBean(BloomFilterRebuilder.class),
                    context.getBean(BloomFilterProperties.class));

            assertThat(endpoint.rebuild("users")).containsEntry("status", "started");
            awaitTrue(() -> registry.managed("users").rebuildSuccessCount() == 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> rebuild = (Map<String, Object>) endpoint.filter("users").get("rebuild");
            assertThat(rebuild).containsEntry("successes", 1L).containsEntry("running", false);
        });
    }

    private static BloomFiltersEndpoint endpoint(BloomFilterRegistry registry, BloomFilterRebuilder rebuilder,
                                                 BloomFilterProperties properties) {
        return new BloomFiltersEndpoint(registry, rebuilder, properties);
    }

    private static void warmUp(BloomFilterWarmUpRunner runner) {
        runner.run(new DefaultApplicationArguments());
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10s");
            }
            Thread.sleep(50);
        }
    }

    /** A fake "database table" and its loader. */
    @Configuration(proxyBeanMethods = false)
    static class UsersTable implements BloomFilterLoader {

        final Set<Long> ids = ConcurrentHashMap.newKeySet();
        volatile boolean failing;

        UsersTable() {
            ids.addAll(Set.of(1L, 2L, 3L));
        }

        @Override
        public String filterName() {
            return "users";
        }

        @Override
        public void load(BloomFilter filter) {
            if (failing) {
                throw new IllegalStateException("database down");
            }
            filter.putAll(ids);
        }

        @Bean
        static String marker() {
            return "users-table";
        }
    }
}
