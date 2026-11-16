package io.github.ncibioussama.bloomfilter.annotation;

/**
 * Behaviour of {@link BloomCheck} when the key is definitely absent.
 */
public enum OnMiss {

    /**
     * Return an empty value matching the return type: {@code Optional.empty()}, an empty
     * {@code List}/{@code Set}/{@code Map}/{@code Stream}, {@code false}, an empty {@code Mono}/{@code Flux},
     * a completed future of {@code null}, or {@code null} for other object types.
     */
    RETURN_EMPTY,

    /**
     * Throw {@link BloomFilterMissException}, which Spring MVC maps to HTTP 404.
     */
    THROW
}
