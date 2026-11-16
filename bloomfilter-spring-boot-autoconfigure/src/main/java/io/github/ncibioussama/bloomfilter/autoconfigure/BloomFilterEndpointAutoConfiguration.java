package io.github.ncibioussama.bloomfilter.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@code bloomfilters} actuator endpoint when Spring Boot Actuator is present.
 */
@AutoConfiguration(after = BloomFilterAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint")
@ConditionalOnBean(BloomFilterRegistry.class)
public class BloomFilterEndpointAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    BloomFiltersEndpoint bloomFiltersEndpoint(BloomFilterRegistry registry,
                                              ObjectProvider<BloomFilterRebuilder> rebuilder,
                                              BloomFilterProperties properties) {
        return new BloomFiltersEndpoint(registry, rebuilder.getIfAvailable(), properties);
    }
}
