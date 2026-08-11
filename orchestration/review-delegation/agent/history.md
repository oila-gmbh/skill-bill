## [2026-08-11] SKILL-182 subtask 1 Cursor harness section in review-delegation
Areas: orchestration/review-delegation
- Added a `## Cursor` section to `PLAYBOOK.md` so Cursor no longer falls through the undocumented-runtime stop for delegated-required scopes.
- Cursor launches named installed subagents (project scope wins on name conflict), one lane per routed review skill or specialist pass, all selected lanes in one parallel instruction, with no model override and the embedded native-agent rubric authoritative.
- Reusable: when a harness returns no launch id, lane identity is the launch-plan pair (routed area + assignment digest); shared worker-tracking admits that identity without weakening the no-global-listing rule.
- Negative paths: no attributable structured report fails the lane; parent answering a lane inline must report inline coverage; unavailable subagents stop as delegated-unavailable with no silent inline downgrade.
- Shared id-tracking rule reworded to harness-available identity; Copilot, Claude, Codex, and Junie sections left byte-identical.
Feature flag: N/A
Acceptance criteria: 9/9 implemented
