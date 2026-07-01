# Offline Sync & Cache Consistency

Cues: cache trim/eviction, prefetch or "download for offline", delete-then-refetch flows, queries that join or filter on sync state.

## Delete-before-download loses the only offline copy

When a flow clears or trims a cached artifact **before** its replacement has been confirmed downloaded, an offline or failed fetch leaves the user with neither the old nor the new copy.

- Flag any `clear`/`trim`/`evict` that runs ahead of the `fetch`/`download` whose result is meant to replace it. The safe order is fetch-then-swap: only delete the old artifact once the new one is durably stored.
- A "keep latest version" rule that selects by version number **without checking the item is actually cached** can purge the one copy present on-device. Keep-selection for eviction must be cache-aware.

## Cache-only reads that dropped a fetch fallback

A read path refactored from "return cached, else fetch-and-cache" to "return cached, else fail" silently breaks first-view / cache-miss / post-eviction cases.

- On any load path, check the `-` side of the diff: did a `fetchIfMissing`/`returnCacheDataElseFetch` fallback get replaced by a cache-only read that errors on `nil`? That plan/image/document then never loads on a fresh install or after eviction.

## Queries that hide un-synced rows

A local query that `INNER JOIN`s (or filters) on a related row that is populated only by sync will silently omit entities whose related rows haven't synced yet.

- Flag a join changed from outer/`LEFT` to `INNER` (or a new `WHERE` on a sync-populated column) on a list/enumeration/lookup query: mid-sync or on a fresh install the entity disappears from lists, is skipped by offline-caching enumeration, and resolves `nil` in detail/relationship lookups.

## Trim/eviction driven by the wrong lifecycle

Cache maintenance wired to a screen's lifecycle (view appear/disappear, a specific store instance) instead of the sync pipeline runs too often, not at all, or concurrently with the sync it should follow.

- Watch for eviction moved into a per-screen store: multiple live instances each subscribe and trim (duplicate work), and flows that never open that screen never trim (unbounded growth).
- Eviction that runs on every `syncCompleted` emission — including `.offline`/`.errored`/replayed initial values — trims after failed or non-events. Gate it on a genuine successful completion.
