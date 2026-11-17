package io.github.ncibioussama.demo.user;

import io.github.ncibioussama.bloomfilter.annotation.BloomAdd;
import io.github.ncibioussama.bloomfilter.annotation.BloomCheck;
import io.github.ncibioussama.bloomfilter.annotation.OnMiss;
import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterRegistry;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserRepository repository;
    private final BloomFilterRegistry filters;

    /** Counts queries that actually reached the database, to make the filter's effect visible. */
    private final AtomicLong databaseLookups = new AtomicLong();

    public UserService(UserRepository repository, BloomFilterRegistry filters) {
        this.repository = repository;
        this.filters = filters;
    }

    /** Unknown ids return {@code Optional.empty()} without touching the database. */
    @BloomCheck(filter = "users", key = "#id")
    @Transactional(readOnly = true)
    public Optional<User> findById(long id) {
        databaseLookups.incrementAndGet();
        return repository.findById(id);
    }

    /** Unknown emails throw {@code BloomFilterMissException}, which Spring MVC turns into HTTP 404. */
    @BloomCheck(filter = "emails", key = "#email", onMiss = OnMiss.THROW)
    @Transactional(readOnly = true)
    public User getByEmail(String email) {
        databaseLookups.incrementAndGet();
        return repository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** Sign-up form check: an email that is definitely free returns {@code false} without a query. */
    @BloomCheck(filter = "emails", key = "#email")
    @Transactional(readOnly = true)
    public boolean isEmailTaken(String email) {
        databaseLookups.incrementAndGet();
        return repository.existsByEmail(email);
    }

    /**
     * The id is added declaratively with {@code @BloomAdd}, the email programmatically through the registry.
     * If the transaction rolls back, the keys stay in the filters: that only costs a false positive, never a
     * wrong "not found".
     */
    @BloomAdd(filter = "users", key = "#result.id")
    @Transactional
    public User create(String email, String name) {
        User user = repository.save(new User(email, name));
        filters.get("emails").put(email);
        return user;
    }

    /**
     * Bloom filters cannot remove keys: the deleted id stays "maybe present" (a false positive) until the
     * next rebuild.
     */
    @Transactional
    public boolean delete(long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        return true;
    }

    public long databaseLookups() {
        return databaseLookups.get();
    }
}
