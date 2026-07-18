---
status: Pending
---

# SKILL-130 Subtask 2 - Issue navigation and state

## Scope

Connect the issue summary/detail projections to desktop domain models, gateways, controllers, and view state. Change Work navigation identity from workflow execution to issue identity, add selection and detail loading, and preserve predictable behavior through refreshes, repository changes, errors, and stale responses.

## Acceptance Criteria

1. The desktop Work list shows one row per issue key and separately presents workflows without issue keys.
2. Selecting or keyboard-activating a row loads the issue detail and routes the main pane to a dedicated read-only execution view.
3. Selected issue identity and visible row remain stable through refresh when present; disappearance, repository switch, and stale asynchronous responses are handled deterministically.
4. Summary and detail loading, empty, partial, and error states are distinct and do not discard the last valid unrelated application state.
5. Gateway and controller tests prove one-row identity, selection, refresh, error recovery, repository scoping, and stale-token behavior.
6. Existing tree/editor navigation continues to work, and selecting normal authored content cleanly leaves the execution route.

## Non-Goals

- Final dashboard visual composition.
- Database or artifact parsing inside the desktop layer.
- Workflow mutation actions.

## Dependency Notes

Depends on subtask 1's issue summary/detail contracts.

## Validation Strategy

Add common and JVM desktop domain/data/state tests, including refresh races and repository switching, then run the desktop and full runtime Gradle checks.

## Next Path

Proceed to the dashboard UI once issue selection and detail state are stable.

