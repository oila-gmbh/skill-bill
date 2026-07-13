---
status: Blocked
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

## Operator-authorized integration exception

The requesting user explicitly authorizes this subtask to continue on the
current `feat/SKILL-117-local-work-list` branch and to retain the existing
SKILL-117, SKILL-121, and SKILL-122 artifacts in its integration delta. This
is an accepted integration baseline, not a reason to absorb additional
unrelated work.

The requesting user also authorizes the required full installation during this
goal continuation. Run it only after the normal validation gates and do not
use its result to bypass a failing audit, review, or validation gate. The
installation must stage every supported agent and every discovered platform
pack with full telemetry enabled, and install the desktop application.
For this one authorized run, bypass the goal-continuation installer environment
guard solely to perform the requested full install after the workflow state is
durably safe to preserve.

The requesting user directs that this integration run must not launch another
code-review pass. Preserve the prior durable review evidence and do not invoke
`bill-code-review`, a review specialist, or a parallel review lane again.
Continue through audit, validation, installation, history, and commit gates;
do not represent the user-directed skip as a clean review verdict.

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
