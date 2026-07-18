# Subtask 2: Remove the Dormant Review Pilot

## Scope

Remove review-context and evidence-broker code that has no production construction or execution route, while preserving active parallel review and telemetry behavior.

Audit and remove as a coherent slice where reachability remains absent:

- `ReviewExecutionModePolicy` and its unused eligibility/resolved-mode models.
- `ReviewAssignment`, `ReviewContextPacket`, `GovernedReviewLaunch`, and model fields used only by that pilot.
- `ReviewEvidenceBroker`, review-evidence request/result models, and `FileSystemReviewEvidenceBroker`.
- `review_context_budget` configuration fields and parsing that are not consumed by the active runner.
- Review-context schema paths, copy tasks, bundled resource, typed error, and parity-only tests if no runtime validator/consumer exists.

Keep live types such as provider token usage or budget limits only to the extent that active `ParallelCodeReviewRunner` behavior consumes them. Split shared model files rather than deleting active siblings.

## Acceptance Criteria

1. The removed review slice has no DI binding, composition-root construction, CLI/MCP route, serializer, resource loader, governed-skill consumer, or generated-code reference.
2. `review_context_budget` is either removed from accepted configuration and documentation or proven to affect active execution; silently ignored configuration is not retained.
3. Removing legacy configuration produces a deliberate acceptance/rejection behavior covered by tests, with a clear error for unsupported keys if the config contract is strict.
4. Active parallel code review, review import/triage, learning resolution, token accounting, and review telemetry remain behaviorally unchanged.
5. Review-context schema artifacts and tests are removed together if orphaned; no stale classpath resource copy remains.
6. Runtime-domain, runtime-ports, runtime-application, runtime-infra-fs, runtime-core, runtime-cli, and runtime-mcp review tests pass.

## Non-Goals

- Redesigning active review orchestration.
- Adding a new automatic review mode.
- Removing review telemetry, triage, or learning functionality.

## Dependency Notes

Depends on Subtask 1 for the reachability ledger and proof rules.

## Validation Strategy

- Trace from all composition roots through application services and ports.
- Search repository configuration examples, schemas, docs, tests, and installed skill content for `review_context_budget` and review-context wire names.
- Run focused review and config tests followed by affected module checks.

## Next Path

Proceed to Subtask 3 after active review behavior is proven independent of the removed pilot.

