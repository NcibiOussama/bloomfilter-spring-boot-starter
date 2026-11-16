package io.github.ncibioussama.bloomfilter.autoconfigure;

import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterProperties.Backend;
import io.github.ncibioussama.bloomfilter.autoconfigure.BloomFilterProperties.Security;
import io.github.ncibioussama.bloomfilter.core.HashStrategy;
import io.github.ncibioussama.bloomfilter.core.Murmur3HashStrategy;
import io.github.ncibioussama.bloomfilter.core.SipHashStrategy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.util.StringUtils;

/**
 * Chooses the hash strategy for a backend from {@code bloom.security.*}.
 */
final class HashStrategies {

    private static final Log log = LogFactory.getLog(HashStrategies.class);
    private static final AtomicBoolean warned = new AtomicBoolean();

    private HashStrategies() {
    }

    static HashStrategy forBackend(Backend backend, Security security) {
        boolean hasSecret = StringUtils.hasText(security.getSecret());
        boolean shared = backend != Backend.MEMORY;
        return switch (security.getHashAlgorithm()) {
            case MURMUR3 -> new Murmur3HashStrategy();
            case SIPHASH -> {
                if (hasSecret) {
                    yield sipHash(security.getSecret());
                }
                if (shared) {
                    throw new IllegalStateException("bloom.security.hash-algorithm=siphash with the '"
                            + backend.name().toLowerCase() + "' backend requires bloom.security.secret "
                            + "(at least 16 characters, identical on every instance).");
                }
                yield SipHashStrategy.withRandomKey();
            }
            case AUTO -> {
                if (hasSecret) {
                    yield sipHash(security.getSecret());
                }
                if (!shared) {
                    yield SipHashStrategy.withRandomKey();
                }
                if (warned.compareAndSet(false, true)) {
                    log.warn("Bloom filters on the '" + backend.name().toLowerCase() + "' backend use "
                            + "MurmurHash3 because bloom.security.secret is not set. If filter keys come from "
                            + "user input, set bloom.security.secret to protect against hash-flooding attacks.");
                }
                yield new Murmur3HashStrategy();
            }
        };
    }

    private static HashStrategy sipHash(String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < SipHashStrategy.MIN_SECRET_BYTES) {
            throw new IllegalStateException("bloom.security.secret must be at least "
                    + SipHashStrategy.MIN_SECRET_BYTES + " bytes long.");
        }
        return SipHashStrategy.withSecret(secret);
    }
}
