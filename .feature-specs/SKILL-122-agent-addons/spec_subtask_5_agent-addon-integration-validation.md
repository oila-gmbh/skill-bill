---
status: Ready for implementation
issue_key: SKILL-122
subtask_id: 5
---

# SKILL-122 Subtask 5: Cross-boundary validation and install refresh

## Scope

Complete the integration matrix for agent add-ons, remove obsolete assumptions
that platform add-ons are the only add-on family, refresh generated installation
staging, and run the full repository quality gate.

## Acceptance Criteria

1. Cross-boundary tests prove dynamic discovery and source validation, staged
   pointer/hash invalidation, atomic scaffold failure recovery, explicit feature
   selection, prose/runtime/goal propagation, confirmation rendering, resume
   digest drift rejection, compatible overrides, incompatible override
   rejection, and unchanged no-add-on behaviour.
2. Tests prove an unselected add-on never enters a prompt and a selected add-on
   cannot be silently dropped, reordered, or replaced after a retry/resume.
3. Tests prove platform-pack and external platform-addon flows still work and
   that no generated outputs are added to governed source directories.
4. Desktop tests prove the distinct **Agent Add-ons** group, dynamic metadata
   and authored-content inspection, manifest reachability, invalid-source
   diagnostics, refresh behaviour, and the Agent add-on scaffold choice; no
   item or label is special-cased for `execution-budget`.
5. `./install.sh` refreshes local staging; rendered `bill-feature` output and
   all internal sidecar pointers are inspected for source/generated boundary
   correctness.
6. `skill-bill validate`, Gradle `check`, strict agnix, and
   `scripts/validate_agent_configs` pass. Any documented fallback or changed
   validation command is recorded with its reason.

## Non-Goals

- New product behaviour beyond the prior four subtasks.

## Validation Strategy

Run the complete command suite and focused regression matrix. Capture only
actionable failures, not verbose generated artifacts, in the final handoff.

## Dependency Notes

Depends on subtasks 1, 2, 3, and 4.

## Next Path

The parent goal is ready for `skill-bill goal SKILL-122`.
