# TypeScript Offline-First Sync Backend Review Add-On

> SCAFFOLD (SKILL-116). Structure and activation signals are authored; the
> per-topic rules are TODO. This file cannot pass the maintained substance gate
> until the companion topic files exist and each carries concrete, reachable
> failure modes. Do not ship as-is.

Use this governed add-on only after stack routing has already selected `typescript`
and the review scope touches an offline-first sync backend — a server API (often
GraphQL) that reconciles state a mobile/offline client mutated locally with the
authoritative server store via timestamps and cursors.

This file is a review index for `bill-typescript-code-review` and its
`persistence`, `reliability`, and `api-contracts` specialists. It is not a
standalone review command. The guidance is generic to offline-first TypeScript
backends; it encodes recurring failure modes, not any single project's internals.

## Activation signals

Activate `offline-first-sync` when the diff shows any of:

- a change-feed read parameterized by a `since` timestamp plus a cursor, or a
  response shaped as `created[]`/`updated[]`/`deleted[]`
- a recoverable-outcome union returned inside the response body (a `problems`
  channel) rather than as transport errors
- create/update paths that accept a client-provided id, or that must be
  retry-safe / idempotent
- conflict reconciliation (last-write-wins, offline-overwrites-server, versioned
  or merged records), or server-vs-client timestamp handling
- a mapper converting between wire and domain shapes, or a new composite index on
  a `(tenantScope, serverUpdatedAt, id)`-style change-feed key

If the diff only touches a synchronous request/response API with no local-client
reconciliation, do **not** activate this add-on.

## Section index

Scan this file first. Then open only the linked topic files whose cues match the
diff instead of loading all offline-first guidance by default.

- `offline-first-sync-consistency.md` *(TODO — create)*
  Read when the diff touches timestamp authority, the change-feed query, or its
  index. Cluster: server-stamped `serverCreatedAt`/`serverUpdatedAt` as the
  ordering/conflict source of truth vs trusting client clocks; a change-feed
  query/index that can skip or re-emit rows under pagination.
- `offline-first-sync-idempotency.md` *(TODO — create)*
  Read when the diff touches create/update, retries, or client-provided ids.
  Cluster: idempotent, retry-safe writes that revert into each other rather than
  throw on a duplicate; duplicate/deleted-parent checks before processing;
  logging the recovered incident.
- `offline-first-sync-contracts.md` *(TODO — create)*
  Read when the diff touches the `problems` channel or wire/domain mappers.
  Cluster: recoverable, strictly-typed `problems` inside `data` vs transport
  `errors` so a client self-heals one slice; explicit mappers with no `as`
  casting across the boundary.

## How to use it

- Treat findings from this add-on as `persistence`/`reliability`/`api-contracts`
  findings — fold them into those specialists' registers, do not create a
  parallel lane.
- Every finding must name a concrete, reachable failure: a row a client never
  receives, a write silently dropped or double-applied on retry, data lost to a
  clock-skew conflict, a whole sync request failing on a slice that should have
  been a recoverable problem. These bugs surface only on a device that is
  offline, mid-sync, freshly installed, or retrying — states a quick manual test
  misses. Describe that state explicitly.
