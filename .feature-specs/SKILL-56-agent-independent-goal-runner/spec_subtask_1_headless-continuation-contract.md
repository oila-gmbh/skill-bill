# SKILL-56 Subtask 1 - Headless Continuation Contract for feature-implement

Parent spec: [.feature-specs/SKILL-56-agent-independent-goal-runner/spec.md](./spec.md)
Issue key: SKILL-56
Subtask order: 1 of 4
Depends on: none
Branch model: same-branch, commit per subtask

## Purpose

Make `bill-feature-implement` runnable **non-interactively** for "the next
runnable subtask of a decomposed `issue_key`," with PR creation suppressed, and
make it emit a **structured, machine-consumable result** for the subtask it ran.
This is the load-bearing assumption of the whole feature: if a goal runner
cannot reliably trigger one subtask headlessly and read back its outcome, no
launcher or driver built on top of it will be reliable. This subtask proves and
codifies that contract before any runtime infrastructure depends on it.

It changes only the *entry* and *result-reporting* edges of feature-implement.
The interactive flow, decomposition behavior, manifest schema, and every phase's
internal logic stay exactly as they are.

## Scope

In scope:

- Define a **goal-continuation entry contract** for `bill-feature-implement`: a
  non-interactive invocation parameterized by the parent `issue_key` (and
  optionally an explicit `subtask_id`) that:
  - resolves the parent decomposition manifest and selects the next runnable
    subtask via the existing `feature_implement_workflow_continue(issue_key)`
    semantics (pending + dependencies complete);
  - runs that single subtask through the normal pipeline
    (assess→preplan→plan→implement→review→audit→validate→write_history→commit_push);
  - **suppresses PR creation** (skips the `pr_description`/PR step) so the goal
    runner can open a single PR for the whole goal later;
  - **requires no interactive confirmation** for the subtask: acceptance
    criteria come from the self-contained subtask spec, not from a human prompt.
- Define the **structured outcome** the run records, with the **durable workflow
  store / manifest as the authoritative channel** (not process stdout). The run
  must persist, via the workflow tools it already calls
  (`feature_implement_finished`, manifest update on commit): `issue_key`,
  `subtask_id`, terminal `status` (`complete` | `failed` | `blocked`),
  `commit_sha` (when complete), `workflow_id`, `blocked_reason` (when blocked),
  and `last_resumable_step`. A top-level headless run interleaves prose and tool
  output, so **stdout is a liveness/diagnostic stream only** — the goal runner
  reads outcomes from the store, never by scraping stdout. (A `RESULT:` line may
  still be echoed for human/debug visibility, but nothing depends on parsing it.)
- Confirm / make idempotent the **branch handling for the decomposed case**: the
  first subtask creates the feature branch; subtasks 2..N must check out the
  existing shared branch, not `git checkout -b` it. Assert this holds for the
  goal-continuation entry rather than assuming it (it may already be handled by
  the SKILL-51 decomposition work — verify and record).
- Record the contract in `skills/bill-feature-implement/content.md` as a new
  "goal-continuation (non-interactive) entry" section, additive to the existing
  continuation-mode rules. Update `orchestration/skill-classes/feature-implement.yaml`
  and any workflow-contract docs (`orchestration/workflow-contract/PLAYBOOK.md`)
  only as needed to name the new entry mode.
- Provide a **dry-run / observability affordance** for de-risking: a way to
  invoke the continuation and have it report *which* subtask it would run and the
  result shape, without requiring a multi-agent setup (e.g. against a scratch
  decomposed manifest).

Out of scope:

- The agent-agnostic launcher that spawns the headless process (subtask 2).
- The manifest-walking loop across multiple subtasks (subtask 3).
- The CLI command and the `bill-goal` skill front (subtask 4).
- Any change to interactive feature-implement behavior or to how decomposition
  produces subtasks.
- Single-PR finalization (owned by the goal-runner service, subtask 3).

## Acceptance Criteria

1. There is a documented non-interactive entry to `bill-feature-implement`
   parameterized by parent `issue_key` (optionally `subtask_id`) that runs
   exactly the next runnable subtask and requires no human confirmation for that
   subtask.
2. The non-interactive run **suppresses PR creation** (no PR is opened) while
   still committing the subtask's work per the manifest `execution_model`.
3. The run records its structured outcome in the **durable workflow store /
   manifest** (the authoritative channel) with at least `issue_key`,
   `subtask_id`, `status` (`complete`/`failed`/`blocked`), `commit_sha` (when
   complete), `workflow_id`, `blocked_reason` (when blocked), and
   `last_resumable_step`. Nothing in the contract requires parsing process stdout
   to learn the outcome.
4. The contract correctly resolves the next runnable subtask using existing
   `workflow_continue(issue_key)` semantics: pending + dependencies complete; if
   none are runnable it reports that distinctly (all-complete vs blocked) rather
   than running an arbitrary subtask.
5. Branch handling is idempotent for the decomposed case: the first subtask
   creates the feature branch and subtasks 2..N check out the existing shared
   branch without error. Verified (or fixed) and recorded.
6. Interactive feature-implement is unchanged: running it directly on a spec
   still confirms acceptance criteria, can decompose, and creates a PR exactly as
   today (verified by existing feature-implement tests / golden fixtures).
7. **The headless-availability assumption is proven, not asserted.** For each
   target agent, a documented exercise shows that a *real headless process*
   (`claude -p` / `codex exec` / `opencode run` as applicable) has both the
   `bill-feature-implement` skill and the skill-bill MCP workflow tools available
   and can resolve the manifest. If an agent's headless context lacks them, that
   is recorded as a constraint the launcher (subtask 2) must satisfy by injecting
   skill + MCP config. The exercise runs against a **real, already-decomposed
   manifest** (not a toy), and its outcome is recorded in history/decisions.

## Validation

```bash
# Existing interactive contract intact (golden / contract tests):
(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-infra-fs:test)

# Skill + class + contract docs validate:
skill-bill validate
npx --yes agnix --strict .

# De-risk exercise (manual, documented), per target agent, against a REAL
# already-decomposed manifest:
#  1. Launch a real headless process (claude -p / codex exec / opencode run).
#  2. Confirm the bill-feature-implement skill AND skill-bill MCP workflow tools
#     are present in that headless context (resolve the manifest, list tools).
#  3. Confirm the goal-continuation entry resolves the next runnable subtask and
#     records its outcome in the WORKFLOW STORE (read it back), with no reliance
#     on stdout parsing.
# Record which agents passed and any context that had to be injected.
```

## Implementation Notes

- The runtime already resolves a parent manifest and selects the current subtask
  for `feature_implement_workflow_continue` when passed an `issue_key`
  (see SKILL-51 decomposition-workflow-state). Reuse that resolution; do not
  build a second selector.
- PR suppression should be a property of the *goal-continuation entry*, not a
  global flag that could leak into interactive runs. Keep interactive runs
  PR-creating by default.
- Keep the result contract identical in shape to fields the manifest already
  records (`status`, `commit_sha`, `workflow_id`, `last_resumable_step`,
  `blocked_reason`) so subtask 3 can map result → manifest update with no
  translation layer.
- This subtask is the right place to be conservative: if the headless trigger
  proves unreliable for any supported agent, surface that as a known limitation
  here (it shapes subtask 2's adapter list) rather than discovering it in the
  driver.
