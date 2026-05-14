# Subtask 04: Render Check and Install Console

Status: Complete

## Parent Context

This subtask belongs to
`docs/desktop-skill-bill-app/ui-feature-implementation-specs.md`. It should
reuse the status, generated-artifact, and console models established by earlier
subtasks.

## UI Entry Points

- Top-toolbar `Render check` button.
- Bottom dock `Install console` tab.
- Inspector `Generated artifacts` section.
- Generated artifact rows in the Changes dock when Subtask 05 exists.

## Goal

Let users run a read-only render/check workflow for the selected target and see
structured progress/output without making generated artifacts editable.

## Scope

- Run render/check for the selected governed skill or supported target.
- Stream process/runtime output into the Install console.
- Show generated output paths as read-only results.
- Show pass/fail status and duration.
- Activate the Install console tab when render/check starts.
- Keep generated artifact details synchronized with inspector rows.
- If a dry-run or preview render mode exists, prefer it for this button.
- Do not write generated output unless the runtime render operation already owns
  that write behavior.

## Runtime and Service Requirements

- Use shared runtime rendering behavior equivalent to `skill-bill render`.
- If rendering currently exists only as CLI behavior, wrap it behind a
  `RenderGateway`/`ProcessGateway` and keep payload semantics intact.
- Do not parse generated wrappers to decide render status.
- Generated artifact classification must reuse shared generated-artifact
  discovery where possible.

## Acceptance Criteria

- `Render check` is disabled when no renderable target is selected.
- Running render check opens or activates the Install console tab.
- Console output shows ordered runtime lines and final status.
- Generated outputs are listed as read-only artifacts.
- Runtime failures show exact error text and leave source drafts intact.
- Git status refreshes after render when Subtask 05 exists.

## Validation

```bash
cd runtime-kotlin
./gradlew --no-configuration-cache :runtime-desktop:core:data:jvmTest :runtime-desktop:feature:skillbill:jvmTest
```

Manual smoke:

1. Open a temporary repo copy.
2. Select a renderable governed skill.
3. Run Render check.
4. Confirm console lines appear in order.
5. Confirm generated outputs remain read-only.

## Non-Goals

- Editing generated output.
- New renderer semantics.
- Packaging/install distribution.
- Git commit or push.
