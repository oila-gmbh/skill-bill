# SKILL-178 · Subtask 4 — Governed content and parity-lock sweep

## Scope

Runtime behaviour and governed skill content must state the same rule. Subtasks 1–3
change behaviour; this subtask makes the prose agree and repairs the content-lock
tests that assert the old rule.

Governed content asserting the Blocker-only rule (line numbers approximate — locate
by text, the in-flight SKILL-175 branch is editing these files):

- `skills/bill-feature-goal/content.md` — the review-contract paragraph (~134) and
  the audit-first/ledger paragraph (~418), both of which say "Only an unresolved
  Blocker finding reopens `implement_fix`" and "a surviving Major moves on and is
  recorded in the ledger". Also the non-convergence paragraph (~151) that says "the
  same Blocker set with no repository change".
- `skills/bill-feature-task-runtime/content.md` — the remediation paragraph (~227)
  and the phase-order paragraph (~383): "Blockers prevent advancement; non-blockers
  advance and are persisted in the goal-wide unaddressed-findings ledger", plus the
  non-convergence sentence (~235).
- `orchestration/review-orchestrator/PLAYBOOK.md` — the `context:feature-remediation`
  definition (~67) and the remediation-scope paragraph (~71), which defines the delta
  as "the prior Blocker findings union the pre-fix-to-post-fix diff" and requires an
  evidenced disposition "for every prior Blocker".
- `skills/bill-code-review/content.md` — ~15, ~69, ~106, the remediation-context
  argument and delta definition.

Keep the ledger sentence: Minor and Nit still advance and are still recorded, and
`skill-bill goal findings --issue-key <KEY>` remains the location-bearing surface.

Tests locking the old rule, to update rather than delete:

- `FeatureTaskRuntimeRemediationPassPromptTest`
- `FeatureTaskRuntimeAuditEntryGateTest` (asserts the remediation-delta phrasing at
  ~560)
- `FeatureTaskRuntimeRunnerTest` (~1893)
- `UnboundedRemediationLoopGovernedContentTest` (~113) — a governed-content lock that
  reads the skill markdown and asserts phrasing, so it fails until the content above
  is updated
- `FeatureTaskRuntimePhasePromptComposerTest` (~281)
- Any parity test asserting skill content matches runtime directives; find these by
  running the full suite rather than from this list alone.

Finish with a repository-wide sweep for stale statements of the Blocker-only rule in
docs, `AGENTS.md`, and area `agent/decisions.md` files. Record a boundary decision
for the gate change if the affected area keeps a decision log.

## Coordination note

SKILL-175 is concurrently editing `skills/bill-feature-goal/content.md`,
`skills/bill-feature-task-runtime/content.md`, and the runtime test files, and it
deletes the prose surfaces. Rebase onto its landed state before editing. Do not
reintroduce a prose-mode surface this feature would otherwise have had to update — if
a prose paragraph is already gone, leave it gone.

## Acceptance Criteria

1. Every governed skill content file that states the remediation rule says a remediation round is handed all findings and that both Blocker and Major block advancement, with no surviving statement that only a Blocker reopens `implement_fix`.
2. Every governed statement of the remediation-delta scope defines it as all findings addressed in that round unioned with the pre-fix-to-post-fix diff.
3. Every governed statement of the non-convergence path describes the unresolved Blocker-or-Major set and the human-resumable, uncapped block.
4. Governed content still states that Minor and Nit findings advance, are recorded in the goal-wide unaddressed-findings ledger, and that location-bearing detail is available only through `skill-bill goal findings --issue-key <KEY>`.
5. The content-lock and parity tests listed in scope are updated to assert the new rule and pass; none is deleted or disabled to make the suite green.
6. No prose-mode surface is added or reintroduced.
7. A repository-wide search for the Blocker-only rule returns no stale statement in skills, orchestration playbooks, or docs.
8. The full build and test suite passes, excluding failures already failing on `main` for unrelated reasons, which must be named explicitly in the result rather than silently tolerated.

## Non-Goals

- Changing runtime behaviour; subtasks 1–3 own that. If this subtask finds behaviour
  that contradicts the content, fix the content and report the gap rather than
  silently changing the gate.
- Rewording governed content beyond the remediation rule.
- Changing the ledger or its retrieval command.

## Dependencies

Subtasks 1, 2, and 3 — the content must describe behaviour that already exists, and
the content-lock tests cannot pass until both sides agree.

## Validation Strategy

- Run the full build and test suite. The content-lock tests are the primary gate:
  they read the governed markdown and compare it to runtime directives, so they fail
  until both sides agree.
- Grep the repository for the stale rule (for example "only a surviving Blocker",
  "prior Blocker findings union", "a surviving Major moves on") and assert no hit
  outside `.feature-specs/done/` historical specs.
- Verify a rendered/installed skill carries the updated content, not only the
  repository source.
- Known unrelated failures to distinguish from real regressions: `:runtime-infra-fs:sourcesJar`
  fails on clean `main` (verify with `-x sourcesJar`), and a stale `compileTestKotlin`
  cache can produce `NoSuchMethodError` in the IntelliJ plugin (clear with
  `--no-build-cache`, not `clean`).

## Next Path

Feature complete. Open the parent PR.
