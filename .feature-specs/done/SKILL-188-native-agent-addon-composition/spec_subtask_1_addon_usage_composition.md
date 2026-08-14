# SKILL-188 · Subtask 1 — Compose `addon_usage` content into rendered native agents

## Scope

Make `addon_usage` the trigger for add-on content reaching a rendered native
agent, replacing the current requirement that the owning `content.md` contain a
markdown link.

Today `NativeAgentSidecarInlining.inlineDeclaredMarkdownSidecars` inlines a
sidecar only when the body already carries a `[label](file.md)` link, and the
`pointers` table is consulted solely to resolve such a link. `addon_usage` is
never read by the rendering path. This subtask introduces add-on composition as
a first-class render input alongside the existing link-triggered inlining.

- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/composition/`
  — represent composed add-on slots in the bundle model
  (`NativeAgentBundle`, `NativeAgentSource`, `NativeAgentComposition`).
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/rendering/`
  — resolve and compose declared add-ons in `NativeAgentRendering`, keeping
  `NativeAgentSidecarInlining` responsible for genuine `content.md`
  cross-references.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/scaffold/platformpack/PlatformManifestAddonUsage.kt`
  — reuse the existing parsed `addon_usage` model; do not re-parse the manifest.

Composition keys off the skill-relative directory being rendered, so the
installed pack's manifest is the only authority. An add-on that arrives by
external overlay is indistinguishable from a pack-owned one at this seam, which
is what makes acceptance criterion 2 of the parent spec fall out for free.

## Acceptance Criteria

1. Native-agent rendering composes add-on content from `addon_usage` for the
   skill-relative directory being rendered. The add-on's `entrypoint` and every
   `companion_pointers` entry are included.
2. A markdown link in the owning `content.md` is neither required nor the
   trigger. Rendering an area whose `content.md` contains no add-on link still
   produces an agent carrying the declared add-on content.
3. Composition resolves every target through the same directory's `pointers`
   table. A target not declared in `pointers` for that directory is an error,
   not a filesystem guess.
4. Ordering is stable and documented: baseline content, then area content, then
   composed add-ons in declared `addon_usage` order, with each add-on's
   `entrypoint` before its `companion_pointers` in declared order.
5. Composition is idempotent: the same manifest and the same add-on files
   produce byte-identical rendered output across repeated renders.
6. A file reachable both as a declared add-on target and as a link-inlined
   sidecar is included exactly once, and the dedup outcome does not depend on
   which path resolved it first.
7. The existing link-triggered inlining path continues to work unchanged for
   genuine `content.md` cross-references.
8. Loud-fail parity: an `addon_usage` entry whose `entrypoint` or
   `companion_pointers` target is missing, unreadable, or undeclared in
   `pointers` fails rendering with a message naming the add-on slug, the slot
   (`entrypoint` or the specific companion pointer name), and the fully
   resolved absolute path. Reuse `MissingContentFileError` and its siblings
   rather than introducing a parallel error channel.
9. Rendering never emits an agent that silently omits declared add-on content.
10. Rendered agents stay within their existing size and context budget. When
    composed add-ons would exceed it, rendering fails loudly naming the pack,
    the skill directory, and the byte total. It never truncates a rubric.
11. Declared activation conditions (`activation.any_path`, `any_content`,
    `any_of_all_content`, `exclude_content`) that are evaluable at render time
    are respected. Conditions that are inherently diff-time compose the content
    and defer to the existing runtime activation logic; no declared condition is
    ignored without a documented reason.
12. No pack slug, add-on slug, or platform name appears by name in shell or
    runtime code.

## Non-Goals

- No extension beyond the render target(s) this seam already drives; full
  per-harness coverage is subtask 2.
- No change to `ReviewLaunchPlanPolicy`'s slug projection.
- No change to add-on authoring format, `addon-manifest.yaml`, or the
  external-source config shape.
- No documentation rewrite; that lands with subtask 3.
- No edit to any upstream pack `content.md` to activate an add-on.

## Dependency Notes

None. This subtask mints the composition seam that subtasks 2 and 3 extend and
pin.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Focused: native-agent rendering and composition suites, plus the platform-pack
manifest loading path that supplies `addon_usage`.

## Next Path

Subtask 2 extends this composition to every harness render target and reconciles
the launch-plan slug projection with what was actually composed.
