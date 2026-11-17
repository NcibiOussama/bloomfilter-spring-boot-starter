# Bloom filter starter: demo application

A small Spring Boot 4 user API that uses
[`bloomfilter-spring-boot-starter`](../../README.md) from Maven Central, the way your application would.
Its tests check every feature end to end.

| Feature | Where |
|---|---|
| `@BloomCheck` returning `Optional.empty()` / `false` without a query | `UserService.findById`, `isEmailTaken` |
| `@BloomCheck(onMiss = THROW)` → HTTP 404 | `UserService.getByEmail` |
| `@BloomAdd` and programmatic `registry.get(..).put(..)` | `UserService.create` |
| Warm-up from the database with `BloomFilterLoader` | `UserFilterLoaders` |
| Nightly `rebuild-cron`, rebuild via actuator | `application.yml` |
| `/actuator/bloomfilters` and Micrometer metrics | `application.yml` |
| Shared filters in Redis, keyed SipHash | `application-redis.yml` |
| Suspend / resume around a SQL migration | `MigrationTest` |

## Run it

From the repository root (needs Java 21):

```bash
./mvnw -f examples/demo/pom.xml spring-boot:run
```

Then:

```bash
curl localhost:8080/users/1                     # Alice, found
curl -i localhost:8080/users/424242             # 404: the filter rejected it, no query
curl localhost:8080/users/database-lookups      # unchanged after the 404
curl "localhost:8080/users/email-taken?email=new@example.com"   # {"taken":false}, no query
curl -H 'Content-Type: application/json' -d '{"email":"dave@example.com","name":"Dave"}' localhost:8080/users
curl localhost:8080/actuator/bloomfilters       # sizing, fill ratio, counters, rebuild status
curl -X POST localhost:8080/actuator/bloomfilters/users   # rebuild now (drops deleted ids)
curl "localhost:8080/actuator/metrics/bloom.filter.checks?tag=result:absent"
```

### With Redis (several instances share the filters)

```bash
docker run -d -p 6379:6379 redis:7.4-alpine
export BLOOM_SECURITY_SECRET="$(openssl rand -hex 32)"   # same value on every instance
./mvnw -f examples/demo/pom.xml spring-boot:run -Dspring-boot.run.profiles=redis
```

## What the tests prove

`./mvnw -f examples/demo/pom.xml verify` (the Redis tests need Docker and are skipped without it):

- **`InMemoryFilterTest`**: warm-up finishes before the app reports ready. Unknown ids and emails are
  rejected without a database query. New users are found immediately. A deleted user counts as a false
  positive until a rebuild removes it. The actuator endpoint and metrics report all of this.
- **`RedisSharedFilterTest`**: a second instance reuses the filter the first one loaded (warm-up runs
  once), and keys added on either instance are visible to both. An instance with a different secret uses its
  own filter instead of misreading the shared one. 60 concurrent sign-ups during a rebuild are all found
  afterwards. Redis holds only bitmaps, never emails or ids.
- **`RedisOutageTest`**: while Redis is paused, writes still succeed and no lookup returns a wrong 404,
  because the degraded filter is bypassed. Once Redis is back, the filter rebuilds itself from the database,
  including the user created during the outage, and rejects unknown ids again.
- **`RedisFailClosedTest`**: with `failure-mode: throw`, an outage returns an error (5xx), never a 404.
- **`MigrationTest`**: a row inserted with plain SQL is wrongly "not found" until a rebuild. With
  `POST .../users/suspend` before and `.../resume` after the migration, every answer stays correct, on both
  instances sharing Redis.
- **`SecurityTest`**: hostile input over HTTP. Keys above `max-key-bytes` cause no error and cannot switch the
  filter off. SpEL, placeholder and control-character payloads are treated as plain data. Emails never appear
  in responses, error bodies, `/actuator/bloomfilters`, metrics or logs.
- **`SecureDefaultsTest`**: the rebuild endpoint is disabled by default. A short secret, or `siphash` on Redis
  without a secret, fails startup.

To test unreleased changes to the starter, install it first and override the version:

```bash
./mvnw install -DskipTests
./mvnw -f examples/demo/pom.xml verify -Dbloomfilter.version=0.3.0-SNAPSHOT
```
