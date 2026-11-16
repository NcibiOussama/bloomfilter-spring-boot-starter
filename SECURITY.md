# Security Policy

## Supported versions

The latest released minor version receives security fixes.

## Reporting a vulnerability

Please **do not open a public issue**. Report vulnerabilities privately through
[GitHub Security Advisories](https://github.com/NcibiOussama/bloomfilter-spring-boot-starter/security/advisories/new).

You will get an answer within 7 days. Once a fix is released, the advisory is published and you are credited
unless you prefer otherwise.

## Security design

- **Hash flooding:** in-memory filters use SipHash-2-4 with a random key by default. Shared (Redis) filters
  use SipHash when `bloom.security.secret` is set; otherwise MurmurHash3 with a startup warning.
- **No false "not found" on failure:** backend outages, lost writes and unfinished warm-up make the filter
  answer "might contain", so the application falls back to its source of truth.
- **No key leakage:** keys never appear in exception messages, logs, metrics or the actuator endpoint.
- **Resource limits:** filter size (`bloom.max-bits-per-filter`) and key size (`bloom.max-key-bytes`) are capped.
- **No Java deserialization** anywhere in the library.
- **Supply chain:** minimal dependencies (the core module has none), signed artifacts, CycloneDX SBOM per
  release, Dependabot and dependency review on every pull request.
