# SKILL-163 Subtask 2: Retain install_id across telemetry disable

## Scope

Stop telemetry disable from destroying the installation identity.

`install_id` already survives install and uninstall: it lives in `~/.config/skill-bill/config.json`
(`FileTelemetryConfigStore.kt:52-65`), `install.sh:630-651` migrates the legacy
`~/.skill-bill/config.json` to that durable path before the pre-install uninstall, and
`uninstall.sh:718-722` only removes `~/.skill-bill`. The one remaining reset path is disable, which
deletes the entire config file:

- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/telemetry/config/TelemetryConfigMutations.kt:64-71`
  — `disableTelemetry` calls `configStore.delete()`.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/install/apply/InstallApplySideEffects.kt:78-80`
  — the install-apply path duplicates the same delete when the chosen level is `off`.

Because `normalizedInstallId` (`FileTelemetryConfigStore.kt:122-124`) only mints a UUID when no
persisted id exists, deleting the file makes the next enable mint a fresh identity. Choosing "3.
off" at the install prompt (`install.sh:1786-1789`) therefore also resets it.

Decided behavior: **no code path deletes `config.json`.** Disable stops all collection, clears
queued events, and sets the persisted level to `off`, but the file — and everything in it —
survives. A re-enable reuses the retained `install_id`.

The config file is shared with non-telemetry keys — `external_addon_sources`
(`FileExternalAddonSourceConfigStore.kt:30`) and `execution_matrix`
(`ExecutionMatrixModels.kt:6`) — which the current delete destroys as collateral damage. A
telemetry-level mutation has no business removing add-on sources or the execution matrix.

The delete capability exists solely to serve these two callers. It has exactly two production call
sites (`TelemetryConfigMutations.kt:69`, `InstallApplySideEffects.kt:79`), one port declaration
(`runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/telemetry/TelemetryConfigStore.kt:17`),
and one implementation (`FileTelemetryConfigStore.kt:33`). Remove it from the port rather than
leaving an unused destructive method available to a future caller.

## Acceptance Criteria

1. Disabling telemetry leaves `config.json` on disk. The file is updated in place to record level
   `off`; it is not deleted.
2. `delete()` is removed from the `TelemetryConfigStore` port and from
   `FileTelemetryConfigStore`, so no code path can delete the config file.
3. Disabling telemetry clears telemetry settings so that no events are collected, queued, or
   uploaded while disabled.
4. Disabling telemetry clears any already-queued events from `telemetry_outbox`.
5. Disabling telemetry preserves `install_id`, `external_addon_sources`, and `execution_matrix` in
   the config file.
6. Re-enabling telemetry after a disable reuses the retained `install_id`; no new UUID is minted.
7. The install-apply path that selects level `off` follows the same rule as the CLI disable path,
   with the two paths sharing one implementation rather than duplicating the behavior.
8. Choosing `off` at the `install.sh` telemetry prompt preserves an existing config file and its
   contents.
9. A test asserts the `off -> anonymous` round trip preserves `install_id`.
10. A test asserts `external_addon_sources` and `execution_matrix` survive a disable.
11. A test asserts no events are queued while the level is `off`.

## Non-Goals

- Providing an explicit "forget me" or identity-reset command. Deleting the config file by hand
  remains available to the user and is out of scope to formalize. Note this is now the *only* way
  to reset the identity, which is a deliberate consequence of criterion 2.
- Changing the default telemetry level.
- Changing config file location or the legacy-path migration.

## Dependency Notes

Independent of subtasks 1 and 3; may run in any order relative to them.

Subtask 4 documents the retention rule this subtask establishes, so it must land before subtask 4
finalizes.

## Validation Strategy

- Unit tests over `TelemetryConfigMutations` covering disable-then-enable identity continuity and
  preservation of non-telemetry config keys.
- Confirm no production or test source still calls a config-store `delete()`; the port method is
  gone, so a leftover caller is a compile error rather than a silent regression.
- A test covering the install-apply `off` path to prove it shares the CLI behavior.
- A test asserting the outbox is cleared on disable.
- `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

Proceed to subtask 3.
