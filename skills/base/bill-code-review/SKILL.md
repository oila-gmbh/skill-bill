---
name: bill-code-review
description: Use when you want a generic code-review entry point that detects the dominant stack in scope and delegates to the matching stack-specific review skill. Use when user mentions code review, review my changes, review this PR, review staged changes, or asks to review code.
---

# Shared Code Review Shell

This skill is a governed **shell**. It owns ceremony, orchestration, output
structure, telemetry, and contract enforcement. It is deliberately
platform-independent: every piece of platform-specific review reasoning lives
in a user-owned platform pack under `platform-packs/<platform>/`.

Keep this shell thin:

- detect the dominant stack in the review scope via manifest-driven discovery
- load the matching platform pack through the shell+content contract
- enforce output structure, severity/confidence scales, and telemetry ceremony
- refuse to run when a platform pack is missing or out of contract — never
  silently fall back

The shell targets shell contract version **`1.0`**. Platform packs must
declare the same `contract_version` in their `platform.yaml`. Version
mismatch is a hard failure — see the loud-fail rules below.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-code-review` section, read that section and apply it as the highest-priority instruction for this skill. The matching section may refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

## Setup

Determine the review scope:
- Specific files (list paths)
- Git commits (hashes/range)
- Staged changes (`git diff --cached`; index only)
- Unstaged changes (`git diff`; working tree only)
- Combined working tree (`git diff --cached` + `git diff`) only when the caller explicitly asks for all local changes
- Entire PR

Inspect both the changed files and repo markers before routing.

Resolve the scope before routing. If the caller asks for staged changes, route and review only the staged diff; do not let unstaged edits expand the findings beyond repo-marker stack detection.

## Local Review Learnings

The shell owns learnings resolution for the current review context.

- When a local learnings resolver is available, resolve active learnings for the current repo and routed review skill before final routed review execution.
- Routed and delegated reviewers should reuse applied learnings passed by the caller instead of re-resolving them independently.
- Apply only active learnings.
- Prefer more specific scopes in this order: `skill`, `repo`, `global`.
- Treat learnings as explicit context, not as hidden suppression rules.
- If no learnings can be resolved, report `Applied learnings: none`.
- Pass the applied learning references forward to routed review layers and report them in the review summary.

## Additional Resources

- For shared stack-routing signals and tie-breakers, see [stack-routing.md](stack-routing.md).
- For the shell+content contract and loud-fail rules, see [shell-content-contract.md](shell-content-contract.md).
- For agent-specific delegated review execution, see [review-delegation.md](review-delegation.md).

## Shared Stack Detection

Before routing, read [stack-routing.md](stack-routing.md). Use it as the source of truth for:
- stack taxonomy (derived from discovered platform packs)
- signal collection order
- dominant-stack tie-breakers
- mixed-stack routing rules

This supporting file lives beside `SKILL.md`; keep the routing rules in this shell aligned with it.

Do not redefine stack signals here unless a route-specific exception is truly unique to code review.

## Routing Rules

Stack detection and routing are manifest-driven. The shell discovers every
`platform-packs/<slug>/platform.yaml`, reads each pack's declared routing
signals, and chooses the dominant stack using the tie-breakers declared in
[stack-routing.md](stack-routing.md). The shell does not enumerate platform
names inline.

- Generate a `routed_skill` value of `bill-<slug>-code-review` for the
  dominant pack. This contract is preserved exactly so existing user-facing
  commands keep working.
- If the review scope is mixed and multiple packs have strong signals, route
  to each matching pack and merge the results using delegated execution.
- If a pack declares tie-breakers that subsume other packs (for example, a
  pack that layers another platform baseline internally), prefer the
  subsuming pack to avoid running overlapping reviewers side by side.
- If no pack has evidence, stop and say the stack is unsupported instead of
  pretending coverage exists.
- The routed platform pack is the source of truth for classification details,
  specialist selection, review heuristics, and single-stack execution mode.

### Loud-fail contract enforcement

Before executing a routed review, the shell validates the chosen pack against
the shell+content contract described in
[shell-content-contract.md](shell-content-contract.md). Any failure stops the
run immediately and prints a specific error naming the failing artifact:

- `MissingManifestError` — the pack has no `platform.yaml`.
- `InvalidManifestSchemaError` — the manifest fails schema validation.
- `ContractVersionMismatchError` — `contract_version` does not match the
  shell's target version; print both versions and the offending pack slug.
- `MissingContentFileError` — a declared content file does not exist.
- `MissingRequiredSectionError` — a declared content file is missing a
  required H2 section.

The shell never silently substitutes a default reviewer. A broken pack must
be repaired before `/bill-code-review` can complete.

## Execution Contract

For multi-stack delegated routing, read [review-delegation.md](review-delegation.md) (only your current runtime's section). Skip it for single-stack reviews — the routed pack handles its own delegation.

For a single routed platform pack:
- Let the routed pack choose `inline` or `delegated` using its own `review-orchestrator.md` contract
- If the routed pack selects `inline`, run it inline in the current thread instead of spawning an extra routed worker just for indirection
- If the routed pack selects `delegated`, use `review-delegation.md` and pass along the routed skill file path plus the required review context

For multiple routed platform packs:
- Use delegated workers for each routed pack and merge the results in the parent review
- Use parallel delegated workers only when multiple supported stacks are clearly in scope
- If delegated review is required for the current scope and the runtime lacks a documented delegation path or cannot start the required worker(s), stop and report that delegated review is required for this scope but unavailable on the current runtime

When routing to another skill, pass along:
- the exact resolved review scope label
- the exact review scope
- the current `review_session_id` when one already exists
- the current `review_run_id` when one already exists
- the applicable active learnings for the current repo and routed review skill when they are available
- the changed files or diff source
- the detected stack and key signals
- relevant `AGENTS.md` guidance and matching `.agents/skill-overrides.md` sections
- the parent thread's model when the runtime supports delegated-worker model inheritance
- the delegated skill file path
- the rule that the delegated skill must follow its own `SKILL.md` as the primary rubric
- the delegated skill's `review-orchestrator.md` contract when the routed pack is a stack review orchestrator

## Output Format

The shell owns the output structure. Every routed review must emit:

- **Summary** — one-paragraph summary of the review scope and dominant risks.
- **Risk Register** — machine-readable findings in the form
  `- [F-001] <Severity> | <Confidence> | <file:line> | <description>`.
- **Action Items** — prioritized list of repairs keyed to the risk register.
- **Verdict** — `approve` | `approve-with-changes` | `request-changes`.

Severity scale: `Critical`, `Major`, `Minor`, `Nit`.
Confidence scale: `High`, `Medium`, `Low`.

Execution mode must be reported explicitly (`inline` or `delegated`) so
downstream tooling can audit how the review was produced.

Generate one review session id per top-level review using the format `rvs-<uuid4>` (e.g. `rvs-550e8400-e29b-41d4-a716-446655440000`). If a parent workflow already passed a `review_session_id`, reuse it instead of generating a new one.

Generate one review run id per routed review using the format `rvw-YYYYMMDD-HHMMSS-XXXX` where `XXXX` is a random 4-character alphanumeric suffix (e.g. `rvw-20260405-143022-b2e1`). If a parent workflow already passed a `review_run_id`, reuse it instead of generating a new one.

For a single routed skill:

```text
Routed to: <skill-name>
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: <stack>
Signals: <markers>
Execution mode: inline | delegated
Applied learnings: none | <learning references>
Reason: <why this stack-specific reviewer was selected and why this execution mode was used>

<review output>
```

For multiple delegated skills:

```text
Routed to: <skill-a>, <skill-b>
Review session ID: <review-session-id>
Review run ID: <review-run-id>
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: Mixed
Signals: <markers>
Execution mode: delegated
Applied learnings: none | <learning references>
Reason: <why multiple stack-specific reviewers were selected and why delegated routing was required>

<merged delegated review output>
```

For unsupported stacks:

```text
Detected review scope: <staged changes / unstaged changes / working tree / commit range / PR diff / files>
Detected stack: Unknown/Unsupported
Signals: <markers>
Result: No matching platform pack is available yet.
```

## Telemetry

This shell is thin by design and **never emits telemetry on its own** — routing metadata is carried in the concrete routed pack's telemetry call.

For telemetry ownership, triage ownership, and the `orchestrated` flag contract, follow [telemetry-contract.md](telemetry-contract.md).

The `orchestrated` flag must come from the caller (the orchestrator passes it explicitly). A standalone review never sees the flag set and always emits `skillbill_review_finished` as before.
