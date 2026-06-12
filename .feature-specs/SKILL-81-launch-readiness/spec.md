# SKILL-81 — Launch readiness (public / Reddit-ready)

## Context

A four-dimension readiness audit (first-impression, onboarding, rough-edges,
differentiation) of skill-bill ahead of a public "I built this" launch post found a
consistent shape: **the engine is ready, the wrapper is not.** The product is real and
genuinely differentiated — durable SQLite-backed resume across agent crashes / the
$100 `claude -p` context ceiling, one skill source synced across five agents, a
self-auditing telemetry loop, 219 Kotlin test classes. None of that is in question.

What is not ready is the surface a stranger judges in the first 60 seconds, and the
first-run path a cold reader exercises after clicking the post. Three of the gaps are
launch *blockers* — left unfixed they make the post backfire rather than land.

### Evidence summary (readiness audit, 2026-06-12)

- **Onboarding has a silent first-run footgun.** `install.sh` writes launchers to
  `${SKILL_BILL_BIN_DIR:-$HOME/.local/bin}` (`install.sh:16`) and never checks whether
  that directory is on `PATH`. A reader who copy-pastes the README quickstart and runs
  `skill-bill version` gets `command not found` whenever `~/.local/bin` is not already
  on PATH. The completion banner (`install.sh:2224`–`2271`) prints the launcher path and
  "Edit skills in …" but no PATH guidance and no "now run `/bill-feature-task` in your
  agent" next step. Secondary: `detected` agent-selection mode can complete with **zero
  agents linked** and no warning, so skills install nowhere and the user only finds out
  when a slash command fails. At launch scale this becomes the top comment on the thread.
- **The demo is a placeholder that advertises incompleteness.** `README.md:7`–`9`
  embeds `docs/assets/skill-bill-demo-placeholder.svg` with a disclaimer that "the
  recorded binary is intentionally missing for now." A storyboard
  (`docs/assets/skill-bill-demo-storyboard.md`) and capture instructions
  (`docs/assets/skill-bill-demo-capture-instructions.md`) exist, but no real asset has
  been recorded. A missing demo on Reddit/HN reads as "not finished," regardless of how
  finished the code is.
- **The value prop is buried under jargon.** The README's actual one-liner
  (`README.md:5`, "One source of truth … durable workflow state to keep them from
  rotting") is strong, but the framing copy below pivots to "the runtime, governance,
  and operations layer for AI-agent skills" before a stranger knows what the tool *does*
  or *who it is for*. There is no explicit audience frame, no one-paragraph "vs. raw
  Claude Code skills" differentiator, and no status/license/release badges.
- **Launch-hygiene docs are absent or ambiguous.** No `CONTRIBUTING.md` exists, so an
  MIT, actively-developed project reads as closed/solo and external pack authors have no
  entry point. The prose-vs-runtime mode story is documented ambiguously enough that a
  careful reader (and the audit itself) misread the default-prose mode as "deprecated
  legacy," when prose is the default and runtime is opt-in.

## Intended outcome

skill-bill can be posted publicly without the post backfiring on first-run friction or a
missing demo, and a cold reader can tell within 60 seconds what it is, who it is for, and
why it is different. After this feature:

- A first-time installer is never silently left with launchers off PATH or skills linked
  to nothing; the completion banner tells them exactly what to do next.
- The README leads with a plain-language hook and an explicit audience frame; the
  architecture jargon is demoted below the fold; the page carries basic maturity signals.
- A real generated demo (reproducible, never-stale) replaces the placeholder, and the
  "intentionally missing" disclaimer is gone.
- A minimal `CONTRIBUTING.md` and an unambiguous prose-vs-runtime explanation exist, so
  the project reads as open and the mode story is not misread.

## Acceptance Criteria

1. Subtask 1 (install first-run UX) is complete: `install.sh` warns when the launcher
   dir is not on PATH with a copy-pasteable fix, warns when zero agents were linked, and
   the completion banner includes a concrete "run a starter command in your agent" next
   step — per its own spec.
2. Subtask 2 (README repositioning) is complete: the first screen leads with the concrete
   hook and an explicit audience frame, jargon is demoted, a "vs. raw skills"
   differentiator and basic badges are present, and honest pre-1.0 framing is preserved —
   per its own spec.
3. Subtask 3 (generated demo + swap) is complete: a real animated demo asset is committed
   and embedded as the README hero, generated reproducibly from `generate_demo_gif.py`,
   and the "intentionally missing" disclaimer is removed — per its own spec.
4. Subtask 4 (launch-hygiene docs) is complete: a minimal `CONTRIBUTING.md` exists and
   the prose-vs-runtime default is documented unambiguously (prose default, runtime
   opt-in) — per its own spec.
5. Subtask 5 (agent install verification) is complete: an isolated per-agent install
   smoke test exists and passes for all five agents, and the README tiers agent support
   into verified-end-to-end vs install-verified-runtime-unconfirmed — per its own spec.

## Non-goals

- A full MCP tool API reference or an exhaustive platform-pack authoring guide (a short
  pointer doc is in scope; the full reference is not).
- Runtime/end-to-end verification of Copilot/OpenCode/Junie, and CI wiring of the agent
  install smoke test — both tracked as fast-follows of subtask 5, not part of this feature.
- Removing or re-architecting the prose orchestrator, or any prose/runtime code cleanup —
  this feature only clarifies the *documentation* of the existing, intentional dual mode.
- Desktop-app documentation or test coverage.
- Re-running the readiness audit or adding new product capabilities.

## Constraints

- Spec source: local. Issue key: SKILL-81.
- Any Kotlin runtime changes must pass `(cd runtime-kotlin && ./gradlew check)`; this
  feature is expected to be shell + Markdown only.
- `install.sh` changes must not break the existing install/reinstall/desktop paths and
  should pass `shellcheck` where the surrounding script already does.
- Preserve the honest pre-1.0 / solo-maintainer framing; do not overclaim production
  adoption.

## Subtasks

1. `spec_subtask_1_install-first-run-ux.md` — PATH + agent-link warnings, next-step banner
2. `spec_subtask_2_readme-repositioning.md` — Hook-first README, audience frame, badges
3. `spec_subtask_3_demo-capture-and-swap.md` — Generated demo, swap placeholder, drop disclaimer
4. `spec_subtask_4_launch-hygiene-docs.md` — CONTRIBUTING + prose/runtime clarity
5. `spec_subtask_5_agent-install-verification.md` — Install smoke test + tiered agent support

Ordered by launch impact. Subtasks 1, 4, and 5 are independent. Subtask 3 depends on
subtask 2 (both touch the README hero block; the copy rewrite lands first, then the demo
swap edits the finalized block) — see the manifest.

## Status (reconciled 2026-06-12)

This feature was executed conversationally with commits landed directly on `main`, not
through the `skill-bill goal` runtime. Current state:

- **Done:** subtask 2 (README repositioning, `0be8f34f`), subtask 3 (generated demo,
  `d63e6875`/`8a503cef`), subtask 5 (install smoke test + tiering, `04a6b31c`).
- **Remaining:** subtask 1 (installer first-run UX — only a README PATH stopgap exists so
  far; the `install.sh` changes are not done) and subtask 4 (CONTRIBUTING + prose/runtime
  doc clarity).
- **Fast-follows (out of scope):** CI wiring of the smoke test; runtime verification of
  Copilot/OpenCode/Junie.

## Next path

The remaining work is subtasks 1 and 4. They are independent shell/Markdown changes and
can be done directly, or run through the runtime:

```bash
skill-bill goal SKILL-81
```
