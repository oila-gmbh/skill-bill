# SKILL-172 Subtask 1 - Planning-context discovery stops buying review routing tables

Parent spec: [.feature-specs/SKILL-172-goal-planning-burst-and-context/spec.md](spec.md)
Issue key: SKILL-172

## Scope

Stop goal-planning context discovery from reading `platform-packs/*/platform.yaml`, so the
32KB discovery budget is spent on the boundary memory and repo conventions a planning agent
can actually use.

Today `FileSystemGoalPlanningContextDiscovery.discover` reads platform packs first
(`FileSystemGoalPlanningContextDiscovery.kt:17`). In a repo with eight or more packs whose
`platform.yaml` exceeds the 4KB per-file excerpt cap, those reads consume the entire
`MAX_DISCOVERY_TOTAL_BYTES = 32KB` budget, `canRead()` goes false, and `boundary_memory` and
`validation_guidance` both come back empty. The content bought is review and quality-check
routing metadata — `routing_signals`, `declared_code_review_areas`, `declared_files`,
`area_metadata`, `lane_conditions`, `declared_quality_check_file`, `pointers` — with no
planning value, and truncated at 4KB so only the routing header ever arrives.

Keep the `platform_packs` packet field present and serialising as `{}`. Removing the key
changes `PACKET_FIELDS`, which `validate()` matches exactly and `integrity_sha256` covers, and
would break resume for in-flight goals. Key removal is deferred to versioned work.

Primary files:

- `runtime-infra-fs/src/main/kotlin/skillbill/goalplanning/FileSystemGoalPlanningContextDiscovery.kt`
- `runtime-ports/src/main/kotlin/skillbill/ports/goalrunner/model/GoalPlanningContext.kt`
- `runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalPlanningSweep.kt` (line 684, packet assembly)

Verified before scoping: `GoalPlanningContext.platformPacks` has exactly one consumer, the
packet assembly at `GoalPlanningSweep.kt:684`. Every other `platformPacks` symbol in the tree
belongs to install, scaffold, or review and operates on `platformPacksRoot` paths or
`InstallPlatformPackSnapshot`, none of which this subtask touches.

## Acceptance Criteria

1. `FileSystemGoalPlanningContextDiscovery` no longer reads `platform-packs/*/platform.yaml`,
   and the assembled packet's `platform_packs` field is an empty object.
2. A test over a fixture repo containing nine platform packs, each `platform.yaml` larger than
   `MAX_DISCOVERY_EXCERPT_BYTES`, plus per-pack `agent/history.md` and `agent/decisions.md` and
   a root `AGENTS.md`, asserts that `boundary_memory` and `validation_guidance` are both
   non-empty. This test must fail against the current implementation before the fix.
3. The discovery budget still bounds total bytes, per-file excerpt size, and file count; this
   subtask reallocates the budget, it does not raise or remove it.
4. `GoalPlanningSharedContextPacket.PACKET_FIELDS` and `VERSION` are unchanged, and a packet
   serialised before this change still passes `validate()` — covered by a test that validates a
   packet fixture carrying populated `platform_packs`.
5. Boundary memory is read before repo conventions, and the ordering is stated in a comment so
   a future reader knows the sequence is load-bearing rather than incidental.
6. If discovery still cannot fit everything, what survives is deterministic and documented —
   silent truncation by argument order is not acceptable as the final behaviour.
7. `./gradlew build -x sourcesJar` and `detekt` pass.

## Non-Goals

- No removal of the `platform_packs` packet key and no rename of `validation_guidance`; both
  require a `VERSION` bump and recovery migration and are deferred.
- No change to `MAX_DISCOVERY_*` values.
- No change to install, scaffold, or review platform-pack handling.
- No change to what the planning briefing does with the packet once assembled.

## Dependency Notes

Standalone. Independent of subtask 2 and of commit `50bbdf38`. Can land first or second.

## Validation Strategy

1. New discovery test over a nine-pack fixture asserting non-empty `boundary_memory` and
   `validation_guidance`; confirm it fails before the change and passes after.
2. Packet-compatibility test: a packet fixture with populated `platform_packs` still passes
   `GoalPlanningSharedContextPacket.validate` and its `integrity_sha256` check.
3. `(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test :runtime-application:test detekt)`.
4. `(cd runtime-kotlin && ./gradlew build -x sourcesJar)`.

## Next Path

Subtask 2 (planning burst control), or the deferred versioned packet change that removes the
now-empty `platform_packs` key and renames `validation_guidance` to `repo_conventions`.
