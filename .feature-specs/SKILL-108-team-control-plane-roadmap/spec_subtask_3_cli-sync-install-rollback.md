# SKILL-108 Subtask 3: CLI Sync, Install Integration, and Rollback

## Scope

Implement `skill-bill team sync` and local rollback for Phase 1. Sync installs a
verified team bundle into the local Skill Bill workspace by reusing existing
render/install/validate/staging behavior. It must not copy generated agent files
directly from the bundle.

The command should:

- accept a bundle file, registry coordinate, or channel selector
- verify archive checksum, content hash, contract version, channel, and source
  shape before mutation
- stage bundle source into a controlled candidate location
- run governed validation against candidate source
- render and install using existing install plan/apply behavior
- persist current bundle state and previous bundle state for rollback
- provide `team status`, `team list`, or equivalent inspection of installed
  bundle metadata
- provide rollback to the previous bundle version after failed or user-requested
  sync
- preserve local telemetry settings according to bundle policy and user opt-out
  rules

## Acceptance Criteria

1. `skill-bill team sync <bundle>` verifies checksum, content hash, schema
   contract version, channel, required source files, and platform manifests
   before mutating the local workspace.
2. Sync installs by feeding governed bundle source through the existing
   render/install/staging path; it never installs generated wrappers or
   provider-native agent files copied from the bundle archive.
3. If validation, render, staging, install, native-agent rendering, or link
   update fails, sync leaves the previous installed setup usable or reports a
   typed rollback-incomplete error.
4. Successful sync records installed bundle id, version, channel, content hash,
   source ref, installed time, and previous bundle state in durable local state.
5. Rollback restores the previous bundle through the same governed install path
   and reports the restored bundle id/version/channel.
6. Sync can operate from a local registry channel, selecting the latest valid
   bundle for `development`, `beta`, or `stable`.
7. A scratch install can sync a bundle, run `/bill-feature`,
   `/bill-code-review`, and `/bill-code-check`, then rollback without
   maintainer hand edits.
8. Tests cover invalid checksum, stale contract version, missing `content.md`,
   malformed `platform.yaml`, generated artifact entry, install failure with
   rollback, successful rollback, and registry channel selection.

## Non-goals

- No desktop sync or publish UI.
- No hosted account system.
- No remote permissions model.
- No telemetry analytics dashboard.
- No direct mutation of agent-specific generated files outside existing install
  behavior.

## Dependency Notes

Depends on subtasks 1 and 2. The sync command consumes bundle contract models
and export/local-registry outputs.

## Validation Strategy

Run install, CLI, and bundle sync tests plus:

```bash
(cd runtime-kotlin && ./gradlew :runtime-cli:test :runtime-infra-fs:test)
skill-bill validate
scripts/validate_agent_configs
npx --yes agnix --strict .
```

Manual smoke: export a bundle to a temp local registry, sync it into a scratch
Skill Bill home, verify slash command installation, then rollback.

## Next Path

After this lands, run subtask 4 to expose local admin publish/sync/rollback
from the desktop app without adding Git publishing.
