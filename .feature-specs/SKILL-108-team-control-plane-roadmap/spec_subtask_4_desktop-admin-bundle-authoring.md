# SKILL-108 Subtask 4: Desktop Admin Bundle Authoring and Publish

## Scope

Add local-first desktop surfaces for admin bundle authoring and publishing.
The desktop app already edits governed source, validates, scaffolds, and opens
installed workspaces. This subtask connects those capabilities to the team
bundle export/sync/rollback flow.

Desktop should support:

- editing authored `content.md`, platform pack manifests, add-ons, and team
  override source through existing runtime-backed authoring services
- validating before publish using `skill-bill validate` equivalent behavior and
  `scripts/validate_agent_configs` equivalent coverage where practical
- previewing the bundle contents and source-entry hash changes before publish
- publishing a bundle to a local registry or file destination by calling the
  same export service used by CLI
- syncing and rolling back a local bundle by calling the same sync service used
  by CLI
- showing current installed bundle metadata, available local registry channels,
  validation status, and rollback target

Desktop publish must not reintroduce Git commit, push, fork, compare-URL, or
pull-request workflows.

## Acceptance Criteria

1. Desktop exposes team bundle metadata for the current workspace: installed
   bundle id, version, channel, content hash, source ref, and rollback target
   when available.
2. Desktop publish runs runtime validation and refuses to publish a bundle from
   invalid governed source.
3. Desktop preview shows bundle entries, changed source hashes, selected
   channel, version, destination, and generated-artifact exclusions before
   publish.
4. Desktop publish calls the shared export service and produces the same bundle
   metadata and JSON-equivalent outcome as `skill-bill team export`.
5. Desktop sync and rollback call the shared sync/rollback service and surface
   typed success/failure/rollback-incomplete outcomes.
6. No desktop source imports or state models reintroduce Git publishing,
   compare-URL, pull-request, or fork configuration concepts.
7. UI tests cover publish disabled states, validation failure, successful local
   registry publish, sync failure, rollback success, and generated-artifact
   exclusion display.
8. Desktop docs describe bundle publish as local/registry publishing, not Git
   publishing.

## Non-goals

- No hosted registry UI.
- No authenticated PR creation.
- No source-control diff, commit, push, or fork setup.
- No remote role enforcement.
- No automatic skill tuning from telemetry.

## Dependency Notes

Depends on subtasks 1, 2, and 3. The desktop app must consume shared export and
sync services rather than shelling out or duplicating bundle rules.

## Validation Strategy

Run desktop and bundle service tests plus:

```bash
(cd runtime-kotlin && ./gradlew :runtime-desktop:feature:skillbill:jvmTest :runtime-desktop:core:data:jvmTest)
npx --yes agnix --strict .
```

Manual smoke: publish a bundle from desktop to a temp local registry, sync it
into a scratch workspace, then rollback from desktop.

## Next Path

After this lands, run subtask 5 to add the local role/proposal workflow on top
of desktop and CLI bundle publishing.
