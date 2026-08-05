# Subtask 1: Decision ladder in implementation-phase prompts

Part of SKILL-162 (`spec.md`). Port ponytail's core discipline into the feature-task
implementation and implementation-fix subagent prompts.

## Scope

Edit the `bill-feature-task-implementation` and `bill-feature-task-implementation-fix`
agent definitions in `skills/bill-feature-task-prose/native-agents/agents.yaml`. Before
editing, confirm whether the runtime mode consumes the same rendered agent sources; if
runtime-mode implementation prompts have a separate source, apply the identical guidance
there — the ladder must hold in both modes.

Add a compact "minimalism discipline" block to both prompts, adapted from
`ponytail/SKILL.md` (in scratchpad and at the source repo):

1. **The ladder** — stop at the first rung that holds: (1) does this need to exist at all
   (YAGNI — skip and say so in one line); (2) already in this codebase — reuse the helper,
   util, type, or pattern that already lives here; (3) stdlib does it; (4) native platform
   feature covers it; (5) an already-installed dependency solves it — never add a new one
   for what a few lines can do; (6) can it be one line; (7) only then the minimum code that
   works. The ladder runs after understanding the problem, never instead of it.
2. **Rules** — no unrequested abstractions (no interface with one implementation, no
   factory for one product, no config for a value that never changes); no scaffolding "for
   later"; deletion over addition; boring over clever; shortest working diff once the
   problem is understood; between two equal-size options take the one correct on edge
   cases.
3. **Root-cause bug fixes** — before editing, trace every caller of the touched function;
   fix once where all callers route through, not on the one path the report names.
4. **Never-simplify carve-outs** — input validation at trust boundaries, error handling
   that prevents data loss, security, accessibility basics, anything the spec explicitly
   requires, and skill-bill's own governed contracts: typed errors, loud-fail seams,
   contract-version constants, parity tests, and validator-backed rules are never
   over-engineering.
5. **`shortcut:` marker convention** — a deliberate simplification with a known ceiling
   gets a comment: `shortcut: <ceiling>, <upgrade trigger>` (e.g.
   `// shortcut: global lock, per-account locks if throughput matters`). This is the one
   comment form the guidance instructs agents to write; it records a non-obvious why.

Keep the block tight — the source discipline is ~40 lines; the port must not bloat the
prompts it exists to shrink.

## Acceptance Criteria

1. Both implementation-phase prompts in `skills/bill-feature-task-prose/native-agents/agents.yaml`
   contain the seven-rung ladder with reuse-before-write ordering intact.
2. Both prompts contain the anti-over-engineering rules, the root-cause bug-fix rule, and
   the "understand first, then be lazy" guard.
3. Both prompts define the `shortcut: <ceiling>, <upgrade trigger>` marker convention and
   the never-simplify carve-outs, including the skill-bill governed-contract carve-out.
4. If runtime mode consumes a separate implementation prompt source, that source carries the
   identical block; if it consumes the same source, the check confirming this is recorded in
   the implementation summary.
5. `skill-bill validate` passes and `./install.sh` completes after the change.
6. No benchmark figures or savings claims appear in the added content.

## Non-Goals

- No changes to pre-planning, planning, quality-check, completeness-audit, or
  PR-description prompts.
- No intensity levels or mode toggles.
- No runtime-kotlin code changes.

## Dependency Notes

None. Subtasks 2 and 3 reference the `shortcut:` marker this subtask defines, so this
subtask runs first.

## Validation Strategy

`skill-bill validate`, then `./install.sh`; inspect the rendered agent output for both
prompts to confirm the block renders once and unmodified. `npx --yes agnix --strict .` if
agent-config lint applies to the edited file.

## Next Path

On completion, subtask 2 (`spec_subtask_2_over-engineering-review.md`) and subtask 3
(`spec_subtask_3_shortcut-debt.md`) become unblocked; they are independent of each other.
