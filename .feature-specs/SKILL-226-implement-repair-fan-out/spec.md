# SKILL-226: Implement repair fan-out

## Intended Outcome

Large implement and audit-gap remediation passes can run as **runtime-owned,
file-disjoint repair workers** instead of one long single-agent session, without
letting the phase agent spawn delegated Task/subagents.

Motivation from SKILL-221-style work: after suppressions are removed, audit can
hand back dozens of peer findings across many paths. Fixing them serially in one
composer session is correct but slow. Parallelism is safe only when partitions
share **no writable paths**; partitioning by tool (detekt vs spotless vs tests)
is the wrong cut because those writers collide on the same files.

Shape:

1. A governed **repair partition plan** (from audit gaps / finding inventory)
   groups work into file-disjoint partitions, or falls back to a single
   partition when disjointness cannot be proven.
2. The **runtime** launches one worker per partition (same implement/audit-gap
   phase semantics), merges results, and records **one** implement settlement /
   receipt.
3. Phase prompts keep **banning agent-spawned delegated subagents**; fan-out is
   only legal when the runtime owns launch, lease, merge, and settlement.
4. Optional follow-through: after the tree is stable, validate-owned gate pieces
   that are read-only or non-overlapping may run in parallel — not as implement
   Task children.

## Acceptance Criteria

1. A versioned contract under `orchestration/contracts/` defines an implement
   repair partition plan: partition ids, assigned finding/gap refs, exclusive
   writable path sets, and a single-partition fallback identity when the planner
   cannot prove file-disjointness.
2. A runtime planner (or audit-owned producer validated by the same schema)
   builds that plan from audit gap notes / structured finding inventory and
   **loud-fails** (or collapses to one partition) when any two partitions share
   a writable path.
3. When the plan has two or more file-disjoint partitions, the feature-task
   runtime launches one implement (or audit-gap implement) worker per partition,
   waits for all, merges worktree outcomes, and emits a **single** implement
   phase settlement / implementation receipt for the parent attempt.
4. Implement / audit-gap / build / validate phase prompts continue to forbid
   agent-spawned delegated subagents; parallelism is only via this runtime path.
5. Crash, pause, and process-retry resume remain safe: partial partition
   completion is reconcilable (idempotent converge-to-target) and does not
   double-apply edits or lose the parent attempt's lease semantics.
6. When the plan is a single partition (non-disjoint or below a configured
   threshold), behavior matches today's single-agent implement path.
7. Observability records partition count, per-partition worker status, and
   merge outcome on the goal/feature-task status surfaces (enough to explain
   "why is implement still running?").
8. Contract version constant, schema parity test, typed parse errors, and
   classpath schema copy follow the repo's runtime-contract rules.

## Constraints

- Partition by **exclusive writable paths** (and finding refs), not by tool name
  (detekt / spotless / tests).
- Shared dirty worktree without isolation is only allowed when partitions are
  proven file-disjoint; overlapping paths must not run concurrently.
- Do not weaken validation ownership: full `collect_all_full_gate_command` /
  check suites stay validate-owned except existing gate-proof exceptions.
- Preserve one subtask commit finalisation model; fan-out must not leave
  multiple implement commits on the feature branch for one phase attempt.
- Prefer extending existing agent-run / lease / settlement seams over a second
  orchestration stack.

## Non-Goals

- Letting the implement agent freely spawn Task/subagents to "analyze and
  parallelize" on its own.
- Parallel review lane redesign (already has its own fan-out).
- Reworking audit criteria text or detekt rule policy (SKILL-221).
- Multi-worktree or multi-clone implement by default (optional later if
  single-tree disjointness is insufficient).
- Replacing Gradle's own task parallelism inside one validate gate run.

## Validation Strategy

- Schema / parity / loud-fail parse tests for the partition plan contract.
- Unit tests: overlapping path sets collapse or reject; disjoint sets launch N
  workers; single-partition path matches legacy implement.
- Integration or runner test: fake multi-partition plan → N launches → one
  settlement; crash mid-partition still resumes without duplicate apply.
- Prompt composer tests still assert "do not spawn delegated subagents" on
  implement / audit-gap / build / validate.
- `skill-bill validate` and targeted runtime module tests.

## Delivery Plan

1. Contract + partition planner with disjointness rules and single-partition
   fallback.
2. Runtime fan-out launch, merge, single settlement, resume safety, and
   observability; wire audit-gap implement to consume the plan.
