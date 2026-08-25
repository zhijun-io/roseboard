# Roseboard Cache Consistency Implementation Plan

**Spec:** [`007-cache-consistency-spec.md`](007-cache-consistency-spec.md)

## Risks / Dependencies

- Requires Java 21 and the existing PostgreSQL/Redis test infrastructure.
- Cache behavior must preserve existing Device, Credentials, Attribute, Telemetry, HTTP, MQTT and WebSocket contracts.
- `ApplicationEventPublisher` events currently exist inside transactional write paths; cache eviction listeners must use `AFTER_COMMIT` semantics and must not change existing WebSocket event behavior.
- Redis 是共享缓存后端，不依赖 Pub/Sub 或持久化 eviction 队列；Redis eviction 删除共享 key 后，其他节点自然看到 cache miss。
- The cache backend must remain replaceable: Caffeine is the single-node default, Redis is the shared multi-node option.

## Slice 1: TB-aligned cache backend, configuration, and transactional cache fill

- Goal: Enable configurable Caffeine/Redis cache-aside reads with TB-aligned cache specs, bounded TTL/size, tenant-safe keys, and per-key cache fill commit/rollback semantics.
- Acceptance: AC-1, AC-7, AC-8
- Depends on: None
- Test or proof: Add failing cache component tests proving default Caffeine configuration, `maxSize=0` disable behavior, TTL/max-size configuration, cache miss database fallback, cache serialization failure propagation, and cache-fill rollback when the database supplier fails.
- Implementation outline:
  - Add the minimal Spring Cache/Caffeine and Redis cache infrastructure required by the existing Spring Boot stack.
  - Define the five cache specs: `deviceCredentials`, `devices`, `deviceProfiles`, `attributes`, and `tsLatest`, with TB-aligned defaults and enable/TTL/max-size settings.
  - Add a versioned key builder with tenant/device/scope boundaries and ThingsBoard-compatible credential lookup keys; encode sensitive lookup material internally without exposing it as a readable key.
  - Add a small cache abstraction for cache-aside reads, null markers, and bounded values; concurrent misses may fall back independently, and the cache never becomes the database source of truth.
  - Ensure cache misses and disabled caches fall back to PostgreSQL; cache backend exceptions never return stale or fabricated values.
- Verification: `mvn -Dtest=CacheConfigurationTest,CacheAsideServiceTest,CacheEvictionServiceTest,CacheKeyBuilderTest test`
- Done: true

## Slice 2: Device, Profile, and Credentials cache with post-commit eviction

- Goal: Cache Device/Profile reads and Credentials authentication results while preserving TB credential positive/negative caching and strict write invalidation.
- Acceptance: AC-2, AC-3, AC-5, AC-8
- Depends on: Slice 1
- Test or proof: Add failing integration tests that read through cache, update/rotate/revoke/delete the entity inside a transaction, verify rollback does not publish eviction, verify commit invalidates current-node entries, and verify both old/new credential lookup keys are evicted.
- Implementation outline:
  - Route the eligible Device and Device Profile reads through the cache-aside abstraction.
  - Route credential lookup by credentialsId through the cache, including the TB-compatible null-result marker.
  - Cache only non-sensitive Principal/entity projections; never cache plaintext credential material.
  - Add typed cache eviction events carrying entity identity, tenant boundary, credential old/new lookup keys, and version.
  - Register `AFTER_COMMIT` listeners for Device, Profile, and Credentials eviction; keep existing domain/WebSocket events unchanged.
  - Add defensive eviction behavior for writes executed without an active transaction, matching TB behavior.
- Verification: `mvn test`
- Done: true

## Slice 3: Attribute and Telemetry latest cache invalidation

- Goal: Cache current Attribute and Telemetry latest reads and invalidate exactly the affected tenant/device/scope/key after successful writes.
- Acceptance: AC-4, AC-5, AC-8
- Depends on: Slice 1
- Test or proof: Add failing integration tests for single and batch Attribute writes, Attribute deletion, single and batch Telemetry writes, latest deletion/rewrite, scope separation, device separation, tenant separation, and rollback behavior.
- Implementation outline:
  - Identify the existing current-value/latest read methods without changing historical Telemetry query behavior.
  - Add cache-aside reads for Attribute current values and Telemetry latest values.
  - Register eviction events inside the existing write transactions; execute cache eviction only through the `AFTER_COMMIT` listener.
  - Ensure batch operations invalidate every affected key once or through an equivalent idempotent batch event.
  - Ensure retention deletion invalidates latest entries when the deleted range can contain the current latest value.
- Verification: `mvn test`
- Done: true

## Slice 4: Redis shared cache and cross-node eviction

- Goal: Make Redis mode share cache values and apply ThingsBoard-compatible transactional cache eviction across application nodes without introducing a second event bus.
- Acceptance: AC-5, AC-6, AC-7, AC-8
- Depends on: Slice 2, Slice 3
- Test or proof: Add failing two-context integration tests backed by the existing Redis/Valkey service: node A commits a write, its `AFTER_COMMIT` listener deletes the shared key, and node B reads the new value; repeat with cache miss fallback and Redis backend failure without rolling back the database write.
- Implementation outline:
  - Configure Redis as the shared `TbTransactionalCache`-equivalent backend when selected.
  - Implement per-key cache fill commit/rollback semantics for Redis, including null markers.
  - Keep Redis values and version fences in separate keys; guard versioned writes and evictions with small atomic Lua scripts.
  - Register typed eviction events inside database transactions and handle them after commit.
  - Delete shared Redis keys idempotently; do not add Pub/Sub, a durable eviction queue, or a second event bus.
  - Cache fill transaction failures propagate after rollback, matching the TB transaction contract; database writes remain governed by their own transaction.
- Verification: `mvn -Dtest=CacheConfigurationTest,CacheAsideServiceTest,CacheEvictionServiceTest,CacheKeyBuilderTest test`
- Done: false

## Slice 5: Full regression and contract verification

- Goal: Demonstrate that TB-aligned caching does not change existing domain, transport, API, or WebSocket behavior.
- Acceptance: AC-9
- Depends on: Slice 1, Slice 2, Slice 3, Slice 4
- Test or proof: Run the full Maven suite with the default cache configuration and the cache-disabled configuration used by the cache tests.
- Implementation outline:
  - Fix only regressions caused by the cache integration.
  - Verify no cache business tables or API contract changes were introduced.
  - Record each completed slice and command result in this plan.
- Verification: `mvn test`
- Done: true

## Execution Notes

- `mvn -Dtest=CacheConfigurationTest,CacheAsideServiceTest,CacheEvictionServiceTest,CacheKeyBuilderTest test` passed: 16 tests after removing unused direct-versioned backend writes.
- `mvn test` passed: 156 tests.
- `bash -n scripts/device-lifecycle.sh` and `./scripts/device-lifecycle.sh --help` passed.
- Redis cross-node integration remains unverified in this environment: `redis-cli` is not installed; no live Redis-specific integration test was run.


- Device deletion now snapshots related Attribute and Telemetry latest keys before deletion and registers their post-commit eviction events.
- `mvn -Dtest=DeviceServiceTest,CacheAsideServiceTest,CacheEvictionServiceTest,CacheKeyBuilderTest test` passed: 15 tests.
- `mvn test` passed: 157 tests after the device-deletion eviction fix.