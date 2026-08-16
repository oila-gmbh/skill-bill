# SKILL-193 Subtask 2: Store-backed packet composition

## Intended Outcome

`ReviewPreparationService` builds a 2.0 parent packet whose hunks address
bodies in the SKILL-164 shared evidence store. Composition never copies hunk
text into `ReviewContextPacket` or the parent envelope. Digest mismatch or a
missing locator loud-fails before launch.

## Scope

- Compose `changed_hunks` from the checkpoint-keyed evidence store (store
  path plus file/hunk index) already used for feature-task review evidence.
  Standalone `bill-code-review` uses the same store/locator seam; do not
  introduce a second body store.
- At composition time, read stored hunk bytes only to compute `hunk_id` and
  `content_digest`, then drop the bytes. The packet and parent envelope keep
  locator, digest, spans, and id.
- `content_digest` is SHA-256 over normalized hunk body bytes. A locator that
  does not match its digest at compose or at first broker read is a typed
  integrity failure, not a retry with a substitute body.
- Keep assignment composition as hunk-id ownership. Assignments still must
  not embed bodies.
- Fail closed when the store path is missing, unreadable, or the fingerprint
  contradicts the addressed location (existing SKILL-164 loud-fail).
- Update `ReviewPreparationService` tests that inspect
  `changed_hunks[].content` on the parent envelope.

## Acceptance Criteria

1. `composePacket` succeeds for a stored patch larger than
   `max_parent_packet_bytes` when the index itself fits the parent budget.
2. The composed parent envelope's `changed_hunks` entries contain locators and
   digests and do not contain `content`.
3. `hunk_id` computed from stored body bytes matches the id on the packet;
   altering the stored body without changing the digest/locator pair fails
   compose or first read with a typed integrity error.
4. A missing, unreadable, or fingerprint-contradicting evidence locator fails
   compose with a typed error and does not launch workers.
5. Assignments still cover exactly the selected lanes and only packet-owned
   hunk ids; they do not grow with body size.
6. Standalone review and feature-task review share one body-store seam.
7. `ReviewPreparationServiceTest` no longer asserts parent-envelope `content`;
   it asserts index fields and compose-success on an oversized patch fixture.
8. Focused module tests for `runtime-application` preparation pass.

## Non-Goals

- Broker batching into workers and incomplete-lane evidence overflow
  (subtask 3).
- Changing how shared evidence is derived or cached (SKILL-164), except to
  consume its locators.
- Raising `max_parent_packet_bytes`.

## Dependency Notes

Depends on subtask 1 (2.0 index-only hunk contract).

## Validation Strategy

- Preparation fixture: 700 KiB stored `diff.patch`, parent envelope well
  under 524288, compose returns packet + assignments.
- Integrity fixture: digest/locator mismatch loud-fails with a stable code.
- Missing store path loud-fails.
- Existing lane-count, ownership, and expansion tests remain passing without
  reading `content` off the parent envelope.

## Next Path

Commit on the feature branch. Subtask 3 moves launch/prompt assembly onto
broker fetches and applies lane evidence budgets.
