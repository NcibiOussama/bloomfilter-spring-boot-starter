package io.github.ncibioussama.demo.user;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterLoader;
import io.github.ncibioussama.bloomfilter.core.BloomFilter;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fill the filters from the database at startup and on every rebuild. One loader per filter.
 */
@Configuration(proxyBeanMethods = false)
public class UserFilterLoaders {

    @Bean
    UserIdLoader userIdLoader(UserRepository users) {
        return new UserIdLoader(users);
    }

    @Bean
    UserEmailLoader userEmailLoader(UserRepository users) {
        return new UserEmailLoader(users);
    }

    public static class UserIdLoader implements BloomFilterLoader {

        private final UserRepository users;
        private final AtomicInteger runs = new AtomicInteger();

        UserIdLoader(UserRepository users) {
            this.users = users;
        }

        @Override
        public String filterName() {
            return "users";
        }

        @Override
        @Transactional(readOnly = true)
        public void load(BloomFilter filter) {
            runs.incrementAndGet();
            try (Stream<Long> ids = users.streamAllIds()) {
                filter.putAll((Iterable<Long>) ids::iterator);
            }
        }

        /** How often this instance ran the loader (warm-up and rebuilds). */
        public int runs() {
            return runs.get();
        }
    }

    public static class UserEmailLoader implements BloomFilterLoader {

        private final UserRepository users;

        UserEmailLoader(UserRepository users) {
            this.users = users;
        }

        @Override
        public String filterName() {
            return "emails";
        }

        @Override
        @Transactional(readOnly = true)
        public void load(BloomFilter filter) {
            try (Stream<String> emails = users.streamAllEmails()) {
                filter.putAll((Iterable<String>) emails::iterator);
            }
        }
    }
}
