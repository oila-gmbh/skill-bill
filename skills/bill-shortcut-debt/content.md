---
name: bill-shortcut-debt
description: "Harvest shortcut: markers into a debt ledger. Use when listing shortcuts or reviewing deferred debt."
---

# Shortcut Debt Content

Every deliberate shortcut is marked with a `shortcut: <ceiling>, <upgrade trigger>`
comment. This skill harvests those markers into one ledger so a deferral cannot quietly
become permanent. One-shot report.

The marker convention itself is owned elsewhere; this skill only reads and reports what
is already in the tree. Hand-written markers are valid input.

## Scan

Grep the repo for comment-prefixed markers, skipping `.git`, build output, and dependency
directories (`node_modules`, `.gradle`, `build`, `dist`, `target`, vendor trees, and similar):

`grep -rnE '(#|//) ?shortcut:' .`

Extend the comment-prefix alternation for other stacks in scope (`<!--`, `/*`, `--`, `;`,
and so on). The comment prefix keeps prose that merely mentions the convention out of the
ledger.

Each hit is one ledger row.

## Ledger

One row per marker, grouped by file:

`<file>:<line>, <what was simplified>. ceiling: <limit named>. upgrade: <trigger to revisit>.`

Parse ceiling and upgrade trigger straight from the comment form
`shortcut: <ceiling>, <upgrade trigger>`. Use nearby code context only to name what was
simplified when the comment itself is terse.

Tag rot risk: any marker that names no upgrade trigger gets a `no-trigger` tag. Those are
the ones that silently rot.

End with a summary line: `<N> markers, <M> with no trigger.`

Nothing found: `No shortcut debt. Clean ledger.`

Do not add git-blame ownership rows.

## Honesty

Never invent per-repo savings numbers (lines, tokens, cost, or speed "saved here"). The
counted ledger is the only real per-repo figure this skill may report.

## Boundaries

Read-only by default: scan and report only; change nothing in the repo.

On explicit request, write the ledger to a file path the user names. Do not invent a
default ledger path or persist a scoreboard. One-shot.
