# Reddit draft(s)

Ready-to-post copy. The **primary draft** targets **r/ClaudeAI** (see
[subreddit-plan.md](subreddit-plan.md)). It leads with the maintainer narrative and
points at the prebuilt quickstart. Every claim traces to the
[ground-truth fact sheet](README.md). Keep the [objection-faq.md](objection-faq.md) open
in a tab while the post is live.

> Pre-post check: confirm the **go/no-go open items** are cleared
> ([go-no-go-checklist.md](go-no-go-checklist.md)) before posting. Do **not** claim a
> demo video — it is storyboarded only.

---

## Primary draft — r/ClaudeAI

### Title

> I got so used to structured AI coding that unstructured now feels broken — so I built a governed layer that syncs my skills across every agent

### Body

I work with AI coding agents all day, and somewhere along the way a switch flipped: once
I had structured, repeatable workflows, going back to ad-hoc prompting started to feel
like writing code with no version control. Unstructured just feels *incomplete* now. So
I built the thing I wanted and have been using it daily — sharing it here for honest
feedback.

It's called **Skill Bill**. The one-line version:

> One source of truth for your AI coding skills — authored once, synced across Claude
> Code, Copilot, Codex, OpenCode, and Junie, with the validation and durable workflow
> state to keep them from rotting.

The problem it solves for me: my skills and prompts used to drift. Different copies in
different agents, names wandering, stack-specific logic leaking into generic prompts,
and no way to tell when something silently went stale. Skill Bill treats skills more
like software — one authored source, a contract that **fails loudly** when things drift
instead of quietly rotting, durable resumable workflow state so a long multi-phase run
survives a crash or context compaction, and automatic decomposition when a task is too
big to do reliably in one shot.

The thing I keep coming back to: **the product is the framework, not the prompts.** It
doesn't ship "the one true code review." It ships everything *around* the prompt that
makes one person's prompt something a team can actually rely on. A working Kotlin/KMP
reference pack is included as a wired example — use it, fork it, or delete it and author
your own.

It looks like a lot of machinery, and honestly it is — but the path you actually run is
one command. Prebuilt by default, so no JDK and no build step:

```bash
git clone https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

That downloads and checksum-verifies the prebuilt runtime for your OS (needs only curl,
tar, unzip, and shasum). Then:

```bash
skill-bill version
skill-bill doctor
```

Supported prebuilt hosts are macos-arm64, macos-x64, windows-x64, and linux-x64;
anything else automatically falls back to a from-source build (that one needs a JDK).
There's an optional Compose Desktop app too (`./install.sh --with-desktop-app`, or add
it later with `./install.sh --desktop-app-only`).

Two honest caveats up front: I'm a **solo maintainer** (it's actively maintained, but
it's me), and the **desktop installers are unsigned for v1** — so macOS Gatekeeper and
Windows SmartScreen will warn you the first time. Happy to walk anyone through the
open-anyway steps in the comments.

Repo: https://github.com/Sermilion/skill-bill

I'd genuinely like to hear where this is over-built, where it's useful, and how your own
"my prompts keep drifting across agents" pain shows up. I'll be around in the comments
for the next few hours.

---

## Alternate variant — r/ChatGPTCoding (cross-agent angle)

### Title

> If you use more than one AI coding agent, you're maintaining the same prompts N times. I got tired of that and built a single source of truth.

### Body

I kept hitting the same wall: I use more than one AI coding agent, and my prompts/skills
ended up as N slightly-different copies that drifted apart. Fixing one never fixed the
others. I got used to structured AI work, and the unstructured copy-paste-across-agents
life started to feel broken — so I built a tool for it and have been running it daily.

**Skill Bill** is a governed layer that lets you author a skill once and sync it across
Claude Code, Copilot, Codex, OpenCode, and Junie — five agents, one source of truth —
with a contract that **fails loudly** when a copy drifts instead of silently going
stale. It also keeps durable, resumable state for long multi-phase workflows and
auto-decomposes work that's too big to do reliably in one pass.

The framing that matters: **the product is the framework, not the prompts.** It doesn't
hand you "the correct prompts." It handles cross-agent install, per-platform routing,
drift protection, workflow state, and project-level overrides — the stuff *around* the
prompt. A Kotlin/KMP reference pack ships as a worked example; fork it or replace it
with your own stack's packs.

One command, prebuilt by default (no JDK, no build):

```bash
git clone https://github.com/Sermilion/skill-bill.git ~/Development/skill-bill
cd ~/Development/skill-bill
./install.sh
```

Then `skill-bill version` and `skill-bill doctor` to confirm. Prebuilt hosts:
macos-arm64, macos-x64, windows-x64, linux-x64 (other hosts auto-fall-back to a
from-source build that needs a JDK).

Honest caveats: solo maintainer (actively maintained, but it's just me), and the
optional desktop installers are unsigned for v1 so you'll see a first-launch OS warning
I'm happy to walk through.

Repo: https://github.com/Sermilion/skill-bill

Curious how many agents people here juggle and whether the drift pain is as real for you
as it is for me. I'll be in the comments.
