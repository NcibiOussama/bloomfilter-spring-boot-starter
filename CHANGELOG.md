# Changelog

All notable changes to this project are documented here. The project follows [Semantic Versioning](https://semver.org).

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

[0.2.0]: https://github.com/NcibiOussama/bloomfilter-spring-boot-starter/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/NcibiOussama/bloomfilter-spring-boot-starter/releases/tag/v0.1.0
