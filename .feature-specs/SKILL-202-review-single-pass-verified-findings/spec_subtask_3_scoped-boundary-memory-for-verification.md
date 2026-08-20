# SKILL-202 · Subtask 3 — Path-scoped boundary memory for verification

Parent: `spec.md`

## Scope

Give `verify_findings` the historic context for the code a finding touches,
without loading history wholesale. Verification receives a titles-only catalog
scoped to the boundaries that own the finding paths, selects headings
semantically, and receives only those bodies under caps tighter than planning's.

Files in play:

- `runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/goalrunner/model/GoalPlanningContext.kt`
  — the catalog and body caps to reuse, and the verification-specific caps to add
  beside them.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/goalplanning/FileSystemGoalPlanningContextDiscovery.kt`
  — heading discovery, to be reachable with a path-scoped boundary set.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/goalplanning/FileSystemGoalPlanningBoundaryBodyResolver.kt`
  — body resolution for selected ids.
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/FeatureTaskRuntimePlanningProjectionModels.kt`
  — `selectedBoundaryHeadings` and
  `FEATURE_TASK_RUNTIME_SELECTED_BOUNDARY_HEADING_MAX_COUNT` as the shape to
  mirror for the verification selection.
- the verification phase prompt, projection shape, and disposition model from
  subtask 2.

## Preferred Approach

Derive the boundary set from the findings, not from the repository. For each
finding path, walk up to the nearest ancestor directory holding `agent/history.md`
or `agent/decisions.md`, and take the union across findings. A boundary that owns
no finding path contributes nothing. Feed that set through the existing discovery
seam so heading parsing, stable ids, per-file caps, and
`MAX_BOUNDARY_FILE_BYTES` stay exactly as they are.

Run verification as a two-step exchange inside the one phase, mirroring how
preplanning selects headings for the plan phase: the catalog goes out with titles
only, the phase returns the heading ids it judges relevant, and the runtime
resolves those bodies and delivers them back under the verification caps. A body
is never delivered unselected, and the phase cannot widen its own scope.

Add verification caps beside the planning caps rather than reusing the planning
numbers: at most 3 selected headings per finding, at most 8 resolved bodies per
phase, 4096 bytes per body, 16384 bytes total. Unresolvable ids are reported
bounded and single-line, as planning already does, and do not fail the phase.

Each disposition records the heading ids it relied on, so a rejection that leans
on a past decision is auditable.

## Acceptance Criteria

1. The catalog delivered to verification contains only headings from boundaries
   that own at least one finding path.
2. The catalog carries headings, stable ids, source paths, and kinds. It never
   carries bodies.
3. Only selected heading ids have their bodies resolved and delivered, under the
   verification caps: 3 per finding, 8 bodies, 4096 bytes per body, 16384 bytes
   total.
4. Exceeding a cap truncates deterministically and reports the truncation. It
   does not fail the phase and does not silently deliver more.
5. Heading discovery reuses the existing seam and its `MAX_BOUNDARY_FILE_BYTES`
   read cap unchanged, and no code path delivers a whole `history.md` or
   `decisions.md` to a prompt.
6. A finding in a module with no `agent/history.md` and no `agent/decisions.md`
   verifies against intent alone, with no error and no empty-catalog failure.
7. Unresolvable selected ids are reported bounded and do not fail the phase.
8. Each disposition records the heading ids it relied on, empty when it relied on
   none.
9. Planning's own discovery, caps, and allowlist are unchanged, and their tests
   still pass untouched.
10. A finding whose module carries a decision entry contradicting the finding is
    rejected with a reason naming that entry.
11. The repository validation gate passes.

## Non-Goals

- Changing planning's boundary discovery, caps, or allowlist.
- Adding a new boundary-memory file kind, or a new location convention.
- Full-text or embedding search over boundary files. Selection is the model
  reading titles.
- Writing to `history.md` or `decisions.md`, which `write_history` already owns.

## Dependencies

Subtask 2. The verification phase and its disposition model must exist first.

## Validation Strategy

Unit tests for boundary derivation from finding paths, including a nested module,
a module with neither file, and two findings resolving to one boundary. Cap tests
asserting deterministic truncation and its report. A prompt test asserting the
catalog carries no bodies. Run the runtime-owned validation gate.

## Next Path

`spec_subtask_4_surface-verification-dispositions.md`
