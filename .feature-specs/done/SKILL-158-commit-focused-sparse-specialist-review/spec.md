# SKILL-158 - Commit-Focused Sparse Specialist Review

## Mode

decomposed

Four dependency-ordered units:

1. Add an ordered commit-aware review evidence model without widening the
   bounded evidence surface.
2. Route changed commits sparsely to only the specialist lanes whose rubric is
   relevant to each commit.
3. Review each lane's assigned bundle in one single-pass worker operation, with
   commit identity and order carried as bundle metadata rather than as an
   execution protocol.
4. Add the final integration pass, lifecycle/projection contracts, governed
   guidance, native-agent parity, and end-to-end validation.

## Intended Outcome

Delegated code review uses the repository's logical commit structure when the
review scope has commits. The parent owns all discovery and relevance analysis:
it resolves the ordered commit sequence, attributes changed hunks to commits,
decides which hunks may produce findings under each lane's rubric, and assembles
one bundle per lane containing exactly those hunk bodies with their commit
identity and order attached.

Each specialist lane then performs one operation over its assembled bundle. It
does not walk commits one at a time, does not re-decide relevance the parent
already decided, and does not explore the repository to discover its own scope.
Commit order is metadata the specialist reads, not a sequence it steps through,
so a lane can still reason across its assigned commits — seeing that an earlier
commit introduced a contract a later commit changed — in a single pass.

Worker count equals selected lane count. The system does not launch one worker
per commit, does not send the complete PR diff to every lane, and does not make
every lane inspect every commit. A single bounded integration pass still checks
cross-commit behavior after specialist review. Single-commit, staged, unstaged,
working-tree, and file scopes retain a safe single-unit fallback when no commit
sequence exists.

## Current Behavior

- The parent resolves the review into one base-to-head diff and parses changed
  hunks, but the evidence model has no commit identity, order, or per-commit
  delta.
- Delegated assignments are lane/path based. A lane receives the complete set
  of assigned hunks across the PR, even when individual commits are unrelated
  to that lane's rubric.
- Specialist workers start in fresh conversations and receive bounded assigned
  hunks, but those hunks carry no commit attribution, so a specialist cannot tell
  which change introduced a contract another change later modified.
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
5. A delegated lane receives one assembled bundle holding only its assigned hunk
   bodies with commit identity and order attached, bounded direct dependencies,
   applicable rubric, and named evidence targets. No lane receives the raw
   complete PR diff or an irrelevant commit merely because the commit belongs to
   the same PR.
6. The lane worker reviews its bundle in one single-pass operation. It does not
   step through commits one at a time, does not re-decide the relevance the
   parent already decided, and does not restart from the final aggregate diff.
   Commit order is readable metadata, so the worker can still relate an earlier
   assigned commit to a later one within that single pass.
7. Every commit/lane pair receives an explicit `focused` or `skipped` disposition
   with a bounded reason, decided by the parent before launch and auditable
   without worker output. A skipped commit is absent from the bundle and cannot
   produce specialist findings; an assigned commit cannot be silently omitted.
   Findings identify the involved commit or commit range.
8. The implementation launches at most one normal worker per selected
   specialist lane per review pass. Worker count equals selected lane count and
   is independent of commit count; it never scales with commit/lane pairs, and
   it does not make every specialist lane inspect every commit by default.
9. One parent-owned or dedicated integration pass evaluates the final feature
   behavior and interactions between assigned commits after specialist lanes
   finish. It does not repeat every specialist rubric over every commit and
   does not replace commit-focused specialist review.
10. Lifecycle persistence, cancellation, timeout, retry, resume, accounting,
    finding attribution, and deduplication preserve commit order, lane
    assignments, focus/skip dispositions, and per-lane completion state. A lane
    is the unit of retry: a resume re-runs only lanes that did not complete and
    never re-runs a lane whose single pass already produced a durable result.
11. Existing bounded evidence-broker rules remain enforced: workers cannot
    rediscover scope, routing, guidance, or a broad diff; complete-file reads
    and dependency expansion remain authorized, bounded, and attributable.
12. Existing single-commit and non-commit review behavior remains compatible,
    including the current inline mode and the current final finding format.
13. Governed review contracts, stack-specific specialist guidance, generated
    native-agent prompts, schemas, and parity tests describe the same
    commit-focused sparse-review behavior.
14. Parent-side commit and relevance analysis runs under an explicit bounded
    budget. Because the parent now absorbs the per-commit rubric analysis that
    workers no longer repeat, its own context growth is measured and capped, and
    exceeding the cap fails loudly rather than silently degrading routing.
15. A lane bundle that exceeds the worker context budget is split into the
    fewest size-driven segments that fit, each still carrying commit identity and
    order. The split is mechanical and size-driven, never a per-commit protocol,
    and each segment is separately accounted. A lane that cannot be reviewed
    within budget reports an explicit incomplete disposition naming the
    unreviewed segments and is never aggregated as clean coverage.
16. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
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
- A specialist lane may review multiple relevant commits, but it receives them
  as one assembled bundle in a single pass and must not receive clear irrelevant
  commit bodies.
- Relevance is decided once, by the parent, from actual changed hunks. Workers
  do not repeat that judgment. This concentrates rubric awareness in the parent,
  so parent analysis quality is the coverage guarantee and must be tested
  directly rather than backstopped by worker confirmation.
- Commit order is bundle metadata the specialist reads, never an execution
  sequence it steps through.
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
lane evidence. Exercise single-pass bundled lane review, worker count equal to lane count and
invariant under commit count, cross-commit reasoning inside one pass, bounded
integration review, and lane-granular resume. Cover an oversized bundle that
splits into size-driven segments, a lane that cannot complete within budget and
reports an incomplete disposition rather than clean coverage, and a parent
analysis budget breach that fails loudly. Add
native-agent and governed-prose parity tests, then run the full repository
validation commands from Acceptance Criterion 16.

## Next Path

```bash
skill-bill goal SKILL-158
```
