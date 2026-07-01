# Background Sync Reliability

Cues: background-sync engine, sync queue, retry/backoff, sync-vs-migration interplay, lifecycle-driven sync triggers, interceptor/chain composition.

## Migration-vs-sync ordering

A schema migration that adds a column a sync path immediately reads, or a sync that runs before a migration completes, produces missing-column errors or empty results on the first launch after update.

- Confirm a newly-added column is available to every read/write site before sync consumes it, and that migrations run to completion before the first sync that depends on them.
- Migrations must stay frozen and additive: reference **frozen string literals** for table/column names, never a live model symbol whose value can change later and retroactively alter an already-shipped migration. When a table has been renamed, the migration must handle the legacy name (an `alterRenamedTable`-style helper), not assume the current name exists on un-migrated devices.

## Retries, backoff, and cancellation

- A long-running or repeatable sync effect must carry an effect-identity (`cancellableId`) so a superseding trigger cancels the in-flight one; otherwise overlapping syncs race and a slower older run can overwrite newer results.
- Check that a removed `.retry`/backoff wasn't silently dropped in a refactor — trace whether it moved into the new request path or vanished.
- Errors on a background path must be observable (logged/metered), not swallowed. A `do { … }` block with no `catch` propagates (fine); an empty `catch {}` or `.catch { _ in Empty() }` hides failures — flag the latter.

## Lifecycle-driven triggers

- Sync/refresh sent unconditionally on every view appearance re-fires on modal present/dismiss and foreground/background cycles. Confirm it is idempotent or guarded, and that a same-key guard actually disambiguates two concurrent runs (comparing the same id-set does not).
- Sync that only triggers from one screen's lifecycle silently stops for flows that never open that screen — verify the trigger lives at the right level.

## Per-item work inside the sync loop

Moving a single end-of-sync maintenance pass into per-item processing multiplies DB/network round-trips by N. Flag per-item trims/queries/fetches that a single batched pass previously handled, unless the batching is preserved.
