---
internal-for: bill-feature
name: bill-feature-task-runtime
description: Use when running a single governed feature spec through the runtime-driven feature-task phase loop via the foreground `skill-bill feature-task` runtime. This is the runtime-backed mode of bill-feature-task. Consumes the router-confirmed run and launches the runtime. Use when user mentions implement feature, build feature, implement spec, run feature-task, or feature from design doc, and the runtime-driven phase loop is preferred over prose in-session orchestration.
---

# Feature Task Content

`bill-feature-task-runtime` is the runtime-backed mode for running a single
governed spec through the runtime-driven feature-task phase loop
(`plan -> implement -> review -> audit -> validate`) owned by the local
`skill-bill feature-task` driver.

Durable workflow rows use the public workflow identity `bill-feature-task` with
`mode=runtime` in the shared feature-task workflow store. Runtime-specific tool
names are compatibility aliases for that mode, not a separate authoritative
workflow family.

The workflow database is the continuation authority. Resume keeps the existing workflow id and validates the issue key, canonical repository identity, persisted governed spec path, and runtime mode before branch preparation or phase launch. Completed durable phases remain skipped. Initial implementation hydrates from the completed `plan`. Audit-gap implementation remediation reuses the original completed `preplan` and `plan` outputs; neither planning phase is relaunched or overwritten.

For a goal child, hydrated preplan and plan records carry goal-planning provenance: no child agent is launched for them and their payloads, duration, tokens, and agent identity are not counted as child execution. A standalone feature task is unchanged and executes and attributes its own preplan and plan directly to the standalone workflow.

`bill-feature-task-runtime` consumes the normalized, router-confirmed run and
launches the runtime command. It does **not** re-implement phase orchestration
in prose — the runtime owns the phase loop, the per-phase handoff, the schema
gates, and the durable state. This skill must never restate or re-derive that
orchestration.

## Confirmed Input

Consume the router-confirmed run:

- the issue key
- the governed spec path the run implements
- the agent currently executing this skill
- the parallel review agent (from args as `parallel-review:<agent>`; absent when not provided)
- the normalized review selection from `code-review:auto|inline|delegated`
- the already-resolved ordered agent add-on selection, if present

The `bill-feature-task` router has already rejected invalid review-selection
tokens and presented the only confirmation gate. Do not reparse, default, or
change `code-review:<selected-mode>`, and do not ask another confirmation
question. If the router failed to provide the issue key, spec path, or
normalized selection, stop rather than inventing a value. The runtime sources
the run-invariants (spec reference, acceptance criteria, mandates and overrides)
directly from the spec at launch — this skill does not parse or restate them.

**opencode and zcode are prose-only; refuse before launch.** Even when a caller
supplies the already-confirmed input directly, this sidecar must refuse on its
own when the agent currently executing it is opencode or zcode: stop before the
foreground launch and emit the actionable refusal
pointing to `bill-feature-task-prose`:

> Runtime mode is not supported on opencode or zcode in this harness. opencode's foreground Bash tool is hard-killed at 120s before a phase can finish and per-phase output cannot be harvested back; zcode's foreground runtime exceeds the Bash execution ceiling and a detached zcode child emits no harvestable output before the supervisor kills it as unresponsive. Use prose instead — run bill-feature-task-prose for a single feature task, or bill-feature-goal mode:prose for a decomposed goal.

Do not launch `skill-bill feature-task` on opencode or zcode — the runtime CLI refuses the
same way whenever the resolved runtime agent is opencode or zcode, and runtime mode is
non-viable for both (opencode's 120s foreground kill and the un-harvestable PTY output;
zcode's foreground run exceeding the Bash ceiling and detached children killed as
unresponsive before emitting harvestable output). For a prose run instead, read the
sibling `bill-feature-task-prose.md` sidecar.

## Runtime Launch

Execute the foreground driver directly in the current agent session, always
passing `--agent` set to the agent currently executing this skill:

```bash
skill-bill feature-task run <issue_key> <spec_path> --agent <currently-executing-agent>
```

Append `--parallel-review-agent <agent>` when `parallel-review:<agent>` was passed to this skill.
Append `--code-review-mode <auto|inline|delegated>` using the resolved selection.
Append `--agent-addon-selection-json <structured-json>` when a selection was
provided. Do not parse, reorder, or rediscover it. Runtime preparation verifies
each recorded source identity and exact-byte digest before workflow, branch, or
phase side effects and injects only verified selected content into prompts.

Always pass `--agent` set to the agent currently running this skill (for example
`claude` from Claude Code, `codex` from Codex, `opencode` from OpenCode), so the
invoking agent — not a hardcoded default — drives the phase runs. Only use
`--agent-override` when the user explicitly selected a different agent;
`--agent-override` wins over `--agent`. An optional repeatable
`--phase-agent <phase-id>=<agent-id>` (for example `--phase-agent plan=claude`)
assigns a specific agent to one phase.

Do not ask the user to run this command manually. Keep the run in the foreground
unless the user asks otherwise; pass `--monitor` to tee phase transitions to the
terminal.

### Live Observation

The run is long-lived and the user must see it progress, not wait in silence for
a terminal result. The invoking agent owns surfacing phase transitions in the
conversation as they happen:

- Always launch the driver with `--monitor` so the runtime tees its structured
  per-phase progress.
- When the run is executed in the background (for example because it can outlast
  a single foreground shell window), attach a persistent, line-buffered observer
  to the runtime's progress stream — tailing the run's output — filtered to phase
  starts and completions, schema-gate results, retries, blocked phases,
  non-validation failures, and run completion. Relay each event inline in plain
  language as it arrives.
- Surface a blocked or failed gate loudly and immediately; never narrate a
  blocked run as if it were progressing normally. Validation findings are the
  exception: the runtime reopens `validate` for repair instead of persisting a
  blocked phase. On completion, report the terminal per-phase summary.

This is observation only: the agent reports what the runtime emits and never
re-derives or re-orders the phase loop. The durable workflow state remains
authoritative over any relayed line.

The runtime owns everything after launch: it opens the durable runtime workflow,
runs each phase through its own agent, validates each phase output against the
schema gate, persists per-phase state, and blocks loudly on a failed
non-validation gate or a missing upstream output. Validation findings never
persist `validate` as blocked; they are fed back to the validation agent to fix
and rerun, the same way code-review findings are fixed before the workflow
continues. Treat the durable workflow state as authoritative over any prose.

## Review-driven implement-fix loop

The runtime closes a bounded remediation loop around `review`. The `review`
phase emits a structured verdict derived from its findings: `approved` when no
unresolved Blocker findings remain, or `changes_requested` when any are present.
Major findings remain durable evidence but never prevent advancement. The runtime
evaluates that verdict — prose alone cannot advance past a Blocker finding.

- On `approved`, the run advances to `audit` (a clean run never launches a fix).
- On `changes_requested`, the runtime takes a backward edge to a dedicated
  `implement_fix` phase, which addresses the carried review findings on the
  current working tree as incremental reconciliation (not a plan re-application),
  then re-runs `review`. This `review` → `implement_fix` → `review` cycle is
  capped at one remediation iteration via a durable per-edge counter: the
  initial review may use the selected mode, while the only re-review is reserved
  before launch and always invokes
  `bill-code-review mode:inline context:feature-remediation` against only
  the staged, unstaged, and untracked remediation delta since the checkpoint
  created before `implement_fix`, never the full feature-branch diff. The first
  `approved` verdict advances the run to `audit`.
- If the loop exhausts its cap without an `approved` verdict, no further
  review-fix iteration is launched. The flow advances to `audit` with the latest
  review result and findings preserved as durable evidence; reaching the review
  remediation cap is not itself a blocking condition.

The loop is crash-safe: a death during `implement_fix` or a re-`review` resumes
at the correct phase and iteration with no double-applied mutations, and a loop
that already burned its cap advances without launching another remediation.
Each `implement_fix` launch and re-`review` carries the `review_fix` loop id
and iteration in the ledger and status output, and finished telemetry reflects
the review-fix iteration count.

## Audit-gap context-reuse implementation-remediation loop

The runtime closes a second, wider remediation loop around `audit`. The
`audit` phase emits a structured verdict derived from the acceptance criteria it
checked: `satisfied` when every criterion is met, or `gaps_found` when one or
more remain unmet. The runtime evaluates that verdict — prose alone cannot
advance past an unmet acceptance criterion.

- On `satisfied`, the run advances to `validate`.
- On `gaps_found`, the runtime takes a backward edge re-entering `implement`,
  then `review`, then `audit`. The implementation handoff contains the immutable
  original `preplan` and `plan` outputs plus only the latest failing criteria, so
  the loop addresses the gaps rather than redoing settled content. This
  `audit` → `implement` → `review` → `audit` cycle has no fixed
  iteration cap. Its durable counter records progress and recovery state but
  never turns a valid `gaps_found` verdict into a permanent policy block. The
  first `satisfied` verdict advances the run to `validate`.

The re-entered `implement` is idempotent: it reconciles the working tree toward
the original plan without double-applying, and a crash mid-loopback resumes at the
correct phase and iteration while preserving the `audit_gap` watermark.

The two loops compose under one durable two-pass review budget. The initial
review and the first later review reached by either a review-fix or audit-gap
path use the run-selected mode. That later review consumes pass two; later
audit-gap iterations reuse its completed result rather than launching a third
review. Each backward edge carries the `audit_gap` loop id and iteration in the
ledger and status output, and finished telemetry reflects the audit-gap
iteration count alongside the review-fix count.

## Status and Resume

Status is read-only and never starts a run:

```bash
skill-bill feature-task status <workflow_id>
```

Report the complete, pending, and blocked phase counts, the current phase, and
each phase's status exactly as returned. Do not mutate state during a
status-only request.

To resume an interrupted run against its existing workflow id:

```bash
skill-bill feature-task resume <workflow_id> <issue_key> <spec_path> --agent <currently-executing-agent>
```

Resume re-runs the runtime phase loop, which deterministically skips
already-complete phases from the durable per-phase records. If the runtime blocks
a phase, summarize the blocked phase and reason rather than continuing the loop
manually.

Phase failures carry a durable typed disposition. `retryable`,
`process_failure`, and `invalid_output` may relaunch under the applicable
bounded policy. `non_retryable_policy_conflict` and `needs_user_action`
re-block unchanged on resume without launching another agent or consuming an
attempt. Do not override this decision in-session.

Every launched phase records its before, after, and introduced changed-path
manifests. If a phase introduces a governed `.feature-specs/` path for another
issue, the runtime records a non-retryable policy block. A path already dirty
before the phase remains evidence but is not attributed to that phase. Do not
work around this guard by committing, staging, or renaming the unrelated spec.

To deliberately replace a nonterminal run, terminalize that exact workflow
through the supported operator path:

```bash
skill-bill feature-task abandon <workflow_id> --reason "<operator reason>"
```

Abandonment requires the exact workflow id and a non-blank reason, records the
reason and timestamp in durable workflow artifacts, preserves phase records and
ledger history, and rejects unknown or already-terminal workflows. Never edit
SQLite directly to make continuation lookup select a replacement.

If a legacy nonterminal runtime workflow loud-fails because it predates the
immutable execution-identity contract, repair only the missing identity through
the explicit operator seam:

```bash
skill-bill feature-task repair-identity <workflow_id> <issue_key> <spec_path> \
  --repo-root <repo-root> --reason "<operator reason>"
```

The repair canonicalizes repository and governed-spec paths, requires the
persisted issue key to agree, records repair evidence, and preserves immutable
identity conflict checks. It never guesses identity or silently migrates a
workflow during resume.

### Rehydrate a missing linear-mode spec before resume

The spec source is the sibling `decomposition-manifest.yaml` `spec_source`
artifact stamp, defaulting to `local` when omitted. A bare `spec.md` is preparation
intake, not prepared source authority. For `spec_source: local`, resume needs no
extra step.

For `spec_source: linear`, the local spec scratch is deleted on terminal success,
so before calling `resume` check whether the file at `<spec_path>` (or a needed
subtask spec) exists. If it is missing, rehydrate it first: fetch the parent
issue by `issue_key` and the subtask by its `linear_issue_id` via the Linear MCP,
rewrite the local spec file(s), and only then call `resume`. The runtime read
path is unchanged — it still reads `<spec_path>` once and freezes invariants;
rehydrate only guarantees the file is present first. Rehydrate is agent-side MCP
only; the runtime gains no Linear dependency.
