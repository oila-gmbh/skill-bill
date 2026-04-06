---
name: bill-go-code-review-architecture
description: Use when reviewing architecture, boundaries, package design, and source-of-truth consistency in Go backend or service changes. Use when user mentions Go architecture, package design, cmd/ layout, internal/ boundaries, or dependency direction in Go.
---

# Architecture Review Specialist

Review only high-signal architectural issues.

Focus on package boundaries, dependency direction, module ownership, source-of-truth consistency, sync/async boundaries, and architectural drift. Ignore formatting/style-only and naming preferences without architectural impact.

Apply shared architecture rules to every review. Apply deeper concern-specific checks only when the changed code touches those areas.

## Project Overrides

If `.agents/skill-overrides.md` exists in the project root and contains a `## bill-go-code-review-architecture`
section, read that section and apply it as the highest-priority instruction for this skill. The matching section may
refine or replace parts of the default workflow below.

If an `AGENTS.md` file exists in the project root, apply it as project-wide guidance.

Precedence for this skill: matching `.agents/skill-overrides.md` section > `AGENTS.md` > built-in defaults.

If `.agents/feature-specs/<feature-name>.md` or an equivalent saved feature spec is available and contains an expected
architecture shape, review the implementation against that saved shape as well.

## Project-Specific Rules

### Shared Architecture
- Keep business logic independent from transport and storage concerns unless the project intentionally uses a simpler shape
- Dependencies must point inward toward stable business rules, not outward toward frameworks or concrete infrastructure details
- Preserve a single source of truth for each important piece of business state; avoid duplicated ownership across packages
- Do not leak driver, ORM, HTTP, or protobuf-generated details across unrelated boundaries when that creates tight coupling
- External systems (network, database, messaging, file system) should sit behind explicit adapters, repositories, clients, or gateways
- Prefer explicit dependencies and visible wiring over hidden globals, package-level singletons, or init-driven magic that obscures ownership
- Avoid non-trivial package-level `init()` side effects for wiring, network setup, worker startup, or registry mutation when explicit constructors or startup hooks would keep ownership visible

### Go Package / Interface Checks
- Package names should stay short, lowercase, and domain-specific; avoid catch-all packages like `util`, `common`, or `helpers`
- Interfaces should usually live on the consuming side and stay minimal; do not introduce broad interfaces only for speculative abstraction
- `cmd/` entrypoints should stay thin and delegate to real application packages rather than accumulating business logic
- `internal/` boundaries should not be bypassed by moving shared logic into higher-level packages just for convenience
- Avoid package cycles, transitive dependency tangles, and public APIs that expose internal implementation details without intent
- Constructors and dependency wiring should make ownership explicit; avoid hiding lifecycle management in side-effect imports or init hooks
- Exported and unexported seams should express real ownership; do not leak mutable internals through broad `any`, reflection, or callback escape hatches just to bypass package boundaries
- Avoid `any`-heavy public boundaries and repeated type assertions/type switches when concrete types or smaller interfaces would preserve clearer package contracts and compile-time safety

### Data Ownership / Coordination
- Repository and gateway boundaries must not leak transaction handles, raw rows, or concrete driver state into higher layers unless that is an explicit architectural choice
- Cross-package composition should happen in coordinators or use-case layers, not by reaching directly into another package's internals
- Hot or repeated cross-boundary reads should prefer explicit read helpers, caches, or batched calls over convenience coupling
- One business operation should have one clear orchestration owner and one clear transaction owner unless partial completion is explicitly intended

### Events / Background Work / Integration Architecture
- Background workers, subscriptions, and event handlers should converge on the same use-case boundaries instead of duplicating business logic in multiple package paths
- Publish-after-commit flows should preserve the intended consistency model; do not blur durable state changes and side effects accidentally
- Projectors, consumers, and rebuild flows should update derived state only and avoid quietly becoming primary business workflow owners
- Event-triggered or job-triggered paths should not bypass validation, authorization, or domain invariants enforced by the primary path
- Packages that start goroutines, subscription loops, or watchers should expose explicit lifecycle ownership and shutdown paths instead of relying on hidden package globals or fire-and-forget startup

### Transport / Entry-Point Orchestration
- HTTP handlers, RPC servers, and CLI entrypoints should stay thin: parse input, derive context, call a use case, map the response
- Entry points should not become hidden composition roots for cross-package reads, transaction management, or retry-sensitive workflows when the architecture expects dedicated application boundaries

## Output Rules
- Report at most 7 findings.
- Include the affected architectural surface for each finding when possible.
- Include `file:line` evidence for each finding.
- Severity: `Blocker | Major | Minor`
- Confidence: `High | Medium | Low`
- Include a minimal, concrete fix.

## Output Format

Start with a short review summary:

```text
Shared sections applied: Shared Architecture, Go Package / Interface Checks
Relevant deeper sections applied: Events / Background Work / Integration Architecture
```

Then list findings using this exact bullet format for downstream tooling:

```text
- [F-001] <Severity> | <Confidence> | <file:line> | <description>
```

Do NOT use markdown tables, numbered lists, or any other format for findings.
