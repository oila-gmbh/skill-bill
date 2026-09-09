# Review evidence validation

SKILL-236 implementation leaves execution to the owning build and validate phases. The focused regressions cover the MCP bridge and authenticated endpoint, broker delivery accounting, production lane settlement and persistence, typed discovery rejection, and required companion composition.

The source changes pin review context 2.3 and platform packs 1.8.

Before CLI smoke runs, record the installed executable path and version and confirm that its schemas, staged inline skill, KMP manifest, and native-agent bodies contain this change. This goal child must not install or synchronize artifacts. An authorized external run must refresh them with `./install.sh`. Stale artifacts leave AC-007 unmet.

Use a fixture repository with two commits changing the same path, shared and unique files routed to two rubrics, and an owned file eligible for expansion. Record the selected immutable revisions, each delivered selector, required and delivered unit counts, and the final exit status for each scenario.

```sh
skill-bill code-review "$head" --repo-root "$fixture" --agent1 "$agent" --execution-mode inline
skill-bill code-review --repo-root "$fixture" --agent1 "$agent" --execution-mode inline --base-revision "$base" --head-revision "$head" --diff-file "$diff_file"
skill-bill code-review "$head" --repo-root "$fixture" --agent1 "$agent" --execution-mode inline --expand-file "$specialist:$owned_path=inspect direct caller"
```

The third scenario must discover the prelaunch authorization and receive the whole file at the selected revision after reading its delta. Include a non-primary rubric in the merged case. Invalid lanes, unreachable paths, traversal, and symlink escape must fail explicitly. An approved worker response with missing required units must exit unsuccessfully; complete delivery after an ordinary refusal may recover.

Report unavailable external providers and stale installed artifacts as limitations. Controlled transport tests do not prove external-provider behavior. Do not mark generated-output freshness or CLI smoke acceptance complete from source inspection alone.

## Audit remediation scenarios

Discover and deliver assigned changes to `AGENTS.md` and `specialist-contract.md` through both committed target selectors and projected hunk selectors. Plain path reads and whole-file guidance expansions must remain refused. A selector paired with another path must deliver nothing.

For committed, staged, and unstaged reviews, put different content in HEAD, the index, and the worktree. Verify that a non-primary lane expansion delivers the selected source through the final merged broker. Include symbolic committed revisions and the complete discovered `agent:bill-skill` lane in `--expand-file`. Change a checkpoint file after preparation and confirm refusal without coverage credit, then restore the recorded content and retry.

Exercise oversized reasons, authenticated frames, retained expansion metadata, and response serialization. Refusals must remain bounded and recorded. Confirm that only complete units retained in the final response receive delivery credit, and that byte-budget exhaustion stays terminal. These scenarios are authored regressions pending execution by the owning validation phase.
