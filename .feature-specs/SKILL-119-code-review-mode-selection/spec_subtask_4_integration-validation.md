---
status: Ready for implementation
issue_key: SKILL-119
parent_issue_key: SKILL-119
subtask_id: 4
---

# SKILL-119 Subtask 4: Integration, migration boundary, and validation

## Outcome

The independently delivered selection and goal-policy work is verified as one
feature, installed from governed source, and ready for a clean goal workflow
without relying on the abandoned single-spec runtime row.

## Scope

- Reconcile subtask outputs against all parent acceptance criteria and preserve
  the old single-spec workflow as historical evidence rather than resuming it.
- Add cross-boundary regression tests for default compatibility, runtime/prose
  parity, resumed goal state, parallel-as-one-pass, raw-artifact retention, and
  no-path goal summaries.
- Resolve Detekt issues in SKILL-119-owned code and tests so the full Kotlin
  validation gate is meaningful on the selected integration branch.
- Run governed validation, full Kotlin checks, source lint, agent-config
  validation, and the required install refresh; inspect installed sidecars and
  native-agent output without committing generated artifacts.

## Acceptance Criteria

1. Parent acceptance criteria 1 through 15 are covered by a combination of
   subtask tests and at least one cross-boundary regression for runtime and
   prose goal behavior.
2. Parent criterion 16 is satisfied: the required validation commands pass on
   the selected integration branch, including full `./gradlew check`.
3. Local install staging reflects every authored skill and native-agent source
   change, while generated artifacts remain outside the repository worktree.
4. The decomposition is ready to run through `skill-bill goal SKILL-119` and
   does not resume or mutate `wftr-20260713-045113-oseu`.

## Non-goals

- Do not use manual audit, validation, or review overrides to claim feature
  completion.
- Do not absorb unrelated SKILL-117, SKILL-120, or SKILL-121 changes merely to
  make the integration branch green.

## Dependencies

Depends on subtasks 1, 2, and 3.

## Validation Strategy

Run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
```

Then inspect installed `bill-feature`, `bill-code-review`, and subtask-runner
artifacts for the expected selection and continuation guidance.

## Next Path

Run the decomposed goal after all dependencies are complete.
