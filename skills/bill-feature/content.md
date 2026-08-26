---
name: bill-feature
description: "Single entry point for governed feature work."
---

# Feature Entry

`bill-feature` is the only feature entry point. It gathers request details,
checks for updates, asks one runtime-composed confirmation question, launches
the goal, and relays the result.

## Update Check

Call `mcp__skill-bill__update_check` before any other action.

When the tool returns `status: "update_available"`:

- Show the installed version (`installed_version`) and latest version
  (`latest_version`).
- Ask whether to update or continue with the current version.
- If the user chooses to update, stop and show `recommended_install_command`.
- If the user chooses to continue, proceed to Intake.

For `up_to_date`, `ahead_of_release`, or `unknown`, proceed to Intake without
prompting.

## Intake

Establish:

- the issue key
- the intended outcome
- the acceptance criteria
- constraints, affected areas, and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

## Token Forwarding

Accept at most one `code-review:auto|inline|delegated`, and zero or more ordered `agent-addon:<slug>`
arguments. Forward supplied values as flags without resolving a catalogue or
constructing JSON. Omitted values remain omitted.

If the caller passes `parallel-review:<agent>`, stop immediately, name the removed
dual-agent parallel review capability, and do not run preflight or launch.

## Preflight

Call this command exactly once:

```text
skill-bill goal preflight <issue-key> --format json
```

Forward the agent, review, and agent add-on values as flags.
Derive the next action from the returned `verdict`. Invoke `bill-feature-spec`
after this preflight when the verdict reports new work, retaining the returned
gate state without recomputing it. Report and stop for an already-running or
terminal-only goal. Report every candidate for an ambiguous verdict. Surface
loud failures.

## Gate

Present the returned `gate_block` as a concise human-readable summary. Include
the issue key, feature name, child agent, review settings, add-ons, expected
first runnable subtask, and each subtask with its status and dependencies.
Do not print the raw JSON or expose internal field names. Ask exactly one
question: whether to proceed. Do not launch while unconfirmed. If the user
declines, stop.

## Rehydrate

For each entry in `rehydrate_targets`, fetch the listed issue from Linear and
write the returned spec content to the target path. Fetch nothing when the list is empty.

## Launch

After confirmation and any required rehydration, run:

```text
skill-bill goal <issue-key> --agent <currently-executing-agent> --no-live-output
```

Forward the supplied review and agent add-on flags. Never ask
the user to run the command manually.

## Relay

Await the launched process through the harness completion primitive. Relay its
output verbatim, adding nothing. Do not poll, sleep, tail logs, re-read status,
launch an observer, or compose monitoring, completion, summary, or progress
output. Run goal status only when the user explicitly asks.
