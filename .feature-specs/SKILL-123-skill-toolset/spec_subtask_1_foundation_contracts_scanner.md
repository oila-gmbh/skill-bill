# SKILL-123.1 - Foundation Contracts And Opaque Bundle Scanning

## Scope

Establish the smallest shared runtime foundation for machine-managed third-party skills. This subtask covers managed-skill record contract hardening, typed validation errors, safe managed source and snapshot path construction, opaque runtime skill bundle validation, deterministic bundle hashing from captured bytes, and focused runtime tests.

This subtask intentionally stops before agent discovery, inventory classification, symlink mutation services, transaction journaling across links/snapshots/records, desktop UI, and navigator integration.

## Acceptance Criteria

1. The managed-skill record schema is strict, versioned, bundled onto the runtime classpath, and rejects malformed selected targets, unsafe provider identifiers, unsafe hashes, empty selected target sets, and additional fields.
2. Kotlin record mapping validates records at every read seam, reports typed `InvalidManagedSkillRecordSchemaError` failures, binds records to their managed directory name, and confines managed paths under `~/.skill-bill/managed-skills` and `~/.skill-bill/installed-skills`.
3. The record store rejects unsafe names before creating directories, rejects duplicate JSON keys, validates before publication, supports stale-preview compare-and-swap through an expected digest, and refuses non-atomic record replacement.
4. Agent target identity normalizes provider and absolute path data so duplicate logical target mutations cannot be introduced through lexical aliases.
5. Opaque bundle scanning accepts a `SKILL.md` file or directory containing one root `SKILL.md`, parses strict YAML frontmatter, requires textual `name` and `description`, rejects protected names, rejects duplicate or nested ownership keys, rejects symlinks and special files, and returns immutable captured bundle bytes.
6. The content hash covers sorted normalized relative paths and captured bytes, and tests prove identical scans are stable while content changes alter the hash.
7. Focused managed-skill contract, record-store, and bundle-scanner tests pass, and `git diff --check` is clean.

## Non-Goals

- Agent-target discovery and product-skill classification.
- Inventory rows or ownership/health classification.
- Symlink creation, retargeting, repair, adoption, deletion, or cross-resource rollback.
- Desktop Tools UI, command palette integration, manager UI, or navigator changes.
- Full repository validation gates beyond the focused foundation checks.

## Dependency Notes

This is the prerequisite for every later SKILL-123 subtask. Later mutation services must reuse these models and validators instead of creating another record parser, path builder, bundle scanner, or content-hash implementation.

## Validation Strategy

Run:

```bash
git diff --check
(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test --tests 'skillbill.contracts.managedskill.*' --tests 'skillbill.managedskill.*')
```

## Next Path

Continue with `spec_subtask_2_discovery_inventory.md`.
