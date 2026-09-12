# Subtask 2: Retained content checkpoints and final audit eligibility

## Scope

Close the repository-evidence boundary for diagnosis, authorized repair, checkpoint capture, final assessment and review handoff. Every completion path must prove the same authoritative criterion census and exact final content. Include dirty implementations that need no repair, later repair rounds, new and deleted files, interrupted capture and carried output.

Own parent ACs 3 and 4, the repository portion of AC 5, the downstream gating portion of AC 7, and related compatibility and regression coverage. Preserve subtask 1's ownership, recovery and operator controls.

## Acceptance Criteria

1. Before repair and capture, the runtime persists an authorized scope and content identity covering its owned implementation changes and repairs. Agent-reported outcome paths cannot enlarge that scope or omit existing owned dirty content. No branch-history or index mutation is required to capture audit evidence.
2. Diagnosis, checkpoint intent, retained capture, final assessment and review handoff use a consistent content identity. The activity fingerprint is not compared with a Git tree ID. Schema and codec distinguish identities where needed and reject incompatible active records through typed recovery.
3. Capture uses an isolated index, preserves HEAD and the user's staged entries, and retains files, modes, deletions and new owned files. Initially satisfied audits retain dirty implementation content even with an empty repair-outcome list.
4. Capture intent is durable before Git operations. Reopening after capture but before attachment verifies and attaches the same immutable content without another repair. Different rounds have distinct intents and retained references. Conflicting or corrupt retained content blocks; a retry cannot silently replace the content of an existing intent.
5. Retained verification proves checkpoint content against scope and expected identity, not merely commit existence. Current verification checks the same scope. Every final-audit acknowledgement returns the engine-verified immutable checkpoint and enough scope data for an exact re-audit.
6. One final eligibility operation verifies the authoritative criterion census, satisfied durable assessment, exact final output value, pending validation obligations, retained content and current content. Tool settlement, envelope ingestion, carried-forward restoration and review handoff all use it before publishing completion.
7. Review checkpoint creation receives eligibility checks before and after it runs. Message-only amendments preserve eligibility; changes to reviewable content invalidate it. Carried-forward paths use the owning cycle and attempt rather than a hard-coded attempt or an unchecked saved output.
8. Missing active run authority produces typed recovery rather than disabling cycle requirements. Valid completed legacy records remain readable independently of the current worktree. Active incompatible evidence cannot be promoted to legacy success.
9. Real Git and SQLite regressions prove multi-gap repair, initially satisfied dirty files, unrelated staged files, capture interruption, later rounds, wrong retained content, changed review content and message-only amendment. A failure at final re-audit cannot advance to review or validation.
10. The touched modules pass their configured quality gate. Test and full-quality obligations remain explicit when the selected build gate proves only compilation.

## Implementation Baseline

The current tree separates repository activity from content identity, captures retained commits with an isolated index, pins the initial implementation checkpoint and checks retained/current content at settlement. Carried and restored completion compare the exact durable final value. Existing real Git and SQLite tests cover several capture and settlement failure cases.

Those tests do not by themselves prove every requirement in this census. In particular, verify scope authorization before capture and the durable intent boundary before Git operations. Do not reinterpret an implementation shortcut as permission to weaken those requirements.

## Implementation Plan

1. Trace who owns and persists the implementation scope, authorized repair scope, content identity and capture intent. Verify that agent outcome paths cannot independently authorize extra content or exclude existing owned changes.
2. Check the ordering of durable intent, Git capture and durable attachment. Repair any path that cannot recover an interrupted capture against the same intent and immutable content.
3. Preserve isolated-index capture and prove file contents, modes, additions and deletions. Keep activity diagnostics separate from the content identity used by diagnosis, final assessment and review.
4. Verify retained references against intent, scope and expected content. A conflicting existing intent must block rather than move to newer content. Return the verified immutable checkpoint through CLI and MCP acknowledgements.
5. Trace one final eligibility operation through tool settlement, envelope ingestion, carried output, satisfied restoration and review's before/after checks. Require the full census, exact final value and pending validation before publishing completion.
6. Preserve valid completed legacy reads while rejecting missing or incompatible active authority. Add only the real Git and persistence regressions needed to prove remaining gaps.

## Non-Goals

- No repair of unrelated worktree changes, branch reset or checkpoint retention-policy change.
- No acceptance-criteria extraction changes, substitute repair claims or validation claims as audit evidence.
- No replacement of provider continuation, operator controls, review or validation.

## Dependency Notes

Requires subtask 1. Use its fenced state owner and recovery identities. Persist capture intent before the Git operation and attach afterward through the same governed cycle. Git operations run outside SQLite transactions; the durable intent and attachment must make interruption recoverable without holding a database transaction across Git work.

## Validation Strategy

Use a temporary Git repository and production SQLite stage service. Cover an initially satisfied dirty implementation, multiple authorized gaps, unrelated staged content, file modes, additions and deletions. Verify that capture preserves HEAD and the user's index.

Interrupt after intent persistence and again after Git retention but before attachment. Reopen and prove attachment of the same content without another repair. Test conflicting retained evidence, unauthorized outcome paths, omitted owned paths, exact final-value mismatch, content-changing review preparation and message-only amendments. Failed final eligibility must prevent review and validation.

## Mandates and Overrides

- Treat the existing working-tree changes as the implementation baseline. Preserve correct behavior and close only demonstrated acceptance gaps. Do not infer acceptance from existing code, repair prose or a passing compile.
- Preserve source and generated-output boundaries, manifest-driven routing, typed contract failures, cancellation and diagnostics required by AGENTS.md.
- Audit owns diagnosis, authorized repair and final repository assessment in one attributable cycle. Stop after a stage rejection or paused acknowledgement. Do not return to an unrelated implement session to repair audit gaps.
- Keep builds and test execution with their authorized validation phase. A goal build-only gate runs only its declared build commands. Existing external validation evidence must name its tested source state and cannot establish later untested changes.
- Do not install, reset workflow state, change acceptance criteria, or edit SQLite from a goal child. The runtime owns subtask commits and pushes under same_branch_commit_per_subtask.

## Next Path

After final eligibility passes and the runtime records this subtask's reviewed commit, continue to subtask 3 for status, durable telemetry and assembled parent acceptance.
