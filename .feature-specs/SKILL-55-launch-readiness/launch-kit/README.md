# Skill Bill Launch Kit

This directory holds the launch-readiness material for Skill Bill: the soft-launch
Reddit copy, the subreddit targeting plan, an objection-preempt FAQ, a Product Hunt
kit, a go/no-go checklist, and the sequencing note that ties them together.

It is **maintainer-facing working material**, not a published page. Nothing here ships
to users. Treat every other file in this kit as drawing its facts from the
**ground-truth fact sheet** below.

---

## ACCURACY MANDATE (read before editing or posting anything)

Every claim in this kit must match what is actually shipped in the repo **today**.
No aspirational or unshipped feature may be described as if it already exists. The
specific traps, called out so they are not repeated downstream:

1. **The demo is a static placeholder, not a video.** The committed asset is
   [`docs/assets/skill-bill-demo-placeholder.svg`](../../../docs/assets/skill-bill-demo-placeholder.svg).
   The real motion demo (`docs/assets/skill-bill-demo.gif`) is **not recorded and not
   committed**. It is only *storyboarded*. Any reference to a "demo video" must be
   phrased as **planned / storyboarded**, and must link the storyboard
   ([`docs/assets/skill-bill-demo-storyboard.md`](../../../docs/assets/skill-bill-demo-storyboard.md))
   or the capture recipe
   ([`docs/assets/skill-bill-demo-capture-instructions.md`](../../../docs/assets/skill-bill-demo-capture-instructions.md)),
   never a finished file. **Do not link `docs/assets/skill-bill-demo.gif` anywhere — it
   does not exist on disk and a broken link fails `agnix --strict`.**

2. **There is no `skill-bill desktop install` subcommand.** The desktop app is added
   through the installer flag `./install.sh --with-desktop-app`, or later with
   `./install.sh --desktop-app-only`. Do not invent a CLI verb for it.

3. **Desktop installers are UNSIGNED for v1.** This is a recorded decision, not an
   oversight. The "won't open / unidentified developer" answer in the FAQ is
   load-bearing and the open-anyway steps must be quoted verbatim.

4. **No public stable release/tag has been cut yet,** and `.rpm` / `.dmg` / `.msi`
   desktop extraction is **unexercised by CI** (CI has only exercised `.deb` on
   `linux-x64`). These are **OPEN gate items** in the go/no-go checklist — never
   pre-checked.

If a downstream file contradicts this fact sheet, the fact sheet wins; fix the file.

---

## Ground-truth fact sheet

### What the product is

> One source of truth for your AI coding skills — authored once, synced across Claude
> Code, Copilot, Codex, OpenCode, and Junie, with the validation and durable workflow
> state to keep them from rotting.

Positioning lines (verbatim, reusable):

- "the product is the framework, not the prompts"
- "deep under the hood, trivial to use"
- "soft inside, hard shell"

Differentiation, in one sentence: Skill Bill sits at the **unoccupied intersection** of
(a) cross-agent reach across **five** agents, (b) a governed loud-fail contract with
drift protection, (c) durable resumable workflow state, and (d) automatic decomposition
of oversized work. CLAUDE.md, rulesync, and Spec Kit each cover part of that space; none
covers all of it.

### The five synced agents

Claude Code, GitHub Copilot, OpenAI Codex, OpenCode, JetBrains Junie.

### Install (verbatim — copy exactly)

```bash
git clone https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

- Default `./install.sh` is the **prebuilt** path: it downloads and
  **checksum-verifies** the release runtime images for your host. It needs only
  `curl`, `tar`, `unzip`, and `shasum`/`sha256sum`. **No JDK, no Gradle, no `gh`.**
- First checks after install:

  ```bash
  skill-bill version
  skill-bill doctor
  ```

### Install variants

| Goal | Command |
|------|---------|
| Default prebuilt CLI install | `./install.sh` |
| Include the desktop app | `./install.sh --with-desktop-app` |
| Add the desktop app later | `./install.sh --desktop-app-only` |
| Build from your checkout | `./install.sh --from-source` |
| Pin a specific release | `./install.sh --release TAG` (or `SKILL_BILL_RELEASE_TAG=TAG ./install.sh`) |

### Supported prebuilt hosts (exactly four)

`macos-arm64`, `macos-x64`, `windows-x64`, `linux-x64`. Any other host **auto-falls
back to `--from-source`**, which requires a JDK.

### Desktop installer formats

`.dmg` (macOS), `.msi` (Windows), `.deb` + `.rpm` (Linux). **Unsigned for v1.**

### Maintainer narrative (lead with this — authentic, first person)

> I got so used to structured AI work that unstructured now feels incomplete — so I
> built this.

Maintenance posture, stated honestly: **solo, but actively maintained.**

### Demo status

- Committed: a **static placeholder SVG** + a **storyboard** + **capture instructions**.
- **Not** committed: the recorded motion demo. Reference it only as planned.

---

## Kit index

| File | Purpose | Acceptance criteria |
|------|---------|---------------------|
| [README.md](README.md) (this file) | Kit index + ground-truth fact sheet + accuracy mandate | AC6 |
| [subreddit-plan.md](subreddit-plan.md) | Ranked, named target subreddits with rationale + engagement guidance; names the primary target | AC2 |
| [reddit-draft.md](reddit-draft.md) | Ready-to-post Reddit draft(s) leading with the narrative, pointing at the prebuilt quickstart | AC1, AC6 |
| [objection-faq.md](objection-faq.md) | Objection-preempt FAQ: unsigned/won't-open, overengineered, vs-alternatives, maintenance/solo | AC3, AC6 |
| [product-hunt-kit.md](product-hunt-kit.md) | Product Hunt kit: tagline, description, gallery plan, maker comment, later-moment gating | AC4, AC6 |
| [go-no-go-checklist.md](go-no-go-checklist.md) | Launch go/no-go checklist tracing to S1–S5 deliverables; surfaces open gates | AC5, AC6 |
| [sequencing-note.md](sequencing-note.md) | Reddit-first / Product-Hunt-later sequencing rationale | AC4, AC5, AC6 |
