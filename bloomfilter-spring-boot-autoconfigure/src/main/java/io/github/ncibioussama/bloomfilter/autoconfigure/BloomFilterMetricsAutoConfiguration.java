package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers Micrometer metrics when Micrometer is on the classpath.
 */
@AutoConfiguration(after = BloomFilterAutoConfiguration.class)
@ConditionalOnClass(MeterBinder.class)
@ConditionalOnBean(BloomFilterRegistry.class)
public class BloomFilterMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    BloomFilterMeterBinder bloomFilterMeterBinder(BloomFilterRegistry registry) {
        return new BloomFilterMeterBinder(registry);
    }
}
