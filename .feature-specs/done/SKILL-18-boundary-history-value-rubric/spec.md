---
issue_key: SKILL-18
feature_name: boundary-history-value-rubric
feature_size: SMALL
status: Draft
created: 2026-04-17
depends_on: none
---

# SKILL-18 — Add a rubric for `boundary_history_value` to fix central-tendency bias

## Problem

Telemetry for `bill-feature-implement` shows the `boundary_history_value` field is almost always `medium`. PostHog over the last 180 days (event `skillbill_feature_implement_finished`, 553 runs):

| value | runs | % |
|---|---:|---:|
| medium | 492 | 89.0% |
| *(unreported/null)* | 52 | 9.4% |
| high | 6 | 1.1% |
| none | 2 | 0.4% |
| low | 1 | 0.2% |

At those proportions the metric carries no signal: we cannot tell whether the boundary history read during pre-planning actually helped implementation. The metric was added to answer that exact question, so this is a regression of purpose, not a side detail.

## Root cause

The subagent self-reports `boundary_history_value` with no rubric. The only guidance is one line in `skills/base/bill-feature-implement/reference.md:42`:

> "Read `agent/history.md` in each boundary likely to be touched (newest first; stop when no longer relevant). **Rate boundary-history value as: none | irrelevant | low | medium | high.**"

`skills/base/bill-feature-implement/SKILL.md:171` describes the field as "how useful the boundary history was during pre-planning" and defines only `none` ("if no history existed"); `irrelevant | low | medium | high` are listed without definition.

Given an unqualified 5-point ordinal scale, LLMs exhibit classic central-tendency bias and anchor on the middle label. `medium` is also the most "safe/neutral" word when the agent is uncertain. The skewed distribution is the expected outcome of this prompt shape, not a logic bug in the telemetry pipeline (the enum is validated in `skill_bill/feature_implement.py:75` and `skill_bill/constants.py:78`; the MCP tool passes through whatever the subagent reports).

A secondary issue: `boundary_history_value = "none"` overloads the word. The SKILL.md schema at line 171 says `none` means "no history existed", but `boundary_history_written = true` can still co-occur with `value = "none"` in the data (1 run observed). Semantically that combination is legal (a new boundary had no pre-existing history to read, and SKILL-14's `bill-boundary-history` orchestrator wrote a new entry after completion). The overload invites misreading of telemetry but is not broken logic.

## Why now

1. **Metric is unusable today.** 89% on one bucket is indistinguishable from "everyone reports the default." We are effectively flying blind on whether the `agent/history.md` pre-read carries its weight in pre-planning time.
2. **Cheap fix, no runtime behavior change.** The enum, storage, validation, and telemetry contract all stay. This is a prompt-wording change in one skill plus its reference doc. No contract version bump, no schema migration, no installer change.
3. **Precondition for evaluating `bill-boundary-history`.** The downstream question is whether `bill-boundary-history` entries pay off on later features. That analysis needs a signal here; without rubric-anchored ratings the signal is noise.

## Context (what a new-session implementer needs to know)

### Where the rating is produced

- `skills/base/bill-feature-implement/reference.md:42` — pre-planning subagent briefing, step 3. This is the only place the subagent is told to rate the value.
- `skills/base/bill-feature-implement/reference.md:58` — RESULT schema shows the bare enum.
- `skills/base/bill-feature-implement/SKILL.md:171` — telemetry field description in the skill's own Telemetry Ceremony Hooks / telemetry section.

### Where the rating is consumed

- `skill_bill/feature_implement.py:61,75,139,169,191,228,316` — validation and persistence. Uses `BOUNDARY_HISTORY_VALUES` tuple.
- `skill_bill/constants.py:78` — enum: `("none", "irrelevant", "low", "medium", "high")`. **Unchanged by this spec.**
- `skill_bill/mcp_server.py:338,358,384` — MCP tool parameter. Default is `"none"`.
- `skill_bill/db.py` — column definition. **Unchanged by this spec.**
- `tests/test_feature_implement_telemetry.py` — coverage for the field. **May gain cases; see AC 5.**

### Proposed rubric (author proposes, planner locks in)

Anchor each enum value with an observable criterion so the subagent can pick deterministically:

- **`none`** — boundary had no `agent/history.md` file, or the file existed but was empty.
- **`irrelevant`** — history existed and was read, but no entry applied to this feature's boundaries or problem.
- **`low`** — history provided minor context (naming, conventions, shape of past changes) but did not change the plan, the file list, or the approach.
- **`medium`** — history caused the subagent to reuse a named pattern, reuse an existing component, or avoid a concrete known pitfall. The pre-planning digest explicitly references a past entry.
- **`high`** — history materially changed the approach (e.g., switched from path A to path B, blocked a naive plan that would have re-introduced a fixed bug, or redirected the feature to an existing abstraction). The digest cites the specific past entry and the decision it drove.

Central-bias guardrail: **require a citation for `medium` and `high`.** The `boundary_history_digest` field in the RESULT schema already exists; extend the rubric to say "if you report `medium` or `high`, the digest MUST name the past entry (issue key, date, or entry title) that drove the rating." No schema change; it's a prompt-level constraint the validator already tolerates (digest is free-text today).

### Alternative considered — enum narrowing

Collapse to a 3-point scale `none | low | high`. Rejected: loses resolution, doesn't fix the underlying "no rubric" problem, requires a storage + telemetry + dashboard migration, breaks historical comparability. Keeping the 5-point scale with anchors is cheaper and lossless.

### Alternative considered — split the enum

Split into two fields: `boundary_history_read: bool` (or the existing `written` flag repurposed) and `boundary_history_impact: low|medium|high`. Rejected: redundant with today's `boundary_history_written` which tracks a different moment (post-completion write, not pre-planning read). Adds schema churn without addressing the anchoring problem.

### `none` overload

Two candidate clean-ups, both optional for SKILL-18:

1. **Split `none` into `none` (no file) and `empty` (file exists but empty).** Adds an enum value; requires migration. Low value — the data shows `none` is already rare. **Skip.**
2. **Re-document `none`.** Clarify in SKILL.md:171 that `none` is a property of the *pre-read* (no history existed at read time) and that `boundary_history_written=true` with `value=none` is a legal, expected combination when the subagent creates a fresh `agent/history.md` post-completion. **In scope.**

## Acceptance criteria

1. **Rubric added to reference.md.** `skills/base/bill-feature-implement/reference.md:42` expands from the one-line mention into a bulleted rubric with the five anchored definitions above. The ordering `none | irrelevant | low | medium | high` is preserved. Step numbering in the surrounding Instructions list stays intact.

2. **Citation requirement for `medium`/`high`.** The rubric in reference.md includes an explicit sentence: "If you report `medium` or `high`, `boundary_history_digest` MUST cite the specific past entry (issue key, date, or entry title) that drove the rating. Otherwise downgrade to `low`." The sentence lives alongside the rubric, not buried elsewhere.

3. **SKILL.md field description updated.** `skills/base/bill-feature-implement/SKILL.md:171` replaces the current bare enumeration with a one-line gloss per value (compressed form of the reference.md rubric; the full rubric stays in reference.md to keep SKILL.md terse). The `none` description is clarified to mean "no history existed at pre-read time" and explicitly notes that `boundary_history_written=true` with `value=none` is legal.

4. **No enum, schema, or storage changes.** `BOUNDARY_HISTORY_VALUES` in `skill_bill/constants.py:78` stays `("none", "irrelevant", "low", "medium", "high")`. `skill_bill/db.py` column definition is untouched. `skill_bill/feature_implement.py` validation call site is untouched. `skill_bill/mcp_server.py` signature and default stay.

5. **Test coverage — no regression.** `tests/test_feature_implement_telemetry.py` continues to pass. Optionally add one explicit test that asserts all five enum values round-trip through `record_feature_implement_finished` (if such coverage is not already present).

6. **No telemetry contract version bump.** This is a prompt-wording change in a single base skill plus its reference doc. `SHELL_CONTRACT_VERSION` is irrelevant (bill-feature-implement is a pre-shell family per `skill_bill/constants.py::PRE_SHELL_FAMILIES`). No platform-pack manifests change.

7. **Validation suite passes.** Canonical triad:
   ```
   .venv/bin/python3 -m unittest discover -s tests
   npx --yes agnix --strict .
   .venv/bin/python3 scripts/validate_agent_configs.py
   ```

8. **No dashboard or telemetry CLI changes.** The PostHog event name (`skillbill_feature_implement_finished`) and property key (`boundary_history_value`) stay. Any existing dashboards or saved queries keep working. Post-merge, the distribution is expected to shift but the schema does not.

9. **README / CLAUDE.md / AGENTS.md.** No catalog changes. No governance-rule changes. This ticket is below the threshold that triggers `AGENTS.md` edits (it is not adding a platform, a skill family, or a contract).

10. **Boundary history.** Add a short `agent/history.md` entry noting SKILL-18 refined the `boundary_history_value` rubric to fix central-tendency bias, with a pointer to the telemetry evidence. No `agent/decisions.md` entry — this is a wording fix, not an architectural choice.

## Non-goals

- Changing the `BOUNDARY_HISTORY_VALUES` enum (no new values, no removed values, no reorderings).
- Migrating historical telemetry rows. The pre-SKILL-18 data stays as-is; post-merge runs will rate under the new rubric. Split the time series at merge date when analyzing.
- Adding a dashboard or insight in PostHog for the new distribution. Out of scope; can be done once the distribution stabilizes.
- Touching `bill-feature-verify`, `bill-kmp-feature-implement` overrides, or any other skill. The rubric lives in the base skill only; platform overrides inherit it through the usual override-reference chain.
- Piloting `bill-feature-implement` onto the shell+content contract. SKILL-17 still gates that.
- Adding a new telemetry property (e.g. `boundary_history_digest_cites_entry: bool`). The citation requirement is enforced by the prompt and checked post-hoc via the free-text digest field, not by schema.

## Open questions to resolve in planning

1. **Citation enforcement location.** The proposed rubric puts the "must cite" sentence in reference.md alongside the anchors. Alternative: put it as a separate sub-step in the Instructions block and have the RESULT schema enforce the shape (e.g., `boundary_history_digest` starts with `[<issue_key>]` when value is `medium`/`high`). Planner picks the stricter one if the shift after one week of data is still insufficient.

2. **Rubric in SKILL.md length.** AC 3 says one-line-per-value in SKILL.md. Some skills keep SKILL.md minimal and push detail to reference.md. Planner decides whether SKILL.md carries the full rubric or a `see reference.md` pointer.

3. **Do we backfill historical data?** No runtime migration is planned, but planner may choose to annotate the dashboard/insight description with "pre-2026-04-17 rows use unanchored scale; post-2026-04-17 rows use SKILL-18 rubric" so downstream readers are not misled.

4. **Do we also fix the `none` overload semantically?** Proposed answer: documentation-only (AC 3). Planner may choose to add an `empty` value instead; that bumps scope to a proper enum change and is arguably out of scale for a SMALL ticket. Spec recommends the documentation fix.

5. **Test scope.** AC 5 makes the round-trip test optional. Planner decides whether it's required or skipped. If skipped, at minimum confirm existing tests still cover all five enum values.

## Scenario mock-ups (for context)

### Scenario A — high with citation (legal under new rubric)

```
RESULT:
{
  ...
  "boundary_history_digest": "Reused pattern from [SKILL-14] 2026-03 entry: sibling supporting-file symlinks instead of repo-relative paths. Avoided re-introducing the exact pitfall that entry documents.",
  "boundary_history_value": "high",
  ...
}
```

### Scenario B — medium without citation (downgrade under new rubric)

Subagent reports `boundary_history_value: "medium"` but `boundary_history_digest` contains only a generic summary ("reviewed recent entries, nothing surprising"). Rubric requires downgrade to `low` and the subagent re-reports accordingly before emitting the RESULT payload.

### Scenario C — none with written=true (legal, documented)

Fresh boundary (no prior `agent/history.md`). Subagent reports `boundary_history_value: "none"` at pre-planning. After completeness audit passes, `bill-boundary-history` orchestrator creates the file and reports `boundary_history_written: true`. Telemetry row shows `value="none", written=true`. Under AC 3 this combination is documented as legal in SKILL.md.

## Files expected to change

Modified:
- `skills/base/bill-feature-implement/reference.md` — expand line 42 into rubric; update RESULT-schema comment at line 58 if needed.
- `skills/base/bill-feature-implement/SKILL.md` — update line 171 field description.
- `agent/history.md` — SKILL-18 entry.
- `tests/test_feature_implement_telemetry.py` — optional round-trip test (see AC 5).

Not modified:
- `skill_bill/constants.py` (enum stays)
- `skill_bill/feature_implement.py`
- `skill_bill/mcp_server.py`
- `skill_bill/db.py`
- Any `platform-packs/<slug>/` manifest or content
- `install.sh`, `uninstall.sh`
- `AGENTS.md`, `CLAUDE.md`, `README.md`
- `orchestration/*`
- Any other skill

## Feature flag

N/A. Prompt-wording change in one skill.

## Backup / destructive operations

None. Two doc edits and one optional test addition.

## Validation strategy

`bill-quality-check` auto-routes to `bill-agent-config-quality-check` for this repo. The canonical triad must pass:

```
.venv/bin/python3 -m unittest discover -s tests
npx --yes agnix --strict .
.venv/bin/python3 scripts/validate_agent_configs.py
```

Post-merge success signal is behavioral, not automated: re-run the PostHog distribution query over a 2-week window after merge and confirm the `medium` share drops meaningfully (target: below 60% of reported values; exact threshold is a planner call). If the shift is insufficient, tighten the citation requirement per Open Question 1.

## References

- Telemetry evidence: PostHog event `skillbill_feature_implement_finished`, 180-day window, 553 runs. 89% `medium`, 1% `high`, 0.2% `low`, 0.4% `none`, 9.4% unreported.
- Rating site: `skills/base/bill-feature-implement/reference.md:42`.
- Field schema: `skills/base/bill-feature-implement/SKILL.md:171`.
- Enum: `skill_bill/constants.py:78` (`BOUNDARY_HISTORY_VALUES`).
- Validation: `skill_bill/feature_implement.py:75`.
- MCP tool param: `skill_bill/mcp_server.py:338,358,384`.
- Related skill: `bill-boundary-history` (writes the `agent/history.md` entries this metric rates the usefulness of).
