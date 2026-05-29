# Launch sequencing note

How the two launch channels are ordered, and why. Facts trace to the
[ground-truth fact sheet](README.md); gates trace to
[go-no-go-checklist.md](go-no-go-checklist.md).

## TL;DR

**Reddit first. Product Hunt later.** Reddit is a narrative-led soft launch you can do
the moment the core install gates clear. Product Hunt is the bigger, one-shot moment and
is artifact-gated — it additionally requires the recorded demo and all-OS desktop
verification.

---

## Phase 1 — Reddit (narrative-led soft launch)

- **What it is.** A genuine "I built this and want feedback" post led with the
  maintainer narrative ([reddit-draft.md](reddit-draft.md)), primary target r/ClaudeAI
  ([subreddit-plan.md](subreddit-plan.md)).
- **Why first.** It's lower-stakes, reversible, and feedback-oriented. Reddit rewards a
  story and a working artifact over polish — so it does **not** need the recorded demo
  (the post links the repo, not a video). It surfaces the objections
  ([objection-faq.md](objection-faq.md)) and real-world install friction *before* the
  high-visibility Product Hunt moment.
- **Gate to start (from the checklist).** A **public stable release is cut** (S3 master
  gate) with all four hosts' artifacts + checksums, and a **clean-machine install is
  verified** on at least one host per OS family (S4 gate). Until those close, even the
  soft launch is a NO-GO — the post points people at `./install.sh` and that path must
  actually work from a published release.

## Phase 2 — Product Hunt (artifact-gated, demo-dependent)

- **What it is.** The polished, one-shot launch with the desktop app as gallery hero and
  the maker comment ([product-hunt-kit.md](product-hunt-kit.md)).
- **Why later.** Product Hunt is a single high-visibility shot — you don't get a clean
  do-over. It should fire only when the product looks as good as it works, which means
  the **recorded motion demo must exist** (today it is storyboarded only — see
  [`docs/assets/skill-bill-demo-storyboard.md`](../../../docs/assets/skill-bill-demo-storyboard.md))
  and all desktop installer formats must be verified.
- **Additional gates (beyond the Reddit gates).** `.rpm` / `.dmg` / `.msi` desktop
  extraction verified (S2 gate, currently only `.deb`/`linux-x64` exercised by CI), and
  the **recorded demo committed** with the README embed swapped off the placeholder (S5
  gate).

## Why not both at once

Doing Product Hunt simultaneously with — or before — Reddit would burn the one-shot
moment on an unfinished surface (placeholder demo, unverified desktop formats) and skip
the cheap feedback loop. Reddit de-risks the install path and the objection handling
first; Product Hunt then launches on hardened, verified, demo-backed artifacts.

## One-line sequence

> Cut the release → verify clean-machine install → **soft-launch on Reddit** → fold in
> feedback + record the demo + verify all desktop formats → **launch on Product Hunt.**
