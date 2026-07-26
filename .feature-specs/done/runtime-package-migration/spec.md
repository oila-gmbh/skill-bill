# Feature: runtime-package-migration
Created: 2026-04-04
Status: In Progress
Sources: conversation context, scripts/review_metrics.py, docs/review-telemetry.md

## Acceptance Criteria
1. scripts/review_metrics.py is fully migrated into skill_bill/ as properly separated modules
2. skill-bill CLI exposes all current subcommands: review import, review stats, review triage, review feedback, learnings add/list/show/edit/resolve/disable/delete, telemetry status/enable/disable/sync, doctor, version
3. scripts/review_metrics.py is deleted — no wrapper, no shim
4. bill-code-review/SKILL.md Auto-Import section calls skill-bill review import instead of resolving a script path
5. All review orchestration skills that reference learnings resolution use skill-bill learnings resolve
6. install.sh telemetry setup references skill-bill telemetry enable/disable instead of the script path
7. SQLite storage, config loading, and telemetry sync logic preserved exactly (no behavior changes)
8. All existing test scenarios from tests/test_review_metrics.py (1395 lines) are migrated to test the package
9. Existing tests continue to pass
10. Validator contracts updated for the new CLI surface
11. pyproject.toml remains zero external dependencies
12. CI workflow installs the package before running tests
13. All validation passes: unittest, agnix, validate_agent_configs.py
