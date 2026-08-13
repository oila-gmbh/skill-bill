# SKILL-188 — Compose platform-pack add-on content into rendered native review agents

## Context

A platform pack declares add-ons in two manifest sections:

- `addon_usage[<skill-relative-dir>]` — which add-on slugs apply to a baseline or
  area skill, with an `entrypoint` and optional `companion_pointers`.
- `pointers[<skill-relative-dir>]` — the `name` → `target` table that makes those
  add-on markdown files resolvable.

Pack-owned add-ons are authored in `platform-packs/<slug>/addons/`. External
add-on sources (`docs/external-addons.md`) are overlaid onto the *installed* pack
at install time: `FileSystemExternalAddonOverlay` copies the `.md` files into the
installed pack's `addons/` directory and appends their `addon_usage` and
`pointers` entries into the installed `platform.yaml`. That overlay works — the
files land and the manifest entries appear.

Nothing carries that content into the rendered native agents.

`NativeAgentSidecarInlining.inlineDeclaredMarkdownSidecars` inlines a sidecar
**only when the owning `content.md` body already contains a markdown link**
matching `[label](file.md)`. The `pointers` table is consulted solely to
*resolve* a link that the body already contains
(`platformPointerSidecarResolver`, which throws when a link has no matching
pointer). `addon_usage` is never read by the rendering path at all — the string
does not appear anywhere under
`runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/nativeagent/`.

The consequence is structural, not incidental:

- An add-on can only reach a native agent by getting a markdown link into the
  pack's authored `content.md`.
- An **external** add-on can never do that. Its whole purpose is to stay out of
  the pack, and the overlay deliberately touches only `addons/` and
  `platform.yaml` — never `content.md`. Editing upstream `content.md` to link a
  private add-on would defeat the feature.
- Therefore every external add-on is inert on the delegated review path, and
  every pack-owned add-on is inert unless someone also hand-edited `content.md`.

The delegation contract closes the last escape hatch. `review-delegation.md`
states, for Claude Code, Codex, and Cursor alike: *"The installed native agent's
embedded governed rubric is authoritative. Do not tell the worker to read a
sibling rubric sidecar."* A parent orchestrator that correctly follows the
contract therefore cannot compensate — it is forbidden from naming the add-on
files, and the agent it launches does not contain them.

`ReviewLaunchPlanPolicy` does read `addonUsage`, but only to project add-on
**slugs** (`ReviewLaunchPlanPolicy.kt:120-121`,
`?.addons.orEmpty().map { it.slug }`). Names, not content. So the launch plan can
announce `Selected add-ons: capmo, offline-first` while the workers that execute
the review have never seen a byte of either.

## Diagnostic Evidence

Observed on a real `/bill-code-review mode:delegated` run against
`capmo-ios` (iOS pack, contract 1.4, add-ons `capmo` + `offline-first`
declared), 2026-08-13.

Installed pack has the add-on files:

```
$ ls ~/.skill-bill/platform-packs/ios/addons/
capmo-api-contracts.md        capmo-review.md            offline-background-reliability.md
capmo-architecture.md         capmo-security.md          offline-conflict-resolution.md
capmo-performance.md          capmo-testing.md           offline-first-review.md
capmo-persistence.md          capmo-ui.md                offline-sync-consistency.md
capmo-platform-correctness.md capmo-ux-accessibility.md
capmo-reliability.md
```

Installed `platform.yaml` declares them — 11 `slug: capmo` entries across the
baseline and all ten areas, plus `offline-first` on persistence, reliability,
and platform-correctness.

No `content.md` in the ios pack contains a markdown link to any of them:

```
$ grep -oE "\]\([a-z0-9-]+\.md\)" ~/.skill-bill/platform-packs/ios/code-review/*/content.md
(no matches)
```

The rendered agents therefore contain no add-on content:

```
$ grep -c "Inlined Reference" .../claude-agents/bill-ios-code-review-persistence.md
0
$ grep -ci capmo .../claude-agents/bill-ios-code-review-persistence.md
0
```

Blast radius across the whole install — exactly one rendered agent out of ~36
has any inlined sidecar, and it is a pack-owned `content.md` that happens to
contain a hand-written link:

```
$ grep -rl "Inlined Reference" .../claude-agents/*.md | wc -l
1
$ grep -rl "Inlined Reference" .../claude-agents/*.md
.../claude-agents/bill-kmp-code-review-ui.md
```

### Why it mattered on this review

The reviewed diff was a sync-ordering fix in `SyncEngine+ReadRequests.swift`
whose premise is `deepSyncRequired` resetting sync cursors. The add-ons that did
not load name that mechanism explicitly:

- `capmo-persistence.md`: *"Schema-affecting changes need the deep-sync story
  (`deepSyncRequired`/`cleanupForDeepSync`)."*
- `capmo-reliability.md`: *"SyncMagic (offline). … This repo is a prime instance
  of every failure mode in the generic `offline-first` add-on."*
- Both carry a **Grounding** section requiring findings to cite an existing
  Capmo precedent and forbidding recommendations that contradict established
  patterns — a rule that shapes every finding's admissibility and that no
  delegated specialist received.

Five delegated specialists (architecture, platform-correctness, persistence,
reliability, testing) ran without any of it. A single `mode:inline` lane run on
the identical delta *did* receive it, because the inline contract has the parent
name the rubric paths explicitly and the parent listed the add-on files. The two
tiers of the same shell were given materially different rubrics for the same
review — the delegated tier, which is documented as the deeper one, got less.

## Intended Outcome

Add-on content declared through `addon_usage` reaches every worker that executes
the review, on every harness, without the pack's authored `content.md` needing a
markdown link and without an orchestrator being told to read sidecars.

The `addon_usage` declaration becomes the single authority for add-on
composition. A pack-owned add-on and an external add-on compose identically —
the overlay's appended `addon_usage`/`pointers` entries are enough on their own.

`Selected add-ons: <slugs>` in review output becomes a truthful claim: a slug is
reported only when its content was actually composed into the workers.

## Acceptance Criteria

1. Native-agent rendering composes add-on content from `addon_usage` for the
   skill-relative directory being rendered. An add-on's `entrypoint` and every
   `companion_pointers` entry are included. A markdown link in the owning
   `content.md` is not required and is not the trigger.
2. External add-ons compose identically to pack-owned add-ons. An add-on
   overlaid by `FileSystemExternalAddonOverlay` into the installed pack reaches
   the rendered agents with no edit to any upstream `content.md`.
3. Composition is deterministic and idempotent: same manifest and same add-on
   files render byte-identical agent output, with a stable, documented ordering
   of baseline content, area content, and composed add-ons.
4. The existing link-triggered inlining path continues to work for genuine
   `content.md` cross-references, and a file reachable both as a declared add-on
   and as a linked sidecar is included exactly once.
5. Loud-fail parity with the rest of the shell+content contract: an
   `addon_usage` entry whose `entrypoint` or `companion_pointers` target is
   missing, unreadable, or not declared in `pointers` for the same directory
   fails rendering with a message naming the slug, the slot, and the fully
   resolved absolute path. It must not render an agent that silently omits
   declared content.
6. Rendered agents remain within their existing size and context budget. If
   composed add-ons would exceed it, rendering fails loudly naming the offending
   pack, skill directory, and byte total — it never silently truncates a rubric.
7. `ReviewLaunchPlanPolicy`'s `addOns` projection and the composed agent content
   cannot disagree. A slug reported as selected is a slug whose content is in the
   worker, and a conformance test pins that equivalence.
8. Composition covers every rendered native-agent target — all harnesses under
   `installed-skills/native-agents-*/` (claude, codex, cursor, copilot), not the
   Claude set alone.
9. A regression test reproduces this exact defect: an ios-shaped pack with
   `addon_usage` declaring an add-on, no markdown link in `content.md`, asserts
   the add-on's distinctive content is present in the rendered agent. It must
   fail against the current renderer.
10. A regression test covers the external-overlay path end to end: overlay an
    external source onto an installed pack, render, assert the content is in the
    agent and that the upstream `content.md` was not modified.
11. `docs/external-addons.md` and the native-agent composition documentation
    state that `addon_usage` alone composes content, and drop any implication
    that a `content.md` link is required.
12. The runtime check suite passes.

## Scope

- `runtime-kotlin/runtime-infra-fs/.../nativeagent/rendering/` — add-on
  composition alongside the existing sidecar inlining.
- `runtime-kotlin/runtime-infra-fs/.../nativeagent/composition/` — composition
  targets and bundle model where add-on slots must be represented.
- `runtime-kotlin/runtime-infra-fs/.../scaffold/platformpack/PlatformManifestAddonUsage.kt`
  — reuse the existing `addon_usage` model rather than re-parsing.
- `runtime-kotlin/runtime-domain/.../review/plan/ReviewLaunchPlanPolicy.kt` —
  only where the slug projection must be reconciled with composed content.
- Rendering, overlay, and conformance tests.
- `docs/external-addons.md` and native-agent composition docs.

## Constraints

- Do not require or encourage editing an upstream pack's `content.md` to
  activate an add-on. That is the workaround this feature removes.
- Do not weaken the delegation contract's prohibition on sending workers to read
  sidecar rubrics. The fix belongs in what the agent *contains*, not in what the
  orchestrator is told to hand it.
- Keep the shell platform-independent. No pack slug, add-on slug, or platform
  name may be referenced by name in shell or runtime code.
- Preserve the existing loud-fail vocabulary and error shapes
  (`MissingContentFileError` and siblings) rather than introducing a parallel
  error channel.
- Respect add-on activation conditions already declared in `addon_usage`
  (`activation.any_path`, `any_content`, `any_of_all_content`,
  `exclude_content`) where they are evaluable at render time; where they are
  inherently diff-time conditions, compose the content and let the existing
  runtime activation logic decide, but do not silently ignore a declared
  condition without a documented reason.
- No unbounded agent growth. Budgets are explicit and enforced.

## Non-Goals

- No change to add-on authoring format, `addon-manifest.yaml` schema, or the
  external-source config shape.
- No change to which add-ons apply to which packs or areas.
- No new user-facing command for inspecting composed agents (a diagnostic
  affordance may be added later if rendering failures prove hard to debug).
- No change to review routing, mode selection, specialist lane selection, or the
  severity/confidence vocabulary.
- No retrofit of already-installed agent files outside the normal
  render/install refresh.

## Subtasks

1. Compose `addon_usage` entrypoints and companion pointers into rendered native
   agents, with deterministic ordering, dedup against link-inlined sidecars, and
   loud-fail on missing or undeclared targets.
2. Extend composition to every harness render target and reconcile
   `ReviewLaunchPlanPolicy`'s slug projection with composed content.
3. Regression and conformance coverage: the no-link defect, the external-overlay
   path, idempotence, budget enforcement, and slug/content equivalence; plus the
   documentation corrections.

## Validation Strategy

```bash
cd /home/sermilion/StudioProjects/skill-bill/runtime-kotlin
./gradlew check -x sourcesJar
```

Then verify against a real install:

```bash
./install.sh
grep -c "Inlined Reference\|capmo" \
  ~/.skill-bill/installed-skills/native-agents-*/claude-agents/bill-ios-code-review-persistence.md
```

Expect the composed `capmo-persistence.md` and `offline-first-review.md` content
present in the rendered persistence agent, and the analogous add-ons present in
every other declared area agent.

## Next Path

After the fix lands, re-run the `capmo-ios` delegated review that surfaced this
(`SyncEngine+ReadRequests.swift`, permissions-first read ordering) and compare
against the archived `mode:inline` result from the same delta. The delegated
findings should now carry Capmo precedent citations — the Grounding rule the
add-ons impose — and the inline/delegated rubric asymmetry should be gone.
