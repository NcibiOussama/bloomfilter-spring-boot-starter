package io.github.ncibioussama.bloomfilter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds a key to a Bloom filter after the method returns successfully.
 *
 * <pre>
 * &#64;BloomAdd(filter = "users", key = "#result.id")
 * public User create(CreateUserRequest request) { ... }
 * </pre>
 *
 * Not executed when the method throws, or when a non-void method returns {@code null} or an empty
 * {@code Optional}. If the key expression yields a collection or array, every element is added.
 * A failure to add never fails the method; the filter switches to a safe bypass mode instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BloomAdd {

    /**
     * @return name of the filter, as configured under {@code bloom.filters.<name>}
     */
    String filter();

    /**
     * SpEL expression for the key. {@code #result} is the return value (unwrapped if it is an
     * {@code Optional}); method arguments are available by name, for example {@code "#request.email"}.
     *
     * @return the key expression
     */
    String key();
}
