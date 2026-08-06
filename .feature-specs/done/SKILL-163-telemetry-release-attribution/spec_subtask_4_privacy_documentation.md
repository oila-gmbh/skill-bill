# SKILL-163 Subtask 4: Privacy documentation and stale path correction

## Scope

Publish the privacy documentation that an opt-out telemetry default through a hosted relay
requires, and correct documentation that no longer matches the code.

Current state: no privacy document, no data-protection text, and no mention of PII anywhere in
`docs/` or `README.md`. The only description of collected data is the per-level table at
`docs/review-telemetry.md:153-157`, which subtask 3 changes. `README.md` says only that skill-bill
ships "structured telemetry through a pluggable proxy you can self-host" (line 60), with no
statement of what is collected or how to opt out.

Facts the document must state accurately:

- Default level is `anonymous`, i.e. collection is on unless disabled
  (`TelemetryConfigRules.kt:11`, `install.sh:68`, `DefaultTelemetrySettingsProvider.kt:104`).
- Events relay by default to `https://skill-bill-telemetry-proxy.skillbill.workers.dev`
  (`TelemetryConstants.kt:11`).
- The correlation key is `install_id`, a random UUID stored in `~/.config/skill-bill/config.json`;
  after subtask 2 it persists across enable/disable cycles and across reinstalls. No hardware or
  device identifier is collected.
- Opt out with `skill-bill telemetry disable`, or choose `off` at the install prompt, or set
  `SKILL_BILL_TELEMETRY_LEVEL=off`.
- The relay is self-hostable; `proxy_url` is configurable and the worker source is in the repo.

Stale documentation to correct:

- `docs/review-telemetry.md:768` names `~/.skill-bill/config.json`. The durable path is
  `~/.config/skill-bill/config.json`; the legacy path is migrated away at `install.sh:630-651`.
  Check the whole file for other instances of the legacy path.
- `docs/review-telemetry.md:776` and `:814` describe disable as removing local telemetry config.
  After subtask 2 that is no longer accurate about `install_id`.
- The per-level table at `docs/review-telemetry.md:153-157` must match what subtask 3 ships.

## Acceptance Criteria

1. A privacy document exists stating, per telemetry level (`off`, `anonymous`, `full`), which
   fields are collected.
2. The document states where events are sent by default, that the relay is self-hostable, and how
   to point at a different relay.
3. The document states every supported opt-out mechanism.
4. The document states what identifier correlates events, that it persists across reinstalls and
   enable/disable cycles, and that no hardware or device identifier is collected.
5. The document states the data retention position.
6. `README.md` links to the privacy document from its telemetry mention.
7. `docs/review-telemetry.md` names `~/.config/skill-bill/config.json` in every place it currently
   names the legacy `~/.skill-bill/config.json` path.
8. The `docs/review-telemetry.md` per-level collection table matches the redaction behavior shipped
   in subtask 3, including the `skill_bill_version` field added in subtask 1.
9. The disable description in `docs/review-telemetry.md` states that `install_id` is retained.
10. `skill-bill validate` and `npx --yes agnix --strict .` pass.

## Non-Goals

- Legal review or sign-off. The document states what the software does; whether that satisfies a
  given jurisdiction is a separate determination.
- Writing a terms-of-service or a data-processing agreement.
- Implementing automated retention enforcement in the Cloudflare worker.
- Changing the telemetry default to opt-in.

## Dependency Notes

Depends on subtasks 1, 2, and 3. This document describes their shipped behavior:

- the `skill_bill_version` field from subtask 1
- the `install_id` retention rule from subtask 2
- the per-level collected-field table from subtask 3

Writing it before those land would document intent rather than behavior.

## Validation Strategy

- Cross-check every claimed field against the payload builders as shipped, not against this spec.
- Grep the docs tree for remaining `~/.skill-bill/config.json` occurrences.
- `skill-bill validate`
- `npx --yes agnix --strict .`

## Next Path

Feature complete. Reconcile `spec.md` to its final state.
