# Bloom Filter Spring Boot Starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.ncibioussama/bloomfilter-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.ncibioussama/bloomfilter-spring-boot-starter)
[![CI](https://github.com/NcibiOussama/bloomfilter-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/NcibiOussama/bloomfilter-spring-boot-starter/actions/workflows/ci.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

Secure, observable, declarative Bloom filters for Spring Boot 3.

- **One dependency, a few lines of YAML.** In-memory or Redis backend, switchable by configuration.
- **Annotations:** `@BloomCheck` short-circuits lookups of keys that definitely don't exist; `@BloomAdd` keeps
  the filter up to date.
- **Safe by design:** never answers a wrong "not found". Backend outages, lost writes and warm-up all fall back
  to "might contain".
- **Secure hashing:** keyed SipHash protects against hash-flooding attacks.
- **Observable:** Micrometer metrics (including measured false positives) and an `/actuator/bloomfilters` endpoint.

## Install

```xml
<dependency>
  <groupId>io.github.ncibioussama</groupId>
  <artifactId>bloomfilter-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'io.github.ncibioussama:bloomfilter-spring-boot-starter:0.1.0'
```

Requires Java 17+ and Spring Boot 3.x.

## Configure

```yaml
bloom:
  filters:
    users:
      expected-insertions: 1000000   # sized for 1M keys
      false-positive-rate: 0.01      # 1% → 1.14 MiB, 7 hash functions
```

## Use

```java
@Service
class UserService {

    // If the id was never added, returns Optional.empty() without touching the database.
    @BloomCheck(filter = "users", key = "#id")
    public Optional<User> findById(Long id) { return repository.findById(id); }

    // Throws BloomFilterMissException → HTTP 404
    @BloomCheck(filter = "users", key = "#email", onMiss = OnMiss.THROW)
    public User getByEmail(String email) { ... }

    @BloomAdd(filter = "users", key = "#result.id")
    public User create(CreateUserRequest request) { ... }
}
```

Or use the registry programmatically:

```java
BloomFilter users = registry.get("users");
if (!users.mightContain(id)) {
    return Optional.empty();   // definitely absent
}
```

Keys are compared by a canonical form, so `42`, `42L` and `"42"` are the same key. Supported key types:
`String`, integral numbers, `BigDecimal`, `UUID`, enums and `byte[]`.

## Load existing data at startup

```java
@Component
class UserIdLoader implements BloomFilterLoader {
    private final UserRepository users;

    public String filterName() { return "users"; }

    @Transactional(readOnly = true)
    public void load(BloomFilter filter) {
        try (Stream<Long> ids = users.streamAllIds()) {
            filter.putAll(ids::iterator);
        }
    }
}
```

Until the loader finishes, the filter answers "might contain" for every key. Spring Boot marks the application
ready only after loading, so readiness probes wait for it. If loading fails, startup fails.

## Share a filter across instances with Redis

Add `spring-boot-starter-data-redis`, then:

```yaml
spring.data.redis.host: localhost
bloom:
  default-backend: redis
  security:
    secret: ${BLOOM_SECRET}     # ≥16 chars, same on every instance
  redis:
    key-prefix: "bloom:my-app:"
  filters:
    users:
      expected-insertions: 10000000
      false-positive-rate: 0.001
      failure-mode: fail-open   # or 'throw'
```

Each check or insert is a single atomic Lua call. With the default `warm-up.mode=if-empty`, only the first
instance loads a shared filter.

## Security

| Threat | Protection |
|---|---|
| Hash flooding (attacker crafts keys that fill the filter) | Keyed SipHash-2-4. Random key for in-memory filters, `bloom.security.secret` for Redis |
| Wrong "not found" when Redis is down or a write was lost | Fail-open, then the filter switches to bypass mode (`DEGRADED`) |
| Wrong "not found" during startup | Checks are bypassed until the loader finishes |
| Misconfiguration exhausting memory | `bloom.max-bits-per-filter` (default 512 MiB) and `bloom.max-key-bytes` (default 4096) |
| Keys leaking (emails, tokens) | Keys never appear in logs, exceptions, metrics or the endpoint |
| Changing size or secret on a shared filter | The Redis key embeds a configuration fingerprint, so a new empty filter is used and reloaded |

See [SECURITY.md](SECURITY.md) to report a vulnerability.

## Observe

| Metric | Tags |
|---|---|
| `bloom.filter.checks` | `filter`, `backend`, `result` = `might_contain` / `absent` / `bypassed` |
| `bloom.filter.puts`, `bloom.filter.false.positives`, `bloom.filter.backend.errors` | `filter`, `backend` |
| `bloom.filter.fill.ratio`, `bloom.filter.estimated.false.positive.rate`, `bloom.filter.approximate.elements`, `bloom.filter.ready` | `filter`, `backend` |

`management.endpoints.web.exposure.include=bloomfilters` exposes `/actuator/bloomfilters` and
`/actuator/bloomfilters/{name}` (sizing, fill and counters, never keys).

## All properties

| Property | Default | Description |
|---|---|---|
| `bloom.enabled` | `true` | Enable auto-configuration |
| `bloom.default-backend` | `memory` | `memory` or `redis` |
| `bloom.filters.<name>.expected-insertions` | `1000000` | Number of keys to size for |
| `bloom.filters.<name>.false-positive-rate` | `0.01` | Target false-positive rate |
| `bloom.filters.<name>.backend` | default backend | Per-filter backend |
| `bloom.filters.<name>.failure-mode` | `fail-open` | `fail-open` or `throw` |
| `bloom.security.hash-algorithm` | `auto` | `auto`, `siphash` or `murmur3` |
| `bloom.security.secret` | none | SipHash key, ≥16 bytes |
| `bloom.redis.key-prefix` | `bloom:` | Redis key prefix |
| `bloom.redis.batch-size` | `500` | Keys per Redis call in bulk inserts |
| `bloom.warm-up.enabled` | `true` | Run `BloomFilterLoader` beans |
| `bloom.warm-up.mode` | `if-empty` | `if-empty` or `always` |
| `bloom.max-key-bytes` | `4096` | Reject larger keys |
| `bloom.max-bits-per-filter` | `4294967296` | Reject larger filters |

## Limitations (v0.1)

- Keys cannot be removed (standard Bloom filter). Rebuild with atomic swap is planned for v0.2.
- A `DEGRADED` filter stays in bypass mode until restart.
- Annotations work on calls through the Spring proxy, not on self-invocation (same as `@Transactional`).

## Build

```bash
./mvnw verify          # Redis tests run when Docker is available
```

## License

[Apache License 2.0](LICENSE)
