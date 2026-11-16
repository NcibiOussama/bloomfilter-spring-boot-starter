package io.github.ncibioussama.bloomfilter.annotation;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by {@link BloomCheck} with {@link OnMiss#THROW} when the key is definitely absent.
 * Spring MVC answers HTTP 404. The key is deliberately not part of the message.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class BloomFilterMissException extends RuntimeException {

    private final String filterName;

    /**
     * @param filterName the filter that rejected the key
     */
    public BloomFilterMissException(String filterName) {
        super("Not found");
        this.filterName = filterName;
    }

    /**
     * @return the filter that rejected the key
     */
    public String getFilterName() {
        return filterName;
    }
}
