# SKILL-129 - Super-optimized delegated code review

Created: 2026-07-17
Issue key: SKILL-129
Mode: decomposed

## Intended Outcome

Delegated code review performs repository, scope, diff, project-guidance,
learnings, stack, add-on, and lane discovery exactly once per review lane. A
deterministic parent compiler turns those facts into one schema-valid packet and
direct specialist assignments. Every worker starts isolated, consumes only its
assigned hunks, matched rules, and bounded direct dependencies, and cannot
repeat discovery through repository commands, MCP calls, or broad reads.

Layered platform composition is flattened before launch. In particular, a KMP
review expands its required Kotlin baseline into the selected Kotlin specialist
lanes without launching a Kotlin baseline orchestrator that repeats ceremony and
then launches another generation of workers. Installed native-agent targets are
complete and reconciled, so no dangling baseline links or silent
`general-purpose` fallback can hide a broken installation.

Existing review-context budgets become production enforcement rather than
model-only policy. Parent packet, launch, evidence, expansion, result, tool-call,
turn, and provider-token usage are bounded and attributed. Reports distinguish
fresh, cached, and cumulative provider input so a displayed 90k cumulative
input total is understandable and actionable. Review correctness, required
coverage, independent specialist reasoning, and finding provenance remain
intact while duplicate work and avoidable context growth are eliminated.

## Motivation

SKILL-125 specified bounded review context and introduced a review-context
schema, models, configuration, evidence broker, and token accounting. A live
Codex KMP review still showed a platform specialist consuming about 76k input
tokens and a generic Kotlin baseline worker consuming about 96k. Audit found
that the remaining cost is structural, not rubric size:

- the top-level shell resolves the review, but delegated routed layers still
  receive full scope, routing, ceremony, guidance, learnings, and telemetry
  instructions that invite rediscovery;
- KMP composition launches the Kotlin review orchestrator, which selects and
  launches its own specialists instead of contributing flattened lane
  assignments to the original parent;
- the native-agent bundles declare specialists but not the baseline
  orchestrators, while stale installed orchestrator symlinks may remain dangling
  and cause a generic-worker fallback;
- review budget models and schema exist, but most production native-review paths
  do not validate the packet, launch, evidence, expansion, lane result, and
  provider usage at their real boundaries; and
- the current packet does not fully encode lane decisions, direct-dependency
  allowlists, matched project guidance, or an auditable expansion ledger.

Provider input totals are cumulative across model turns. Even a modest initial
prompt becomes expensive when every tool cycle resends the growing context.
Optimization therefore must reduce both initial payload and subsequent turns,
reads, and tool-output volume.

## Acceptance Criteria

1. One deterministic review preparation service resolves repository identity,
   exact scope, base/head revisions, status, changed paths and hunks, dominant
   stack, composed platform layers, add-ons, applied learnings, applicable
   project guidance, build/test facts, selected lanes, and lane assignments
   exactly once for each top-level review lane.
2. The canonical review-context schema and typed models include stable packet,
   hunk, assignment, rule, dependency, lane-decision, and expansion identifiers;
   direct-dependency allowlists; lane inclusion/exclusion reasons; matched rule
   references or bounded excerpts; evidence targets; and immutable review
   session/run revisions.
3. Packet construction, projection, canonical serialization, digesting, size
   measurement, assignment ownership, and coherence validation are deterministic
   runtime behavior. Invalid or drifting packets fail loudly through typed
   errors before any worker starts.
4. Delegated routed layers have an explicit packet-consumer execution contract.
   They do not repeat status, scope, diff, stack, pack, add-on, guidance,
   learnings, build/test, or telemetry-ownership discovery and are not given
   ceremony instructions that require those operations.
5. Every governed Codex specialist uses `fork_turns: "none"`. Every provider
   receives only the compact specialist contract, one applicable rubric,
   assigned hunks and paths, matched rules, bounded direct dependencies,
   evidence targets, immutable identifiers, and its budget summary.
   Provider-native agents use their embedded governed rubric as the single
   rubric source and are not instructed to reread the sibling sidecar; an
   explicitly supported generic worker receives one projected rubric instead.
6. Specialist execution rejects `git status`, broad `git diff`, base-revision
   discovery, AGENTS traversal, stack routing, learning-resolution MCP calls,
   unselected MCP tools, broad repository search, unrelated rubric loading, and
   unassigned file reads. No worker is responsible for rediscovering facts in
   the authoritative packet.
7. Specialists obtain code through one bounded evidence broker and batch
   assigned evidence requests where practical. An out-of-assignment dependency
   requires a nonblank reachability reason, parent-packet ownership, an allowed
   expansion, and an auditable expansion record.
8. KMP and all other manifest-declared layered reviews compile required baseline
   layers into one ordered flattened launch plan. A KMP review launches selected
   Kotlin baseline specialists directly and never launches a Kotlin baseline
   orchestrator merely to repeat classification and fan out again.
9. Flattening preserves manifest-declared required baseline coverage,
   signal-relevant specialist selection, deterministic lane order, attribution,
   add-ons, independent reasoning, merge/deduplication, severity, confidence,
   and finding evidence.
10. Runtime launch planning drops empty and duplicate lanes, assigns each changed
    hunk only to justified lanes, and reuses immutable shared facts without
    copying the full parent packet or unrelated diff into every launch.
11. The configured review-context policy is enforced on every production review
    path for parent-packet bytes, lane-launch bytes, evidence-result bytes,
    cumulative evidence bytes, assignment expansions, lane-result bytes,
    specialist tool-call count, specialist model-turn count, and provider token
    thresholds where the provider exposes an enforceable seam.
12. Exceeding an enforceable limit terminates only the affected lane with typed
    `review_context_budget_exceeded` evidence. The system does not truncate
    required evidence, widen access, omit required coverage, replace the worker,
    silently fall back to inline/general-purpose execution, or change mode.
13. Post-run provider thresholds that cannot be enforced live produce an
    explicit `budget_regression` outcome. Reports separate direct from inclusive
    ownership and input, cached input, output, reasoning, total, and fresh-token
    approximation without double-counting nested sessions.
14. Default budgets are conservative enough to prevent ordinary bounded
    specialists from silently reaching the observed 76k-96k cumulative-input
    range. Repository overrides remain schema-validated, positive, internally
    coherent, documented, and visible in review metadata.
15. Native-agent bundles, renderers, and install plans have one declared source
    of truth for every worker the launch plan may name. A required logical agent
    cannot be absent, resolve to a dangling link, or be substituted with a
    generic worker.
16. Install reconciliation atomically creates or refreshes selected native-agent
   targets and removes obsolete or dangling managed targets from earlier
   installations. It never deletes an unmanaged user-owned agent file.
   Reconciliation uses the complete managed-link inventory rather than only the
   filenames still generated by the current source bundle.
17. Install and runtime preflight verify that every selected native agent exists,
    resolves to the current installed staging/cache, has the expected logical
    name and content digest, and is readable before delegated review begins.
18. Missing or invalid required native agents fail loudly with a repair command;
    a review never silently labels a `general-purpose` worker as a Kotlin or KMP
    baseline layer.
19. Review summary, status, durable artifacts, and telemetry expose per-parent
    and per-lane launch bytes, evidence bytes, expansions, tool calls, model
    turns, direct/inclusive token ownership, provider token dimensions, fresh
    approximation, terminal outcome, and aggregate totals while persisting no
    prompts, diffs, source, guidance bodies, or tool-output bodies.
20. End-to-end Codex fixtures prove one-time discovery, isolated launches, no
    forbidden child discovery commands or MCP calls, no nested baseline
    orchestrator, bounded batched evidence, exact lane selection, and correct
    accounting for small Kotlin and layered KMP diffs.
21. Regression fixtures reproduce a long parent briefing, full AGENTS guidance,
    overlapping lanes, a dangling baseline-agent link, excessive tool output,
    excessive turns, and cumulative provider input. Each case is either removed
    from specialist context, reconciled before launch, or terminated/reported by
    the appropriate typed budget outcome.
22. Parallel review lanes use the same preparation, flattening, native-agent
    preflight, evidence, and accounting boundaries independently, without
    recursive `parallel:` invocation or duplicate discovery inside a lane.
23. Governed source, generated snapshots, operator documentation, and
    architecture documentation explain the authoritative packet, flattened
    layering, forbidden rediscovery, evidence expansion, native-agent preflight,
    and cumulative-versus-fresh token interpretation.
24. Maintainer validation passes:

    ```bash
    skill-bill validate
    (cd runtime-kotlin && ./gradlew check)
    npx --yes agnix --strict .
    scripts/validate_agent_configs
    ```

## Constraints

- Extend and complete SKILL-125 contracts instead of introducing a competing
  review-context or budget authority.
- Preserve manifest-driven platform composition and user-removable platform
  packs; do not hard-code Kotlin or KMP in the shared runtime.
- Preserve explicit `mode:inline`, `mode:delegated`, and `mode:auto` semantics.
- Keep independent specialist reasoning and required baseline coverage.
- Keep raw repository material in ephemeral bounded evidence surfaces only.
- Keep runtime-agent differences behind strategies; do not add provider identity
  branching to the process runner.
- Follow the authored/generated boundary and run `./install.sh` after governed
  source, rendering, or native-agent generation changes.
- Preserve unrelated working-tree changes already present in the repository.

## Non-Goals

- Removing specialists solely to reduce cost.
- Replacing evidence-backed review with a single generic skim.
- Enforcing cached provider input as though it were fresh token consumption.
- Persisting prompts, complete packets, diffs, source, AGENTS contents, or raw
  tool output in telemetry.
- Optimizing non-review feature-task phases.
- Changing finding severity, confidence, verdict, or merge semantics except to
  preserve attribution through flattened composition.
- Deleting unmanaged user-authored native agents.

## Validation Strategy

Each subtask owns focused unit, contract, and integration coverage for the seam
it changes. The final observability slice adds the cross-seam Codex fixtures but
does not defer packet, broker, composition, or installer correctness tests from
their owning subtasks. Validate generated source boundaries and agent configs
after rendering changes, then run the complete Kotlin and repository gates.

## Next Path

Run `skill-bill goal SKILL-129`.
