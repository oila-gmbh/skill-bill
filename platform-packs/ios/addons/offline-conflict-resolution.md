# Offline Write Reconciliation & Optimistic UI

Cues: last-write-wins, versioned/merge records, optimistic local updates, `isDeleted`/status flags flipped before an async write, save-on-navigation.

## Premature local state flip before the async write confirms

Setting a success/deleted/completed flag synchronously, before the effect that performs the write completes, corrupts state when the write later fails.

- Flag `state.isDeleted = true` / `state.saved = true` set in the reducer ahead of the delete/save effect's completion. On failure the UI shows the item gone or saved while the store still holds it (or vice versa). Drive the flag from the effect's success, not its dispatch.

## Optimistic update with no rollback path

An optimistic local mutation applied before the server confirms must have a defined revert if the write fails.

- If the diff applies a local change and fires a fire-and-forget write whose failure is swallowed (`.catch { _ in Empty() }`, no error surfaced, no rollback), a failed sync leaves local state diverged from the server with no user-visible signal. Flag the swallow; require the error to either surface or roll back.

## Save/sync on navigation without a dirty check

Auto-saving on back/disappear whenever the record "validates," with no comparison against the initial value, schedules a redundant write and sync job for unchanged records.

- Flag `willDisappear`/back handlers that unconditionally `saveChanges` in view mode. Require a `state.initialItem != state.item`-style dirty check so open-and-back on an unedited record is a no-op.

## Write-semantics changes on a synced table

Changing how a sync-participating table is written (last-write-wins vs versioned, which fields are authoritative, whether a field is backfilled) can desync clients that haven't migrated.

- When a new persisted field is added by migration but no deep sync / backfill is triggered, pre-existing rows stay `NULL` until each is independently re-fetched — flag the missing backfill story, or confirm the field is nullable-and-tolerated by all readers.
- A deleted item re-saved during pop/navigation (delete effect maps to a "back" action that then re-persists) resurrects it — check delete-then-navigate ordering.
