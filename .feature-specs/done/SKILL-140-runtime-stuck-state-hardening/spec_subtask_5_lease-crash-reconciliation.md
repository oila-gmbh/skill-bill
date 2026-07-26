# SKILL-140 Subtask 5 - Lease-Based Crash Reconciliation

## Scope

Make orphaned non-terminal workflow rows self-heal. When a child process dies before its workflow row reaches a terminal state (session limit, provider 5xx, crash, kill), the next runner startup or goal-parent supervision pass reconciles the row to a typed resumable state automatically — no manual lease clearing, no row deletion.

- Add an unconditional reconciliation pass at runner startup and in the goal parent's child-supervision seam, in the same self-healing spirit as the existing unconditional DB column ensures:
  - candidate rows: non-terminal workflow status, worker lease expired (per `feature_task_runtime_worker_leases`), and process confirmed dead (the existing activity/progress probe strategies on `AgentRunProcessRequest`; no new agent-conditional branching in the process runner).
  - action: transition the row to the typed resumable interrupted state carrying an interruption reason derived from available evidence (exit status if recorded, lease expiry otherwise) and release the lease.
  - rows with live leases or confirmed-alive processes are never touched.
- The goal parent's "Subtask N finished without a terminal workflow-store outcome" path stops emitting a terminal blocked reason when the row is reconcilable: it reconciles and reports the subtask as resumable, so `skill-bill goal <key>` resume continues instead of requiring surgery.
- Reconciliation is idempotent: a pass over an already-reconciled or empty candidate set is a no-op; concurrent passes (two runners starting) are safe through the existing lease machinery.
- Count reconciliations in telemetry (row count, reason class) without recording row contents.

## Acceptance Criteria (this subtask)

1. A workflow row left non-terminal by a killed child process, with an expired lease and dead process, is transitioned to the typed resumable interrupted state on the next runner startup, and a subsequent resume continues the run from that phase.
2. The goal parent treats a reconcilable child row as resumable rather than emitting a terminal `NO_TERMINAL_STORE_OUTCOME`-class block; resume of the goal proceeds without manual lease or row clearing.
3. Rows with live leases or running processes are never modified, proven by a test with an active lease and a live process probe.
4. Reconciliation is idempotent and safe under concurrent startup, proven through the lease machinery.
5. Liveness detection goes through the existing injectable probe strategies; no agent identity branching is added to `ProcessWaitLoop` or the process runner.
6. Telemetry counts reconciliations by reason class without recording row contents.
7. Documentation (AGENTS.md runtime behavior section, runtime docs) describes automatic crash reconciliation; manual lease clearing is documented as corruption fallback only.

## Non-Goals

- Automatic re-launch of the interrupted work (resume remains operator- or goal-driven).
- Retrying external failures beyond existing policy.
- Changing lease acquisition, extension, or idle-policy semantics for live processes.

## Dependency Notes

Functionally independent of Subtasks 1-4; ordered last because it touches the supervision seam that Subtask 4's regeneration edge also exercises, and landing it after keeps the interaction surface reviewed once.

## Validation Strategy

- Persistence-layer tests for candidate selection (expired lease + dead process) and non-candidates (live lease, live process).
- Runner/goal-parent tests: killed-child reconciliation then successful resume; idempotent double pass; concurrent startup safety.
- `(cd runtime-kotlin && ./gradlew check)` plus the full validation command block from the parent spec.

## Next Path

Feature complete. Reconcile the parent spec status, run the full validation commands, and confirm blocked-run telemetry drops across the next tracked goals.
