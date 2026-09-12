# SKILL-239 subtask 2: Separate database readiness and projection outcomes

## Scope

Fix F-002 and F-003 at the existing persistence boundaries. DatabaseRuntime.ensureDatabase currently runs on every write acquisition. A second open of an empty, already initialized database executed 250 SQL statements in the investigation. DecompositionManifestWriter separately erases filesystem failures by returning null after database state has committed.

Own SQLiteDatabaseSessionFactory, DatabaseRuntime, the affected existing migrations and repair functions, DecompositionManifestWriter, DecompositionManifestProjectionWriter, and their goal/workflow callers. Keep semantic workflow transitions in the application or engine. An adapter may preserve an atomic multi-repository write without becoming a new workflow-policy layer.

## Acceptance criteria

1. One bound database lifecycle distinguishes schema readiness from opening a connection. After readiness succeeds, repeated transaction and selfManagedWrite calls do not execute historical migration bodies, data backfills, or full-table repair scans. They still set required connection pragmas and use the correct transaction mode.
2. Initialization failure cannot publish readiness. Separate processes cannot race migrations into a partially usable schema. A missing, recreated, or reset database at the same path invalidates readiness using a documented identity or schema signal. Do not cache readiness forever by path alone.
3. Keep required repair behavior at initialization, explicit recovery, or a bounded invalidation path. Existing malformed/legacy database fixtures still recover or return their typed failure. Do not delete repair code merely because current databases have already passed it.
4. A JDBC recording fixture on an initialized database demonstrates that repeated application writes execute the requested work without maintenance scans. Report the before/after statement categories. Do not pin an exact total or use a wall-clock assertion as the regression test.
5. Manifest projection returns an explicit outcome for absent projection, written projection, and failed projection. Remove both IOException-to-null catches on the projection path. Error details preserve the operation and path without including artifact bodies.
6. Callers that commit database state before writing a projection distinguish durable success from projection failure. They record the failure and can retry only the projection from the current authoritative database record. The retry must not repeat child creation, review settlement, reset, or another already committed mutation.
7. A filesystem failure after a committed workflow update leaves the database change intact and the existing manifest uncorrupted. After filesystem recovery, regeneration produces the expected manifest. A missing applicable artifact remains a legitimate absent outcome, not a fake error.
8. Transaction rollback, consistent read snapshots, lease fencing, and self-managed write contracts remain intact. The unused core Connection.inTransaction helper can be deleted after confirming it still has no callers; do not replace it with another transaction abstraction.

## Dependency notes

This commit can ship independently of process cleanup. Preserve current 347 validation receipts and SKILL-236 telemetry outbox behavior. Use existing versioned migration infrastructure and schema rules for any changed persisted shape. The manifest's four-subtask sequence is execution order, not a claim that this code needs subtask 1.

## Validation strategy

Add a narrow JDBC work-amplification test and fault-injected database-to-filesystem projection tests. Reuse DatabaseMigrationsTest, SQLiteDatabaseSessionFactoryTest, typed-failure tests, and existing goal persistence fixtures. Run `./gradlew :runtime-infra-sqlite:test --tests 'skillbill.infrastructure.sqlite.*' :runtime-application:test --tests '*DecompositionManifest*' --console=plain`, plus the affected goal-store integration test classes if they live in runtime-core. Use temporary databases only.

The failures to catch are repeated whole-table maintenance during heartbeats, readiness surviving database replacement, and a stale manifest silently reported as synchronized after a committed write.

## Non-goals

Connection pooling, a new ORM, distributed transactions, a second durable state store, changing the source of truth, or removing compatibility recovery. Do not move every SQLite collaborator to another module merely to change its package label.

## Next path

Continue to subtask 3. The runtime owns commit creation and publication.
