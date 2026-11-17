package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.core.BloomFilter;

/**
 * Fills a filter with existing keys at startup. Declare one bean per filter:
 *
 * <pre>
 * &#64;Component
 * class UserEmailLoader implements BloomFilterLoader {
 *     private final UserRepository users;
 *
 *     public String filterName() { return "user-emails"; }
 *
 *     &#64;Transactional(readOnly = true)
 *     public void load(BloomFilter filter) {
 *         try (Stream&lt;String&gt; emails = users.streamAllEmails()) {
 *             filter.putAll((Iterable&lt;String&gt;) emails::iterator);
 *         }
 *     }
 * }
 * </pre>
 * Until loading finishes the filter answers "might contain" for every key, so no request gets a wrong
 * "not found". If loading fails, the application fails to start.
 */
public interface BloomFilterLoader {

    /**
     * @return the name of the filter to fill
     */
    String filterName();

    /**
     * Adds all existing keys to the filter.
     *
     * @param filter the filter
     * @throws Exception if loading fails
     */
    void load(BloomFilter filter) throws Exception;
}
