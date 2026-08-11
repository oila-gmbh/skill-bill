# SKILL-183 · Subtask 2 — Surface the current phase's recorded model in the IntelliJ plugin status popup

## Scope

Subtask 1 puts an optional `current_model` object on the IDE status wire. This subtask carries it
through the plugin's existing read path to one line in the details popup.

The plugin path is: `CliSkillBillStatusRepository` shells out to the CLI →
`IdeStatusJsonMapper.map` turns stdout into a `SkillBillStatusOutcome` →
`StatusUiMapper` builds `SkillBillStatusUiState` → `StatusDetailsPopupContent` renders it.

**Mapper.** `IdeStatusJsonMapper` already has the degradation rule this field must follow:
`parsePlanning()` states that optional context is "never a reason to lose a whole status
reading", and any missing, mistyped, or out-of-range field degrades the block to null while the
surrounding outcome still maps. Parse `current_model` the same way — a non-object value, a
missing or blank `model`, or a non-string `model` yields null, not a malformed-output outcome. A
present-but-blank `effort` degrades to null effort while keeping the model. Reuse
`getAsJsonObjectOrNull` and `getAsString`; the file's private helpers already cover every access
this needs.

Run the parsed value through `safeSummary`-equivalent redaction only if a model id could ever
carry a path — it cannot, so plain trimming plus a bounded `take` is sufficient. Do not widen
`AbsolutePathGuard` usage for it.

**Domain.** The outcomes that can carry a current step are `Active`, `Paused`, `Stale`,
`Blocked`, and `Failed` (`SkillBillStatusOutcome.kt`). The model is current-phase context, so it
belongs on the same outcomes that already carry `currentStepId` / `currentStepLabel`. Add it as
an optional field with a null default so no existing construction site or test fixture breaks.
`Idle` and `Done` have no current phase and must not carry it.

**Presentation and UI.** `StatusUiMapper` passes the value into `SkillBillStatusUiState`;
`StatusDetailsPopupContent` renders exactly one additional row labelled `Model:` positioned with
the other current-phase context, and renders nothing at all — no label, no placeholder, no
em dash — when the value is null. When an effort is present, render it inline with the model in
a single row rather than adding a second row.

Leave the status-bar widget text, icon, and tooltip untouched. Leave
`GoalControlsPresentation`, refresh cadence, and the preference cache untouched.

`PluginArchitectureTest` enforces the plugin's layering; the new field must not introduce a
dependency that violates it.

## Acceptance Criteria

1. `IdeStatusJsonMapper` maps a payload whose `current_model` carries a non-blank `model` into an
   outcome carrying that model, and carries the `effort` when present.
2. A payload whose `current_model` is absent, not an object, or carries a missing, blank, or
   non-string `model` maps to a null model while the surrounding status outcome still maps
   normally — never to `Unavailable` or `Incompatible`.
3. A `current_model` with a valid `model` and a blank or mistyped `effort` maps to that model with
   a null effort.
4. The model is carried on the outcomes that already carry a current step (`Active`, `Paused`,
   `Stale`, `Blocked`, `Failed`) and is absent from `Idle` and `Done`.
5. `StatusDetailsPopupContent` renders exactly one `Model:` row when a model is present, and no
   row, label, or placeholder when it is null.
6. When an effort is present it appears in that same single row alongside the model.
7. The status-bar widget text, icon, and tooltip are byte-identical to their pre-change output for
   the same status.
8. `PluginArchitectureTest` passes unchanged, and the `intellij-plugin` test task passes.

## Non-Goals

- A per-phase model table, or any model for a phase other than the current one.
- Any status-bar widget or tooltip change.
- New refresh, caching, or preference behaviour for the model value.
- Any runtime-side change; subtask 1 owns the producer and the contract.
- Localization or theming work beyond matching the popup's existing row style.

## Dependencies

Depends on subtask 1: the `current_model` field, its schema declaration, and its golden fixtures
must exist before this subtask can parse or test against them.

## Validation Strategy

- Extend `ProcessRunnerAndMapperTest`, which already builds raw JSON payload strings and asserts
  mapper outcomes, with the degradation cases: absent key, non-object value, blank `model`,
  non-string `model`, blank `effort`. Each must keep the surrounding outcome intact — that is the
  nameable regression, since a strict parse here would turn an optional field into a lost status
  reading.
- Extend the popup fixture test with a present case and an absent case, asserting the `Model:`
  row appears exactly once and is wholly absent respectively.
- Assert widget text for an unchanged status to prove no collateral drift into the bar.
- Run the `intellij-plugin` test task.

## Next Path

Feature complete: the runtime records what each phase launched with and the plugin shows it for
the phase currently running.
