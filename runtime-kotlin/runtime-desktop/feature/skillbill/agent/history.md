# SkillBill desktop feature — history

## [2026-05-12] SKILL-44 validation-workbench (subtask 02)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data, runtime-desktop/core/domain, runtime-desktop/core/testing, runtime-core/scaffold
- Added on-demand validation: SkillBillViewModel.beginValidate/runValidate(request)/finishValidate mirrors the Subtask 01 begin/finish + activeOperationToken pattern, plus a new reusable ValidationRunRequest that captures token + session + previousValidationSummary on the caller dispatcher so stale finishes restore the pre-RUNNING summary instead of leaving the slice stuck on RUNNING.
- New domain boundary `ValidationGateway` in core/domain with `validate(session)` + `resolveTreeItemIdForSource(session, path)`; JVM impl on RuntimeRepoBrowserService delegates to RepoValidationRuntime.validateRepo via an internal `validator: (Path) -> RepoValidationReport` seam — reusable test hook for FAILED-by-exception branches without leaking the seam into production callers.
- `RepoValidationReport.structuredIssues` widened additively in runtime-core; CLI `toPayload()` shape preserved so `skill-bill validate --format json` contract holds.
- Inspector + bottom-dock validation rows are state-driven from `state.validation.issues` (single source); every row surfaces 5 fields (severity name, code, message, sourcePath, exceptionName) and both surfaces render a FAILED-no-details fallback row symmetrically.
- Clipboard side effect for "copy source path" is fully hoisted to SkillBillRoute (LocalClipboardManager.current read in the route); inspector emits `onCopyIssueSource` only. Pattern: keep all Compose-thread side effects at the route boundary so inspector composables stay pure.
- Refresh and repo-switch both reset `validation` to UNAVAILABLE unconditionally (no sameRepo guard) — on-disk state may have changed.
- Limitation: per-issue parsing in RepoValidationIssue.fromRawIssue is a heuristic over the legacy string format (severity defaults to ERROR, code/name unset); future work should let RepoValidationRuntime emit truly structured issues at source so the desktop UI stops paying parser cost. Pre-existing concurrency hazard on `RuntimeRepoBrowserService.snapshot` (non-volatile var, cross-dispatcher) carried forward from Subtask 01; not introduced here.
Feature flag: N/A
Acceptance criteria: 8/8 implemented

## [2026-05-12] SKILL-44 state-repo-tree (subtask 01)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data
- Closed two test gaps against the read-only repo browser polished in earlier SKILL-44 commits: AC7 collapsed-group keyboard navigation in SkillBillViewModelTest (asserts moveSelection traverses only visibleItems(expandedNodeIds) and clamps at the end), and AC11 failed-open no-write invariant in RuntimeRepoBrowserServiceTest (snapshot-compare around a failed open + recovered open + treeFor + describeSelection).
- Pattern reinforced: state-repo-tree contract verification reuses MutableSkillTreeService, FakeSkillBillServices, seedRepo, and repoFileSnapshot helpers — prefer these over new test scaffolding when extending coverage.
- AC scaffolding was already in place from the prior SKILL-44 commits (SkillBillViewModel activeOperationToken stale-load protection, RuntimeRepoBrowserService SHA-256 repoToken, model-backed status bar, expect/actual JFileChooser); subsequent subtasks should layer on top of this state model rather than reimplementing it.
- Mechanical spotless reformat of SkillBillFrame.RepositoryAction signature; no behavior change.
- Limitation: review surfaced 3 deferred Minor findings (clamp-tied assertEquals at end of moveSelection test, missing expanded-precondition assert, missing failure-result assert on the failed-open call) — tighten in a follow-up testing pass.
Feature flag: N/A
Acceptance criteria: 11/11 implemented
