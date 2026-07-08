# SKILL-108 Subtask 6: Team Telemetry Attribution and Privacy

## Scope

Extend telemetry so admins can measure outcomes by team setup without turning
telemetry into surveillance. This subtask adds team, channel, and bundle-version
attribution to workflow telemetry and remote stats contracts while preserving
the existing privacy tiers.

Events and stats should support grouping by:

- team id or local team alias
- bundle id
- bundle version
- channel
- routed skill
- platform pack
- workflow type
- telemetry source: local, hosted relay, or custom proxy

Privacy requirements:

- `off`: no events are queued or sent
- `anonymous`: aggregate usage and outcomes only; no code, prompt text, file
  contents, finding descriptions, rejection prose, or learning content
- `full`: detailed telemetry only for organizations that explicitly opt in

Bundle/team attribution should be read from installed bundle state created by
subtask 3. Unbundled installs must keep working and emit null/absent team fields
according to the existing payload style.

## Acceptance Criteria

1. Workflow telemetry payloads include bundle/team/channel/version attribution
   when the current install was synced from a team bundle.
2. Unbundled installs continue emitting valid telemetry without fake team or
   bundle values.
3. Anonymous telemetry excludes code, prompt text, file contents, finding prose,
   rejection notes, and learning content even when team attribution is present.
4. Remote stats request/response contracts can filter or group by bundle
   version, channel, routed skill, platform pack, team, and workflow type.
5. Self-hosted telemetry proxy configuration still works and remains the only
   destination when configured.
6. Telemetry failures remain non-fatal to local workflows and sync/install.
7. Tests cover payload filtering at `off`, `anonymous`, and `full`; unbundled
   installs; bundled installs; remote stats grouping; and proxy capability
   compatibility.
8. Docs update `docs/review-telemetry.md` with team attribution fields,
   privacy examples, and proxy expectations.

## Non-goals

- No dashboards or visual analytics UI.
- No automatic skill mutation based on telemetry.
- No hidden learning writes.
- No hosted org account system.
- No customer code or source upload.

## Dependency Notes

Depends on subtasks 1, 2, and 3 so telemetry can read installed bundle metadata.
Subtask 7 consumes the new grouping/filtering contract.

## Validation Strategy

Run telemetry, CLI, and proxy-contract tests plus:

```bash
(cd runtime-kotlin && ./gradlew :runtime-domain:test :runtime-application:test :runtime-infra-sqlite:test :runtime-cli:test)
skill-bill validate
npx --yes agnix --strict .
```

Manual smoke: sync a test bundle, run a review/check/feature-task workflow with
anonymous telemetry enabled, inspect queued payload fields, and confirm no prose
or file content is present.

## Next Path

After this lands, run subtask 7 to build admin-facing analysis and before/after
tuning surfaces on top of the telemetry attribution.
