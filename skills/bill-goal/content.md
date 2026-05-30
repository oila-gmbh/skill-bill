---
name: bill-goal
description: Use when a user asks to complete a larger implementation goal through interactive decomposition, one confirmation gate, and the foreground `skill-bill goal` runner; complete small non-decomposed goals directly without starting the loop.
---

# Goal Runner Content

`bill-goal` is the interactive front door for a goal that may need multiple decomposed implementation subtasks. It decides whether decomposition is necessary, asks for exactly one confirmation before starting any automated loop, and hands confirmed decompositions to the local `skill-bill goal` driver.

## Intake

Clarify the user's goal enough to identify:

- the issue key
- the intended outcome
- the acceptance criteria
- known constraints, affected areas, and non-goals

If the issue key is missing, stop and ask for it. Do not invent one.

Classify the goal before decomposing:

- If the goal is small enough for one normal implementation pass, complete it directly in the current agent session. Do not create a decomposition manifest and do not invoke `skill-bill goal`.
- If the goal needs multiple independently resumable implementation subtasks, prepare a decomposition proposal.

## Decomposition Proposal

For decomposed goals, present a concise proposal that includes:

- the issue key and feature name
- the parent acceptance criteria
- two or more ordered subtasks with dependency notes
- the expected first runnable subtask
- the agent that will be used for child runs, including any explicit override

Ask one confirmation question: whether to proceed with this decomposition and start the foreground goal loop.

Do not start `skill-bill goal` while the decomposition is unconfirmed. If the user declines, stop and either revise the proposal or leave the goal unstarted, depending on their response.

## Confirmed Handoff

After confirmation, ensure the decomposed parent workflow and runtime manifest have been created by the normal feature-implementation decomposition path. Then hand off to the foreground driver:

```bash
skill-bill goal <issue_key>
```

Use `--agent` when the invoking agent id is known and `--agent-override` only when the user explicitly selected a different child agent. Keep live output enabled unless the user asks for quieter output.

During the run, treat workflow state as authoritative. Child stdout and stderr are diagnostic. If the driver stops and reports a blocked or failed subtask, do not continue the loop manually; summarize the stopped subtask, reason, workflow id when present, and resumable step.

## Status Checks

Use the read-only status command whenever the user asks where a decomposed goal stands:

```bash
skill-bill goal status <issue_key>
```

Report complete, pending, and blocked counts, the current subtask and step, and the active agent exactly as returned by the command. Do not mutate workflow state during a status-only request.
