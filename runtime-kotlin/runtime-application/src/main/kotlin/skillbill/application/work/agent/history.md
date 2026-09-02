# work / IdeStatus boundary history

## [2026-09-01] SKILL-228 subtask 2 — IdeStatus validate/build operator-block visibility
Areas: runtime-application/work, runtime-application/featuretask, orchestration/contracts, intellij-plugin/{domain,infrastructure/cli,presentation,ui}
- Goal/child IdeStatus now projects `lifecycle_state: blocked` when validate or build is durable `BLOCKED` + `NEEDS_USER_ACTION`, even while the parent lease is live — not `active` with an in-progress phase label.
- `operatorDecisionPause` (and parallel pause/summary fields) read that disposition on quality-gate phases, not only `PAUSED` + `NEEDS_USER_ACTION`; `pause_reason` / summary carry the phase blocked reason for CLI and IntelliJ details.
- Repair-loop `BLOCKED` without operator disposition stays `active`. Schema goldens and plugin `Blocked.pauseReason` stay on contract `0.2` without a bump.
- Pattern: durable disposition owns operator-facing blocked lifecycle; omit disposition to keep repair-loop active projection. reusable
Feature flag: N/A
Acceptance criteria: 5/5 implemented
