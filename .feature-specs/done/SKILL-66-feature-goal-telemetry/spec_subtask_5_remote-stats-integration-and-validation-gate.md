---
status: Complete
---

# SKILL-66 Subtask 5 - Remote Stats Integration and Validation Gate

Parent spec: [.feature-specs/SKILL-66-feature-goal-telemetry/spec.md](./spec.md)
Issue key: SKILL-66

## Scope

Integrate the goal family into the remote telemetry surface: map `"goal"` and
`"bill-feature-goal"` in `telemetry_remote_stats`, update
`telemetry_proxy_capabilities` if (and only if) the existing pattern
enumerates families explicitly, and update the remote-stats payload schema
branch where it constrains workflow names. Update docs that enumerate the
telemetry families, resolve any remaining parent open questions, and run the
full maintainer validation gate as the closing act of the SKILL-66 goal.

## Acceptance Criteria

1. `McpToolDispatcher.telemetryRemoteStats` accepts `"goal"` and
   `"bill-feature-goal"` and maps them to the goal family, exactly parallel
   to the existing `"implement"`/`"verify"` mappings; unknown workflows keep
   their current rejection behavior.
2. The parent-spec open question on `telemetry_proxy_capabilities` is resolved
   from code: if capabilities enumerate families, the goal family is added and
   tested; if generic, the spec's Open Questions section records that no
   change was needed.
3. The `telemetry_remote_stats` request/payload constraints in
   `telemetry-event-schema.yaml` admit the new workflow values, consistent
   with how implement/verify appear there.
4. `skill-bill telemetry` inspection/sync subcommands treat goal events like
   the other lifecycle events wherever the existing implementation branches
   per family (verified, with tests where a branch exists).
5. Docs are updated where telemetry families are enumerated (at minimum
   `runtime-kotlin/ARCHITECTURE.md` if it lists telemetry families, and the
   relevant `agent/history.md` entries per repo convention); no doc restates
   contract details that the schema owns.
6. All parent Open Questions are marked resolved in
   [spec.md](./spec.md) with the chosen answer and a one-line rationale.
7. The full maintainer validation gate passes:
   - `skill-bill validate`
   - `(cd runtime-kotlin && ./gradlew check)`
   - `npx --yes agnix --strict .`
   - `scripts/validate_agent_configs`
8. End-to-end smoke: one real `skill-bill goal` run on a trivial decomposed
   spec, followed by `skill-bill goal-stats` and a `telemetry_remote_stats
   workflow="goal"` dispatch (against the configured proxy or its test
   double), shows the run in both surfaces.

## Non-Goals

- No remote proxy server changes; only the local mapping/capability surface.
- No new events, storage, or aggregation logic.
- No changes to implement/verify remote-stats behavior.

## Dependency Notes

Depends on: Subtask 4 (the local stats surface this connects to remote
aggregation; transitively 1-3).

Final subtask of the SKILL-66 goal.

## Validation Strategy

Dispatcher tests for the workflow mapping (accept + reject cases); proxy
capability tests if applicable; the end-to-end smoke; the full maintainer
command set as the closing gate.

## Next Path

Final subtask. On completion the SKILL-66 goal is complete; no further subtask
spec follows.

## Spec Path

.feature-specs/SKILL-66-feature-goal-telemetry/spec_subtask_5_remote-stats-integration-and-validation-gate.md
