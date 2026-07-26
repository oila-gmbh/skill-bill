# Product Hunt kit

Assets for a Product Hunt launch. All facts trace to the
[ground-truth fact sheet](README.md).

> **This is the LATER moment, not the soft launch.** Product Hunt is a one-shot,
> high-visibility event and should only fire once the launch is genuinely ready. It is
> **gated on:** prebuilt artifacts live for **all four** OSes (a public stable
> release/tag cut), a **verified clean-machine install**, and the **recorded demo**
> existing (today it is only storyboarded). Reddit goes first; Product Hunt follows. See
> [sequencing-note.md](sequencing-note.md) and the open gates in
> [go-no-go-checklist.md](go-no-go-checklist.md).

---

## Tagline (≤60 chars)

**`Author AI coding skills once, sync them across 5 agents`**

- **Character count: 55** (limit 60). ✅

Backup tagline (45 chars): `One source of truth for your AI coding skills`

---

## Description

Skill Bill is one source of truth for your AI coding skills — authored once, synced
across Claude Code, Copilot, Codex, OpenCode, and Junie, with the validation and durable
workflow state to keep them from rotting.

Most prompt/skill setups drift: different copies in different agents, names wandering,
stack logic leaking into generic prompts, and no signal when something goes stale. Skill
Bill treats skills like software — one authored source, a contract that fails loudly on
drift, durable resumable workflow state that survives crashes and context compaction,
and automatic decomposition of work too big to do reliably in one pass.

The product is the framework, not the prompts. It's deep under the hood and trivial to
use: install is one command, prebuilt by default, no JDK and no build step. A working
Kotlin/KMP reference pack ships as a wired example — use it, fork it, or replace it with
your own.

---

## Gallery plan

Ordered. The desktop app is the **hero**.

1. **Hero — the desktop app (image).** A clean screenshot of the Compose Desktop app:
   the tree-based skill/artifact browser with a skill selected. This is the hero because
   it makes "governed skill platform" tangible in one glance and shows the "trivial to
   use" surface over the deep machinery.
2. **One-command install (image).** A terminal frame showing the verbatim quickstart
   (`git clone … && cd … && ./install.sh`) plus `skill-bill version` / `skill-bill
   doctor` succeeding. Communicates the low-friction prebuilt path.
3. **Cross-agent sync (image/diagram).** One source → five agents (Claude Code, Copilot,
   Codex, OpenCode, Junie). The core differentiator in one frame.
4. **Loud-fail validation (image).** A validation run failing loudly on drift — the
   "hard shell" that keeps skills from rotting.
5. **Motion demo (PLANNED — see below).** Reserved gallery slot for the spec→PR motion
   demo once it exists.

### Motion demo slot — PLANNED / STORYBOARDED, not yet recorded

The motion demo (`/bill-feature-implement` running spec → PR) is **storyboarded but not
recorded**. The committed asset today is a static placeholder
([`docs/assets/skill-bill-demo-placeholder.svg`](../../../docs/assets/skill-bill-demo-placeholder.svg)).
The shot list and capture recipe live in
[`docs/assets/skill-bill-demo-storyboard.md`](../../../docs/assets/skill-bill-demo-storyboard.md)
and
[`docs/assets/skill-bill-demo-capture-instructions.md`](../../../docs/assets/skill-bill-demo-capture-instructions.md).
This is the **same demo reused** from the README front door — record it once, use it on
the README and in this gallery. **Recording this demo is a hard gate for the Product
Hunt launch**; do not launch with a placeholder in the hero gallery.

---

## Maker comment (first comment from the maker)

Hey Product Hunt 👋

I'm a solo maker, and I built Skill Bill because I got so used to structured AI coding
that unstructured work started to feel incomplete. My skills kept drifting across the
different agents I use — different copies, names wandering, no signal when one went
stale — and fixing it by hand didn't scale.

So I built the layer I wanted: author a skill once, sync it across Claude Code, Copilot,
Codex, OpenCode, and Junie, with a contract that fails loudly when things drift instead
of quietly rotting, plus durable resumable workflow state for long multi-phase runs. The
product is the framework, not the prompts — it ships everything *around* the prompt that
makes one person's prompt something a team can rely on.

It's deep under the hood but trivial to use: one command to install, prebuilt by
default, no JDK. A Kotlin/KMP reference pack is included as a worked example — fork it or
replace it with your own stack's packs.

Two honest notes: I'm a solo maintainer (actively maintained — it's my daily driver),
and the optional desktop installers are unsigned for v1, so you'll see a first-launch OS
warning I'm glad to walk through. I'd love your feedback on where it's over-built and
where it's actually useful.
