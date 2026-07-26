# Objection-preempt FAQ

Prepared answers for the predictable pushback during the soft launch. Keep this open in
a tab while a Reddit post is live. All facts trace to the
[ground-truth fact sheet](README.md). Answers are written to be pasted into comments with
light edits — honest, specific, non-defensive.

---

## 1. "It won't open — macOS says unidentified developer / Windows SmartScreen blocked it."

The desktop installers are **unsigned for v1**. That's a deliberate, recorded decision
for the first release, not a sign something's wrong. The runtime CLI install
(`./install.sh`) is unaffected — this only applies to the optional desktop app. Here's
how to open it on each OS:

**macOS (Gatekeeper):**

> Right-click (or Control-click) the app in Finder -> Open -> Open in the confirmation
> dialog. Alternatively: System Settings -> Privacy & Security -> Open Anyway.

**Windows (SmartScreen):**

> On the "Windows protected your PC" dialog, click More info -> Run anyway.

**Linux (.deb / .rpm):**

> No OS signing gate — nothing to do.

Signing for the desktop installers is on the list; for v1 the open-anyway path above is
the supported flow, and I'll fix it before I'd ever call signing "done."

---

## 2. "This looks massively overengineered / too complex for what it does."

Fair — it *is* a lot of machinery, and I won't pretend otherwise. The design goal is
**deep under the hood, trivial to use**: the path you actually run is one command
(`./install.sh`, prebuilt, no JDK, no build). Everything else — the contracts,
validation, workflow state, decomposition — is optional depth that's invisible during
normal use.

The reason it's not just a folder of prompts: the hard part of skills isn't writing the
prompt, it's keeping it from rotting across five agents and surviving real
multi-phase runs. **The product is the framework, not the prompts.** If you only want a
prompt pack, this is more than you need — and that's a legitimate reason to pass. If
you've felt prompt-drift across agents, the machinery is doing the work you'd otherwise
do by hand.

---

## 3. "How is this different from CLAUDE.md / rulesync / Spec Kit?"

Short version: those each cover part of the space; Skill Bill aims at the **intersection
none of them occupy**:

- **Cross-agent reach** — one authored source synced across **five** agents (Claude
  Code, Copilot, Codex, OpenCode, Junie), not a single-agent config file.
- **Governed loud-fail contract + drift protection** — when a skill drifts, validation
  *fails loudly* instead of letting copies silently go stale.
- **Durable, resumable workflow state** — long multi-phase runs survive crashes and
  context compaction and can be resumed.
- **Automatic decomposition** — work too big to do reliably in one shot is broken into
  resumable subtasks the runtime tracks.

So: **CLAUDE.md** is a per-project instruction file for one agent. **rulesync** syncs
rules across agents but isn't a governed workflow runtime with durable state. **Spec
Kit** is a spec-driven workflow but not a cross-agent governance/sync layer. Skill Bill
is the layer that does cross-agent sync *and* governed loud-fail *and* durable workflow
state *and* decomposition together. The product is the framework, not the prompts —
which is exactly why it's not "just a better CLAUDE.md."

---

## 4. "It's a solo project — is this going to be maintained, or abandoned in three months?"

Honest answer: it's **solo, but actively maintained.** It's me. I built it because I use
it every day — I got so used to structured AI work that unstructured now feels
incomplete — so maintaining it isn't a chore I'll drift away from, it's my daily driver.

I'm not going to oversell a team that doesn't exist. What I can point to instead of
promises: it's open, the governed contracts and validation are designed so the project
fails loudly rather than rotting quietly, and the reference packs are explicitly
fork-or-replace so you're never locked into my pace. If single-maintainer risk is a
dealbreaker for you, that's a reasonable call — but the framing here is "actively used
and maintained by the person who depends on it," not "abandonware waiting to happen."
