## [2026-07-10] SKILL-112 iOS pack alignment
Areas: platform-packs/ios, platform-packs/ios/code-review, runtime-kotlin/runtime-infra-fs
- Rebuilt iOS quality checks around discovered project commands, xcodebuild/SPM
  selection, lint configuration, a priority fix ladder, and targeted-to-full escalation.
- Generalized review coverage with REST/Codable and Core Data/SwiftData branches,
  signal-based routing, and generated/vendored exclusions while retaining tuned rules.
- Added enforceable modern iOS checks for concurrency, background execution,
  SwiftUI/image/import performance, platform security, and observable-object ownership.
- Reusable: pack conformance tests now assert manifest and agent metadata, all ten
  severity closers, failure-mode clusters, routing/scoping, and absence of Nit ratings.
- No breaking interface changes; app-specific Apollo/GRDB rules remain applicability-gated.
Feature flag: N/A
Acceptance criteria: 7/7 implemented

## [2026-07-01] ios-platform-pack (dry-run-validation)
Areas: platform-packs/ios/code-review
- Read-only dry-run of `bill-ios-code-review` vs two real stuck iOS-app PRs:
  routing matched changed-file signals; 32 findings accurate/non-noisy
  (incl. a genuine Blocker); zero GitHub posting.
- Reusable acquisition trick: shallow clone at PR head loads repo-local
  `AGENTS.md`/`CLAUDE.md` + `references/*.md`, so repo-local-knowledge
  specialists read real project rules (no `gh pr diff` fallback needed).
- Known gaps (report-only, NOT fixed): iOS specialists in
  `native-agents/agents.yaml` are unregistered in `~/.claude/agents`, so the
  documented Agent-tool spawn path can't resolve on Claude (validated rubric
  + routing, not installed spawn wiring); overlap needs merge-layer dedup;
  installed skill dir lacks sidecar ceremony files.
Feature flag: N/A
Acceptance criteria: 1/1 implemented

## [2026-07-01] ios-platform-pack (revise-validate-install)
Areas: platform-packs/ios/code-review
- Frequency-weighted revision of 5 specialist rubrics from mined patterns:
  reliability +6, architecture +5, testing +3, platform-correctness +3
  (sharpened, breadth kept), performance -3. Trimming rule: never empty a
  declared area — keep frontmatter/Focus/Ignore/Applicability + >=1
  Project-Specific rule so `show` stays `complete`.
- Reusable: add depth as generalized house-voice bullets only (no verbatim
  mined PR text, no proprietary internals) so the public tree stays free of
  proprietary content.
- Router + 5 low-signal specialists unchanged; routing verified via synthetic
  diffs (background-sync-engine paths -> reliability, *.graphql -> api-contracts).
- `validate-agent-configs` pass/[] across all 11 + render clean; `./install.sh`
  blocked only by goal-continuation guard (install.sh:67) — run post-goal.
Feature flag: N/A
Acceptance criteria: 4/4 implemented

## [2026-07-01] ios-platform-pack (pack-foundation)
Areas: platform-packs/ios, platform-packs/ios/code-review
- Added `ios` platform pack: `platform.yaml` routing signals (`.xcodeproj`,
  `.xcworkspace`, `import SwiftUI`, `import UIKit`, `iOSApplicationExtension`)
  with no overlap vs KMP's iosMain/androidMain/commonMain signals.
- Added `bill-ios-code-review` router + 10 area specialists (architecture,
  performance, platform-correctness, security, testing, api-contracts,
  persistence, reliability, ui, ux-accessibility) mirroring the established
  router/specialist shape and min-2/max-10 bounds pattern.
- Reusable: `architecture`/`persistence`/`platform-correctness`/`reliability`
  specialists each add a "Repo-Local Knowledge" section pointing the reviewer
  at the target repo's own `.agents/skills/*/references/*.md` and root
  `AGENTS.md`/`CLAUDE.md` — keeps proprietary knowledge out of
  skill-bill's public repo while still informing review.
Feature flag: N/A
Acceptance criteria: 5/5 implemented
