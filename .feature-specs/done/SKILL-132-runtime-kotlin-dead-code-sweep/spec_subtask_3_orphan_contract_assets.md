# Subtask 3: Prove and Remove Orphan Runtime Contract Assets

## Scope

Audit contract schemas and resources that appear to be bundled or parity-tested without a runtime validation seam. Focus on:

- Feature-task execution identity.
- Feature-task runtime worker ownership.
- Goal subtask review state.
- Any review-context artifacts not already removed in Subtask 2.

For each family, trace the schema from producer through serialization, persistence or file storage, resource copying, parser, validator, and consumer. Remove an orphan family as one unit: YAML, Kotlin path/version constants, typed errors, resource-copy Gradle tasks, classpath resource, parity-only tests, unused models, and exclusive dependencies.

## Acceptance Criteria

1. Each audited schema has a recorded producer-consumer-validation trace and a disposition of active or removable.
2. A schema is removed only when no runtime read seam, durable record, migration, external file contract, installation artifact, or compatibility requirement depends on it.
3. Removed schemas leave no path constants, copy tasks, processResources dependencies, classpath entries, errors, tests, or documentation references.
4. Retained schemas have an identified runtime validator and version-parity coverage, or an explicit blocker explaining why removal and validation changes require a separate decision.
5. No database table, column, or historical migration is deleted without an approved migration plan.
6. JAR/distribution inspection proves removed assets are no longer bundled, and contract, persistence, workflow, migration, and configuration-cache tests pass.

## Non-Goals

- Weakening loud-fail behavior for active contracts.
- Bumping unrelated schema versions.
- Deleting durable data structures based only on absent file-name references.

## Dependency Notes

Depends on Subtask 1. It may use the review-context disposition from Subtask 2 but must not reverse active review decisions.

## Validation Strategy

- Search path constants, resource file names, schema IDs, contract versions, and typed errors.
- Trace database mappings and workflow read/write seams.
- Inspect built JARs and distributions.
- Run schema parity, persistence, workflow, migration, and configuration-cache tests.

## Next Path

Proceed to Subtask 4 once MCP schema dependencies can be evaluated against the reduced active contract set.

