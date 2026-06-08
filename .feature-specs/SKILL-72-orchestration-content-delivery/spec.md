# SKILL-72 - Coherent orchestration-content delivery for installed skills

Created: 2026-06-08
Status: Complete
Issue key: SKILL-72
Parent: follow-up to the install/render line (SKILL-11 telemetry-contract
sidecar, SKILL-40 install staging). Makes how an installed skill reaches repo
`orchestration/` content coherent and install-detachment-safe.

## Problem

Installed skills live in a content-addressed cache
(`~/.skill-bill/installed-skills/<skill>-<hash>/`) that is **detached** from the
source repo. Today skills reach repo `orchestration/` content through three
inconsistent mechanisms, two of which dangle from the cache location:

**Mechanism (1) — generated support-pointer sidecars.** Every governed skill
ships sidecars (`shell-ceremony.md`, `telemetry-contract.md`,
`shell-content-contract.md`, `review-scope.md`, `review-orchestrator.md`,
`review-delegation.md`, `stack-routing.md`, `specialist-contract.md`, plus
platform-pack addon implementation sidecars). `writeRenderedSupportPointerFiles`
wrote each sidecar's *content* as a path relative to the **source** skill dir
(`<repo>/skills/<skill>` → `../../orchestration/...`, or `../../../../orchestration/...`
for the deeper platform-pack specialist skills). Written into the detached cache,
those relative paths resolve to `~/.skill-bill/orchestration/...` (or higher),
which does not exist. **All ~30 installed skills currently carry dangling
sidecars.** This is the concrete failure observed: a `bill-feature-verify` run
reported "the ceremony/telemetry pointer files are dangling (no orchestration/
dir on disk)" and silently degraded — losing the shell-ceremony project-overrides
pass (`.agents/skill-overrides.md` action mandates) and the telemetry-contract
playbook.

**Mechanism (2) — inline prose `orchestration/...` references in skill bodies.**
Three references in skill `content.md` bodies name a repo-relative orchestration
path that dangles the same way if an agent tries to open it:

- `skills/bill-feature-spec/content.md` → `orchestration/contracts/decomposition-manifest-schema.yaml`
- `skills/bill-feature-task-prose/content.md` → `orchestration/workflow-contract/PLAYBOOK.md` (a "see also")
- `skills/bill-feature-task-prose/content.md` → `orchestration/contracts/decomposition-manifest-schema.yaml`

These are **informational/provenance**, not load-bearing for the agent: the
decomposition-manifest schema is validated by the **runtime**, not by the agent
reading the file (see Key Finding), and the workflow-contract playbook has no
runtime consumer. But a bare `orchestration/...` path in the body still invites
the same dangling-read confusion that degraded `feature verify`.

**Mechanism (3) — the orchestration symlink.** `applyOrchestrationLinks`
(`InstallApplyOrchestrationLinks.kt`) creates `<agentTarget.parent>/orchestration`
(e.g. `~/.claude/orchestration` → `<repo>/orchestration`) at apply time. It was
the implicit backing that made the relative pointers and inline refs *appear* to
work when an agent resolved a path lexically through the agent symlink directory.
It is unreachable from the agent's real working directory and is near-vestigial
once mechanism (1) is self-contained — but it perpetuates the illusion that
repo-relative orchestration paths resolve post-install.

There is no single, stated rule for how an installed skill is supposed to reach
orchestration content, so each new reference re-invents a fragile path.

## Key Finding (grounds the design)

The codebase already contains the correct pattern for install-detached
orchestration content the **runtime** needs, and it is not the symlink.

- **Runtime contracts are bundled, multi-source resolved.**
  `runtime-contracts` exposes `DecompositionManifestSchemaPaths` (and siblings:
  install-plan, workflow-state, goal-progress, etc.) with a three-source
  resolution order: `REPO_RELATIVE_PATH` (`orchestration/contracts/...yaml`, dev)
  → `CLASSPATH_RESOURCE` (`skillbill/contracts/...yaml`, bundled into the runtime
  distribution) → `EXPECTED_SCHEMA_ID` (remote URL). The schema is copied into the
  runtime jar by the `runtime-contracts` Gradle copy task, so the runtime validates
  the decomposition manifest with **no dependency on the repo or the symlink**.
- **So mechanism (2)'s schema references are already runtime-safe.** The agent is
  never required to open them; they are provenance. The workflow-contract playbook
  is a pure documentation pointer with no consumer at all.
- **Mechanism (1)'s content is agent-read** (ceremony, telemetry, review
  contracts the orchestrating agent must follow), so its correct home is
  self-contained inside the install cache — inlined — not a path back to the repo.

This yields one coherent principle (the design this spec encodes):

> Orchestration content an installed skill's **agent** must read is made
> self-contained in the install cache (inlined at render time). Orchestration
> content the **runtime** needs is bundled as a classpath resource (the
> `*SchemaPaths` pattern). The orchestration symlink must not be load-bearing —
> and is removed so nothing can come to depend on it.

## Current State

A first-cut fix for mechanism (1) is already in the working tree (uncommitted),
and this spec formalizes and completes it:

- `InstallSupportPointerRendering.kt` — `writeRenderedSupportPointerFiles` now
  writes the **inlined canonical content** of the target doc
  (`normalizeMarkdownLineEndings(readString(target))`) instead of a repo-relative
  path.
- `InstallStaging.kt` — `computeInstallContentHash` folds the target doc's
  **bytes** into the cache key (was the relative path string), so editing an
  orchestration doc invalidates the cache and re-inlines on next install.
- `InstallStagingTest.kt` — asserts the staged sidecar inlines content and carries
  no `../` path.

These pass `:runtime-infra-fs:test`, `detekt`, and `spotlessKotlinCheck`. The
two orchestration docs referenced via sidecars contain no internal markdown
links, so no recursive inlining is required; the existing
`validatePointerInputs` existence check still fires before the new hash read.

## Goals

1. **Formalize mechanism (1):** support-pointer sidecars are inlined,
   self-contained content in the install cache for **every** governed skill and
   sidecar (including the deeper platform-pack specialist skills), and the cache
   key reflects the inlined content so doc edits invalidate it.
2. **Make mechanism (2) robust without new plumbing:** reword the three inline
   `orchestration/...` references so none presents a dangling, agent-openable path
   — the two schema references read as runtime-validated contracts (which they
   are), and the workflow-contract "see also" reads as clearly-marked repo-dev
   provenance. No literal `orchestration/...` path survives in any skill body.
3. **Remove mechanism (3):** delete the orchestration-symlink apply behavior and
   its model/CLI/cleanup/test surface, so no skill or agent can come to depend on
   a repo-relative orchestration path resolving post-install.
4. **Encode the principle as a guard:** add an author-time repo-validation rule
   (and test) that fails if any skill `content.md` body contains a bare
   `orchestration/...` path token, preventing regression to a fragile reference.

## Non-Goals

- Do **not** build skill-class/pointer plumbing to deliver the workflow-contract
  playbook as a sidecar. `bill-feature-task-prose` and `bill-feature-spec` match
  no pointer-bearing skill class (the `feature-implement` matcher
  `^bill-[a-z0-9-]*feature-task$` deliberately excludes `-prose`; `-spec` matches
  nothing), so promoting it would require new governance surface and would also
  leak the pointer onto the `bill-feature-task` router and `-runtime`.
  Disproportionate for one "see also."
- Do **not** change the `*SchemaPaths` runtime resolver, the bundled-resource
  copy task, or the decomposition-manifest schema/contract.
- Do **not** change which skills get which sidecars, the skill-class matchers, or
  the sidecar set.
- Do **not** rename the "support pointer" concept/identifiers across the codebase
  (the files now carry inlined content; the established naming stays to keep the
  change contained).
- Do **not** inline runtime-only contract YAML into skill bodies (the runtime
  already resolves those; the agent does not read them).

## Target User Experience

- After install, `cat ~/.skill-bill/installed-skills/bill-feature-verify-*/shell-ceremony.md`
  shows the full ceremony doc, not `../../orchestration/...`; the same holds for
  every sidecar of every installed skill, including the deeply-nested
  platform-pack specialists.
- A `bill-feature-verify` (or any governed) run reads its ceremony and telemetry
  contracts directly from the self-contained sidecars and performs the
  `.agents/skill-overrides.md` project-overrides pass — no "dangling pointer"
  degradation.
- An agent running `bill-feature-task-prose` / `bill-feature-spec` never sees a
  bare `orchestration/...` path it might try to open and fail; the manifest is
  validated by the runtime as before.
- `skill-bill install` no longer creates an `orchestration` symlink beside the
  agent skill targets; nothing functional regresses from its absence.
- `skill-bill validate` fails loudly if a future edit reintroduces a bare
  `orchestration/...` path into a skill body.

## Acceptance Criteria

1. For every governed skill and every declared support sidecar, the installed
   sidecar file contains the **inlined canonical content** of its orchestration
   target (line-ending normalized), and contains no `../` relative path —
   verified across both top-level skills (`../../` depth) and platform-pack
   specialist skills (`../../../../` depth).
2. `computeInstallContentHash` incorporates each support sidecar target's
   **content**, so editing an orchestration doc changes the cache key and forces a
   re-inline on the next install; an unchanged repo reuses the cache (hash stable).
   The pre-existing missing-target loud-fail still fires before any content read.
3. None of the three inline `orchestration/...` references remains in
   `skills/bill-feature-spec/content.md` or `skills/bill-feature-task-prose/content.md`:
   the two decomposition-manifest-schema references read as runtime-validated
   contract behavior (no agent file-open implied), and the workflow-contract
   reference reads as clearly-marked repo-dev provenance — with no literal
   `orchestration/...` path token in either body.
4. `applyOrchestrationLinks` and its surface are removed: no orchestration symlink
   is created on install; the `OrchestrationLink*` model types, the
   `result.orchestrationLinks` field, the `ORCHESTRATION_LINK_FAILED` issue kind,
   the cleanup handling in `InstallApplyCleanup`, the CLI payload mapping, and
   `InstallApplyOrchestrationLinksTest` are deleted, with no dangling references
   and the install apply flow otherwise unchanged.
5. A repo-validation rule (surfaced by `skill-bill validate`) fails when any
   `skills/*/content.md` body contains a bare `orchestration/...` path token, with
   a clear message naming the file and the offending reference; the rule passes on
   the reworded tree.
6. Regression protection: a test asserts inlined sidecar content + absence of a
   relative path (mechanism 1); a test covers the content-keyed hash invalidation
   (mechanism 2 of the hash); the symlink-removal is reflected by deleting the
   obsolete test rather than leaving it asserting removed behavior; a test covers
   the new skill-body `orchestration/...` guard (positive and negative).
7. Maintainer validation passes: `skill-bill validate`,
   `npx --yes agnix --strict .`, `scripts/validate_agent_configs`, and
   `(cd runtime-kotlin && ./gradlew check)` (runtime files are touched).
8. After a local reinstall (`SKILL_BILL_SKIP_PREINSTALL_UNINSTALL=1 ./install.sh
   --reuse-last-selection`, preserving the DB), the installed sidecars across
   skills contain inlined content and no `orchestration` symlink is present beside
   the agent targets.

## Design Notes

- **Single chokepoint for mechanism (1).** All sidecars route through
  `writeRenderedSupportPointerFiles`; inlining there fixes every skill and every
  nesting depth at once. Depth is exactly why relative paths were doomed — a php
  specialist's `../../../../orchestration` points four levels above the cache root.
- **Hash correctness.** Fold target **bytes** (not the path) into
  `computeInstallContentHash` so an orchestration-doc edit re-inlines; keep the
  existence validation ahead of the read so the missing-target test still fails in
  validation, not in the hash.
- **Mechanism (2) rewording.** Keep the engineering meaning, drop the path:
  e.g. "the runtime validates the manifest against the decomposition-manifest
  contract" instead of "validate the manifest against
  `orchestration/contracts/decomposition-manifest-schema.yaml`"; and for the
  playbook, frame it as repo-only background (no path an installed agent would
  open). The schema's canonical location stays discoverable to maintainers via
  `DecompositionManifestSchemaPaths`, which is the source of truth.
- **Symlink removal blast radius (verified):**
  `InstallApplyOrchestrationLinks.kt` (delete),
  `InstallApply.kt` (drop the call + result wiring),
  `InstallApplyCleanup.kt` (drop the orchestration-link cleanup branch),
  `runtime-domain/.../install/model/InstallModels.kt` (`OrchestrationLinkOutcome`,
  `OrchestrationLinkStatus`, `ORCHESTRATION_LINK_FAILED`, the result field),
  `InstallCliApplyPayloads.kt` (drop payload mapping/output),
  `InstallApplyOrchestrationLinksTest.kt` (delete). Sweep README/docs for any
  "orchestration symlink/link" mention.
- **Guard placement.** Add the skill-body `orchestration/...` rule to
  `RepoValidationRuntime` alongside the existing governed-content checks (it
  already scans `skills`), with a focused unit test. The rule is enforceable
  precisely because, after Goal 2, zero legitimate `orchestration/...` tokens
  remain in any skill body.
- **Native-agent parity.** The native-agent composition path
  (`inlineDeclaredMarkdownSidecars`) already inlines sidecars into the single-file
  output; this spec only changes the multi-file install-cache path, so native
  agents are unaffected and remain consistent.

## Validation Strategy

- Unit/build: `(cd runtime-kotlin && ./gradlew check)` — covers the install
  staging tests (inline content + no `../`), the content-keyed hash test, the new
  repo-validation guard test, and confirms the symlink-removal leaves no dangling
  references; `detekt` + `spotless` clean.
- Author-level: `skill-bill validate`, `npx --yes agnix --strict .`,
  `scripts/validate_agent_configs` confirm the reworded governed content is valid,
  installs for all agents, and trips the new guard on a deliberately-reintroduced
  `orchestration/...` token.
- Behavioral: a local reinstall preserving the DB, then inspect a top-level and a
  platform-pack-specialist installed skill to confirm inlined sidecars and the
  absence of the orchestration symlink; run `bill-feature-verify` and confirm no
  dangling-pointer degradation.

## Open Questions (Resolved)

- **Symlink: keep vs remove → Resolved: remove entirely.** After mechanism (1) is
  self-contained, the symlink only backs informational prose refs (themselves
  reworded away). Removing it eliminates misleading infrastructure and enforces
  the "not load-bearing" principle, accepting the larger but well-scoped blast
  radius enumerated above.
- **Mechanism (2): sidecar vs reword → Resolved: reword all three, no new
  plumbing.** The carrier skills are not sidecar-capable, the schema is
  runtime-validated, and the playbook is a "see also"; rewording closes the
  dangling-path failure mode without new governance surface or content
  duplication/drift.
- **Mode: single_spec vs decomposed → Resolved: single_spec.** One coherent
  implementation pass; the four goals are interdependent edits to the same
  install/validation surface, not independently-resumable milestones.

Run bill-feature-task on .feature-specs/SKILL-72-orchestration-content-delivery/spec.md
