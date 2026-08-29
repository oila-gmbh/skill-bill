# SKILL-220 Subtask 1: Package Clustering

## Intended Outcome

Resolve P-01 and P-02. Cross-area type buckets and mixed area roots force a
reader to wade through unrelated noun families. Cluster by area so each
package holds one cohesive family.

## Scope

Mechanical package moves and import updates only. No type rename, visibility
change, or public shape change.

### Dissolve layer-wide buckets

- Move every file out of `skillbill.application.model` into the area-owned
  `.model` package that already owns that noun (featuretask, goalrunner,
  review, telemetry, learning, workflow, work, update-check, ide-status).
  Delete the empty bucket.
- Split `skillbill.ports.persistence` and `skillbill.ports.persistence.model`
  by product area (`featuretask`, `goalrunner` / planning, `review`,
  `telemetry`, `learning`, `workflow`, `work`, diagnostics /
  `RejectedOutput*`). Keep `DatabaseSessionFactory` in a session/db package,
  not in a catch-all.

### Split mixed area roots

- Cluster `skillbill.workflow` loose files into concept packages: engine,
  decomposition, goal (observability / progress / planning envelope),
  taskruntime (FeatureTask* validators already partly there), ide-status,
  spec-source. Apply the same split to `skillbill.workflow.model`.
- Pull FeatureSpec, GoalPlanning, SpecSource, and RejectedOutput types out of
  `skillbill.application.featuretask` into their area packages. Leave
  FeatureTaskRuntime files in `featuretask`; do not invent a catch-all.
- Split `skillbill.application.goalrunner` into runner, planning, and
  findings (`UnaddressedFindings*`).
- Split `skillbill.scaffold.policy` into PlatformPack vs Scaffold.
- Move `GoalSubtaskReview*` out of `skillbill.workflow.taskruntime.model`
  into the goal/review cluster they belong to.
- Split `skillbill.ports.goalrunner` (planning vs runner vs verification) and
  `skillbill.ports.workflow` (decomposition vs spec-scratch vs git ops).

Fold genuine leftovers into the nearest real cluster. Do not create
`model.misc`. Empty `../../../agent` stub packages may be removed if they hold no
sources and no recorded purpose.

## Applicable Principles

- Pure model types live in a `.model` package; split into concept subpackages
  when two or more noun families are present.
- Group by area, not by type. A layer-wide bucket of every repository or
  every mapper is not an area.
- Dependencies point inward; a package move must not create a new edge.
- Existing architecture tests that pin module graph and ownership must stay
  green; update documented package paths they assert.

## Acceptance Criteria

1. `skillbill.application.model` no longer exists, and every former file lives
   in an area `.model` package named for its noun family.
2. `skillbill.ports.persistence` no longer holds repositories from two product
   areas. Each persistence port sits with its area.
3. `skillbill.workflow` root retains only types that belong to no cluster, and
   each retained file is justified in the commit message.
4. `application.featuretask` no longer holds FeatureSpec, GoalPlanning,
   SpecSource, or RejectedOutput types. `application.goalrunner` no longer
   mixes planning and findings with the runner. `scaffold.policy` no longer
   mixes PlatformPack and Scaffold.
5. No type's visibility, name, or public shape changes.
6. No import cycle is introduced, and existing architecture tests confirm
   dependency direction is unchanged.
7. `../../../scripts/validate` passes with no test assertion changed — only imports
   and moved paths.
8. No test is added by this subtask.

## Failure And Recovery Behavior

Unchanged. This subtask moves declarations only.

## Non-Goals

- Renaming types, changing visibility, or altering any public shape.
- The inline FQN sweep (subtask 2).
- Splitting oversized files (subtasks 4–6).
- Changing port method signatures or persistence SQL.

## Dependency Notes

Lands first. Must not run concurrently with subtasks 2, 4, 5, or 6. Every
later subtask reads these imports.

## Validation Strategy

`../../../scripts/validate`, plus a diff review confirming the change is
import-and-move only. Compare the test suite result to the pre-change
baseline.

## Next Path

Subtask 2 removes inline fully-qualified names on the settled packages.
