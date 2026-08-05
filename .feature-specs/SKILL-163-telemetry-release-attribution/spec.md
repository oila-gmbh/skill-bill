# SKILL-163: Telemetry release attribution and durable install identity

Status: Prepared

## Intended Outcome

Every telemetry event skill-bill uploads is attributable to the release that emitted it, and a
given installation keeps one stable `install_id` across releases and across telemetry
enable/disable cycles. Alongside those two capabilities, close the gap between what the
`anonymous` level is documented to send and what it actually sends, and publish the privacy
documentation that a hosted, opt-out telemetry relay requires.

Today neither attribution nor identity continuity exists:

- The only version on the wire is `contract_version` (`"1.7.0"`), a wire-format version. Nothing
  identifies the build. `skillbill_runtime_exception` uploads stack traces with no release to
  attribute them to.
- `install_id` survives `install.sh` and `uninstall.sh` (the `~/.config/skill-bill` migration at
  `install.sh:630-651` handles that), but setting telemetry to `off` deletes the whole config
  file, so `off -> anonymous` mints a new UUID and silently resets the installation's identity.

## Acceptance Criteria

1. Every event uploaded to the telemetry proxy carries the emitting release version as a
   `skill_bill_version` property, sourced from the release-derived `SkillBillVersion.VALUE`.
2. The version recorded on an event is the version of the build that emitted it, not the version
   of the build that later uploads it; an event queued under one release and synced under a
   later release reports the earlier one.
3. No code path deletes `config.json`. Disabling telemetry updates the file in place — clearing
   telemetry settings and queued events while retaining `install_id`, `external_addon_sources`,
   and `execution_matrix` — so a subsequent re-enable reuses the same `install_id` rather than
   minting a new UUID.
4. At `anonymous` level, no event carries a raw customer-identifying string: `issue_key`, the
   `repo` field on `resolve_learnings`, and `skillbill_runtime_exception` stack traces and error
   messages are redacted or stably hashed rather than sent verbatim.
5. `full` level behavior for those fields is unchanged.
6. A privacy document exists stating, per telemetry level, which fields are collected, where they
   are sent, how to opt out, and how to self-host the relay.
7. `docs/review-telemetry.md` names the durable `~/.config/skill-bill/config.json` path wherever
   it currently names the legacy `~/.skill-bill/config.json` path, and its per-level collection
   table matches the redaction behavior shipped under criterion 4.
8. `(cd runtime-kotlin && ./gradlew check)` and `skill-bill validate` pass.

## Constraints

- Release attribution must not require a `telemetry-event-schema.yaml` `contract_version` bump.
  `TelemetryEventSchemaValidator` runs only in the MCP dispatcher against tool-call arguments
  (`McpToolDispatcher.kt:150`); the events that are actually uploaded are built in
  `runtime-infra-sqlite` and enqueued as opaque `payload_json` (`LifecycleTelemetryEmitSupport.kt:70-72`),
  never validated against that schema. Bumping the contract would loud-fail legacy records for no
  gain on this path.
- `SkillBillVersion.VALUE` already exists and is genuinely release-derived (`RELEASE_VERSION` env
  or `git describe --tags`, root `build.gradle.kts:17-36`). Reuse it; do not add a second version
  source.
- Redaction must be applied where the payload is built, so an unredacted value is never written to
  `telemetry_outbox`. Redacting at upload time would leave plaintext at rest on the user's disk.
- Existing rows in `telemetry_outbox` at upgrade time have no recorded version. They must sync
  successfully rather than being dropped or blocking the queue.
- The `anonymous` redaction changes what existing dashboards receive for `issue_key`. Any proxy or
  dashboard query keyed on raw `issue_key` needs to be identified as affected.

## Non-Goals

- Deriving a hardware machine id. Explicitly rejected during design: a persistent device
  fingerprint is personal data under GDPR with a materially worse posture than a rotating
  installation id; it is a public-repo optics liability; and it is not actually stable — absent on
  macOS without `IOPlatformUUID`, fresh per Docker container, cloned across golden VM images, and
  different again under WSL. It would also measure devices, not users. The durable `install_id`
  already delivers the continuity this feature needs.
- Changing the telemetry default from opt-out `anonymous` to opt-in. That is a product and legal
  decision, not part of this work.
- Changing the `full` level's collected fields.
- Retention policy enforcement in the Cloudflare worker. The privacy doc states the policy;
  implementing automated deletion is separate work.
- Migrating or backfilling already-uploaded events with a version.

## Subtasks

1. `spec_subtask_1_release_attribution.md` — stamp the emitting release version on every event.
2. `spec_subtask_2_durable_install_id.md` — retain `install_id` across telemetry disable.
3. `spec_subtask_3_anonymous_redaction.md` — redact customer-identifying strings at `anonymous`.
4. `spec_subtask_4_privacy_documentation.md` — publish the privacy doc and correct stale paths.

## Next Path

```bash
skill-bill goal SKILL-163
```
