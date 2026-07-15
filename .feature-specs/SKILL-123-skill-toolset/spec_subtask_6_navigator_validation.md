# SKILL-123.6 - Third-Party Skills Navigator And Full Validation

## Scope

Add the persistent left-navigator **Third-Party Skills** projection and complete cross-boundary validation. This subtask wires the shared machine inventory into normal repository browsing, opens managed runtime skills through the governed managed-skill edit path, opens unmanaged items read-only with adoption guidance, and runs the complete validation matrix for SKILL-123.

## Acceptance Criteria

1. The left navigator contains a top-level collapsible **Third-Party Skills** group as a machine-scoped sibling of repository-backed groups.
2. The group is present with or without an open repository and shows an empty-state child when no third-party skills are found.
3. The group badge shows the number of logical non-product skills, deduplicated by skill name across agent targets.
4. Child rows come from the same inventory snapshot used by **Manage installed skills** and show concise ownership/health state such as `managed`, `unmanaged`, `conflict`, `broken`, or `divergent`.
5. Expanding or refreshing the group triggers shared machine-inventory load with stale-request protection.
6. Successful install, adoption, edit, target change, repair, or deletion refreshes both the navigator group and any open manager dialog from one result.
7. Selecting a managed child opens its canonical managed `SKILL.md` in the center editor, labeled **Third-party runtime skill**, distinct from governed `content.md`, and saving uses the same validate-restage-retarget path as manager Edit.
8. Selecting an unmanaged child opens a read-only source/details view with adoption guidance; divergent same-name copies require choosing which agent copy to inspect or adopt.
9. The inspector shows ownership, canonical source/snapshot where applicable, installed agents, link health, and conflicts.
10. Product Skill Bill skills never appear in the group.
11. Collapse state is preserved for the desktop session, and keyboard tree navigation, focus, selection, and accessibility semantics match existing navigator behavior.
12. Tests cover the full SKILL-123 matrix: registry rendering/dispatch, keyboard and accessibility behavior, file/directory import validation, multi-provider and multi-profile discovery, idempotent install/update, all-vs-subset target selection, ownership classification, product exclusion, unmanaged/divergent adoption, edit restaging, target reconciliation, conflict preservation, broken-link repair, delete preview/apply, stale-plan rejection, rollback/post-mortem, Windows preflight, and end-to-end symlinks to one managed snapshot.
13. The repository validation gates pass: `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Non-Goals

- New machine-skill services beyond those needed by the navigator/editor path.
- Marketplace, export, health-check catalog tools, source watching, or headless CLI commands.

## Dependency Notes

Depends on subtasks 1 through 5. This subtask is the final integration and validation slice for SKILL-123.

## Validation Strategy

Run focused desktop and runtime tests first, then the full repository validation gates:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
```

## Next Path

After this subtask completes, SKILL-123 should be ready for final PR description and review.
