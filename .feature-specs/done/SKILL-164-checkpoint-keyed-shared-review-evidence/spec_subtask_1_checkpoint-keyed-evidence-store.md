# SKILL-164 · Subtask 1: Checkpoint-keyed evidence store and deriver

## Scope

Introduce the durable, checkpoint-keyed derived-evidence artifact and the derive-once-reuse-
after port that owns it. This subtask delivers the storage and derivation seam only; no phase
consumes it yet.

In scope:

- A domain model for the shared derived evidence artifact: its identity
  (workflow id + `FeatureTaskRuntimeRepositoryCheckpoint.fingerprint`), the resolved
  `baseRef` / `headRef`, a file index, a hunk index, and a pointer to the materialized diff
  payload.
- A port that resolves evidence for a requested checkpoint with derive-once semantics: an
  artifact stored at the requested fingerprint is returned without repository traversal; any
  other outcome derives fresh and stores.
- A filesystem adapter in `runtime-infra-fs` writing under the repo-local run store, addressed
  by workflow id and fingerprint. Writes are atomic (staged then replaced) so a crash mid-write
  leaves no half-artifact that a later run would serve.
- Fingerprint mismatch handling: a stored artifact is served only for the fingerprint it was
  derived at. A mismatch re-derives; a stored artifact whose recorded fingerprint disagrees
  with its own addressed location loud-fails rather than being served.
- Corruption and absence tolerance: an unreadable, truncated, or missing artifact re-derives
  rather than failing the run.

Out of scope: projection wiring, phase briefing changes, review lane consumption, telemetry
events, model hoisting from the review package.

## Acceptance Criteria

1. A shared derived-evidence domain model exists carrying the checkpoint fingerprint, resolved
   `base_ref` and `head_ref`, a file index, a hunk index, and a reference to the materialized
   diff payload.
2. Resolving evidence for a checkpoint whose fingerprint matches a stored artifact returns the
   stored artifact and performs zero repository traversal.
3. Resolving evidence for a checkpoint whose fingerprint has no stored artifact derives fresh,
   persists it, and returns it.
4. Resolving evidence for a fingerprint that differs from the stored artifact's fingerprint
   re-derives rather than serving the stored artifact.
5. The filesystem adapter persists artifacts under the repo-local run store addressed by
   workflow id and checkpoint fingerprint, and a new process resolving the same fingerprint
   reuses the persisted artifact without re-deriving.
6. Artifact writes are atomic: an interrupted write leaves no artifact that a subsequent
   resolve would serve.
7. A missing, truncated, or otherwise unreadable stored artifact causes a fresh derivation and
   never fails the run.
8. A stored artifact whose recorded fingerprint contradicts its addressed location loud-fails
   with a message naming both fingerprints, rather than being served.
9. No new `.gitignore` entry is added; the store resides beneath the already-ignored repo-local
   `.skill-bill/` directory.

## Non-Goals

- Wiring any phase, projection, or briefing to the new store.
- Changing `ReviewCommitUnit`, `ReviewEvidenceTarget`, or `ReviewLaneBundleAssembly`.
- Cross-run or global caching outside the per-workflow run store.
- Telemetry emission.

## Dependency Notes

None within this feature. Feature-level: sequenced after SKILL-158 lands.

## Validation Strategy

Unit tests over the port contract covering each resolve outcome: hit at matching fingerprint
(asserting no traversal occurred), miss, fingerprint mismatch, corrupt payload, and absent
artifact. Filesystem adapter tests over a temporary run store covering persistence,
cross-process reuse (fresh adapter instance against the same directory), atomic write under
simulated interruption, and the contradictory-fingerprint loud-fail. Existing `runtime-infra-fs`
and `runtime-domain` suites must stay green.

## Next Path

Proceed to subtask 2.
