# Skill Bill Roadmap

## Why this document exists

Skill Bill can look deceptively simple because most of the repository is Markdown rather than application code. But the project is not just a prompt collection. It is a framework for governed AI-agent behavior: a portable layer of routing, orchestration, review depth, safety rules, authoring contracts, and cross-agent installation for AI-assisted engineering.

The product is that framework. The skills and platform packs shipped in this repo are reference examples — real, validated, ready to use, and meant to be forked or replaced. The governance model is what a team adopts; the packs are what they start from.

Without that distinction, the project drifts into "more skills, more stacks, more prompts" instead of becoming meaningfully more reliable or more useful to teams.

This roadmap exists to keep the bigger picture visible while the repository grows.

## North star

**Make AI-assisted engineering feel like a reliable team capability, not a bag of prompts.**

In practical terms, that means a team should be able to adopt stable commands such as `/bill-code-review`, `/bill-feature-implement`, and `/bill-quality-check` and trust:

- what behavior they will get
- how scope will be interpreted
- how depth will be chosen
- what review guarantees and failure modes exist
- how platform-specific behavior stays consistent with shared entry points

The long-term goal is not only broader stack coverage. The goal is to become the policy and orchestration layer that makes AI behavior portable, governed, and dependable across agents and teams.

## Product thesis

Skill Bill is most valuable when it behaves like infrastructure rather than a prompt dump. The framework is the product — the shell+content contract, the scaffolder, the validator, the cross-agent installer, the manifest-driven discovery. Shipped skills are reference examples that prove the framework works; they are not the thing being adopted.

The clearest structural moat is **cross-agent portability**. Individual pieces of Skill Bill — governed taxonomy, validator-backed rules, skill scaffolders — exist in adjacent projects or could plausibly be bundled by a first-party agent vendor. The piece a first-party vendor structurally cannot bundle is one source of truth synced across competing coding agents. That is the piece to protect.

The repository should continue to optimize for:

- **stable user-facing entry points** instead of proliferating ad hoc commands
- **platform depth behind the router** instead of stack-specific sprawl leaking into the base layer
- **validator-backed rules** instead of tribal knowledge
- **portable behavior across agents** instead of one runtime getting all the quality
- **explicit contracts** instead of implicit prompt folklore
- **predictable failure handling** instead of silent fallback or "best effort" ambiguity
- **framework separable from examples** so teams can fork, replace, or ignore shipped packs without touching governance

## What success looks like

Skill Bill is succeeding when most of the following are true:

1. Teams can install it once and get consistent behavior across supported agents.
2. Shared entry points stay stable even as platform depth grows behind them.
3. Reviews are high-signal, scope-faithful, and understandable enough to influence merge decisions.
4. Feature workflows reliably move from spec to implementation to validation with clear contracts at each step.
5. Project-specific customization is possible without degrading the shared taxonomy.
6. New skills and edits are constrained by tests and validators, not only maintainer judgment.
7. Teams start treating Skill Bill as part of engineering process, not as an experimental side tool.
8. **Non-maintainers author and ship platform packs.** The scaffolder and contract are usable by people who have never read the maintainer's mind. External authorship is the real test of whether the framework is complete.
9. **At least one lighthouse team uses it in production.** One concrete before/after from a real engineering org does more to validate the project than any amount of internal polish.

## Recent milestones

- **SKILL-14 (shipped):** Piloted the shell + content architectural split on `bill-code-review`. The shell at `skills/bill-code-review/` is now platform-independent and owns routing, telemetry, output structure, and contract enforcement; platform-specific reviewer content lives under `platform-packs/<platform>/` and is discovered through the versioned contract at `orchestration/shell-content-contract/PLAYBOOK.md`.
- **SKILL-15 (in progress):** New-skill scaffolder + auto-installer. Turns the shell+content contract into a one-shot authoring flow so first-time authors — including forks — succeed without hand-wiring directories, sidecars, and manifests. Treated as a core product feature, not tooling; external authoring is a success metric the project needs to validate.
- **SKILL-14 follow-up:** Apply the same shell + content split to `bill-quality-check`, `bill-feature-implement`, and `bill-feature-verify` so every stable command benefits from the same governance.
- **Upcoming — examples extraction:** Separate framework from reference packs even more aggressively. The current repo now keeps only the Kotlin and KMP first-party references, but the long-term architectural move is still to make example packs fully separable from the governance system so adoption and forking stay distinct.

## Current position

Today, Skill Bill is strongest in these areas:

- governed naming and package taxonomy
- portable installation across multiple agent environments
- stack-aware routing for code review and quality-check flows
- layered review orchestration with shared and platform-specific contracts
- validation coverage that prevents many forms of repository drift

Today, Skill Bill is still relatively early in these areas:

- reliability under real-world runtime behavior
- organization-level rollout and override strategy
- measurement of review quality and usefulness
- deeper process routing beyond stack detection
- broader team adoption patterns, documentation, and change management

## Strategic priorities

The roadmap below is ordered by importance, not by date.

### 1. Reliability first

The first responsibility of a governed AI workflow repo is to behave predictably.

This means continuing to reduce:

- scope drift between staged, unstaged, PR, and file-based review modes
- orchestration noise such as background-agent bookkeeping failures
- review-mode ambiguity such as unclear inline vs delegated behavior
- false confidence caused by unsupported or partially supported execution paths
- hidden fallbacks that make output look stronger than the actual guarantees

Key outcome:

**A user should be able to trust that the reported behavior matches what actually happened.**

### 2. Make existing workflows truly strong

Adding new languages is useful, but deepening the main workflows matters more.

The highest-leverage flows today are:

- `/bill-code-review`
- `/bill-feature-implement`
- `/bill-feature-verify`
- `/bill-quality-check`
- `/bill-pr-description`

The next phase of maturity is making these workflows feel complete and dependable enough for daily team use.

That means improving:

- signal quality of reviews
- consistency of outputs across agents
- risk classification and prioritization
- completeness checks against specs and acceptance criteria
- end-to-end handoff quality into PRs and team review process

Key outcome:

**A small set of stable commands should cover a meaningful portion of normal engineering work without feeling shallow or flaky.**

### 3. Deepen governance

Skill Bill already has a strong taxonomy bias. The next step is turning that into a fuller operating model.

This includes:

- stronger validation of routing and orchestration contracts
- clearer portability boundaries between runtime-facing skill files and maintainer playbooks
- better rules for overrides, versioning, migration, and deprecation
- explicit support for what is guaranteed vs best effort
- clearer contracts for how stack-specific packages are allowed to diverge

Key outcome:

**Maintainers should be able to evolve the repository without slowly eroding consistency.**

### 4. Support team and org adoption

The project becomes meaningfully more valuable when teams can adopt it as process infrastructure.

That requires more than good skill text. It requires:

- team-friendly documentation
- rollout guidance
- conventions for repository-local overrides
- clear guidance for when to trust, verify, or escalate outputs
- easier onboarding for EMs, tech leads, and senior ICs
- patterns for introducing Skill Bill into code review, feature work, and quality gates

Key outcome:

**Teams should be able to operationalize Skill Bill without each team inventing its own model from scratch.**

### 5. Add measurement and feedback loops

A governed framework needs evidence, not only intuition. Because the shipped skills are reference examples rather than the product, measurement should favor framework-level signals over per-skill output quality.

The signals that most influence where to invest:

- **authoring success rate** — can someone land a new pack using only the scaffolder and the validator, without reading maintainer source?
- **fork health** — do external forks diverge in breaking ways, or extend cleanly?
- **contract drift** — how often does a validator catch something vs. let drift through?
- **cross-agent parity** — do the same commands produce comparable output on each supported agent?
- **team adoption and repeated use** — once installed, does it stick or get abandoned?
- **review usefulness on the reference packs** — kept as a secondary signal, not the primary one, because the shipped packs are demos, not the product.

This does not need to begin as heavy analytics. Even structured manual evaluation of one external fork's first week is more informative than any amount of internal intuition.

Key outcome:

**The project should learn from real authoring and adoption patterns, not only from its maintainer's instincts.**

### 6. Expand from stack routing to process routing

Right now, Skill Bill is strong at answering:

- what stack is this?
- which specialist review layers should run?

Its next level is being able to answer:

- what kind of change is this?
- what process should apply?

Examples:

- migration or schema change
- risky auth/session change
- rollout or feature-flag change
- incident fix
- refactor with behavior-preservation expectations
- design-heavy UI work
- reliability-sensitive infra change

This is a major strategic shift because it moves Skill Bill from stack-aware prompting to workflow-aware engineering policy.

Key outcome:

**Routing should eventually reflect both technical stack and work type.**

### 7. Build reusable organizational memory

One of the most promising directions for Skill Bill is encoding engineering judgment once and reusing it everywhere.

That includes:

- architecture rules
- review heuristics
- rollout practices
- security expectations
- testing standards
- change-management norms
- boundary history and feature history

The goal is not just to ship prompts. The goal is to make team knowledge durable and portable across agent runtimes.

Key outcome:

**Skill Bill should become a reusable container for engineering standards, not only a skill catalog.**

## Roadmap themes

The next set of improvements should cluster around a few durable themes.

### Theme A: Execution reliability

Focus:

- explicit scope contracts
- deterministic execution mode selection
- safer delegated worker tracking
- clearer unsupported-runtime behavior
- less noisy failure handling

Examples of work:

- tighten orchestration rules for parent/child delegated reviews
- add contract tests for common runtime failure patterns
- require more explicit reporting when guarantees are weakened
- reduce accidental broadening of review scope

### Theme B: Review quality and trust

Focus:

- higher signal-to-noise
- better actionability
- fewer speculative findings
- more consistent prioritization

Examples of work:

- improve evidence and minimal-fix expectations
- refine severity guidance
- add more comparison-based evaluation of review outputs
- standardize verdict wording so teams can act on it

### Theme C: Workflow completeness

Focus:

- stronger end-to-end feature implementation flow
- better verification against specs
- cleaner PR handoff
- less manual glue between steps

Examples of work:

- improve feature-to-review-to-quality-check sequencing
- strengthen acceptance-criteria completeness checks
- improve PR summary generation based on actual branch diff
- make "done" states more explicit and less optimistic

### Theme D: Team adoption

Focus:

- easier setup
- clearer documentation
- better organizational customization
- practical team rollout patterns

Examples of work:

- write team adoption docs and examples
- define recommended repo-local override strategy
- document a minimal process integration model for reviews and PRs
- make install/update flows friendlier for non-maintainers

### Theme E: Governance and maintainability

Focus:

- preserve taxonomy clarity
- keep playbooks and runtime-facing files aligned
- make allowed divergence explicit
- protect the project from entropy

Examples of work:

- extend validator coverage
- add more contract tests around routing and sidecar references
- document deprecation and migration policy for skills
- formalize what belongs in base vs platform packages

## Suggested sequencing

This is a recommended sequence of emphasis, not a date-based plan.

### Horizon 1: Stabilize the core

Prioritize:

- review reliability
- scope fidelity
- execution-mode clarity
- delegated orchestration correctness
- stronger tests for existing review flows

This horizon is about making the current system trustworthy enough that adoption does not outpace quality.

### Horizon 2: Make the core workflows excellent

Prioritize:

- stronger review quality
- stronger feature implementation and verification flows
- cleaner PR generation
- better consistency across supported agents

This horizon is about turning the current stable commands into genuinely strong daily tools.

### Horizon 3: Operationalize for teams

Prioritize:

- team adoption documentation
- project-specific customization patterns
- org memory and boundary-history workflows
- process routing for different change types

This horizon is about moving from "useful to an advanced individual" to "safe and valuable for a team."

### Horizon 4: Expand carefully

Prioritize:

- new platforms only when they fit the governance model
- new workflows only when they strengthen the stable command surface
- measurement loops that justify what should be built next

This horizon is about growth without losing coherence.

## What not to optimize for

Skill Bill should resist several attractive but dangerous directions:

- adding stacks faster than the current workflows become trustworthy
- creating many narrow one-off skills without stable base entry points
- hiding runtime limitations behind optimistic language
- overfitting to one agent runtime at the expense of portability
- turning governance into ceremony that slows down useful improvement
- treating every user preference as a new top-level capability

## Open questions worth revisiting

These questions do not need immediate answers, but they should stay visible.

1. How much of Skill Bill should be shared portable contract vs agent-specific runtime behavior?
2. What is the right override model for teams that want local standards without breaking the shared taxonomy?
3. How should review quality be measured in a lightweight but honest way?
4. Which workflows should become first-class after review, implementation, verification, and quality-check?
5. How should Skill Bill communicate levels of confidence and support across different runtimes?
6. At what point should process routing become a first-class concept in the taxonomy?

## Summary

Skill Bill's future is not primarily about becoming a larger collection of skills.

Its future is about becoming a **reliable, portable, governed engineering behavior layer**:

- stable for users
- deep behind the router
- explicit about guarantees
- adaptable across agents
- useful enough for teams to operationalize

That is the bigger picture this roadmap is meant to preserve.
