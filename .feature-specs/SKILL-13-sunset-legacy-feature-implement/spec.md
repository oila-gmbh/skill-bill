# Sunset Legacy Feature-Implement

- Issue key: SKILL-13
- Date: 2026-04-16
- Status: In Progress
- Source: inline user message

## Summary

Sunset `bill-feature-implement` (orchestrator-centric) and promote `bill-feature-implement-agentic` (subagent-based) to become the canonical `bill-feature-implement`.

## Acceptance Criteria

1. Rename `bill-feature-implement-agentic` to `bill-feature-implement` — the agentic variant becomes the canonical skill, dropping the `-agentic` suffix
2. Remove the old `bill-feature-implement` — delete the former orchestrator-centric skill directory entirely
3. Remove "experimental" language — strip any "experimental" markers from the promoted skill's SKILL.md and reference.md
4. Update `install.sh` — add migration rule to handle the rename (old `bill-feature-implement-agentic` command symlink removed)
5. Update `scripts/validate_agent_configs.py` — remove `bill-feature-implement-agentic` from allowed names/orchestrator lists; keep `bill-feature-implement` pointing to new content
6. Update tests — adjust `test_feature_implement_routing_contract.py` and `test_feature_implement_telemetry.py` to reference the consolidated skill
7. Update orchestration references — telemetry-contract PLAYBOOK.md referencing the agentic variant
8. Update `CLAUDE.md` / installed commands — the installed command directory now points to the promoted skill
9. Update README.md — skill catalog reflects one `bill-feature-implement` (no agentic variant listed separately), update counts
10. Validation passes — all three validation commands succeed after changes

## Non-goals

- Changing the subagent-based architecture of the promoted skill
- Adding new features to the workflow
- Changing telemetry event names
