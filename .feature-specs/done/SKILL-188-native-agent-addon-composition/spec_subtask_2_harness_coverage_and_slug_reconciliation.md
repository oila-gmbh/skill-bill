# SKILL-188 · Subtask 2 — Every harness target, and a truthful slug projection

## Scope

Two halves of the same guarantee: composed add-on content must reach *every*
rendered native-agent target, and the slug list the review announces must be the
slug list the workers actually received.

Today exactly one rendered agent across a whole install carries an inlined
sidecar, and `ReviewLaunchPlanPolicy` projects add-on slugs
(`ReviewLaunchPlanPolicy.kt`, `?.addons.orEmpty().map { it.slug }`) with no link
to composed content. That lets the launch plan print `Selected add-ons: <slugs>`
while the workers hold none of it.

- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/rendering/`
  and `.../install/nativeagent/` — carry subtask 1's composition through every
  render/install target under `installed-skills/native-agents-*/`
  (claude, codex, cursor, copilot), not the Claude set alone.
- `runtime-kotlin/runtime-domain/.../review/plan/ReviewLaunchPlanPolicy.kt` —
  reconcile the `addOns` slug projection with composed content, touching only
  what that reconciliation requires.

The reconciliation direction matters: a slug is reported as selected because its
content was composed, not because the manifest named it. A declared-but-not-
composed slug is a rendering failure surfaced by subtask 1's loud-fail, never a
silently dropped line in the launch plan.

## Acceptance Criteria

1. Add-on composition covers every rendered native-agent target under
   `installed-skills/native-agents-*/` — claude, codex, cursor, and copilot —
   with no harness-specific exemption.
2. Per-harness frontmatter and body conventions are preserved; composition adds
   rubric content without breaking any harness's agent file format.
3. A rendered agent for a given area carries the same composed add-on content on
   every harness. Harness output differs only where the harness's own format
   requires it.
4. `ReviewLaunchPlanPolicy`'s `addOns` projection and the composed agent content
   cannot disagree: a slug reported as selected is a slug whose content is in the
   worker.
5. The reconciliation is enforced by a conformance check in the runtime, not by
   convention or documentation alone.
6. A slug that is declared in `addon_usage` but whose content did not compose
   surfaces as the subtask 1 loud-fail. It is never silently omitted from either
   the agent or the projection.
7. Delegated and inline review tiers receive equivalent add-on rubrics for the
   same area. The tier asymmetry described in the parent spec is gone.
8. The delegation contract is unweakened: no orchestrator is told to read a
   sibling rubric sidecar. The fix stays in what the agent contains.
9. No change to review routing, mode selection, specialist lane selection, or
   the severity/confidence vocabulary.
10. No pack slug, add-on slug, or platform name appears by name in shell or
    runtime code.

## Non-Goals

- No retrofit of already-installed agent files outside the normal
  render/install refresh.
- No new user-facing command for inspecting composed agents.
- No change to which add-ons apply to which packs or areas.
- No change to the external-source config shape or overlay behavior; the
  overlay already lands files and manifest entries correctly.
- Regression and conformance test authoring beyond the check required by
  criterion 5 belongs to subtask 3.

## Dependency Notes

Depends on subtask 1: the composition seam, the bundle model's add-on slots, and
the loud-fail vocabulary must exist before they can be driven across harnesses
or reconciled against the slug projection.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Then against a real install:

```bash
cd /home/sermilion/StudioProjects/skill-bill
./install.sh
grep -rl "Inlined Reference" ~/.skill-bill/installed-skills/native-agents-*/*/*.md | wc -l
```

Expect composed content in every declared area agent across every harness
directory, not the single file the parent spec's blast-radius probe found.

## Next Path

Subtask 3 pins this behavior with regression and conformance coverage and
corrects the documentation that still implies a `content.md` link is required.
