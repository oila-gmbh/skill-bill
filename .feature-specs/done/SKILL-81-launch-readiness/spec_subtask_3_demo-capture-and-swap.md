# SKILL-81 · Subtask 3 — Generated demo + placeholder swap

**Status: completed (commits `d63e6875`, `8a503cef`).** The original plan assumed a manual,
out-of-band screen recording. That was superseded: the demo is now a **generated artifact**,
which made this subtask fully automatable and removed the "blocked on manual capture" risk.

## Scope

The README hero previously embedded `docs/assets/skill-bill-demo-placeholder.svg` with a
disclaimer that the recorded demo was "intentionally missing." On a public launch that
reads as an unfinished product. This subtask replaced it with a real animated demo.

What shipped (differs from the original manual-recording plan):
- `docs/assets/generate_demo_gif.py` — a Pillow script that renders the demo frame by
  frame: a `/bill-feature-task` run where each phase starts (spinner) and finishes (check),
  the run is **interrupted mid-flight for any reason** (usage limit, crash, lost
  connection — generalized, not `claude -p`-specific), then resumes from durable workflow
  state and completes. It bakes in a palette-optimize pass (~3 MB output) and exposes a
  single `SPEED` pacing knob (~45 s). Because the demo is generated, it regenerates on
  demand and never goes stale — no out-of-band recording step.
- `docs/assets/skill-bill-demo.gif` (the committed demo) and
  `docs/assets/skill-bill-demo-poster.png` (static final-frame fallback).
- README hero updated to embed the GIF; the static-placeholder disclaimer removed; caption
  and alt text describe the generalized "interrupted, then resumes" narrative.

## Acceptance Criteria

1. A real animated demo asset is committed under `docs/assets/` and embedded as the README
   hero (not the placeholder SVG, not a stub). ✅
2. The "static placeholder / intentionally missing" disclaimer is removed from the README. ✅
3. The demo is reproducible from a committed generator (`generate_demo_gif.py`), so it does
   not depend on a manual recording and will not rot. ✅
4. The narrative shows each phase start→finish, an interruption that is **not** tied to one
   specific cause, and a durable-state resume to completion. ✅

## Non-goals

- Hosting the asset off-repo.
- Rewriting surrounding README copy (subtask 2 owns the copy).

## Dependency notes

Depended on subtask 2 (shared README hero block); landed alongside it on `main`.

## Validation strategy

- `python3 docs/assets/generate_demo_gif.py` regenerates the GIF (~45 s, ~3 MB, loops).
- README hero renders the animated demo; `git grep "intentionally missing"` and
  `git grep "skill-bill-demo-placeholder"` return no README references.

## Next path

```bash
skill-bill goal SKILL-81
```
