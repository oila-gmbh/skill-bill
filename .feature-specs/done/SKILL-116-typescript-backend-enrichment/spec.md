# SKILL-116 - TypeScript Platform Pack: Backend, DI, Offline-First & AI Depth

## Outcome

Deepen the TypeScript platform pack with expert, enforceable guidance for
server-side TypeScript built on decorator-based dependency-injection frameworks
(NestJS-style), plus three opt-in capability clusters that today's ten always-on
specialists cover only shallowly: dependency-injection backend architecture,
offline-first sync backends, and LLM/agentic backends. The enrichment must land
in the correct delivery vehicle for each concern — **existing code-review
specialists**, the **code-check quality gate**, or new **addons** — with the
implementation making the final placement decision against the criteria in this
spec.

All content stays generic to the TypeScript/Node ecosystem and the named public
frameworks. No single company, product, repository, employee, or internal ticket
may appear anywhere in authored pack content or in this spec.

## Source Reference

Author from the sanitized knowledge artifact committed with this spec:

- `reference/typescript-backend-patterns.md`

It captures the architecture, patterns, and the empirically-observed code-review
signals of a mature production TypeScript backend (a NestJS monolith exposing a
web GraphQL API, an offline-first mobile GraphQL API, and a public REST API),
plus a review-comment analysis of ~100 of its pull requests. It is **background
knowledge only**: it is already obfuscated (no project/company/repo name, no
usernames, no PR numbers, no internal ticket IDs) and must not be copied
verbatim into any specialist, addon, or the quality checker. Every rule drawn
from it must be re-expressed generically, gated on a concrete runtime/framework
condition, and stated as a reachable failure mode.

## Scope

Treat this as an elevation on top of SKILL-111/SKILL-114: the ten TypeScript
specialists and the code checker already exist and already meet the maintained
substance bar. This work adds server/DI/offline/AI depth without regressing that
bar or the cross-pack duplication thresholds.

### Placement decision framework (the core deliverable)

The implementation MUST classify every candidate rule from the source reference
into exactly one delivery vehicle, using these tests:

1. **Existing code-review specialist** — the concern is (a) broadly true for
   TypeScript/Node backends regardless of a specific framework or capability
   being present, and (b) squarely within an existing area's charter. It belongs
   in that specialist's `Project-Specific Rules`. Examples of fit: data-access
   layering and repository purity (`persistence`/`architecture`); single-choke
   authorization, 401-vs-403, log redaction, privileged-identity fallback on
   async consumers, shared-cache tenant isolation (`security`); idempotent
   retryable consumers, bounded fan-out, circuit-breaking, thin events
   (`reliability`); wire envelope / pagination-contract / schema-description /
   nullability stability (`api-contracts`); `orderBy` allowlist + `LIKE`/`ILIKE`
   wildcard escaping, transaction/soft-delete/timestamp discipline
   (`persistence`); integration-first, no-owned-mock, behavior-named,
   assert-outcomes testing (`testing`); container singleton-vs-request scope and
   mutable-singleton tenant bleed, cycle-breaking module surfaces vs
   `forwardRef`, adapter-must-not-own-domain (`architecture`); event-loop / N+1 /
   raw-vs-native-query cost (`performance`); `any`/escape-hatch and
   stringly-typed-vs-enum precision (`platform-correctness`).

2. **Code-check quality gate** — the concern is a *runnable verification or a
   command-derivation behavior*, not a review judgement. It belongs in
   `quality-check/bill-typescript-code-check/content.md`. Examples of fit: honor
   a repository install-guard / dependency-firewall wrapper instead of raw
   `npm install`/`ci` and never incidentally mutate lockfiles; discover and run
   repository-owned **custom lint rules/plugins** as first-class gates and never
   disable them to pass; run the DI-container application-module boot/wiring
   smoke check when the repository owns one, so a broken provider graph fails the
   gate rather than only at runtime.

3. **Addon** — the concern is a *cohesive, opt-in capability cluster* that (a) is
   only present in some TypeScript backends, (b) is gated by detectable diff
   signals, and (c) would bloat an always-on specialist if inlined. Addons fold
   their findings into existing specialists (no parallel review lane) exactly as
   the iOS `offline-first` addon does. See required addons below.

Ambiguous rules default to a specialist over an addon, and to a specialist over
the code checker, so always-on coverage is preserved and addons stay lean.

### Required addons

Author under `platform-packs/typescript/addons/` following the iOS addon shape
(a governed review-index file plus companion topic files; generic to the
capability; encodes recurring failure modes, not any project's internals):

1. **`di-backend`** (decorator-DI backend architecture) — activates when the diff
   touches decorator-DI framework surfaces (`@Module`/`@Injectable`/provider
   registration, resolver/controller adapters, command/query handlers, request
   scope). Cluster: provider scope (singleton/request/transient) and **scope
   propagation upward**; mutable singleton state as a cross-tenant bleed vector;
   module cycle-breaking via a smaller exposed surface (`*Core`/`*Shared`-style)
   versus `forwardRef` masking; thin-adapter-over-coordinating-handler layering,
   handler-never-calls-handler, resolver-never-touches-persistence; ask-don't-
   tell permission providers; execution-context injection vs param threading.
   Folds into `architecture`, `reliability`, `security`.

2. **`offline-first-sync`** (TypeScript/GraphQL offline-first backend) — activates
   when the diff shows a timestamp/cursor change-feed (`since` + cursor), a
   `created/updated/deleted` sync response, a recoverable-`problems` response
   union, conflict reconciliation, or client-provided-id create/update. Cluster:
   server-vs-client timestamp authority and conflict detection; idempotent,
   retry-safe, revert-into-each-other create/update; recoverable `problems`
   channel inside `data` vs transport `errors`; explicit-mapper transforms with
   no `as` casting; composite change-feed index shape. Folds into `persistence`,
   `reliability`, `api-contracts`.

3. **`ai-llm-backend`** (LLM/agentic backend) — activates when the diff touches an
   LLM SDK/gateway, embeddings, a vector store, a reranker, an agent/tool loop,
   or prompt/token budgeting. Cluster: single-entry model-gateway indirection
   with provider/model routing and circuit-breaking over deployments; keeping the
   gateway out of request scope (explicit user context vs scope cascade);
   thin-event durable indexing pipelines and idempotent re-index; RAG retrieval
   correctness (tenant metadata filter, hybrid search, rerank); streaming via
   pub/sub subscriptions; cost/token-budget and structured model-resolution
   logging; eval/experiment isolation. Folds into `reliability`, `security`,
   `performance`, `api-contracts`.

The implementation MAY additionally split a `graphql-server` or `public-rest-api`
addon out of `api-contracts` if, and only if, keeping that material in the
always-on specialist would breach the substance or duplication gate; otherwise
that material stays in `api-contracts`/`security`/`performance`. Record the
decision and its rationale in `agent/history.md`.

## Acceptance Criteria

1. A written placement decision — every candidate rule from the source reference
   is assigned to exactly one vehicle (named specialist, code checker, or named
   addon) with a one-line rationale, recorded in `agent/history.md` and reflected
   in the shipped files. No candidate concern is silently dropped.
2. Enriched specialists still satisfy the maintained substance gate (effective
   area coverage, ≥3 platform-specific failure-mode clusters, ≥10 substantive
   enforceable rules, concrete mechanism/API evidence, zero generic
   `` `TypeScript ... APIs` `` placeholders) and keep the existing
   `Focus`/`Ignore`/`Applicability`/`Project-Specific Rules` structure and the
   per-rule "state the concrete failure; verify the named signal before
   reporting" form.
3. New backend rules are expressed with TypeScript/Node/framework-specific
   mechanisms (event loop, `@Injectable` scope, decorator-DI module graph, the
   ORM/query-builder client, GraphQL SDL, ESM/CJS) so the pack stays under **35%
   shared normalized five-word sequences** and no corresponding authored-rubric
   pair (e.g. against Rust/Kotlin/Go/Python/PHP) exceeds **65% similarity**.
4. `platform-packs/typescript/platform.yaml` declares each new addon under
   `addon_usage` (keyed by the specialists it folds into, with `slug`,
   `entrypoint`, and `companion_pointers`) and adds the matching addon pointer
   targets under `pointers`, mirroring the iOS declaration exactly. `contract_version`
   and `area_metadata` are updated if area focus strings change.
5. Each addon is opt-in and self-gating: a review-index file with explicit
   activation signals and a "do not activate when…" clause, a section index that
   directs the reviewer to only the matching companion topic files, and a "fold
   findings into <named specialists>, do not open a parallel lane" instruction.
   Addons contain no standalone review command and no orchestration duplication.
6. The code checker gains repository-derived guidance for install-guard /
   dependency-firewall wrappers (no raw `npm install`/`ci`, no incidental
   lockfile mutation), repository-owned custom lint rules/plugins run as
   first-class non-suppressible gates, and a DI-container boot/wiring smoke check
   when the repository owns one — without weakening policy, installing tools, or
   narrowing the support matrix, and keeping its required sections.
7. No company, product, repository, person, username, PR number, or internal
   ticket identifier appears in any authored pack file, `platform.yaml`, this
   spec, or the committed reference artifact.
8. Manifest metadata, native agents, pack tests, and an `agent/history.md` entry
   record the enriched TypeScript boundary and the addon additions.
9. `skill-bill validate`, the TypeScript pack tests, the maintained-pack
   substance/duplication audit for TypeScript, and
   `(cd runtime-kotlin && ./gradlew check)` all pass.

## Non-Goals

- No React/DOM-only framing; this elevation is server/DI/offline/AI, and the
  `ui`/`ux-accessibility` specialists are out of scope except where a focus
  string must change to accommodate an addon fold-in.
- No assumption that any one framework (NestJS), transport (GraphQL), datastore,
  vector DB, or model provider is universal; every rule and addon is gated on
  detectable signals.
- No new cross-platform "backend content module" and no edits to other packs'
  content; duplication is avoided by TypeScript-specific phrasing, not by
  centralizing backend prose.
- No hard-coded routing behavior for the new addon slugs beyond the declarative
  `addon_usage`/`pointers` blocks.
- No verbatim copy of the source reference artifact into shipped pack content.

## Constraints

- Follow the maintained substance standard and audit at
  `orchestration/review-orchestrator/` (SKILL-114 subtask 1) as the gate of
  record; do not add a permanent exemption.
- Reuse shared orchestration (review-orchestrator, shell-ceremony,
  telemetry-contract, specialist-contract) via pointers; do not re-embed
  universal evidence/severity/output/scoping/dedup/fix-contract rules into pack
  prose or addons.
- Addons must match the governed iOS addon precedent
  (`platform-packs/ios/addons/*`) in shape, tone, and declaration.
- Keep the reference artifact obfuscated; if any identifier is discovered in it
  later, scrub it rather than propagating it.

## Validation Strategy

Run the maintained-pack substance/duplication audit for TypeScript, the focused
TypeScript pack tests (including addon declaration/pointer rendering),
`skill-bill validate`, and `(cd runtime-kotlin && ./gradlew check)`. Manually
diff each new/edited specialist and the code checker against the corresponding
rubric of at least one other pack to confirm the <65% pairwise and <35% shared
thresholds hold before completion.

## Dependency Notes

Depends on SKILL-111 (TypeScript pack exists) and SKILL-114 subtask 1 (substance
standard + audit gate). Independent of other pack elevations. The addon
mechanics reuse the SKILL-12 addon system and the iOS addon precedent.
