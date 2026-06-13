# Skill Bill Roadmap

## What Skill Bill is

Skill Bill can look like a pile of prompt files from the outside. It is not: the repository is now mostly application code — a layered Kotlin runtime (CLI, MCP server, durable workflow state, telemetry, install primitives, and a desktop app) sits behind the authored skills and turns them into an enforced engineering process.

**The product is not any one of those pieces. It is what they produce together:**

> Your AI coding agent ships whole features with engineering rigor — spec to a PR you'd actually merge — the same way every run, on whatever agent you use.

The skills, the runtime, the contracts, the platform packs, the installer, and the telemetry loop are all load-bearing parts of delivering that outcome. None of them is "the product" on its own, and none is a disposable example. Debating "is it the framework or the skills" is a category error — they are both means to the outcome above.

Skill Bill is pre-1.0 and solo-maintained. This roadmap exists to keep that outcome in focus as the codebase grows, so the project gets *more dependable*, not just *bigger*.

## North star

**Make AI-assisted feature work feel like a reliable team capability, not a bag of prompts.**

A team should be able to run `/bill-feature-task` as a governed spec-to-PR workflow — and use `/bill-code-review`, `/bill-code-check`, and `/bill-feature-verify` as standalone phases — and trust:

- what behavior they will get
- how scope is interpreted and how review depth is chosen
- what is guaranteed versus best-effort, and how failures surface
- that platform-specific behavior stays consistent with the shared entry points
- that a run survives interruption and resumes from durable state instead of chat history

## The pieces, and why none is "the product"

Each part earns its place by making the outcome more reliable. Remove any one and the outcome degrades:

- **Curated skills** encode the engineering judgment — how to plan, review, audit, and gate. They are tuned and dogfooded, not throwaway demos.
- **The Kotlin runtime** owns each run: durable workflow state, resume/continue, the goal loop, decomposition, telemetry, and cross-agent install.
- **Contracts and the validator** fail the build when skills drift, so the judgment can't silently rot.
- **Platform packs** carry stack-specific depth behind generic routers, so the base layer stays clean.
- **The telemetry loop** turns real usage into evidence and fixes.

Forking or replacing skills is a supported **escape hatch and extension point** — author a pack for your stack, override a skill per repo — but it is not the pitch. The pitch is that the shipped, tuned system already knows how to take a feature from spec to PR.

## Moats, honestly

It is worth being precise, because the easy claims are wrong.

- **Cross-agent portability is a property, not a moat.** Rendering one source into several agent formats is replicable in a sprint. It is genuinely useful; it does not defend anything.
- **The runtime and the curated judgment are barriers to entry, not moats.** They cost a competitor months to reproduce — a head start, not a wall. Nothing structural stops someone spending the months.
- **The telemetry → analysis → fix loop is the one candidate that compounds** — real usage produces data a competitor without users cannot have, and the loop turns that data into improvements. But it is **latent**: no adoption means no telemetry means no loop. It only becomes a moat after people use it.

**Honest position:** pre-1.0 and solo, Skill Bill does not have a moat today. It has a head start and one latent moat that switches on with adoption. The strategic job is to **reach enough real usage to turn the telemetry loop on before the head start stops being enough.** Everything below serves that.

## Where it stands today

Strong:

- a flagship `bill-feature-task` workflow composing planning, implementation, routed review, completeness audit, quality gates, history, and PR handoff
- durable, resumable workflow state for `bill-feature-task` and `bill-feature-verify`
- a foreground `skill-bill goal` runtime that decomposes oversized work and runs subtasks from durable state
- stack-aware routing for review and quality-check, with layered shared + platform-specific contracts
- validator-backed contracts that catch many forms of repository drift
- a one-command install across multiple agents, plus CLI, MCP, and a Compose Desktop app
- docs split into landing-page, getting-started, and team-rollout concerns

Still early:

- reliability under real-world runtime behavior across different agents and repos
- evidence: review quality, repeat usage, recovery rate, and external usefulness are barely measured
- org-level rollout and override strategy beyond maintainer intuition
- repeatable external authoring of new packs without maintainer context
- process routing beyond stack detection (work-type awareness)

## Recent milestones

- **One-command install (shipped):** `curl … | bash` fetches a checksum-verified prebuilt runtime — no clone, no JDK, no Gradle — with tag-driven releases (v0.2.0+).
- **Goal runtime + decomposition (shipped):** planning can switch into decomposition, write schema-validated subtask specs and a manifest, and the foreground `skill-bill goal` runtime resumes by issue key from durable state.
- **Prose-goal subtask isolation (shipped):** each subtask runs in a dedicated Level-1 agent with a fresh context budget instead of accumulating the whole goal's context inline.
- **Durable workflow state + resume (shipped):** open/update/continue/show surfaces and matching MCP tools for `bill-feature-task` and `bill-feature-verify`, so long runs no longer depend on chat history.
- **Shell + content architecture (shipped):** the platform-independent shell owns routing, telemetry, and contract enforcement; platform-specific content lives in packs discovered through a versioned contract. Covers the stable core surfaces.
- **Desktop app (shipped):** a Compose Desktop client over the same runtime services as the CLI.
- **Early external signal (observed):** non-maintainers have authored platform packs; `bill-feature-task` is the strongest daily workflow and `bill-code-review` the strongest standalone phase.

## Priorities

Ordered by importance, not date.

### 1. Reliability first

The reported behavior must match what actually happened. Keep reducing:

- scope drift between staged, unstaged, PR, and file-based review modes
- workflow-resume ambiguity when a long run loses step state
- false confidence from unsupported or partially supported execution paths
- hidden fallbacks that make output look stronger than the real guarantees

### 2. Make the core workflows genuinely strong

Deepening the main flows beats adding stacks. Focus on `bill-feature-task`, `bill-code-review`, `bill-feature-verify`, `bill-code-check`, and `bill-pr-description`: review signal quality, output consistency across agents, completeness checks against acceptance criteria, and PR handoff quality. A small set of stable commands should cover a meaningful share of real engineering work without feeling shallow or flaky.

### 3. Reach adoption and switch the telemetry loop on

This is the moat strategy made concrete. Get the system into enough real use that the telemetry → analysis → fix loop runs on real data, not maintainer intuition:

- lower onboarding friction for non-maintainers
- one lighthouse team using the core flows in normal work for several weeks
- a repeatable external-authoring path (scaffolder + docs + validator, no maintainer mind-reading)
- structured evaluation of one external fork's first week beats any internal polish

### 4. Deepen governance

Turn the taxonomy bias into a fuller operating model: stronger validation of routing/orchestration contracts, clearer rules for overrides/versioning/migration/deprecation, explicit guaranteed-vs-best-effort boundaries, and clearer rules for how packs may diverge. Maintainers should evolve the repo without eroding consistency.

### 5. Measure honestly

A governed system needs evidence. Favor outcome- and reliability-signals over vanity counts:

- recovery rate (interrupted runs that resume and finish) — the real KPI, not single-pass completion
- review usefulness on the shipped packs (now a primary signal, since they are dogfooded, not demos)
- cross-agent parity — comparable output on each supported agent
- authoring success rate and fork health for external packs
- contract-drift catches vs. misses

### 6. Expand from stack routing to process routing (later)

Eventually route on *what kind of change this is* (migration, risky auth, rollout, incident fix, behavior-preserving refactor, design-heavy UI, reliability-sensitive infra), not only *what stack*. This moves Skill Bill from stack-aware prompting toward work-type-aware engineering policy. It comes after the core is trustworthy and the loop is running.

## What not to optimize for

- adding stacks faster than the current workflows become trustworthy
- proliferating narrow one-off skills without stable base entry points
- hiding runtime limitations behind optimistic language
- claiming moats the project does not have
- overfitting to one agent at the expense of portability
- turning governance into ceremony that slows useful improvement

## Open questions

1. How much should be shared portable contract vs. agent-specific runtime behavior?
2. What is the right override model for teams that want local standards without breaking the shared taxonomy?
3. How should review quality be measured in a lightweight but honest way?
4. Which workflow should become first-class after the current core?
5. At what point does process routing become a first-class concept?
6. What is the minimum adoption that makes the telemetry loop genuinely informative?
