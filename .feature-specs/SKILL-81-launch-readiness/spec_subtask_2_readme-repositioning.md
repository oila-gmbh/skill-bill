# SKILL-81 · Subtask 2 — README repositioning for a cold audience

**Status: completed (commit `0be8f34f`).** Delivered: softened one-liner, "Who it's for"
audience frame, pre-1.0/solo-maintained honesty line, license/release/validate badges, a
"why not just raw `~/.claude/skills` files?" differentiator, a Quickstart PATH note, the
agent-support tiering hook, and collapsing the 11-item "What you get" deep-dive into
`<details>` folds.

**Deferred (P1 polish, not blocking launch):** moving the abstract "Deep under the hood" /
"runtime, governance, operations layer" framing *below* "What you get", and adding a
one-sentence durable-resume hook directly under the one-liner. Pick these up in a follow-up
pass if desired.

## Scope

The README is structured for someone who already knows what skill-bill is. A stranger
arriving from a launch post bounces before the value lands. The existing one-liner
(`README.md:5`) is strong and must be kept; the problem is everything around it: the
framing copy pivots to "the runtime, governance, and operations layer for AI-agent
skills" before the reader knows what the tool does or who it is for, there is no explicit
audience frame, no concise "vs. raw Claude Code skills" contrast, and no maturity
signals (badges).

In scope (`README.md`, and only the docs it directly links if a sentence must move):
- **Lead with the concrete hook.** Keep the existing one-liner, then immediately follow
  with a plain-language statement of the single most differentiated, demoable capability
  — durable SQLite-backed workflow state that resumes a feature run after a crash or the
  `claude -p` context-limit ceiling, with the multi-agent "one source synced across five
  agents" angle as the second beat. No architecture nouns in the first screen.
- **Add an explicit audience frame** near the top: one sentence naming who it is for
  (developers/teams using one or more coding agents who want their skills to survive
  crashes/limits and stay in sync) and, honestly, who it is overkill for.
- **Demote the jargon.** Move the "runtime, governance, and operations layer" framing
  (and similar "platform packs / manifest-driven / orchestration layer" language) below
  the quickstart and the "what you get" bullets, so the reader reaches concrete examples
  before abstract architecture. Do not delete the technical framing — relocate it.
- **Add a short "vs. raw Claude Code skills" paragraph** (2–4 sentences) stating the
  concrete delta: durable resume + governance/validation that plain skill folders do not
  have. Frame against raw skills, not against named competitor products.
- **Add basic maturity badges** to the top (license, latest release/version, and build
  status if a CI workflow already produces a usable status). Use shields.io; do not
  invent badges for things that do not exist (no fake coverage/downloads numbers).
- **Preserve honest framing.** Keep the pre-1.0 / "proving it on real teams" honesty;
  do not introduce claims of production adoption or scale that are not true.

The demo hero block (`README.md:7`–`9`) is left in place by this subtask; subtask 3
swaps the placeholder for the real asset and removes the disclaimer (dependency below).

## Acceptance Criteria

1. The first screen (above the Quickstart) leads with the existing one-liner followed by
   a plain-language hook naming the durable-resume capability, with no "runtime /
   governance / operations layer / platform packs / manifest-driven" jargon appearing
   before the Quickstart.
2. An explicit one-sentence audience frame is present near the top, naming both the
   intended user and, honestly, who it is overkill for.
3. The "runtime, governance, operations layer" framing still exists in the README but
   now appears **after** the Quickstart / "what you get" section, not before it.
4. A 2–4 sentence "vs. raw Claude Code skills" differentiator paragraph is present and
   frames against raw skills (not named competitor products).
5. License and release/version badges (and build-status only if a real CI workflow backs
   it) are present at the top via shields.io; no badge references a metric the project
   does not actually produce.
6. The pre-1.0 / non-overclaimed framing is preserved; no new unsupported adoption or
   scale claims are introduced. All internal links still resolve.

## Non-goals

- Recording or swapping the demo asset (subtask 3).
- Adding `CONTRIBUTING.md` or the prose/runtime clarification (subtask 4).
- Rewriting `docs/getting-started*.md` or ROADMAP content beyond moved sentences.
- Adding competitor-comparison tables or marketing landing pages.

## Dependency notes

Independent of subtasks 1 and 4. Shares the `README.md` hero block with subtask 3;
this subtask lands the copy rewrite first and subtask 3 then edits the finalized block,
so subtask 3 declares a dependency on this one to avoid a same-file conflict.

## Validation strategy

- Render `README.md` (GitHub preview or a Markdown renderer) and confirm: one-liner +
  plain-language hook + audience frame are above the Quickstart; jargon framing is below
  it; the "vs. raw skills" paragraph reads clearly; badges render and point at real
  resources.
- `git grep` the first-screen region to confirm none of the demoted jargon terms appear
  before the Quickstart heading.
- Link-check the README (no broken relative or shields.io links).

## Next path

```bash
skill-bill goal SKILL-81
```
