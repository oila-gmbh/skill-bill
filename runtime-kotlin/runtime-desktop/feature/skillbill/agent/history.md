# SkillBill desktop feature — history

## [2026-05-12] SKILL-44 state-repo-tree (subtask 01)
Areas: runtime-desktop/feature/skillbill, runtime-desktop/core/data
- Closed two test gaps against the read-only repo browser polished in earlier SKILL-44 commits: AC7 collapsed-group keyboard navigation in SkillBillViewModelTest (asserts moveSelection traverses only visibleItems(expandedNodeIds) and clamps at the end), and AC11 failed-open no-write invariant in RuntimeRepoBrowserServiceTest (snapshot-compare around a failed open + recovered open + treeFor + describeSelection).
- Pattern reinforced: state-repo-tree contract verification reuses MutableSkillTreeService, FakeSkillBillServices, seedRepo, and repoFileSnapshot helpers — prefer these over new test scaffolding when extending coverage.
- AC scaffolding was already in place from the prior SKILL-44 commits (SkillBillViewModel activeOperationToken stale-load protection, RuntimeRepoBrowserService SHA-256 repoToken, model-backed status bar, expect/actual JFileChooser); subsequent subtasks should layer on top of this state model rather than reimplementing it.
- Mechanical spotless reformat of SkillBillFrame.RepositoryAction signature; no behavior change.
- Limitation: review surfaced 3 deferred Minor findings (clamp-tied assertEquals at end of moveSelection test, missing expanded-precondition assert, missing failure-result assert on the failed-open call) — tighten in a follow-up testing pass.
Feature flag: N/A
Acceptance criteria: 11/11 implemented
