# Subtask 3 - Flatten layered review composition

## Scope

Replace nested routed/baseline orchestrator execution with a deterministic,
manifest-driven flattened launch plan. Update canonical review prose, rendering,
and platform composition so routed layers consume the parent packet and selected
specialists launch directly.

## Acceptance Criteria

1. Manifest-declared baseline layers expand recursively into an ordered,
   duplicate-free specialist plan before any worker starts; cycles, missing
   layers, incompatible contracts, and ambiguous ownership fail loudly.
2. A KMP review expands the required Kotlin baseline into selected Kotlin
   specialists and launches no Kotlin baseline orchestrator worker.
3. Flattening retains required baseline lanes, signal-relevant lanes, add-ons,
   attribution, deterministic ordering, independent reasoning, and merge/dedup
   behavior while dropping empty and duplicate assignments.
4. Routed packet consumers receive no full scope/routing/shell ceremony that
   instructs rediscovery, learning resolution, or telemetry import.
5. Platform composition, renderer, validator, and snapshot tests prove exact
   KMP/Kotlin expansion, no nested orchestrator, cycle rejection, mixed-stack
   behavior, and unchanged inline-mode rubric coverage.

## Non-Goals

- Do not remove required Kotlin baseline coverage from KMP.
- Do not hard-code shipped platform slugs in the shared planner.
- Do not alter native-agent linking in this subtask.

## Dependency Notes

Depends on subtask 1 for lane decisions and packet-consumer contracts and on
subtask 2 for direct bounded specialist launches.

## Validation Strategy

Run platform-pack composition, rendering, scaffold, snapshot, and delegated
launch-plan tests. Validate at least Kotlin, KMP, mixed-stack, empty-lane, cycle,
and explicit inline/delegated fixtures.

## Next Path

Continue with subtask 4, which makes every logical worker in the flattened plan
installable and preflighted without generic fallback.
