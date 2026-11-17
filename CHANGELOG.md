# Changelog

All notable changes to this project are documented here. The project follows [Semantic Versioning](https://semver.org).

## [0.4.0]

### Added
- **Suspend and resume**, for writes that bypass the application (SQL migrations, bulk imports, other
  services): `BloomFilterRebuilder.suspend(name)` makes every check answer "might contain", on every instance
  sharing a Redis filter, until `resume(name)` rebuilds the filter from its `BloomFilterLoader`. Scheduled and
  automatic rebuilds never end a suspension, and `resume` keeps a suspension started in the meantime.
  Also `POST /actuator/bloomfilters/{name}/suspend` and `.../resume` (with `bloom.rebuild.endpoint-enabled=true`),
  and a `suspended` field in `/actuator/bloomfilters`.
- `Suspendable` API in `bloomfilter-core`, implemented by the in-memory and Redis backends.
- README: "Writes that bypass your application", covering entity listeners, batch jobs, CDC and migrations.

## [0.3.1]

### Fixed
- **Oversized keys could switch a filter off or block startup.** A key above `bloom.max-key-bytes` made `put`
  fail and marked the filter `DEGRADED`; the automatic rebuild then failed on the same row, so the filter stayed
  bypassed, and warm-up failed at the next restart. A `@BloomCheck` with such a key returned HTTP 500. Oversized
  keys are now never stored and always answer "might contain" (the caller asks its source of truth), on every
  path: `put`, `putAll`, warm-up, rebuilds, both backends.
- Invalid keys (`null`, unsupported types) still throw, but no longer mark the filter `DEGRADED`.
- The `BloomFilterLoader` example (`filter.putAll(ids::iterator)`) did not compile; it now casts to `Iterable<Long>`.

### Added
- `KeyTooLargeException` (an `IllegalArgumentException`) in `bloomfilter-core`, thrown by `DefaultKeyEncoder`.
- `examples/demo`: a Spring Boot 4 application with end-to-end, security and Redis integration tests, run in CI
  against every commit.

## [0.3.0]

### Added
- **Rebuilds with atomic swap.** A filter is reloaded from its `BloomFilterLoader` into a fresh filter and swapped
  in without a window of wrong answers: concurrent writes go to both filters. Removes deleted keys.
  - Scheduled per filter: `bloom.filters.<name>.rebuild-cron`
  - On demand: `BloomFilterRebuilder.rebuild(name)`, or `POST /actuator/bloomfilters/{name}` when
    `bloom.rebuild.endpoint-enabled=true`
  - Redis: built under a `:next` key, swapped with one atomic `RENAME` for all instances; a Redis lock lets
    only one instance rebuild, and a rebuild exceeding `bloom.rebuild.timeout` is cancelled safely
- **Self-healing:** `DEGRADED` filters are rebuilt automatically every `bloom.rebuild.recovery-interval`.
- Rebuild status in `/actuator/bloomfilters` and the `bloom.filter.rebuilds` metric.
- `Rebuildable` / `RebuildSession` API in `bloomfilter-core`; `BloomFilter.isPopulated()` / `markPopulated()`.

### Fixed
- Redis warm-up in `if-empty` mode: an instance starting while another was still loading a shared filter
  considered it loaded and could answer "not found" for keys not loaded yet. Loading is now skipped only after a
  load has completed.
- A filter whose backend was unavailable during warm-up is now marked `DEGRADED` and recovers automatically.

### Changed
- Redis keys now use a Redis Cluster hash tag: `<prefix>{<name>:<fingerprint>}`. Existing Redis filters are
  reloaded once automatically after upgrading; the old keys (`<prefix><name>:<fingerprint>`) can be deleted.

## [0.2.0]

### Added
- **Spring Boot 4 support.** The same artifact works on Spring Boot 3.5 and 4.x (Spring Framework 6 and 7).
  CI builds and tests every change against both.
- Tests for the Redis backend wiring and the actuator endpoint registration.

### Fixed
- Filter sizes are logged in KiB for small filters (`1.2 KiB` instead of `0,00 MiB`), and numbers in logs and
  error messages no longer depend on the system locale.

## [0.1.0]

First public release: in-memory and Redis backends, `@BloomCheck` / `@BloomAdd`, startup loaders, keyed
SipHash, fail-open safety, Micrometer metrics and the `/actuator/bloomfilters` endpoint.

[0.3.0]: https://github.com/NcibiOussama/bloomfilter-spring-boot-starter/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/NcibiOussama/bloomfilter-spring-boot-starter/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/NcibiOussama/bloomfilter-spring-boot-starter/releases/tag/v0.1.0
