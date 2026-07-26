# SKILL-55 Subtask 5 - Front Door: README Hero, Demo Asset, 60-Second Quickstart

Parent spec: [.feature-specs/SKILL-55-launch-readiness/spec.md](./spec.md)
Issue key: SKILL-55
Subtask order: 5 of 6
Depends on: subtask 4
Branch model: same-branch, commit per subtask

## Purpose

Rebuild the README "front door" so a cold visitor grasps the value in ~5 seconds
and reaches first value in ~60. Today the README opens with abstract framing
("runtime, governance, and operations layer") and 25KB of detail before a
newcomer can act. This subtask adds a one-sentence hook, an embedded demo asset,
and a prebuilt-install quickstart that reflects subtask 4's reality (no JDK, no
build). The existing depth is retained, but moved below the fold.

## Scope

In scope:

- **One-sentence hook** at the very top: concrete, pain-first, no jargon. Working
  candidate (refine with subtask 6): *"One source of truth for your AI coding
  skills — authored once, synced across Claude Code, Copilot, Codex, OpenCode,
  and Junie, with the validation and durable workflow state to keep them from
  rotting."*
- **Demo asset** embedded near the top: a short GIF/MP4 showing either
  `/bill-feature-implement` going spec → PR, or the desktop tree + scaffold
  wizard + inline edit. If the binary capture is produced out-of-band, commit a
  storyboard + capture instructions and a placeholder so the README structure is
  complete and the asset can drop in. Store under `docs/assets/`.
- **≤60-second quickstart** as the first actionable block: the prebuilt path from
  subtask 4 (clone-or-download + `./install.sh` → `skill-bill version` →
  one starter command), with **no JDK listed as a prerequisite** for that path.
  List the JDK only under a "build from source" / contributor subsection.
- **Prerequisites stated honestly**: prebuilt path needs only `curl`/`tar`
  (+ a supported OS/arch); from-source path needs a JDK. Make the split obvious.
- **Pre-empt the complexity objection** in one short paragraph: "deep under the
  hood, trivial to use — here's the one-command path; the machinery is optional
  reading." This mirrors the launch narrative (subtask 6) so README and post
  agree.
- **Reorder, don't delete**: move the 11-capability deep dive, architecture
  snapshot, and contracts below the quickstart and "what is this" hook. Keep all
  existing docs links.
- Keep the README catalog and reference-pack framing intact (they're accurate);
  just stop leading with them.
- Update `docs/getting-started.md` cross-links if the install instructions there
  reference the from-source default.

Out of scope:

- The Reddit / PH post copy and gallery (subtask 6) — though the hook and
  objection paragraph drafted here are the shared source the post reuses.
- Installer behavior (subtask 4) — this subtask documents it, does not change it.
- Producing the final polished video if it requires non-repo tooling; the
  storyboard + placeholder is acceptable, the missing binary asset must be
  called out, not silently omitted.

## Acceptance Criteria

1. The README opens with a one-sentence hook a non-expert understands in ~5
   seconds, followed by a demo asset (or a committed placeholder + storyboard +
   capture instructions if the binary is produced out-of-band).
2. The first actionable block is a quickstart completable in ≤60 seconds on a
   clean machine via the prebuilt path, with no JDK named as a prerequisite
   there.
3. Prerequisites are split clearly: prebuilt (curl/tar + supported OS) vs.
   from-source (JDK).
4. A short paragraph pre-empts the "overengineered / too complex" reaction with
   the one-command path.
5. The existing capability deep-dive, architecture, and contracts content is
   retained below the fold; no docs links are dropped.
6. Install commands in the README match subtask 4's actual flags and defaults
   (verified against `install.sh`, not invented).
7. `npx --yes agnix --strict .` and `scripts/validate_agent_configs` still pass
   (no broken links / drift introduced by the README restructure).

## Validation

```bash
# Link/asset integrity and repo drift:
npx --yes agnix --strict .
scripts/validate_agent_configs

# Manual: render README.md and confirm hook → demo → quickstart ordering, and
# that every install command matches install.sh flags from subtask 4.
```

## Implementation Notes

- The hero image already exists (`docs/assets/skill-bill-readme-hero.svg`); the
  new demo asset complements it — keep the SVG, add the motion demo.
- Do not invent install commands: the quickstart must mirror exactly the flags
  and defaults subtask 4 shipped (`--from-source`, `--release`,
  `--with-desktop-app` / `--no-desktop-app`, the `skill-bill desktop install`
  one-liner).
- Keep the honest framing the maintainer prefers: the product is the framework,
  the reference packs are replaceable. The reorder should make that *clearer* to
  a newcomer, not bury it.
- GIF vs MP4: GitHub renders both inline; an MP4/`<video>` is smaller and
  higher-quality for longer demos, a GIF is simplest for ≤10s loops. Pick per
  asset length and note the choice.
