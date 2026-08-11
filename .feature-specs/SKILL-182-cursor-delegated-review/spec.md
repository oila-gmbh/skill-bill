# SKILL-182 — Enable delegated code review on Cursor

## Intended Outcome

An explicit `mode:delegated` code review invoked from the Cursor CLI fans out to
specialist subagent lanes inside Cursor's own harness, merges their findings, and
reports per-lane coverage — the same contract Claude Code and Codex already honour.
Today it stops with "delegated review is unsupported on this runtime", not because
Cursor lacks the capability but because `orchestration/review-delegation/PLAYBOOK.md`
never documents Cursor and the contract's closing rule says an undocumented harness
is unsupported.

## Background

Three sources currently disagree about Cursor:

- `orchestration/review-delegation/PLAYBOOK.md` documents GitHub Copilot CLI, Claude
  Code, and OpenAI Codex, marks Junie intentionally unsupported, and ends with "If the
  current runtime is not documented below, stop and say delegated review is unsupported
  for delegated-required scopes." Cursor falls through that rule by omission — it is
  neither supported nor deliberately excluded.
- `docs/delegated-review/provider-capability-matrix.md` lists Cursor as `experimental`
  with all eight capabilities present, and `docs/delegated-review/decision.md` cites
  `DelegatedReviewProviderCapabilityRegistry` and a "Cursor adapter surface" as the
  executable evidence. That subsystem was deleted by SKILL-159 (see
  `.feature-specs/done/SKILL-159-review-mode-restructure/spec_subtask_1_remove_external_delegated_subsystem.md`),
  so the matrix's evidence no longer exists. Those docs describe out-of-process
  provider workers, which is not what delegation means today.
- The runtime already installs Cursor native agents: `NativeAgentProvider.Cursor`
  renders `cursor-agents/*.md` into `~/.cursor/agents` when `~/.cursor` exists. The
  specialist rubrics a delegated lane needs are therefore already on disk for Cursor
  users.

Vendor behaviour (verified against Cursor's subagent documentation):

- Subagents are supported in the editor, the CLI, and Cloud Agents.
- They are defined as markdown with YAML frontmatter in `~/.cursor/agents/` (user
  scope) or `.cursor/agents/` (project scope, which wins on name conflict).
- Recognised frontmatter fields are `name`, `description`, `model` (`inherit` or an
  explicit model id), `readonly`, and `is_background`. `description` is what drives
  delegation.
- Invocation is by `/name` or by naming the subagent in the prompt; multiple subagents
  launch simultaneously when the parent asks for parallel work.
- There is **no `Task`-style tool** in the Cursor CLI toolset. This is the structural
  difference from Claude Code and Copilot CLI: the harness returns no launch handle,
  so the shared rule "track delegated workers by the ids returned when they are
  launched" has no Cursor implementation.

## Approach

Give Cursor a harness section whose lane accounting is keyed on what Cursor *does*
provide — the routed area plus the assignment digest the parent composed — instead of
a harness-returned id. Every other shared invariant is unchanged: fresh context per
lane, one lane per routed skill or specialist pass, bounded evidence surface, failed
lanes reported explicitly, no silent downgrade to inline.

Alongside that, correct the two supporting surfaces the change exposes: Cursor's
native-agent frontmatter (currently rendered with Claude's `tools:` vocabulary, which
Cursor does not define) and the `docs/delegated-review/` set (which documents a removed
subsystem and would otherwise contradict the new playbook section).

## Acceptance Criteria

1. `orchestration/review-delegation/PLAYBOOK.md` contains a Cursor harness section that
   states how a lane is launched, how it is identified, and how a failed lane is
   reported, and Cursor is no longer covered by the undocumented-runtime rule.
2. Cursor lane identity is defined without a harness-returned id, and the shared "track
   delegated workers by the ids returned when they are launched" rule is reconciled so
   it does not contradict the Cursor section.
3. A delegated selection on Cursor never resolves to inline: an unavailable subagent
   mechanism stops and reports delegated review as unavailable.
4. Cursor native agents are rendered with only frontmatter keys Cursor defines, and no
   Claude-only key is emitted into `cursor-agents/*.md`.
5. `docs/delegated-review/` no longer cites deleted runtime types as evidence, and its
   description of Cursor agrees with the delegation playbook.
6. `scripts/validate_agent_configs`, `npx agnix --strict .`, and `(cd runtime-kotlin && ./gradlew check)` all pass.

## Constraints

- Delegation stays inside the invoking agent's harness. This feature must not
  reintroduce out-of-process provider workers, a lane lifecycle store, or anything
  SKILL-159 deleted.
- A mitigation for Cursor must not change Claude Code, Codex, or Copilot CLI launch
  behaviour — the playbook states this explicitly and it is a hard boundary.
- `orchestration/review-orchestrator/specialist-contract.md` remains the single
  authoritative source of worker rules; the Cursor section describes launch mechanics
  only and does not restate them.
- Installed skills consume the playbook through generated sibling support pointers
  (`review-delegation.md` per `platform-packs/*/platform.yaml`). Those pointers are
  agent-agnostic and target the same `PLAYBOOK.md`; do not add a Cursor-specific
  pointer.
- Every skill installs for every supported agent. This feature changes what the Cursor
  harness section says, not which agents receive which skills.
- Cursor remains an experimental, explicit-opt-in delegated harness. Nothing here
  promotes it to a verified tier or advertises parity with Claude/Codex.

## Non-Goals

- Making `delegated` the default or auto-resolved mode on any harness. `auto` continues
  to resolve to `inline`.
- Junie or Copilot support changes.
- Restoring `DelegatedReviewProviderCapabilityRegistry` or any external delegated
  runtime subsystem.
- End-to-end verification of a real Cursor delegated run against a live Cursor CLI; the
  support tier stays experimental until such a canary exists.
- Changing the parallel-lane (`bill-code-review-parallel`) Cursor path, which uses
  `agent --print` as a second whole-review lane and is a different mechanism.

## Subtasks

1. Cursor harness section in the review-delegation contract.
2. Render Cursor native agents with Cursor's own frontmatter vocabulary.
3. Reconcile the delegated-review docs with the post-SKILL-159 runtime and with the
   new Cursor section.

## Next Path

```bash
skill-bill goal SKILL-182
```
