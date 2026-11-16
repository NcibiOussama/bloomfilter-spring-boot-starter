package io.github.ncibioussama.bloomfilter.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Checks a Bloom filter before running the method. If the key is definitely absent the method is not
 * called and an empty result is returned (or {@link BloomFilterMissException} is thrown).
 *
 * <pre>
 * &#64;BloomCheck(filter = "users", key = "#id")
 * public Optional&lt;User&gt; findById(Long id) { ... }
 * </pre>
 *
 * Works on Spring beans called through their proxy (not on self-invocation), like {@code @Transactional}.
 * The check runs before transactions and caching, so rejected calls never open a transaction.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BloomCheck {

    /**
     * @return name of the filter, as configured under {@code bloom.filters.<name>}
     */
    String filter();

    /**
     * SpEL expression for the key, evaluated against the method arguments: {@code "#id"},
     * {@code "#request.email"}, or {@code "#p0"} if parameter names are not available.
     * A {@code null} key skips the check.
     *
     * @return the key expression
     */
    String key();

    /**
     * @return what to do when the key is definitely absent
     */
    OnMiss onMiss() default OnMiss.RETURN_EMPTY;

    /**
     * When {@code true}, a call that passed the filter but returned an empty result (null, empty Optional,
     * empty collection or {@code false}) is counted as a false positive in metrics.
     *
     * @return whether to track false positives
     */
    boolean trackFalsePositives() default true;
}
