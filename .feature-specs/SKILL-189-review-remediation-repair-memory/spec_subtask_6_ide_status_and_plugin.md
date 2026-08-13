# SKILL-189 · Subtask 6 — IDE status pause reason and IntelliJ plugin surfacing

## Scope

Make the new escalation and churn pauses actionable from the IDE.

The churn pause and the `plan_fix` escalation both stop a run pending an
operator decision. The IDE status bar is where a user notices a stopped goal,
but the IDE status contract carries no pause reason: `familySummary` renders a
paused goal as `"<family> <key> is paused at <step label>."` and nothing else.
A user would see "feature-goal SKILL-189 is paused at Phase 5: Code Review" with
no indication that the run is waiting on them, let alone why. A pause nobody
notices is functionally a hang.

- `runtime-kotlin/runtime-application`: an optional sanitized pause-reason
  field on the IDE status projection for paused lifecycles.
- `orchestration/contracts/ide-status-schema.yaml`: the new optional property.
  The schema is `additionalProperties: false`, so an undeclared field is
  rejected at the producer's own gate.
- `intellij-plugin`: mapper parsing, the `Paused` outcome field, and popup and
  tooltip rendering.

The plugin is an external consumer of a versioned contract and imports no
runtime code, so the two halves must be independently correct.

## Acceptance Criteria

1. The IDE status projection carries an optional, bounded, sanitized pause
   reason for paused lifecycles, distinguishing at minimum a pause awaiting an
   operator decision from other pause causes.
2. `IDE_STATUS_CONTRACT_VERSION` stays `"0.1"`. The field is purely additive
   and optional. The plugin compares the wire contract version by exact string
   equality and maps any mismatch to `Incompatible`, so a bump would blank the
   widget on every already-installed plugin build until each user upgrades.
   Any change that requires a bump is out of scope for this subtask.
3. `ide-status-schema.yaml` declares the new optional property so the producer
   gate accepts it under `additionalProperties: false`, and the schema keeps
   its existing const contract version.
4. The reason is payload-free and sanitized in the same terms as the goal-facing
   pause reason: a reason code plus a bounded label, with no diff hunks, paths,
   line numbers, construct bodies, or raw review output.
5. A paused-awaiting-decision reason is distinguishable in the wire data from a
   limit pause, an operator-requested pause, and a self-paused run, so the UI
   can tell "waiting for you" from "waiting for capacity".
6. The plugin mapper parses the field with an explicit length bound and
   degrades it to null on an over-length, non-string, or malformed value,
   matching the existing optional-block degradation rules. A bad reason never
   invalidates the surrounding outcome.
7. A plugin build that predates this field ignores it: an unknown wire key
   produces the same outcome it does today, with no `Incompatible` state and no
   parse failure. Asserted by a mapper test carrying an unrecognized key.
8. The `Paused` outcome carries the reason, and the details popup and tooltip
   render it. The status bar's 48-char budget is respected: the reason occupies
   popup and tooltip surfaces, and the existing ascending-value drop rule
   governs any bar segment.
9. When the reason indicates an awaited operator decision, the popup names the
   CLI command that resolves it as display text only. The plugin gains no new
   mutating verb; it continues to shell out to `goal stop` and `goal pause`
   alone.
10. `plan_fix` renders correctly through the existing generic path:
    `phaseDisplayName` splits on `_` and title-cases, and `executionWording`
    renders a `semantic_loop` kind as `"Plan Fix loop N"`. Covered by a
    presentation test. No phase-id table is introduced in the plugin.
11. The plugin never displays an inflated remediation loop number: because
    `plan_fix` and `implement_fix` launch within one remediation round, the
    `current_phase_execution` count for the `review_fix` loop still counts
    rounds. Asserted against a two-phase round fixture.
12. Plugin architecture tests still pass unchanged: no presentation to
    infrastructure shortcut, no runtime, JDBC, or SQLite import, and no
    `StatusBarWidget` API outside `ui/`.
13. Both check suites pass: the runtime suite for the producer and schema, and
    the plugin suite for the consumer.

## Non-Goals

- No IDE status contract version bump.
- No new mutating CLI verb in the plugin, and no operator-decision action
  button or control in the widget.
- No tool window, notification, or popup redesign.
- No plugin-side phase-id vocabulary, label table, or phase ordering knowledge.
- No surfacing of the repair ledger, findings, or remediation diffs in the IDE.

## Dependency Notes

Depends on subtask 3 for the `plan_fix` phase id that reaches the wire, and on
subtask 4 for the pause it reports. Independent of subtask 5; either may land
first.

## Validation Strategy

Producer and schema:

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Consumer:

```bash
cd /home/sermilion/StudioProjects/skill-bill/intellij-plugin
./gradlew check --no-build-cache
```

The plugin module has its own Gradle build and is not part of the runtime
build. `--no-build-cache` is deliberate: a cached `compileTestKotlin` entry has
previously served stale test classes here, surfacing as a `NoSuchMethodError`
against an older constructor arity that `clean` alone does not clear.

Focused: `IdeStatusJsonMapper` parsing and degradation tests, the presentation
tests covering `executionWording` and `phaseDisplayName`, the status-bar budget
tests, and the architecture scans.

## Next Path

Run a goal to a churn pause with the plugin installed and confirm the widget
reports that the run is waiting on an operator decision, that the popup names
the resolving command, and that no path, line number, or source body appears on
any IDE surface.
