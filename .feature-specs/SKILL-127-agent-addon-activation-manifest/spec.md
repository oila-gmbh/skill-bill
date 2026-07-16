---
status: Draft
issue_key: SKILL-127
source: inline user request
---

# SKILL-127: Agent Add-on Activation Manifest

## Intended Outcome

Skill Bill extends agent add-ons from an explicit-only feature-run mechanism into
a typed activation system with a session-visible catalogue. Every supported
agent session can discover which add-ons exist, what each add-on is for, which
agent and consumer surfaces it supports, and whether the add-on is an
initializer or contextual add-on, without loading all add-on instruction bodies
up front.

Initializer add-ons are loaded at the beginning of each compatible agent session.
Contextual add-ons remain discoverable through the catalogue and are loaded only
when the active work context matches their declared usage conditions, such as a
KMP Compose UI task using a Material 3 guidance add-on. Existing add-ons migrate
as contextual add-ons by default, with explicit initializer exceptions for
Codex's agent/delegation policy and the GLM/peak-hours warning.

The add-on content body remains governed by the existing
`agent-addons/<slug>/content.md` source contract. The new catalogue contains
metadata and usage guidance only; it must not duplicate full add-on content or
become a free-form prompt-injection channel.

## Background

SKILL-122 introduced agent add-ons as explicit `agent-addon:<slug>` selections.
That model is safe and durable, but it does not help a fresh Codex session learn
that an add-on exists unless the user already knows to request it. It also cannot
express that some add-ons should be automatically loaded at session start while
others should be suggested or loaded only for matching work.

The current `execution-budget` add-on is a Codex-only example. It is installed
as a generated sidecar inside the `bill-feature` skill and is injected into
feature-task prompts only when an explicit resolved selection reaches the
runtime. It is not read at the beginning of ordinary Codex sessions and was not
applied to the current `SKILL-123` goal run because no
`agent-addon:execution-budget` token was selected.

## Product Decisions

1. **Add-on catalogue is metadata only.** The session-visible manifest lists
   names, short descriptions, type, compatible agents, compatible consumers, and
   concise "when to use" guidance. Full `content.md` bodies remain separate and
   are loaded only by the governed activation path.
2. **Add-on type is explicit.** Every add-on declares exactly one activation
   type. The initial supported values are `initializer` and `contextual`.
3. **Existing add-ons become contextual by default.** Existing agent add-ons,
   including `execution-budget`, migrate to `activation.type: contextual`
   unless this spec names them as initializer policy.
4. **Initializer means auto-load content.** An initializer add-on is loaded at
   the beginning of every compatible agent session through a managed startup
   surface. It must still obey precedence rules: user intent, system/developer
   instructions, repo `AGENTS.md`, governed skills, platform packs, and runtime
   safety contracts remain higher authority.
5. **Contextual means discoverable, then selected.** A contextual add-on is
   never injected just because it exists. Its catalogue metadata tells the
   agent when to load it; loading the content goes through the same validation,
   compatibility, provenance, and digest checks as explicit selections.
6. **Existing explicit tokens still work.** `agent-addon:<slug>` remains valid
   for `bill-feature` and continues to produce a durable resolved selection.
   Explicit selection of a contextual add-on forces it into the run when
   compatible. Explicit selection of an initializer is allowed and idempotent
   with startup loading; the content must not appear twice in a child prompt.
7. **Catalogue installation is provider-aware.** Skill Bill renders one canonical
   catalogue under `~/.skill-bill/` and installs or links provider-specific
   startup pointers only for agents that support such a surface. Codex support
   is required in this feature. Other agents may be implemented through the
   same manifest-driven renderer or reported as unsupported with clear
   diagnostics.
8. **No content leakage into telemetry.** Catalogue telemetry may include slug,
   activation type, compatible agent ids, and content digest. It must not emit
   add-on body content or absolute user paths.
9. **Codex agent policy is an initializer.** The Codex policy that subagents or
   delegated workers are used only when the user explicitly requests delegation
   is model/session baseline behavior and must be loaded at the beginning of
   Codex sessions.
10. **GLM/peak-hours warning is a ZCode initializer add-on.** The existing
   peak-hours warning behavior becomes a ZCode-specific agent add-on so
   configured GLM/peak-hour guidance is available as ZCode session-start policy
   rather than a feature-skill-only sidecar.

## Manifest Model

Extend `agent-addons/<slug>/agent-addon.yaml` with required activation metadata:

```yaml
contract_version: "1.1"
slug: execution-budget
description: Keeps feature work within the stopping boundary explicitly set by the user.
agent_ids:
  - codex
consumers:
  - bill-feature
activation:
  type: contextual
  use_when: Apply when the user asks Codex to keep feature work within an explicit stopping boundary or avoid unrelated follow-up execution.
  context_tags:
    - execution-boundary
    - codex
```

For contextual add-ons:

```yaml
activation:
  type: contextual
  use_when: Apply when implementing or reviewing Kotlin Multiplatform Compose UI that must follow Material 3 component, theming, and accessibility conventions.
  context_tags:
    - kmp
    - compose-ui
    - material3
```

`activation.type` is required and must be one of `initializer` or `contextual`.
`activation.use_when` is required, concise, non-empty prose intended for the
catalogue. `activation.context_tags` is optional and allowed only for
contextual add-ons; it contains lowercase kebab-case tags used for catalogue
filtering and future routing.

The schema version bump is intentionally loud: new runtime code must reject a
new add-on declaration missing activation metadata, while migration tooling may
upgrade existing add-on sources in this repository to contextual declarations
except for the named initializer policy add-ons.

## Required Initializer Add-ons

Create or migrate these initializer add-ons:

1. `codex-agent-policy`
   - `agent_ids: [codex]`
   - `consumers: [session, bill-feature]` or the equivalent validated consumer
     model introduced by this feature
   - `activation.type: initializer`
   - `use_when`: always at Codex session start to preserve the rule that Codex
     does not delegate to subagents unless the user explicitly requested
     delegation.
2. `peak-hours-warning`
   - `agent_ids: [zcode]`
   - `activation.type: initializer`
   - `use_when`: always at ZCode session start and before feature/goal launch to
     evaluate configured GLM or peak-hour model windows and surface the warning
     when a configured provider/model match is active.

The peak-hours initializer replaces ad hoc generated `peak-hours-warner.md`
ceremony wiring for ZCode flows. Existing feature launch behavior must remain
compatible during migration, but the source of truth for GLM/ZCode peak-hour
guidance becomes the agent add-on declaration and content.

## Session Catalogue

Render a canonical metadata catalogue at install time:

```text
~/.skill-bill/agent-addons/catalog.md
```

The catalogue contains one bounded entry per add-on:

- slug
- description
- activation type
- compatible agent ids
- compatible consumers
- `use_when`
- context tags, when present
- source identity label and content digest
- how to request or load the add-on through Skill Bill

The catalogue must not include the full add-on body. It may point to the
governed loader command or generated sidecar name for compatible consumers, but
the agent must not bypass the loader by reading arbitrary source paths.

For Codex, install a managed startup pointer so every new Codex session receives
the catalogue-reading instruction and can inspect the catalogue early. The
implementation must use the documented Codex startup surface available in this
environment; if Codex exposes no stable global startup file, the feature must
fall back to a Skill Bill-managed session bootstrap that is injected into every
Skill Bill-launched Codex child and report ordinary manual Codex sessions as
unsupported rather than pretending they are covered.

## Activation Semantics

Initializer add-ons:

- are compatible only with declared `agent_ids`;
- are loaded once at the beginning of a compatible session;
- are included in Skill Bill-launched child prompts without requiring an
  explicit `agent-addon:<slug>` token;
- are deduplicated against explicit selections and inherited durable selections;
- fail loudly when their source is missing or digest verification fails.

Contextual add-ons:

- appear in the catalogue for compatible agents;
- are not loaded automatically at session start;
- can be explicitly selected with `agent-addon:<slug>`;
- can be loaded by a governed contextual loader when the agent determines the
  active task matches `use_when` and `context_tags`;
- must record the same ordered slug, source identity, and content digest in
  workflow state as explicit selections.

The combined prompt section must preserve ordering:

1. initializer add-ons in deterministic catalogue order;
2. explicit user selections not already loaded;
3. contextual selections loaded for the active context.

Lower-level runtime prompts must label which add-ons were initializer,
explicit, or contextual so review and resume diagnostics can explain why each
one was present.

## Acceptance Criteria

1. `agent-addon.yaml` schema version `1.1` requires an `activation` object with
   `type` and `use_when`, supports `initializer` and `contextual`, and rejects
   malformed type, empty usage text, duplicate context tags, or contextual-only
   fields on initializer add-ons with typed errors.
2. Existing agent add-ons are migrated to `activation.type: contextual` by
   default, including `execution-budget`, and existing explicit
   `agent-addon:execution-budget` calls remain accepted.
3. The Codex agent/delegation policy is available as an initializer add-on and
   is loaded at Codex session start before ordinary task work begins.
4. The GLM/peak-hours warning is available as a ZCode initializer add-on, reads
   the existing operator configuration contract, and preserves the current
   warning behavior before ZCode feature/goal launch.
5. Install/render generates a metadata-only add-on catalogue under
   `~/.skill-bill/agent-addons/catalog.md` and includes every valid local and
   external agent add-on in deterministic order.
6. Codex sessions receive a managed startup instruction or supported bootstrap
   that tells them to read the catalogue at session start; unsupported startup
   surfaces are reported honestly with diagnostics.
7. Initializer add-on content is loaded once at the beginning of every
   compatible Skill Bill-launched Codex child session and is deduplicated when
   the same add-on is also explicitly selected.
8. Contextual add-ons are discoverable through the catalogue but are not loaded
   until explicitly selected or loaded by a governed contextual activation path
   that verifies compatibility, source identity, and content digest.
9. Durable workflow state records activation source (`initializer`,
   `explicit`, or `contextual`) alongside slug, source identity, and digest, and
   resume rejects missing sources, digest drift, incompatible agents, or
   activation-mode mismatches before launching a child.
10. Runtime prompt formatting keeps add-on instructions in a guarded section
   below higher-authority instructions and never allows an add-on to skip
   confirmation, review, validation, typed failures, or repo `AGENTS.md`.
11. CLI and docs explain how to list the catalogue, inspect an add-on's metadata,
   explicitly select a contextual add-on, and understand which initializer
   add-ons were loaded for a run.
12. Tests cover schema migration of default existing add-ons to contextual, the
    Codex agent-policy initializer, the ZCode peak-hours initializer, catalogue
    rendering, Codex startup/bootstrap installation, initializer loading,
    contextual loading, explicit selection compatibility, deduplication, resume
    digest drift, unsupported agent diagnostics, and absence of add-on body
    content from the catalogue and telemetry.

## Non-Goals

- Automatically inferring every contextual add-on from arbitrary natural
  language without a governed compatibility and digest check.
- Loading all add-on bodies at session start.
- Replacing platform-pack add-ons or stack routing.
- Changing model, reasoning, service tier, tool permissions, review policy, or
  validation policy through an add-on.
- Supporting remote add-on marketplaces or automatic add-on updates.
- Treating a `bill-*` name prefix or provider name as sufficient authority to
  load an add-on.

## Validation Strategy

- Contract tests for `agent-addon-schema.yaml` version `1.1` and Kotlin parity.
- Source-loader tests for migrated and rejected add-on manifests.
- Install-staging tests that assert catalogue content is metadata-only and
  participates in staging identity without committing generated files.
- Codex install/bootstrap tests that verify the catalogue-reading instruction is
  present on the supported startup path.
- Runtime tests for initializer injection, contextual injection,
  deduplication, durable state, resume drift rejection, and prompt formatting.
- End-to-end goal or feature-task fixture proving `execution-budget` is
  discoverable as contextual and loads only when explicitly selected or when a
  governed contextual activation path selects it.

## Next Path

Run the decomposed goal workflow:

```bash
skill-bill goal SKILL-127
```
