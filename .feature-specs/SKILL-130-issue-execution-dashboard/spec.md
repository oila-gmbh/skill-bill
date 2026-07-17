---
status: Pending
---

# SKILL-130 - Issue execution dashboard

Created: 2026-07-18
Issue key: SKILL-130
Mode: decomposed

## Intended Outcome

Make the desktop Work surface issue-centric. The navigation pane shows exactly one row for each normalized issue key, regardless of how many goal, feature-task, feature-verify, parent, child, retry, blocked, abandoned, or completed workflow records exist for that issue. Selecting the row opens a polished read-only execution dashboard in the main application area that explains what the issue is doing or did, what work remains, why progress stopped, and how its execution evolved.

Durable workflow rows remain intact as execution history. Aggregation is a typed read-model and presentation concern, not database deduplication or destructive cleanup.

## User Experience

The Work list uses the issue key as its primary label and gives a compact summary of the effective issue state, current task or phase, progress, last activity, and attention state. Rows remain stable during refresh and selection.

The selected issue replaces the normal editor content with a dashboard composed from available durable records:

- a header with issue key, effective status, progress, elapsed/last-active timing, current phase, and refresh affordance;
- an at-a-glance summary describing active work, the latest meaningful outcome, and any action needed;
- preplan and plan digest sections with clear empty/unavailable states;
- a dependency-ordered task structure showing pending, running, blocked, skipped, failed, and completed subtasks;
- an execution timeline joining parent goal activity, child workflows, phases, retries, state transitions, and terminal outcomes;
- prominent blocked/failure cards with reason, affected task and phase, occurrence time, retry relationship, and last resumable step where available;
- attempt history that allows a user to inspect every underlying workflow without presenting those attempts as separate issues; and
- a structured artifact inspector for additional available metadata, with readable summaries first and raw payloads behind deliberate disclosure.

The default view optimizes for “How is this issue going?” rather than mirroring database tables. Information is grouped progressively so common answers are visible without expanding raw details.

## Data And Architecture Direction

Introduce an issue-execution query/read model behind runtime ports. It must aggregate by normalized issue key and repository identity, retain deterministic provenance to every source workflow, and expose both a compact summary and a detailed projection. The desktop consumes domain-facing models through gateways; it does not query SQLite or decode artifact JSON directly.

The effective state and current attempt are selected by explicit deterministic policy. Active work outranks historical terminal attempts; otherwise the latest meaningful terminal outcome is used. Ties use durable timestamps and workflow IDs. Parent goal state, decomposition-manifest state, and child execution state must not be flattened into a misleading single value: the projection exposes their relationship and records inconsistencies as diagnostics.

Artifact extraction is allowlisted and typed for known data such as preplan, plan, decomposition, continuation, phase records, block reasons, retry/attempt ledgers, commits, review, validation, and PR handoff. Unknown or newer artifacts remain available through a safe fallback inspector and never crash the whole issue view.

## Acceptance Criteria

1. The Work navigation displays at most one row per normalized nonblank issue key within the selected repository, including when an issue has parent goal, prose, runtime, verify, child, retry, blocked, abandoned, and completed records.
2. All underlying durable workflow records remain stored and inspectable; this feature performs no deletion, merging, mutation, or issue-key-based overwrite of workflow history.
3. Rows without an issue key remain individually addressable under a clearly labeled unassociated-work presentation and do not collapse into one synthetic issue.
4. Each issue row shows the issue key, deterministic effective status, current task/phase when available, aggregate task progress, attention/block indicator, and last meaningful activity using concise accessible text and status semantics.
5. Selecting an issue row opens its execution dashboard in the main application pane, visibly marks the selected row, preserves selection across refresh when the issue still exists, and supports keyboard navigation and activation.
6. The dashboard header and summary make the current outcome understandable without opening individual attempts: issue status, active or last attempt, task progress, current phase, latest activity, and whether user attention is required are all represented.
7. Preplan and plan digests are rendered as readable sections from typed durable artifacts, preserve authored ordering and meaningful structure, distinguish absent from not-yet-produced from malformed data, and do not expose raw JSON as the primary view.
8. The task structure shows decomposition order, dependencies, optional/skipped relationships, status, current intent, associated workflow attempts, commits, last resumable steps, and block reasons when those values are available.
9. The execution timeline deterministically combines parent goal progress, subtask attempts, workflow and phase transitions, retries, validation/review/commit outcomes, and terminal states while retaining source workflow IDs and timestamps for drill-down.
10. Blocked and failed states prominently show the best available reason, affected subtask and phase, time, resumability, and subsequent retry/outcome. Missing reasons are labeled unavailable rather than inferred.
11. Attempt history groups executions under their subtask or standalone route, clearly labels mode, workflow ID, start/end, state, phase, retry relationship, and terminal outcome, and allows an attempt to be selected without creating another navigation row.
12. Known artifacts are decoded through typed infrastructure/application mappings with loud failures or scoped diagnostics appropriate to their contracts. Unknown artifact keys and unsupported newer shapes remain inspectable through a bounded, escaped fallback and do not blank the dashboard.
13. Effective-status, progress, retry grouping, ordering, and current-attempt policies are centralized, documented through names and tests, deterministic across refreshes, and covered for contradictory parent/child and timestamp-tie cases.
14. Summary and detail reads are repository-scoped, bounded in query count, and avoid N+1 reads per issue, subtask, phase, or attempt. A representative multi-issue fixture protects ordering and query-count/performance behavior.
15. Loading, empty, partial-data, malformed-artifact, stale-selection, refresh, and database-error states have intentional UI treatments. A local failure in one optional section does not hide otherwise valid issue information.
16. The dashboard uses the existing Skill Bill design system with strong information hierarchy, restrained status color, consistent spacing and typography, readable line lengths, progressive disclosure, and no horizontal scrolling for the normal summary/task/timeline experience.
17. All controls and status information are keyboard accessible, expose meaningful Compose semantics, do not rely on color alone, and remain usable at supported narrow pane/window sizes and with long issue keys, task names, reasons, and digests.
18. Tests cover repository aggregation, blank keys, effective-state policy, detail projection, artifact parsing, retry history, blocked reasons, selection/refresh state, dashboard rendering, keyboard behavior, accessibility semantics, long content, and representative `SKILL-128`-shaped history.
19. Existing workflow continuation, goal execution, telemetry, CLI/MCP outputs, and durable schemas remain behaviorally compatible unless a separately versioned read-only projection contract is introduced.
20. Maintainer validation passes: `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Constraints

- Treat the authoritative runtime database as immutable user history for this feature.
- Keep persistence details and artifact JSON outside desktop UI/common domain code.
- Scope identity by normalized issue key and repository identity; never combine equal issue keys from different repositories.
- Prefer existing durable facts. Do not fabricate retry relationships, reasons, timing, progress, or successful outcomes.
- Follow the existing design system and desktop architecture boundaries.
- Keep refresh read-only and safe while runtime processes are writing concurrently.
- Follow the comments policy; encode behavior through types, names, and focused functions.

## Non-Goals

- Deleting historical or blocked workflow rows.
- Changing resume behavior or deciding whether a retry reuses a workflow ID.
- Editing specs, manifests, workflow state, block reasons, or artifacts from the dashboard.
- Adding remote synchronization, notifications, or multi-user collaboration.
- Replacing the existing skill/source editor outside the selected Work route.
- Treating raw database tables or JSON blobs as the primary user experience.

## Delivery Plan

1. Build and validate the repository-scoped issue summary/detail read model and deterministic aggregation policies.
2. Add desktop gateway, state, selection, refresh, and navigation behavior around one row per issue.
3. Deliver the designed execution dashboard, progressive drill-down, accessibility, and end-to-end fixtures.

