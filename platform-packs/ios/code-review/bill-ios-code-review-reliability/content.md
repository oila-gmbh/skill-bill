---
name: bill-ios-code-review-reliability
description: Use when reviewing iOS background-sync reliability risks including chained interceptor composition, error-logging discipline, and permission-check completeness.
internal-for: bill-code-review
---

# Reliability Review Specialist

Review only issues that affect production reliability of background and sync-critical work.

## Focus

- Chained-interceptor/chain-of-responsibility composition for background sync (read, write, permission, and utility stages)
- Mandatory error logging on background sync failures
- Permission-check completeness before privileged sync operations
- Retry, backoff, and failure-visibility behavior for background work

## Ignore

- Style preferences in interceptor ordering with no behavioral effect
- Logging verbosity preferences that do not affect failure visibility

## Applicability

Use this specialist for background sync and any interceptor/chain-of-responsibility-style composition that wraps sync operations with cross-cutting stages (e.g. read, write, permission-checking, and utility/logging stages).

## Project-Specific Rules

- Background sync chains composed from Read/Write/Permissions/Utility-style stages must run every applicable stage for a given operation; skipping a stage (especially a permission-check stage) to save time is a reliability and correctness risk, not just a performance shortcut
- Every background sync failure path must produce an error log with enough context (operation, entity, and failure reason) to diagnose the failure after the fact; a swallowed or silently-dropped background sync error is a reliability regression
- Permission checks in a sync chain must run before the corresponding read/write stage executes, not after or in parallel with it; a permission check that runs too late does not actually prevent the unauthorized operation
- New stages added to an existing chain must preserve the chain's existing ordering guarantees unless the change explicitly and visibly redefines that order
- Retries for background sync operations must be bounded and must not silently retry an operation that has already permanently failed (e.g. due to a permission denial) as if it were a transient failure
- Utility/cross-cutting stages (logging, metrics, cleanup) must not swallow or mask an error raised by the read/write/permission stage they wrap
- The swallowed-error rule is not limited to sync chains: on any async path, an error caught and dropped (empty `catch`, mapped to a default, ignored via `_`, or turned into an empty result) rather than logged or propagated to its caller, the UI, or store state is a reliability regression
- A dependent async action (UI transition, save, reload, navigation) that proceeds before the awaited operation it depends on has actually completed is a race: the follow-up can act on stale or partial data
- Concurrent or duplicate async operations (overlapping network fetches, store effects, repeated form submissions) that run without cancelling superseded work or guarding against re-entry let a stale response overwrite newer state, or let a rapidly repeated trigger cause duplicate writes, navigations, or sync jobs
- Extracting or rewriting a view/component that silently drops previously-present functionality (buttons, fallback labels, forwarded callbacks, wired services) is a regression unless the removal is explicitly flagged as intentional
- Debug `print`/logging statements or debug-only code paths left in shipped code are a reliability and information-leak risk, not just noise
- `[weak self]` captured but the closure still reaches `self`/outer state directly instead of through the safely-unwrapped self — or omitted entirely on a long-lived subscription — risks crashes, stale reads, or retain-cycle leaks
- For Blocker or Major findings, describe the concrete data-loss, unauthorized-access, or silent-failure scenario in production

## Repo-Local Knowledge

Before finalizing findings, check whether the repo under review ships its own agent-knowledge docs (e.g. `.agents/skills/*/references/*.md` and a root `AGENTS.md`/`CLAUDE.md`). When present, read them and weigh any documented hard-rule violation (e.g. a required error-logging or permission-check-ordering rule for background sync) as a high-confidence finding. This is a read-only lookup local to the repo under review — nothing from these documents is copied into skill-bill's own tree.
