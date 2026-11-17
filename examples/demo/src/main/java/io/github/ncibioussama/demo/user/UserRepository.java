package io.github.ncibioussama.demo.user;

import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /** Streams ids without loading entities, so warm-up and rebuilds stay cheap on large tables. */
    @Query("select u.id from User u")
    Stream<Long> streamAllIds();

    @Query("select u.email from User u")
    Stream<String> streamAllEmails();
}
