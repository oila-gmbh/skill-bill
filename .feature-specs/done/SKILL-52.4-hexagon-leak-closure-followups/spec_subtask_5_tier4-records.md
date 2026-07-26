# SKILL-52.4 · Subtask 5 — Tier-4 records & guard test (Phase 4)

Parent overview: [spec.md](./spec.md)

The smallest subtask: record the intentional structural-debt decisions
(F16 split package, F17 infra-fs not split), add the F16 JPMS-safety guard test,
add the F15 ARCHITECTURE.md note, and file backlog items for F15/F18. No
behavioral or structural code change beyond the guard test.

Branch: `feat/SKILL-52.4-hexagon-leak-closure-followups` (same-branch model, one commit for this subtask).

## Dependencies

- depends_on: [1]
- dependency_reason: The F16 guard test extends
  `RuntimeEnforcementHardeningArchitectureTest`, part of the Phase 0 enforcement
  surface established in Subtask 1, and the ARCHITECTURE.md / decisions records
  must reflect the package-ownership and open-boundary state finalized in Phase 0.
  Independent of Subtasks 2–4 (records only); intentionally last so it documents
  the final landed state, but it does NOT depend on 2/3/4 functionally.
- dependencies: [{subtask_id: 1, optional: false, skipped: false}]

## Scope (owns)

- **F16 decision + guard test.** Record in `runtime-kotlin/agent/decisions.md`
  that the `skillbill.contracts.*` split package (DTOs/helpers in
  `runtime-contracts`; schema validators under `skillbill.contracts.install` /
  `skillbill.contracts.workflow` in `runtime-infra-fs`) is retained for
  resource-path stability, with the revisit trigger. Add a guard test (extend
  `RuntimeEnforcementHardeningArchitectureTest`) that fails if a NEW
  `skillbill.contracts.*` validator is added to `runtime-contracts` main —
  keeping the split one-directional and intentional.
- **F17 decision.** Record in the relevant `agent/decisions.md` that
  `runtime-infra-fs` is intentionally not split into Gradle modules, with the
  revisit trigger.
- **F15 note + backlog.** Add an ARCHITECTURE.md note that port-model
  `toPayload` (`RepoValidationReport.toPayload` / `ReleaseRefMetadata.toPayload`
  in `RepoValidationGatewayModels.kt`; `ReviewFinished*.toPayload` in
  `ReviewFinishedTelemetryPayload.kt`) is the ONLY sanctioned
  presentation-in-ports shape and is bounded by the open-boundary allow-list.
  File a backlog item to move it to adapter mappers.
- **F18 backlog.** File a backlog item to lift
  `refuseInstallMutationDuringGoalContinuation` (`InstallCliCommands.kt:53`, used
  144/215/507/591/618) and status→exit-code policy into an application service,
  and to share a single CLI/MCP result-mapper surface. Fix in this spec only if
  cheap.

## Reusable patterns / pitfalls

- Use the `bill-boundary-decisions` skill conventions for `agent/decisions.md`
  entries (each with a revisit trigger).
- Write the F16 guard test with a positive fixture proving it catches a new
  contracts validator placed in `runtime-contracts` main; confirm red→green.
- This subtask is mostly prose/records + one guard test — no product behavior
  changes; golden/wire outputs unaffected.
- No explanatory comments in code.

## Acceptance Criteria

1. AC13: decisions recorded in `runtime-kotlin/agent/decisions.md` for F16 (split
   package retained for resource-path stability) and F17 (infra-fs not split),
   each with the revisit trigger; guard test (extending
   `RuntimeEnforcementHardeningArchitectureTest`) fails if a NEW
   `skillbill.contracts.*` validator is added to runtime-contracts main;
   ARCHITECTURE.md note for F15 (toPayload is the only sanctioned
   presentation-in-ports shape, bounded by open-boundary allow-list); backlog
   items filed for F15 and F18.
2. AC14 (this subtask's slice): all four canonical gates pass.

## Non-goals

- Not retiring the `skillbill.contracts.*` split package (F16 — record + guard
  only).
- Not splitting `runtime-infra-fs` (F17 — record + defer).
- Not actually moving `toPayload` to adapter mappers or lifting CLI policy (F15 /
  F18 — backlog items only unless cheap).
- No behavioral change.

## Validation strategy

`bill-code-check` (Kotlin/Gradle → `./gradlew check`). Run all four canonical
gates: `skill-bill validate`; `(cd runtime-kotlin && ./gradlew check)`;
`npx --yes agnix --strict .`; `scripts/validate_agent_configs`. F16 guard test
proven red on a fixture, then green.

## Handoff prompt

Run bill-feature-task on
`.feature-specs/SKILL-52.4-hexagon-leak-closure-followups/spec_subtask_5_tier4-records.md`.
