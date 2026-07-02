# iOS Offline-First Review Add-On

Use this governed add-on only after stack routing has already selected `ios` and the review scope touches an offline-first surface — a local store (GRDB/SQLite, Core Data, Realm) plus a sync layer that reconciles local and remote state.

This file is a review index for `bill-ios-code-review` and its `persistence`/`reliability`/`platform-correctness` specialists. It is not a standalone review command. The guidance is generic to offline-first iOS apps; it encodes recurring failure modes, not any single project's internals.

## Activation signals

Activate `offline-first` when the diff shows any of:

- a local persistent store (GRDB/SQLite statements or migrations, Core Data, Realm) **and** a network/sync path that writes into or reads around it
- a cache-trim/eviction, prefetch, or "download for offline" flow
- a background-sync engine, sync queue, or interceptor/chain-of-responsibility sync composition
- conflict handling: last-write-wins, versioned records, merge functions, optimistic UI with rollback

If the diff only touches UI or pure in-memory state with no local store or sync, do **not** activate this add-on.

## Section index

Scan this file first. Then open only the linked topic files whose cues match the diff instead of loading all offline-first guidance by default.

- `[offline-sync-consistency.md](offline-sync-consistency.md)`
  Read when the diff touches cache trimming/eviction, prefetch/download-for-offline, the ordering of delete-vs-download, or a query whose join/filter can hide un-synced rows.
- `[offline-conflict-resolution.md](offline-conflict-resolution.md)`
  Read when the diff touches write reconciliation: last-write-wins, versioned/merge records, optimistic UI updates, or premature local state flips before an async write confirms.
- `[offline-background-reliability.md](offline-background-reliability.md)`
  Read when the diff touches background sync, retries/backoff, sync-vs-migration interplay, or lifecycle-driven sync triggers.

## How to use it

- Treat findings from this add-on as `persistence`/`reliability`/`platform-correctness` findings — fold them into those specialists' registers, do not create a parallel lane.
- Every finding must name a concrete, reachable failure (data a user loses, a row that disappears, a write that silently drops). Offline-first bugs are high-severity precisely because they surface only on a device that is offline, mid-sync, or freshly installed — states a quick manual test misses. Describe that state explicitly.
