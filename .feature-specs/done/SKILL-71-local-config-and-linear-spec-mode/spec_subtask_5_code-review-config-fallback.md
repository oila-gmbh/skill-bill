---
status: Complete
---

# SKILL-71 Subtask 5 - bill-code-review Config Fallback

Parent spec: [.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec.md](./spec.md)
Issue key: SKILL-71

## Scope

Let `bill-code-review` source its parallel lane-2 agent from repo-local config
when the `parallel:` arg is absent, with the arg still overriding. Code review
has no durable downstream state, so config-as-default is sufficient; no stamp.

- When `parallel:<agent>` is present, behave exactly as today (it overrides).
- When `parallel:` is absent, read `code_review_parallel_agent` from
  `.skill-bill/config.yaml` (subtask 1):
  - `none` (or no config / missing key) -> normal single-lane routed review,
    identical to today's no-arg behavior;
  - a supported agent id -> run the two-lane parallel review with that lane-2
    agent, identical to passing `parallel:<agent>`.
- Validate any config value against the same source the skill already uses for
  the arg: `InstallAgent.supportedIds` plus the sentinel `none`. An unknown value
  loud-fails, naming the value and listing supported agents — matching the
  skill's existing unsupported-`parallel:`-arg behavior
  (`skills/bill-code-review/content.md`).
- Resolution precedence: `parallel: arg > code_review_parallel_agent config > none`.

## Acceptance Criteria

1. `parallel:<agent>` still works and overrides config.
2. With no `parallel:` arg and `code_review_parallel_agent: <agent>` in config,
   the review runs the two-lane parallel flow with that lane-2 agent.
3. With no `parallel:` arg and `none`/missing config, the review is the normal
   single-lane routed review, unchanged from today.
4. An unknown/invalid `code_review_parallel_agent` value loud-fails with a named
   value and the supported-agent list; neither lane starts.
5. Validation uses `InstallAgent.supportedIds + none`, the same source as the arg.

## Non-Goals

- Do not remove or change the `parallel:` arg (it remains the optional override).
- No stamping or artifact changes — code review has no durable downstream state.
- No change to lane execution, merge, or provenance behavior.

## Dependency Notes

Depends only on subtask 1 (the config reader). Independent of subtasks 2-4.

## Validation Strategy

- Skill-level: arg-present override; config-driven parallel; `none`/missing ->
  single-lane; invalid value loud-fail.
- `skill-bill validate`, `npx --yes agnix --strict .`,
  `scripts/validate_agent_configs`.

## Next Path

Proceed to subtask 6:
`.feature-specs/SKILL-71-local-config-and-linear-spec-mode/spec_subtask_6_validation-gate-and-back-compat-sweep.md`
