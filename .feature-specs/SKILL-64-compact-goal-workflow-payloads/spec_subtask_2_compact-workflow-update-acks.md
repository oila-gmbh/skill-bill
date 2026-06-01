---
status: Draft
---

# SKILL-64 Subtask 2 - Compact Workflow Update Acknowledgements

Parent spec: [.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec.md](./spec.md)
Issue key: SKILL-64

## Scope

Change workflow update success responses so routine CLI/MCP update calls return
operation acknowledgements instead of full workflow snapshots. Preserve explicit
full-state reads through read-only get/show commands or an intentional opt-in
mode.

This subtask owns update/open response policy only where it is necessary to stop
routine model-context echo. It must keep existing loud-fail validation at update
parse and persistence seams.

## Acceptance Criteria

1. MCP workflow update success responses return compact acknowledgements by
   default.
2. Acknowledgements include at least:
   - status;
   - workflow id;
   - workflow status;
   - current step id;
   - updated step ids;
   - updated artifact keys;
   - db path.
3. The acknowledgement is produced only after the update has been validated and
   persisted.
4. Full workflow snapshots remain available through explicit read-only get/show
   behavior.
5. CLI and MCP semantics are aligned, or any intentional CLI/MCP difference is
   documented and covered by tests.
6. Golden tests are deliberately updated for the new compact default.
7. Existing callers that need full workflow state have a clear migration path.

## Non-Goals

- Do not weaken validation for `step_updates`, artifact patches, workflow
  status, or current step ids.
- Do not remove read-only full workflow inspection.
- Do not make every workflow command compact if its purpose is explicit
  inspection.

## Dependency Notes

Depends on: none

Can be implemented in parallel with Subtask 1 if mapper write scopes are
coordinated. Subtask 3 depends on the final response shapes.

## Validation Strategy

Add workflow service and adapter tests proving compact acknowledgements reflect
the actual persisted state while omitting large artifact payloads. Update CLI
and MCP goldens deliberately.

## Next Path

Run bill-feature-task on spec_subtask_3_goal-runner-integration.md.

## Spec Path

.feature-specs/SKILL-64-compact-goal-workflow-payloads/spec_subtask_2_compact-workflow-update-acks.md
