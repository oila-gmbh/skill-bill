## [2026-04-02] review-acceptance-metrics
Areas: repo-root governance, orchestration/review-orchestrator, orchestration/review-delegation, skills/base/bill-code-review, stack review skills, scripts, tests, README
- Added a local-first review telemetry contract with `review_run_id` output and machine-readable `finding_id` risk-register lines for code-review flows.
- Added `scripts/review_metrics.py` as a reusable SQLite helper for importing review outputs, recording explicit accepted/dismissed/fix_requested events, and reporting stats.
- Added governance coverage so review contracts now enforce review-run id generation and delegated review-run id reuse across routed reviews.
- Documented the local telemetry workflow and default database location in README.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-03] review-acceptance-metrics phase 2
Areas: scripts/review_metrics.py, README, tests
- Added a number-based triage workflow so users can respond with `1 fix` or `2 skip - intentional` instead of raw finding ids.
- Added a separate local learnings layer with list/show/edit/disable/delete management commands so reusable review preferences stay user-reviewable and removable.
- Kept learnings separate from raw feedback event history so preferences can be changed or wiped without losing the telemetry baseline.
Feature flag: N/A
Acceptance criteria: 6/6 implemented

## [2026-04-03] review-learnings-application
Areas: scripts/review_metrics.py, orchestration/review-orchestrator, orchestration/review-delegation, skills/base/bill-code-review, stack review skills, README, tests
- Added scope-aware learnings resolution so active learnings can be resolved for `global`, `repo`, and `skill` review contexts with deterministic precedence. reusable
- Updated shared review contracts so routed and delegated reviews treat learnings as explicit context, pass them through delegation, and surface `Applied learnings` in the summary instead of hiding the behavior.
- Added validator and regression coverage so future review-skill edits cannot drop the auditable learnings contract silently.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-03] review-telemetry-remote-sync
Areas: scripts/review_metrics.py, install.sh, README, tests
- Added a local telemetry outbox plus optional remote batch sync so SQLite stays canonical while cross-install product analytics can be reported later.
- Added default-on installer telemetry preference handling and helper commands to inspect status, enable or disable sync, and flush pending events manually.
- Kept the remote payload privacy-scoped by excluding repo identity and raw review text while still reporting skill, feedback, and applied-learning metadata.
Feature flag: N/A
Acceptance criteria: 5/5 implemented

## [2026-04-03] review-telemetry-proxy-sync
Areas: scripts/review_metrics.py, install.sh, docs/cloudflare-telemetry-proxy, README, tests
- Added proxy-aware telemetry transport so installs can keep the same local outbox flow while sending batches to a configured relay.
- Added a Cloudflare Worker example that accepts Skill Bill telemetry batches, validates them lightly, and forwards them to the example backend with the credential stored server-side.
- Kept the telemetry privacy boundary: no repo identity leaves the client. Learning content is included in the `skillbill_review_finished` event.
Feature flag: N/A
Acceptance criteria: 6/6 implemented
