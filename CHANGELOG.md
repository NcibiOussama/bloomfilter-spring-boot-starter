# Changelog

All notable changes to this project are documented here. The project follows [Semantic Versioning](https://semver.org).

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
