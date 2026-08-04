# SKILL-159: Port ponytail's minimalism discipline into skill-bill

**Status:** Prepared
**Mode:** decomposed (3 subtasks)
**Source material:** https://github.com/DietrichGebert/ponytail (MIT). Core skill and companions
(`skills/ponytail/SKILL.md`, `ponytail-review`, `ponytail-audit`, `ponytail-debt`) are the
reference texts. Port the mechanisms faithfully; rewrite the prose to skill-bill's writing
policy and governed shapes.

## Intended Outcome

skill-bill's implementation agents stop over-building, and the system gains two focused
surfaces for hunting and tracking complexity:

1. The feature-task implementation phase enforces a reuse-before-write decision ladder with
   explicit anti-over-engineering rules, root-cause bug-fix discipline, and a
   deliberate-shortcut marker convention.
2. A horizontal review skill finds over-engineering only — tagged one-line findings with a
   net-lines score — covering both diffs and whole-repo audits.
3. A ledger skill harvests deliberate-shortcut markers into a tracked debt report so
   deferrals cannot silently become permanent.

## Why

Ponytail demonstrated (95k stars, independently criticized and rebuilt benchmarks) that a
reuse-first decision ladder measurably cuts generated code without cutting safety. skill-bill's
implementation subagents currently have no equivalent bias, and its review areas cover
correctness dimensions but have no complexity-only lens. The mechanisms are prompt-level and
fit skill-bill's governed-skill model directly.

## Acceptance Criteria

1. The feature-task implementation and implementation-fix subagent prompts contain the
   decision ladder, the anti-over-engineering rules, the root-cause bug-fix rule, the
   "never lazy about understanding" guard, and the `shortcut:` marker convention
   (subtask 1).
2. A `bill-over-engineering-review` horizontal skill exists, passes `skill-bill validate`,
   and produces tagged one-line findings with a net-lines score in both diff and repo-wide
   scope (subtask 2).
3. A `bill-shortcut-debt` horizontal skill exists, passes `skill-bill validate`, and renders
   a grouped ledger of `shortcut:` markers with `no-trigger` rot flagging (subtask 3).
4. No ponytail benchmark figures, scoreboards, or per-repo savings claims appear anywhere in
   the ported content.
5. MIT attribution for adapted ponytail content is recorded once, in a place that installs
   with the affected skills.

## Constraints

- Follow AGENTS.md contracts: `content.md` as governed source, scaffolder for new skills, no
  extra organization files under `skills/<skill>/`, no generated files in source.
- Ported guidance must never override skill-bill's existing governed paths: typed errors,
  loud-fail seams, validator-backed rules, and contract schemas are not "over-engineering"
  and must be explicitly carved out wherever the ladder or review lens could target them.
- Prose is rewritten to the repo writing policy (direct, active, no persona role-play
  framing beyond what the mechanism needs); mechanisms, ordering, tags, and output formats
  are kept faithful to the source.
- Comments policy compatibility: the `shortcut:` marker is a permitted comment because it
  records a non-obvious why (a known ceiling and its upgrade trigger).
- Run `./install.sh` after skill-source changes so local installs pick up the new staging
  hash.

## Non-Goals

- No port of `ponytail-gain` or any benchmark scoreboard; its only kept idea — never invent
  per-repo savings numbers — lands as a rule in `bill-shortcut-debt`.
- No intensity levels (`lite`/`full`/`ultra`) and no session-mode toggling; skill-bill
  surfaces are invoked, not persistent modes.
- No new platform-pack review area; the review skill is horizontal, outside the approved
  per-platform area list.
- No runtime-kotlin behavior changes; this is prompt/skill content plus standard skill
  registration only.

## Subtasks

1. `spec_subtask_1_implementation-ladder.md` — decision ladder, rules, and `shortcut:`
   marker convention into the implementation-phase subagent prompts.
2. `spec_subtask_2_over-engineering-review.md` — `bill-over-engineering-review` horizontal
   skill (diff and repo-wide scope).
3. `spec_subtask_3_shortcut-debt.md` — `bill-shortcut-debt` ledger skill (depends on
   subtask 1's marker convention).
