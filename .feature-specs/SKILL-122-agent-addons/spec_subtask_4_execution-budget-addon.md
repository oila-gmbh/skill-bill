---
status: Blocked
issue_key: SKILL-122
subtask_id: 4
---

# SKILL-122 Subtask 4: Execution-budget add-on and product surfaces

## Scope

Ship the first validated `execution-budget` agent add-on and finish the
user-facing source/documentation/desktop surfaces for the new extension type.
The add-on targets Codex and the `bill-feature` consumer only.

## Acceptance Criteria

1. `agent-addons/execution-budget/agent-addon.yaml` conforms to the new
   contract with `slug: execution-budget`, `agent_ids: [codex]`, and
   `consumers: [bill-feature]`; its `content.md` is concise authored guidance,
   not a generated wrapper or support pointer.
2. Its guidance directs the active agent to honour a user-defined stopping
   boundary, avoid unrequested PR babysitting/unrelated follow-up work, use
   compact durable hand-offs and scoped reads, and obey the existing explicit
   delegation rule.
3. Its guidance does not prescribe model/reasoning/speed settings, cap context
   or compaction, change the default workflow terminal phase, bypass existing
   review/validation, request extra confirmation, or claim authority over user
   intent and repository contracts.
4. Dynamic CLI/catalogue and desktop extension-tree surfaces visibly distinguish
   agent add-ons from platform add-ons. The desktop app renders an **Agent
   Add-ons** group, opens `execution-budget`'s authored content on selection,
   displays description, supported agents, consumers, source path, and
   validation status, and reaches its manifest without hard-coding
   `execution-budget` into discovery.
5. Documentation clearly explains source shape, explicit invocation,
   precedence, installation/rendering boundaries, agent compatibility, resume
   digest behaviour, and the difference from platform add-ons. It explicitly
   advises against model-version-specific automatic configuration and manual
   context-window/compaction controls.
6. A real explicit feature invocation can resolve and stage
   `agent-addon:execution-budget` for Codex, while selection for an unsupported
   agent fails before execution and a no-add-on invocation remains unchanged.

## Non-Goals

- Adding a `stop-after` command grammar or automatic phase terminal behaviour.
- Making `execution-budget` a default, a global `AGENTS.md` replacement, or an
  option for other agents without their own validated add-on declaration.
- A desktop UI control that selects add-ons for a feature run.

## Validation Strategy

Run source validation, rendering/install inspection, explicit-selection CLI
coverage, unsupported-agent rejection coverage, and desktop presentation tests.

## Dependency Notes

Depends on the contract/delivery path in subtask 2 and the selection path in
subtask 3.

## Next Path

Proceed to `spec_subtask_5_agent-addon-integration-validation.md`.
