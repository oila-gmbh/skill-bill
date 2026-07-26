---
status: Complete
issue_key: SKILL-122
source: inline user request
---

# SKILL-122: General agent add-ons

## Intended Outcome

Skill Bill gains a governed, dynamic extension surface for **agent add-ons**:
small, authored instruction modules that apply to a named agent/runtime rather
than to a programming-language platform pack. A user explicitly selects an
add-on for a feature run, Skill Bill validates that selection against the
resolved agent and declared consumer, then supplies the exact rendered add-on
content only to the relevant run context.

The first shipped add-on, `execution-budget`, demonstrates the surface for
Codex-oriented long-running work. It adds concise execution discipline such as
honouring an explicit user stopping boundary, not continuing into unrelated
follow-up work, and using bounded hand-offs. It complements—never replaces—the
repository-wide rule that subagents require an explicit user request.

This is a general framework capability, not a permanent GPT-5.6 tuning table.
It must remain valid as models, context-window policies, and provider UI
controls evolve.

## Background

Platform add-ons are deliberately owned by
`platform-packs/<platform>/addons/` and are selected only after dominant-stack
routing. They are the right extension point for stack-specific review and
feature knowledge, but cannot express cross-stack behaviour that is meaningful
only for a particular agent runtime.

Recent agent behaviour makes that distinction useful: a capable agent can run
too far, repeat broad discovery in child contexts, or fan out work beyond what
the user requested. Those are execution-policy concerns, not Kotlin, KMP, or
TypeScript concerns. They should be selectable and auditable without
hard-coding a transient model name or vendor reasoning setting into every
governed skill.

The existing generated-source boundaries remain non-negotiable: authored
content is separate from rendered `SKILL.md` wrappers and generated pointers;
all discovery, rendering, validation, and desktop presentation remain dynamic
and manifest-driven.

## Decided Behaviour

### Source shape and ownership

Add a new top-level, user-owned extension surface:

```text
agent-addons/<slug>/
  agent-addon.yaml
  content.md
```

`agent-addon.yaml` is a versioned runtime contract. Its minimum governed fields
are:

```yaml
contract_version: "1.0"
slug: execution-budget
description: Explicit stop-boundary and compact-handoff guidance for Codex runs.
agent_ids: [codex]
consumers: [bill-feature]
```

`slug` is lowercase kebab-case and unique across `agent-addons/`. `agent_ids`
is a non-empty, duplicate-free list of registered Skill Bill agent ids.
`consumers` is a non-empty, duplicate-free list of currently supported
consumer identifiers. The first supported consumer is `bill-feature`; its
internal task/goal sidecars receive the already-resolved selection rather than
being independently selectable. Unknown agents, consumers, manifests, content
files, contract versions, duplicate slugs, and malformed fields fail loudly
with typed contract errors before a run, render, or install can proceed.

The schema lives at
`orchestration/contracts/agent-addon-schema.yaml`. It follows the repository
runtime-contract recipe: Draft 2020-12 YAML-authored JSON Schema,
`AGENT_ADDON_CONTRACT_VERSION` parity, an
`InvalidAgentAddonSchemaError` extending `ShellContentContractException`,
loud-fail parse seams, and a configuration-cache-friendly classpath copy task.
Cross-field checks document and enforce source-directory/slug parity, source
file presence, unique declaration identity, valid registered agents, and valid
consumer declarations.

Agent add-ons are neither skills nor platform-pack add-ons. They do not appear
as top-level installable skills, may not contain generated `SKILL.md`, support
pointer files, or provider-native output, and may not be placed under
`platform-packs/<platform>/addons/`. `content.md` is the sole ordinary authored
instruction body; it uses H2 sections rather than a private sidecar taxonomy.

### Explicit invocation and selection

`bill-feature` accepts zero or more explicit arguments:

```text
agent-addon:<slug>
```

For example:

```text
/bill-feature SKILL-122 agent-addon:execution-budget
```

No agent add-on is auto-enabled from a model name, reasoning level, installed
provider, repository config, or current time. Omission preserves current
behaviour exactly. Repeated selections are applied in caller order; a duplicate
slug, malformed token, unknown slug, or an add-on incompatible with the
resolved run agent fails before the router's one confirmation gate, workflow
opening, or child launch. A selected add-on must support every explicitly
configured phase agent that will receive its instructions; it is never silently
dropped for an overridden phase agent.

The confirmation gate lists the selected slugs and their one-line manifest
descriptions alongside mode, review mode, and any parallel lane. `bill-feature`
forwards the ordered, validated selection unchanged to single-task and goal
routes. The feature-task router, runtime CLI, prose workflow, runtime workflow,
and decomposed-goal child launches consume that resolved selection; none reparse
or reinterpret user tokens.

The resolved durable state records each slug, source identity, and a content
digest. A retry, review-fix loop, audit re-entry, continuation, or resumed goal
must reuse that exact ordered selection. If the selected source is missing or
its content digest no longer matches the durable run, the resume fails loudly
instead of applying changed policy mid-workflow. A new run may select the
updated source normally.

### Injection and precedence

At install/render time, manifest-declared agent add-ons are discovered
dynamically and materialized as generated pointers only in the staged consumer
skill directories they declare. Those pointers, their source declarations, and
their target contents participate in staging identity/hash calculation. No
generated pointer is committed under `skills/`.

At invocation time the consumer resolves only the explicitly selected pointers,
reads their authored content, and attaches it to the relevant parent/phase
briefing as an ordered, labelled agent-add-on section. The system must not load
or inject every add-on merely because it is discoverable. Runtime-launched
agents receive the same resolved contents and provenance as prose execution;
parallel review lanes and nested workers receive an add-on only when their
declared consumer and effective agent are compatible.

Add-on instructions are additive and lower precedence than user intent,
repository `AGENTS.md`, governed skill contracts, platform-pack contracts,
and runtime safety rules. An add-on may narrow or structure work but may not
override confirmation rules, perform irreversible actions without existing
authority, suppress required validation/review, bypass typed failure behaviour,
or autonomously author a new delegation permission. The existing global
subagent policy stays the hard baseline and is not duplicated as a model-only
exception.

### Initial `execution-budget` add-on

Ship `agent-addons/execution-budget/` as a real validated example with
`agent_ids: [codex]` and `consumers: [bill-feature]`. Its `content.md` provides
short, provider-neutral execution guidance appropriate for Codex:

- honour a user-specified stopping boundary and report completion at it;
- do not extend a confirmed scope into PR babysitting, extra review cycles, or
  unrelated follow-up work without a new user request;
- use the durable compact continuation/artefact contracts and scoped reads
  instead of re-pasting or rediscovering prior context; and
- use subagents only when the user has explicitly requested delegation, as
  required by the repository policy.

The add-on does not change the model, reasoning level, speed mode, context
window, compaction threshold, tool permissions, feature-review policy, or
default workflow terminal phase. It must not introduce a generic free-form
prompt-injection API or a second confirmation gate. A future feature may add a
typed `stop-after` workflow control if product requirements warrant a durable
phase-level primitive; that control is intentionally outside SKILL-122.

### Authoring, discovery, and maintenance

Extend dynamic discovery, `skill-bill show`/`explain`, repository validation,
install staging, and the desktop tree so agent add-ons are visible as a
distinct extension category with manifest metadata and authored content. The
scaffolder receives an `agent-addon` kind that atomically creates the new
source pair after validating slug, supported agents, and consumers. It may
offer `execution-budget` as an example but must not scaffold provider-native
outputs or edit a generated wrapper.

Update `docs/skill-source-generation.md`, the source validator, the scaffold
payload contract, repository catalog/readme material, and relevant agent/config
documentation to state the distinct ownership and generated-output boundaries.

### Desktop application visibility

The Skill Bill desktop application must show agent add-ons in the left
navigator as a first-class **Agent Add-ons** group, separate from the existing
platform **Add-ons** group. Every valid source produces a dynamic item keyed by
its manifest slug. Selecting the item opens the authored `content.md` in the
normal editor/inspector and exposes read-only metadata for description,
supported agent ids, declared consumers, source path, and validation status.
The manifest must be reachable from the same item or as a documented sibling
artefact so authors can inspect the complete governed declaration.

The desktop tree is driven by the shared runtime discovery model; it must not
scan `agent-addons/` independently or special-case `execution-budget`.
Malformed discovered source is represented with clear validation status and
actionable diagnostics rather than hiding unrelated valid sources. Refreshing
after scaffolding, editing, or validation preserves normal tree-selection
semantics. The existing New/Add-on flow gains an explicit **Agent add-on**
choice with only the manifest fields applicable to this new source shape.

The desktop application is not a feature-run launcher in this scope. It
surfaces and authors agent add-ons; `agent-addon:<slug>` remains the explicit
feature-run selection mechanism.

## Scope

- A versioned agent-addon manifest/schema, typed loading model, validation, and
  source discovery rooted at `agent-addons/`.
- Explicit feature-facing selection, one-time compatibility resolution,
  confirmation rendering, durable state, and propagation through runtime,
  prose, and decomposed-goal flows.
- Dynamic staging/rendered pointers and content-hash invalidation for declared
  consumers.
- The first `execution-budget` Codex add-on and an atomic scaffolding path.
- CLI, desktop, renderer/install, contract, and regression coverage for the
  new surface.

## Non-Goals

- Automatically inferring or enabling add-ons from GPT, Codex, model-version,
  reasoning-level, context-window, or provider configuration.
- Replacing platform-pack add-ons, external platform add-on sources, agent
  native-agent strategies, project `AGENTS.md`, or skill contracts.
- Letting add-ons alter model controls, manually cap context/compaction, grant
  tool permissions, bypass approvals, suppress validation, or override review
  routing.
- Supporting external agent-addon directories, per-user automatic defaults,
  free-form add-on arguments, or a generic persisted `stop-after` field in
  this feature.
- Applying arbitrary add-ons to every existing skill before that skill family
  declares itself as a supported consumer.
- Selecting or toggling agent add-ons from a feature-run control in the desktop
  app; this desktop scope is discovery and authoring only.

## Acceptance Criteria

1. A governed `agent-addons/<slug>/agent-addon.yaml` plus `content.md` source
   shape is dynamically discovered without hard-coded add-on names; it is
   distinct from skills and platform-pack add-ons and contains no committed
   generated wrapper, pointer, or provider-native output.
2. `agent-addon-schema.yaml` is a classpath-bundled Draft 2020-12 runtime
   contract with a pinned Kotlin version constant, parity test, typed invalid
   schema error, strict field validation, and documented/enforced coherence
   rules for slug/source parity, duplicates, content presence, agent ids, and
   consumers.
3. `bill-feature` accepts ordered `agent-addon:<slug>` selections, rejects
   malformed, duplicate, unknown, and agent-incompatible requests before its
   sole confirmation gate, and leaves calls with no selection behaviourally
   unchanged.
4. The feature confirmation gate reports selected add-ons. The resolved ordered
   set is forwarded unchanged and persists slug, source identity, and content
   digest through prose and runtime single-task workflows, retries, resumes,
   review/audit re-entry, and decomposed-goal child workflows.
5. A resume with a missing selected source or changed source digest fails loudly
   before applying changed instructions; it never silently substitutes a new
   add-on body, drops the add-on, or changes its order.
6. Render/install dynamically generates declared consumer pointers and folds
   their declarations and target contents into staging hash identity. Source
   trees remain free of generated pointers, and unselected add-ons are not
   injected into a run.
7. Every phase or worker receiving a selected add-on gets the same ordered,
   labelled content and provenance in prose and runtime modes. Any explicit
   phase-agent override incompatible with the add-on fails before launch rather
   than receiving unsuitable instructions or silently omitting them.
8. Add-on precedence is enforced: user intent, repository instructions,
   governed/runtime contracts, and platform contracts win. Add-ons cannot grant
   delegation authority, create another confirmation gate, bypass mandatory
   review/validation, alter model controls, or weaken loud-fail contracts.
9. `execution-budget` is shipped as a valid Codex + `bill-feature` add-on and
   instructs bounded execution and compact hand-offs without changing model
   settings or the default workflow endpoint.
10. `skill-bill new` supports an atomic `agent-addon` scaffold; validation,
    explain/show/catalogue, installation, and the desktop extension tree expose
    agent add-ons dynamically with clear failures for invalid sources.
11. The desktop app has a distinct **Agent Add-ons** navigator group. Each
    dynamic item exposes manifest metadata and opens the authored body; invalid
    sources remain diagnosable without suppressing valid items, and the New
    flow offers an Agent add-on source choice without conflating it with a
    platform add-on.
12. Focused positive and negative tests cover schema validation, discovery,
    rendering/install hashes, selection/confirmation, incompatible agents,
    durable resume drift, prose/runtime/goal propagation, no-selection
    compatibility, scaffold rollback, and desktop/CLI visibility. The standard
    repository validation commands pass after generated staging is refreshed.

## Decomposition

This feature is a dependency-ordered decomposed goal:

1. Define the source, manifest, typed contract, and validation boundary.
2. Add discovery, render/install staging, dynamic catalogue, and scaffold
   support for the governed extension surface.
3. Add explicit feature selection, compatibility resolution, durable state, and
   prose/runtime/goal prompt propagation.
4. Ship the `execution-budget` add-on and integrate UX/docs/desktop exposure.
5. Exercise the cross-boundary regression matrix, refresh installation staging,
   and run all required validation.

Subtasks 2 and 3 depend on the canonical contract in subtask 1. Subtask 4
depends on the source/delivery and selection paths. Subtask 5 is the final
integration gate and depends on every implementation subtask.

## Validation Strategy

Run focused contract, renderer/install, scaffold, CLI, runtime workflow, and
desktop tests while implementing each subtask. Before handoff run:

```bash
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
```

Inspect rendered installed `bill-feature` output to confirm generated pointers
exist only in staging, and exercise an explicit `agent-addon:execution-budget`
selection plus an unchanged no-add-on invocation.

## Next Path

Implement in dependency order with:

```bash
skill-bill goal SKILL-122
```
