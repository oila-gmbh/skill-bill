# SKILL-158 - Commit-Focused Sparse Specialist Review

## Mode

decomposed

Four dependency-ordered units:

1. Add an ordered commit-aware review evidence model without widening the
   bounded evidence surface.
2. Route changed commits sparsely to only the specialist lanes whose rubric is
   relevant to each commit.
3. Review each lane's assigned commits sequentially in one continuing worker
   context, carrying earlier commit understanding forward without re-reviewing
   irrelevant commits.
4. Add the final integration pass, lifecycle/projection contracts, governed
   guidance, native-agent parity, and end-to-end validation.

## Intended Outcome

Delegated code review uses the repository's logical commit structure when the
review scope has commits. Each specialist lane receives only the commits whose
actual changed hunks may produce findings under that lane's rubric. The lane
worker processes those commits oldest-first in one continuing context, decides
whether each candidate commit is relevant, records focused or skipped
dispositions with reasons, and carries prior understanding forward.

The system does not launch one worker per commit, does not send the complete PR
diff to every lane, and does not make every lane inspect every commit. A single
bounded integration pass still checks cross-commit behavior after specialist
review. Single-commit, staged, unstaged, working-tree, and file scopes retain a
safe single-unit fallback when no commit sequence exists.

## Current Behavior

- The parent resolves the review into one base-to-head diff and parses changed
  hunks, but the evidence model has no commit identity, order, or per-commit
  delta.
- Delegated assignments are lane/path based. A lane receives the complete set
  of assigned hunks across the PR, even when individual commits are unrelated
  to that lane's rubric.
- Specialist workers start in fresh conversations and receive bounded assigned
  hunks, but they have no governed instruction or structured input for
  oldest-first commit sequencing and cumulative context.
- Lane inclusion is decided before launch at the specialist-lane level; there
  is no auditable commit-to-lane relevance matrix or per-commit skip reason.
- The current design correctly prevents raw whole-PR diff rediscovery and
  preserves bounded evidence access. Those protections must remain intact.

## Acceptance Criteria

1. For a PR diff or explicit commit-range scope, the parent resolves an ordered
   commit sequence with stable commit identity, parent relationship, subject,
   and exact incremental changed hunks; the union of the incremental hunks
   represents the same final base-to-head change without silently dropping,
   duplicating, or reordering attributable changes.
2. Review context and launch assignments represent commit identity and order
   explicitly, including the per-commit hunk set assigned to each lane. A
   specialist can distinguish a commit-local diff from the accumulated final
   tree without running broad Git discovery.
3. Staged, unstaged, combined working-tree, and file scopes that do not have a
   commit sequence use one synthetic review unit with no invented commit
   history. Existing scope boundaries and staged-versus-unstaged behavior are
   unchanged.
4. The parent produces an auditable commit-to-lane matrix from actual changed
   hunks, changed paths, and the selected specialist rubric. Clear non-relevant
   commit/lane pairs are excluded before launch; a genuinely cross-cutting
   commit may be assigned to multiple lanes when each assignment has evidence.
5. A delegated lane receives only its ordered assigned commit units, bounded
   direct dependencies, applicable rubric, and named evidence targets. No lane
   receives the raw complete PR diff or an irrelevant commit merely because the
   commit belongs to the same PR.
6. The lane worker reviews assigned commits oldest-first in one continuing
   context. It carries earlier commit understanding into later commits, does
   not restart from the final aggregate diff for every commit, and does not
   re-review a previously covered commit unless a later change creates an
   explicit reachable reason to revisit it.
7. Each candidate commit receives an explicit `focused` or `skipped`
   disposition from the responsible lane with a bounded reason. A skipped
   commit cannot produce specialist findings, and an assigned commit cannot be
   silently omitted. Cross-commit findings identify the involved commit or
   commit range.
8. The implementation launches at most one normal worker per selected
   specialist lane per review pass, not one worker for every commit/lane pair.
   It does not make every specialist lane inspect every commit by default.
9. One parent-owned or dedicated integration pass evaluates the final feature
   behavior and interactions between assigned commits after specialist lanes
   finish. It does not repeat every specialist rubric over every commit and
   does not replace commit-focused specialist review.
10. Lifecycle persistence, cancellation, timeout, retry, resume, accounting,
    finding attribution, and deduplication preserve commit order, lane
    assignments, focus/skip dispositions, and cumulative worker progress. A
    resume does not cause completed commit units to be reviewed again unless
    the governing retry state explicitly requires it.
11. Existing bounded evidence-broker rules remain enforced: workers cannot
    rediscover scope, routing, guidance, or a broad diff; complete-file reads
    and dependency expansion remain authorized, bounded, and attributable.
12. Existing single-commit and non-commit review behavior remains compatible,
    including the current inline mode and the current final finding format.
13. Governed review contracts, stack-specific specialist guidance, generated
    native-agent prompts, schemas, and parity tests describe the same
    commit-focused sparse-review behavior.
14. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass.

## Constraints

- Preserve lane-based specialist ownership, bounded context budgets, evidence
  broker enforcement, fresh worker isolation, lifecycle telemetry, and the
  current finding severity/admission rules.
- Use actual commit diffs and hunk ownership for relevance. Commit messages and
  file names may provide routing signals but cannot be the sole evidence for a
  focused assignment.
- Keep the commit sequence as review context, not as a request to rewrite Git
  history, split PRs, or alter merge strategy.
- A specialist lane may review multiple relevant commits, but it must process
  them in order inside one continuing context and must not receive clear
  irrelevant commit bodies.
- Cross-cutting changes must be allowed to reach multiple specialist lanes when
  the changed behavior crosses their boundaries. Sparse routing must not become
  silent coverage loss.
- Non-commit scopes must remain deterministic and safe through a single
  synthetic review unit rather than guessed commit attribution.
- Keep the final integration check bounded and distinct from specialist
  re-review; do not solve integration coverage by sending the full PR to every
  lane.
- Run `./install.sh` after governed skill or native-agent source changes so
  local installed staging reflects the new source hash; generated install
  output stays uncommitted.

## Non-Goals

- Rewriting, squashing, splitting, or otherwise changing the repository's Git
  history or PR merge policy.
- Requiring every commit to build independently or changing the project's
  commit authoring conventions.
- Removing the final end-to-end integration check or making all cross-cutting
  review unnecessary.
- Changing specialist rubrics, severity calibration, finding admission, or
  final risk-register formatting.
- Giving every specialist access to the complete PR diff, parent transcript,
  sibling-lane context, or unrelated repository files.
- Making commit messages authoritative for security, API, persistence, or
  architecture coverage.
- Changing inline review into delegated review or requiring commit-aware
  routing for scopes that have no meaningful commit sequence.

## Validation Strategy

Use focused model and schema tests for ordered commits, incremental hunk
ownership, synthetic non-commit units, and aggregate-diff equivalence. Add
routing matrix fixtures covering pure UI, persistence, API/security, testing,
and cross-cutting commits, including explicit skip reasons and no irrelevant
lane evidence. Exercise one-worker-per-lane sequential execution, cumulative
context, later-commit revisits only through authorized reachability, bounded
integration review, and resume without duplicate completed commit work. Add
native-agent and governed-prose parity tests, then run the full repository
validation commands from Acceptance Criterion 14.

## Next Path

```bash
skill-bill goal SKILL-158
```
