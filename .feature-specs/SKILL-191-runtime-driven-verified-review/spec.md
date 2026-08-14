# SKILL-191 — Runtime-driven verified review

## Intended Outcome

`bill-code-review` stops being a prose-orchestrated skill and becomes a thin entry
over a runtime-owned review driver. The driver runs three durable stages instead of
one: the review pass that produces findings, a claim-verification stage that tests
each finding against the code, and a spec-adjudication stage that weighs surviving
findings against the governed feature spec when one resolves.

Every stage boundary is enforced by the runtime and loud-fails when a stage is
missing or its output fails its schema, so a finding that reaches a consumer carries
a verdict the runtime recorded rather than a claim an agent asserted. Both entry
points — standalone `bill-code-review` and the feature-task runtime's review phase —
run the same driver.

## Background

The runtime already owns more of review than the skill's `content.md` implies.
`ParallelCodeReviewRunner` resolves scope and diff, detects the stack, plans rubrics
through the routed pack, compiles the review-context packet, launches the inline
parent lane and every specialist through `AgentRunLauncher`, runs
`ReviewIntegrationPassRunner` as a bounded post-pass, records durable lane
dispositions and integration boundaries, and supports resume by selecting only lanes
without a durable result. `ReviewPreparationService`, `ReviewPacketProjection`, and
the `review-context-schema.yaml` contract already bound every projection handed to a
worker.

Three gaps make review results weaker than that machinery deserves:

- **Findings are unverified claims.** No stage tests whether a finding's cited code
  says what the finding says. A single attentive pass — which is what the default
  `inline` tier is — reliably produces plausible-but-wrong claims. Inside the
  feature-task loop a false Blocker burns an `implement_fix` round, and the fix for a
  non-bug is a fresh chance to introduce a regression.
- **Review cannot see intent.** Inside feature-task the review phase receives
  acceptance criteria through `FeatureTaskRuntimeRunInvariantPromptAllowlist`, but
  never the spec's constraints, non-goals, or deliberately deferred items — exactly
  the material that decides whether a finding matters here. Standalone review has no
  spec awareness at all. The packet already reserves `criteria_references` on both
  the lane assignment and the specialist launch, and the only non-test producer fills
  it with the literal placeholder `"independent branch-diff specialist review"`
  (`ParallelReviewPreparationCompiler.kt:137`).
- **The standalone entry bypasses the driver.** `skills/bill-code-review/content.md`
  carries mode parsing, scope resolution, signal classification, rubric naming,
  worker preflight, output format, merge, and failure handling as prose. Those steps
  are skippable instructions with no durable record between them, and any measurement
  of review quality taken from them is agent self-report.

## Design

### Three stages, one driver

The driver gains two stages after the review pass reaches a terminal state, modelled
on the existing integration pass: a runtime-owned bounded launch, a schema-validated
result, and its own durable boundary distinct from the stage before it.

**Stage 1 — claim verification.** Runs on every finding, in both tiers. A fresh
worker receives the finding verbatim, the cited region, and the delta. It receives
**no spec and no reviewer narrative**: the question is factual, and intent must not
contaminate it. It answers `confirmed`, `refuted`, or `unresolved`.

**Stage 2 — spec adjudication.** Runs only on findings stage 1 did not refute, and
only when a spec intent projection resolved. It assigns a scope disposition:
`in_scope`, `out_of_scope_preexisting`, `spec_deviation`, or `spec_accepted_tradeoff`.
It may raise severity as well as lower it.

Splitting them is load-bearing. Merged into one pass, a judgment argument launders
itself into a factual retraction — "not really a problem, the spec only asked for X"
gets recorded as a refuted claim when the claim was true and merely out of scope, and
those two outcomes need different downstream handling.

### Stage 1 runs in both tiers

`skills/bill-code-review/content.md` currently states that depth is the only thing
the light tier lowers, and that the finding admission gate, evidence requirements,
severity vocabulary, register format, and telemetry are inherited unchanged. Gating
verification on `mode:delegated` would break that: inline findings would be
unverified claims and delegated findings verified ones. It would also remove
verification from the default path — omission and `auto` both resolve to `inline`,
which is what runs on every subtask review pass inside the feature-task loop.

What varies by tier is verifier **depth**, consistent with existing doctrine: an
inline verifier reads the cited region and direct callers; a delegated verifier may
expand evidence through the existing bounded broker and expansion ledger.

Cost scales with finding count, not delta size — a verifier reads only cited
locations and never traverses the delta. The saving comes from stage 2 skipping
entirely when no spec resolves, which is the common case for ad-hoc standalone
review.

### Guards against retraction drift

A verifier asked to check a finding will produce a plausible reason it is fine unless
the design makes that expensive:

- **Fresh context, one finding per worker.** A worker shown the whole register
  calibrates to an expected refutation rate and clears findings to meet it.
- **Symmetric evidence bar.** A refutation cites the construct that makes the code
  safe, at `file:line`, exactly as the finding must. Hedged reasoning without a
  citation is inadmissible.
- **Default `unresolved`, never `refuted`.** An unsettled finding stays in the
  register at reduced confidence.
- **Verdicts append; they never rewrite.** No stage may edit a finding's text,
  severity, or location in place. The original claim stays verbatim beside its
  verdicts, so drift stays visible.
- **Stage 2 is bidirectional.** A stage that can only downgrade is a filter with a
  better name and will drift toward clearing the register.

### Spec context weighs findings, it never waives them

A spec is not a defect waiver. A downgrade cites the spec line — non-goal,
constraint, deferral — that justifies it, and an uncited downgrade is inadmissible on
the same grounds as an uncited finding. `spec_deviation` is a finding class review
cannot currently produce at all: code that contradicts a stated constraint. Inside
feature-task it stays distinct from the audit phase, which owns "criterion
unsatisfied"; review owns "the code contradicts what the spec says".

Specs are not pasted into the packet. A bounded **spec intent projection** carries
intended outcome, acceptance criteria, constraints, non-goals, and deferred items
under its own declared budget, because per-lane launch budget is 65536 bytes and a
parent spec plus subtask specs exceeds it.

### Scope of the runtime move

The runtime owns stage sequencing, durable stage state and resume boundaries, launch
projections, budgets, verdict schemas and admission rules, register assembly, merge,
and measurement. Workers still do the reading and judging — the runtime launches them
through the existing `AgentRunLauncher` path that already carries the inline parent
lane and every specialist. `content.md` shrinks to argument recognition and an
instruction to invoke the driver.

## Acceptance Criteria

1. `review-context-schema.yaml` defines `spec_intent_projection`, `verification_launch`, `adjudication_launch`, and `finding_verdict`, its `contract_version` is bumped, the Kotlin `*_CONTRACT_VERSION` constant matches, a parity test pins it, and every parse seam loud-fails with a typed `InvalidReviewContextSchemaError`.
2. Review stage state — per-finding verdicts, stage boundaries, and the resolved spec projection reference — persists durably, applies idempotently to an existing database on startup, and survives process restart.
3. A resume re-runs only stages without a durable result; a run that crashed between the review pass and stage 1, or between stage 1 and stage 2, resumes at the missing stage rather than re-running the review pass.
4. The driver resolves a governed spec for the review scope, emits a bounded spec intent projection carrying intended outcome, acceptance criteria, constraints, non-goals, and deferred items, and rejects a projection that exceeds its declared budget instead of truncating it.
5. When no spec resolves, the run records `spec_context: none` with a reason, skips stage 2, and completes; when a spec is explicitly named and cannot be read, the run loud-fails.
6. Stage 1 runs on every finding in both `inline` and `delegated`, one finding per worker, and its launch projection contains neither the spec intent projection nor reviewer narrative.
7. A stage 1 verdict of `refuted` requires a `file:line` citation for the construct that makes the code safe; a verdict without one is rejected and recorded as `unresolved`.
8. Stage 2 runs only on findings stage 1 did not refute and only when a spec intent projection resolved, and can raise as well as lower severity.
9. A stage 2 disposition that lowers severity or marks a finding out of scope cites the spec element that justifies it; an uncited downgrade is rejected.
10. No stage mutates a finding's text, severity, or location in place; the original claim is preserved verbatim alongside its recorded verdicts and any severity adjustment.
11. The assembled register carries every finding with its stage verdicts, and findings are marked rather than dropped — refuted, unresolved, and out-of-scope findings remain visible in the output.
12. Downstream consumers — the findings ledger, triage, the `implement_fix` handoff, and `blocker_dispositions` — treat confirmed and in-scope findings as actionable and read the recorded verdicts rather than re-deriving them.
13. Telemetry records per-stage verdict distribution and refutation rate on `skillbill_review_finished`, and every skipped stage or degraded spec resolution emits a record per `docs/observability-policy.md`.
14. `skill-bill code-review` drives a standalone review end to end with no second lane required, and `skills/bill-code-review/content.md` no longer carries scope resolution, signal classification, rubric naming, merge, or failure-handling prose.
15. The feature-task runtime's review phase delegates to the same driver rather than launching an agent under a prose directive, and both entry points produce identical stage records for the same delta and spec.
16. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Constraints

- The finding admission gate, severity vocabulary, evidence requirements, `F-XXX` register format, and telemetry stay tier-invariant. Only verifier depth varies between `inline` and `delegated`.
- Review remains read-only. No stage may build, compile, or run tests; validate owns execution.
- No new `claude -p` paths for work an in-session or existing `AgentRunLauncher` route already covers.
- Stage launches reuse the existing bounded projection machinery — declared budgets, the evidence broker, the expansion ledger — and reject oversized projections instead of truncating.
- Column ensures run unconditionally on startup; appending a column to an already-applied migration body is a silent no-op on existing databases.
- Public-repo hygiene: no company identifiers in any artifact, fixture, or example.

## Non-Goals

- Changing the severity vocabulary, the confidence vocabulary, or the `F-XXX` register line format.
- Moving worker execution off `AgentRunLauncher` or introducing a new agent launch mechanism.
- Replacing the audit phase's criterion-gap detection. Audit keeps "criterion unsatisfied"; review gains "code contradicts a stated constraint".
- Rewriting the routed pack rubrics or the Diff-Signal Routing Table.
- Automatic spec authoring or spec repair when a spec is missing or stale.
- Changing `bill-code-review-parallel` lane-2 semantics beyond making a second lane optional.

## Decomposition

| # | Subtask | Depends on |
| - | ------- | ---------- |
| 1 | Stage contract and schema versioning | — |
| 2 | Durable review stage state and resume boundaries | 1 |
| 3 | Spec intent projection resolver | 1 |
| 4 | Stage 1 — claim verification runner | 1, 2 |
| 5 | Stage 2 — spec adjudication runner | 1, 2, 3, 4 |
| 6 | Verdict-aware register assembly and downstream consumers | 4, 5 |
| 7 | Stage telemetry and measurement | 4, 5 |
| 8 | Runtime-driven standalone entry | 6 |
| 9 | Feature-task review phase delegation | 6, 8 |

## Validation Strategy

Each subtask carries its own validation. Across the feature:

- Contract parity tests pin every new schema version to its Kotlin constant, following
  `PlatformPackSchemaContractVersionTest`.
- Durability and resume are covered by restart tests that assert stage selection, not
  by asserting call sequences.
- Verdict admission rules are covered one test per rule — uncited refutation rejected,
  uncited downgrade rejected, in-place finding mutation rejected — with no literal
  variation siblings.
- Entry-point parity is covered by one test asserting that the standalone driver and
  the feature-task review phase produce the same stage records for the same delta and
  spec.

## Next Path

```bash
skill-bill goal SKILL-191
```
