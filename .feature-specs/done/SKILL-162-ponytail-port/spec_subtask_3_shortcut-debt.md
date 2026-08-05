# Subtask 3: bill-shortcut-debt ledger skill

Part of SKILL-162 (`spec.md`). Port `ponytail-debt` as the harvester for the `shortcut:`
marker convention defined in subtask 1.

## Scope

Scaffold `bill-shortcut-debt` with `skill-bill new` (kind: `horizontal`) and fill
`content.md`. The skill collects every `shortcut:` comment in the repo into one ledger so a
deferral cannot quietly become permanent.

Port faithfully:

1. **Scan** — grep comment-prefixed markers, skipping `.git`, build output, and dependency
   directories: `grep -rnE '(#|//) ?shortcut:' .` (extend prefixes to the repo's comment
   syntaxes). The comment prefix keeps prose that merely mentions the convention out of the
   ledger.
2. **Ledger row** — one row per marker, grouped by file:
   `<file>:<line>, <what was simplified>. ceiling: <limit named>. upgrade: <trigger to revisit>.`
   The convention is `shortcut: <ceiling>, <upgrade trigger>`, so both fields parse straight
   from the comment.
3. **Rot flagging** — any marker naming no upgrade trigger gets a `no-trigger` tag; those
   are the ones that silently rot.
4. **Summary line** — `<N> markers, <M> with no trigger.` Nothing found:
   `No shortcut debt. Clean ledger.`
5. **Honesty rule** (the one idea kept from `ponytail-gain`) — never print invented per-repo
   savings numbers; the counted ledger is the only real per-repo figure.
6. **Boundaries** — reads and reports only; on explicit request it may write the ledger to
   a file the user names. One-shot.

## Acceptance Criteria

1. `skills/bill-shortcut-debt/content.md` exists, scaffolder-created, and
   `skill-bill validate --skill-name bill-shortcut-debt` passes.
2. The skill defines the scan command with exclusions, the grouped ledger row format, the
   `no-trigger` rot tag, the summary line, and the clean-ledger empty verdict.
3. The skill states the no-invented-savings rule and the read-only-by-default boundary.
4. Trigger phrases cover at minimum: "shortcut debt", "list the shortcuts", "what did we
   defer", "debt ledger".
5. `skill-bill validate` and `./install.sh` pass repo-wide.

## Non-Goals

- No persistent ledger file written by default and no scoreboard rendering.
- No changes to the marker convention itself; that is subtask 1's surface.
- No git-blame ownership rows in the initial port.

## Dependency Notes

Depends on subtask 1: the `shortcut:` marker convention must be defined in the
implementation-phase prompts before this skill's ledger has a governed producer. The skill
itself is still valid against hand-written markers, so the dependency is about coherence,
not build order strictness.

## Validation Strategy

`skill-bill validate --skill-name bill-shortcut-debt`, full `skill-bill validate`,
`./install.sh`, `skill-bill render --skill-name bill-shortcut-debt`. Self-check: plant two
markers in a scratch file — one with an upgrade trigger, one without — and confirm the
ledger shows both rows and exactly one `no-trigger` tag.

## Next Path

When subtasks 1–3 are complete, reconcile the parent `spec.md` to its terminal state and
close SKILL-162.
