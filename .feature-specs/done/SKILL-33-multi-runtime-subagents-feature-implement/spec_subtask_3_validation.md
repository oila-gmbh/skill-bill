# Subtask 3 — Validation: test extensions, Codex smoke test, docs

Parent spec: /Users/sermilion/Development/skill-bill/.feature-specs/SKILL-33-followups/spec_followup_3_feature-implement-multi-runtime.md

## Why this is last

With foundation (subtask 1) and authoring (subtask 2) merged, the system has 14 new agent files and an extended discovery primitive. This subtask hardens the work with automated tests, a real-world smoke test on a non-Claude runtime, and user-facing documentation.

## Scope

1. Extend `tests/test_codex_agents_toml.py`:
   - Cover all 7 new feature-implement Codex TOML files.
   - Assert filename stem == `name` field for each.
   - Assert no Claude-only verbiage (`Agent tool`, `subagent_type`, `general-purpose`) in `developer_instructions`.
   - Assert `description` is non-empty and one line.
   - Assert briefing content matches the corresponding reference.md template (a structural check, not a strict diff — at minimum, key headings/markers from the briefing must be present).
2. Extend `tests/test_opencode_agents_md.py`:
   - Cover all 7 new feature-implement OpenCode MD files.
   - Assert `mode: subagent` frontmatter is present.
   - Assert filename stem == `name` field.
   - Assert no Claude-only verbiage in body.
3. Add a discovery test (extend or add to an existing test): assert that `discover_codex_agent_tomls` and `discover_opencode_agent_mds` return the new feature-implement files in addition to the existing platform-pack files.
4. Run the end-to-end smoke test on Codex (preferred per AC7):
   - Install fresh: `./install.sh` against this repo.
   - Confirm 7 new TOML symlinks at `~/.codex/agents/` and 7 new MD symlinks at `~/.config/opencode/agents/`.
   - Spawn one phase (e.g. pre-planning or planning) on a small disposable spec via Codex CLI; capture the run's outcome (sibling re-spawn behavior, `RESULT:` block parseability, success/failure).
   - Validate AC5 (fix-on-review respawn): trigger the implementation-fix variant via re-spawn under `agents.max_depth=1` on at least one runtime. Document outcome.
   - Capture results in the PR description (artifact paths, any anomalies, parsing-tolerance behavior observed).
5. Update README.md and any relevant docs (e.g. `docs/`) with:
   - Note that `bill-feature-implement` now ships native subagents for Codex and OpenCode in addition to Claude.
   - Reference to `parsing_tolerance.md` for the JSON `RESULT:` posture.
   - Brief callout for the workflow state persistence (AC6) being unchanged across runtimes.
6. Update `agent/history.md` with a validation-milestone entry.

## Acceptance criteria

1. `tests/test_codex_agents_toml.py` and `tests/test_opencode_agents_md.py` cover all 14 new files with the assertions above.
2. Discovery extension is covered by a test that fails if `skills/bill-feature-implement/{codex,opencode}-agents/*` is removed from the walk roots.
3. `.venv/bin/python3 -m unittest discover -s tests` passes.
4. `npx --yes agnix --strict .` and `scripts/validate_agent_configs.py` both pass.
5. Smoke test on Codex executed and recorded in the PR description with: phases spawned, RESULT contract behavior, fix-loop respawn outcome, any anomalies and how they were handled.
6. AC6 confirmed: a workflow state run started under one runtime can be resumed under another without state loss (or, if cross-runtime resume is out of scope, document that intra-runtime resume works on Codex and OpenCode).
7. README.md and docs reference the new multi-runtime support for bill-feature-implement.
8. `agent/history.md` updated.

## Non-goals

- Authoring or modifying any of the 14 subagent files. Those are subtask 2.
- Modifying discovery primitives. That is subtask 1.
- Smoke test on runtimes beyond Codex (OpenCode smoke test is encouraged but optional per AC7).

## Dependencies

- Subtask 1 (foundation) MUST be merged.
- Subtask 2 (authoring) MUST be merged. The smoke test cannot run without the agent files.

## Validation strategy

```
.venv/bin/python3 -m unittest discover -s tests \
  && npx --yes agnix --strict . \
  && .venv/bin/python3 scripts/validate_agent_configs.py
```

Plus the documented Codex smoke-test procedure from the Scope section above. Smoke test artifacts (logs, RESULT blocks, sibling-respawn evidence) attached to the PR description.

## Recommended next prompt

Run `bill-feature-implement` on `.feature-specs/SKILL-33-multi-runtime-subagents-feature-implement/spec_subtask_3_validation.md`.
