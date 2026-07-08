# SKILL-108 Subtask 2: CLI Export and Local Registry

## Scope

Implement the local-first publishing primitive: `skill-bill team export`.
Export creates a deterministic, versioned team bundle from governed Skill Bill
source and writes it either to a requested file path or a local bundle registry.

The command should:

- collect governed source using existing discovery/manifest behavior
- run validation before export by default
- include bundle metadata: version, channel, source repo/ref, created time,
  author, contract version, content hash, source hashes, and selected runtime
  compatibility metadata
- produce a deterministic archive plus sidecar checksum or embedded checksum
  that sync can verify
- support channels such as `development`, `beta`, and `stable`
- publish to a local registry directory when requested
- refuse dirty generated output or generated source entries
- exclude workflow DBs, installed staging artifacts, provider-native agent
  outputs, desktop state, local recents, and telemetry outbox data

CLI output must be machine-readable with `--format json` and human-readable by
default. The command group should be `skill-bill team export`.

## Acceptance Criteria

1. `skill-bill team export --version <version> --channel <channel>` creates a
   deterministic team bundle archive whose metadata validates against the
   subtask 1 contract.
2. Re-running export from unchanged source with the same metadata yields the
   same content hash and source-entry hashes.
3. Export runs `skill-bill validate` equivalent behavior before writing by
   default and refuses invalid governed source.
4. Export has an explicit bypass or dry-run mode only if it is named honestly
   and never produces a publishable bundle from invalid source.
5. Export rejects generated wrappers, generated support pointers,
   provider-native output, installed staging artifacts, workflow DBs, desktop
   state, and telemetry outbox files if they are accidentally selected.
6. Local registry publish writes atomically: a failed write leaves no partial
   publish that `team sync` can discover.
7. The command emits a stable JSON payload with bundle path, bundle id, version,
   channel, content hash, checksum, source ref, validation summary, and registry
   destination when applicable.
8. Tests cover deterministic export, generated-file rejection, invalid-source
   rejection, local registry atomicity, channel validation, and JSON output.

## Non-goals

- No install or sync mutation of the local Skill Bill workspace.
- No desktop publish button.
- No hosted registry.
- No permissions model beyond metadata fields.
- No Git publishing, compare URL, or pull-request handoff.

## Dependency Notes

Depends on subtask 1. Use the team-bundle schema and typed validator as the
archive metadata authority.

## Validation Strategy

Run CLI and export-specific tests plus:

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:test :runtime-infra-fs:test)
skill-bill validate
npx --yes agnix --strict .
```

Manual smoke: export a bundle from this repo to a temp directory, inspect the
archive entries, and verify no generated or install-state files are present.

## Next Path

After this lands, run subtask 3 to sync a verified bundle through the existing
install path and preserve rollback state.
