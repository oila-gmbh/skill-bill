# SKILL-219 — Structured validate gate findings instead of one unparseable blob

## Intended Outcome

When the runtime-owned validation (or build) gate fails with several independent
failures — architecture boundary, dependency health (`projectHealth`),
architecture tests, module unit tests — the repair agent receives a **numbered,
discrete finding set** it can work through in one repair turn. Today many of
those failures collapse into a single `unparseable_gate_failure` whose message
is a head/tail stdout dump. The prompt already says “fix every finding in this
session,” but the handoff often contains only one checklist row.

This feature restores structured handoffs in two layers:

1. **Deterministic extraction** — extend collect-all parsing so known Gradle
   failure surfaces become separate `ValidationGateFinding` rows.
2. **Briefing-only triage fallback** — when parsing still yields only
   `unparseable_gate_failure`, one lightweight agent turn reads the blob and
   emits a recommended-shape `validation_repair_plan` for the repair briefing.
   The runtime does **not** validate that shape and does **not** treat it as
   proof. The next gate run remains the only pass/fail authority.

Measured on SKILL-20 subtask 2 (2026-08-28): four simultaneous gate failures
(architecture boundary, `composition`/`harness-cursor` projectHealth,
`architecture-tests`, `adapters-local` tests) were handed to validate repair as
one blob. The agent fixed several issues across turns but exhausted the repair
budget with one `harness-cursor` `implementation` → `api` line still open — a
fix that was obvious once the failure was isolated.

## Background

SKILL-192 subtask 1 added collect-all union parsing for compiler diagnostics
and JUnit/detekt artifacts. That removed the worst compile-only blob cases.
Gradle task failures that do not emit compiler `e:` lines or JUnit XML — notably
`:projectHealth`, `:architectureCheck`, and multi-task `BUILD FAILED` summaries
— still produce zero parsed findings, so `finalizeFindings` emits exactly one
`unparseable_gate_failure`.

SKILL-198 later simplified the validate cycle: repair turns, gate re-run to
verify, block after three repair turns. SKILL-192 subtask 3’s substantiation
receipt coverage gate and repair-plan proof were **removed** in that
simplification. This feature must not bring them back.

The validate repair prompt already tells the agent to copy open findings into a
numbered checklist (`VALIDATE_REPAIR_CHECKLIST`). That instruction cannot work
when the runtime only supplies one synthetic finding.

## Design

### Layer 1 — Parser expansion (runtime facts)

Extend collect-all finding extraction in `FileSystemValidationGateRunner` (and
any shared quality-tool stdout parser) so a failed gate run yields discrete
findings for at least:

- **Dependency health** — `:projectHealth` / `incorrectConfiguration` advice
  (module, dependency coordinate, required configuration).
- **Architecture boundary** — `:architectureCheck` forbidden project dependency
  lines.
- **Gradle task failure headers** — each `Execution failed for task
  ':module:task'` block in a multi-failure build, when no finer parser already
  produced a row for that task.
- **Existing paths unchanged** — compiler diagnostics, detekt XML, JUnit XML,
  spotless/ktlint stdout lines continue to parse as today.

Findings keep the existing identity key
(`module|ruleOrTestId|message|location`). `unparseable_gate_failure` is emitted
only when the run failed **and** every parser source is empty (same rule as
SKILL-192 subtask 1).

### Layer 2 — Triage fallback (agent prose, briefing only)

Trigger triage **only when** the finding set after a gate run is exactly one
finding with `ruleOrTestId == unparseable_gate_failure` (validate and build
gates that use the same coordinator/runner).

Sequence inside one validate/build gate cycle:

1. Runtime runs discovery gate (unchanged).
2. If findings are discrete (count > 1, or single non-unparseable finding),
   enter repair as today — **no triage turn**.
3. If findings are **only** `unparseable_gate_failure`, launch one **triage**
   agent turn before the first repair turn. Triage may read files and the blob;
   it must not run gate argv or quality-check skills.
4. Triage emits recommended-shape `validation_repair_plan` inside phase prose
   (same “stuff JSON into `value`” pattern as other prose phases). Suggested
   item fields: `item_id`, `module`, `rule_or_task`, `location`,
   `failure_summary`, `fix_intent`. Extra keys allowed; omissions allowed.
5. Runtime injects the plan into the repair briefing as **working notes** —
   not validated, not persisted as proof, not required for repair completion.
6. Repair turn(s) proceed as today (up to three turns, gate verify after each).
7. Pass/fail remains measured gate outcome only.

Explicitly **not** reintroduced:

- Substantiation receipts per finding identity.
- Repair-plan coverage gate before confirmation.
- Schema validation or rejection of triage output.
- A second agent proof path besides the gate.

### Build phase parity

`build` uses the same runner and repair coordinator shape as `validate`. Parser
expansion and triage fallback apply to both when the pack declares the
corresponding gate.

## Acceptance Criteria

1. A collect-all gate run that fails with `:harness-cursor:projectHealth`
   incorrect-configuration advice and `:architecture-tests:architectureCheck`
   forbidden dependency produces **at least two** discrete findings, neither of
   which is `unparseable_gate_failure`.
2. A collect-all run that fails with only JUnit/detekt/compiler findings
   behaves identically to today; no extra triage turn is inserted.
3. `unparseable_gate_failure` is emitted only when the run failed and every
   parser source (compiler, artifacts, quality-tool stdout, new Gradle-task
   parsers) returned empty.
4. When the post-gate finding set is exactly one `unparseable_gate_failure`, the
   runtime launches a triage agent turn before the first repair turn; when the
   set is already discrete, it does not.
5. Triage output is injected into the repair briefing as optional structured
   notes; the runtime does not schema-validate triage JSON and does not block
   repair on missing or malformed triage output.
6. Repair turns, three-turn cap, and post-repair gate verification are
   unchanged; gate pass/fail is the only proof of repair success.
7. Substantiation receipts, repair-plan coverage gates, and confirmation
   identity closure from SKILL-192 subtask 3 are not reintroduced.
8. Focused tests cover: multi-failure Gradle fixture → multiple findings;
   projectHealth-only fixture → discrete finding; unparseable-only fixture →
   triage turn scheduled; discrete findings → no triage turn; build gate
   shares parser/triage behavior.
9. `./gradlew check --continue` passes for the change when this feature lands.

## Non-Goals

- Requiring agents to run `./gradlew check`, `bill-code-check`, or
  `:projectHealth` during repair (repair-window rules from SKILL-198 stay).
- Validating or rejecting triage `validation_repair_plan` shape.
- Per-finding substantiation receipts or repair-plan proof gates.
- Parsing raw gate stdout into the durable agent handoff outside structured
  `ValidationGateFinding` rows (triage plan is briefing-only prose).
- Changing the three repair-turn cap or goal build/validate routing (SKILL-204).
- Skill-bill-v2 slot execution, installer, or harness work.

## Constraints

- Parser output is runtime-authored fact; triage plan is agent-authored prose.
- New parsers must be deterministic on fixture stdout; no LLM in the facts path.
- Pack argv and collect-all declaration stay pack-owned; do not hardcode
  Gradle flags in Kotlin beyond what existing packs already declare.
- Legacy workflows with empty triage fields decode as “no triage plan present.”

## Out of Scope Follow-ups

- LLM-based stdout parsing in the runner (triage fallback is enough for the
  long tail).
- Finding paging or handoff-budget splitting for very large discrete sets.
- Auto-running `spotlessApply` inside the coordinator (separate decision in
  SKILL-211 history).

## Validation Strategy

Fixture-driven runner tests for each new parser branch; coordinator tests for
triage scheduling predicates; one end-to-end feature-task-runtime harness case
with a synthetic unparseable blob proving triage → repair → verify ordering.
Shipped kotlin/kmp packs require no manifest change unless a new parser needs a
declaration hook (prefer stdout parsing first).
