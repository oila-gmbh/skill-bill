---
status: Complete
---

# SKILL-71 Subtask 3 - bill-feature-spec Linear Mode

Parent spec: [.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec.md](./spec.md)
Issue key: SKILL-71

## Scope

Add a Linear-backed spec mode to `bill-feature-spec` that creates the Linear
tracking artifacts and stamps the resolved mode + Linear ids into the prepared
artifact. Creation is agent-side via the Linear MCP; no runtime Linear client.

- Add the `service:default/linear` argument to `bill-feature-spec`
  (`skills/bill-feature-spec/content.md`). Resolve mode as
  `service: arg > config spec_type (subtask 1) > local`.
- In linear mode, before writing artifacts:
  - create a parent Linear issue tagged `task` whose description is the parent
    spec content;
  - for a decomposed feature, create one sub-issue per subtask (tag `task`),
    each sub-issue's description carrying that subtask's spec content;
  - adopt the parent issue's returned key as the issue key and the
    `.feature-specs/{KEY}-{name}/` directory name;
  - stamp `spec_source: linear` into the artifact (manifest field for decomposed,
    `spec.md` line for single_spec) and record each subtask's `linear_issue_id`
    (subtask 2 contract).
- Descriptions must be clearly structured (a clear header per subtask) so the
  Linear ticket is human-legible; rehydrate (subtask 4) keys off
  `linear_issue_id`, not header text.
- If linear mode is selected but the Linear MCP is unavailable/unauthenticated,
  loud-fail with a clear message **before** creating any Linear issue or writing
  any artifact (no partial state).
- In local mode, behavior is exactly as today (no Linear calls, `spec_source`
  resolves to `local`).
- Keep routing through the shared preparation path; do not fork single_spec vs
  decomposed writing logic beyond adding the stamp + Linear-id fields.

## Acceptance Criteria

1. `bill-feature-spec` accepts `service:default/linear`; mode resolves
   `arg > config spec_type > local`.
2. Linear mode creates a parent issue (tag `task`) with the parent spec as
   description and, for decomposed features, one sub-issue per subtask (tag
   `task`) with that subtask's spec as description.
3. The parent issue key becomes the issue key and the `.feature-specs/{KEY}-{name}/`
   directory; the manifest `issue_key` is the parent key.
4. The artifact is stamped `spec_source: linear`, and each subtask records its
   `linear_issue_id`; single_spec records the `spec.md` `spec_source` line.
5. If the Linear MCP is unavailable when linear mode is selected, preparation
   loud-fails before any Linear issue is created or any artifact written.
6. Local mode (no config, `spec_type: local`, or `service:local`) is byte-for-byte
   unchanged from today and makes no Linear calls.

## Non-Goals

- No consumption behavior (commit exclusion, deletion, rehydrate) — subtask 4.
- No runtime Linear client; all Linear access is via the agent's Linear MCP.
- No new manifest fields beyond those defined in subtask 2.
- No change to the shared preparation write path's structural rules.

## Dependency Notes

Depends on subtask 1 (config `spec_type` for default resolution) and subtask 2
(the `spec_source` / `linear_issue_id` contract to stamp).

## Validation Strategy

- Skill-level: local-mode prep unchanged; linear-mode prep creates parent + N
  sub-issues, adopts the parent key, and stamps the artifact; MCP-unavailable
  path loud-fails with no partial state.
- `skill-bill validate`, `npx --yes agnix --strict .`,
  `scripts/validate_agent_configs` for the skill content change.

## Next Path

Proceed to subtask 4:
`.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec_subtask_4_feature-task-goal-stamp-consumption.md`
