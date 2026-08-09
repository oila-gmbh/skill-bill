# SKILL-174: Planning boundary-memory discovery

Status: Prepared

## Intended Outcome

Planning and preplanning stop treating platform packs as context sources, and stop
dumping dumb byte prefixes of `history.md` / `decisions.md` into prompts. Eligible
boundary memory is discovered under an exclusion list, indexed programmatically by
heading, and delivered headings-first with bodies only for headings the planning
agent selects.

## Background

SKILL-172 removed `platform.yaml` from goal-planning discovery and dropped the
`platform_packs` packet field in packet `0.2`. Discovery still walks
`platform-packs/*/agent/history.md` and `agent/decisions.md` into `boundary_memory`,
and still loads each file as a first-N-byte UTF-8 prefix (4KB/file, 32KB total).

That is wrong on two axes:

1. **Platform packs are review-phase routing only.** Pack manifests and pack-local
   `agent/` trees must not participate in planning or preplanning.
2. **Prefix truncation wastes tokens and loses structure.** The runtime does not
   parse headings, does not build a heading→content map, and cannot offer
   headings-first / bodies-on-demand retrieval. The planning agent receives whatever
   happened to sit at the top of each file.

Prose pre-planning already instructs agents to scan history/decisions titles and
open relevant entries. Runtime goal planning should encode that same shape as a
deterministic indexer plus selective body delivery — without spending model tokens
on indexing.

## Decisions

1. **Platform packs = review only.** No pack manifests, pack `agent/` history, pack
   `decisions`, or pack excerpts in any planning/preplanning path or shared packet.
2. **Exclusion list is cleanup + discovery gate.** Listed roots (at least full
   `platform-packs/`, plus other irrelevant directories) never contribute planning
   memory, and any existing `agent/` trees under those roots are deleted — not
   merely skipped on read.
3. **Intelligent walk is programmatic.** Runtime parses eligible files into heading
   lists and/or a `heading → content` map. No AI in the indexer. No tokens spent
   indexing.
4. **Headings first, bodies on demand.** The model reads headings (newest-first where
   that is the file convention), decides relevance, and only then receives content
   under selected headings. Unselected bodies never enter model context. Relevance
   is the agent's judgment; the indexer does not invent relevance.
5. **Entry format stays governed.** History entries follow `bill-boundary-history`;
   decisions entries follow `bill-boundary-decisions`. The parser must loud-fail or
   skip unparseable regions without inventing headings.

## Acceptance Criteria

1. Platform packs do not participate in planning or preplanning in any form (no pack
   manifests, no pack `agent/` history/decisions, no pack excerpts in the shared
   planning packet). Packs remain available to the review phase only.
2. A checked-in exclusion list names irrelevant roots (at least `platform-packs/`);
   discovery never reads `agent/history.md` or `agent/decisions.md` under those
   roots.
3. Existing `agent/` directories under every exclusion-list root are deleted from the
   repository (including all current `platform-packs/*/agent/` trees).
4. Dumb first-N-byte prefix dumping of history/decisions into planning prompts is
   removed.
5. Runtime programmatically parses eligible history/decisions files into a heading
   list and/or `heading → content` map with no model involvement in indexing.
6. Planning/preplanning receives headings first; after the agent selects relevant
   headings, runtime supplies only those bodies; unselected bodies never enter the
   prompt/context.
7. Docs and skill text that still describe pack `platform.yaml` / pack `agent/` as
   planning discovery inputs are updated to match this contract.
8. Tests cover exclusion enforcement, absence of pack paths in planning memory,
   heading index shape, and bodies-on-demand selection; `./gradlew build -x sourcesJar`
   and `detekt` pass.

## Non-Goals

- No change to how review/quality-check compose or route via platform-pack manifests.
- No AI/LLM step inside the history/decisions indexer.
- No rewrite of historical entry prose beyond what deletion of excluded `agent/`
  trees removes.
- No change to `write_history` / `bill-boundary-history` write/skip rules beyond
  ensuring writers do not recreate `agent/` under excluded roots.
- No provider-specific planning burst/pacing work (already covered by SKILL-172).

## Constraints

- Exclusion list is repo-owned and explicit (checked-in config or contract), not
  inferred from stack routing.
- On-demand body delivery may use a retrieval tool, a second prompt segment, or an
  equivalent seam — but must preserve the headings-first / selected-bodies-only
  token economy and remain provider-neutral.
- Shared planning packet / resume integrity remains loud-fail on schema drift; if
  `boundary_memory` wire shape changes, bump/migrate per existing packet versioning
  rules.
- Eligible discovery still stays bounded (file count / catalog size / body size);
  intelligence replaces prefix dumping, it does not open unbounded recursive reads.

## Out of Scope Follow-ups

- Broader allowlist of which non-excluded module `agent/` trees to prefer when many
  exist (touched-path heuristic beyond exclusion).
- Automatic migration/rewriting of non-conforming history/decisions heading formats
  across the monorepo.
