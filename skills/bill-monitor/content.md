---
name: bill-monitor
description: Inspect one decomposed goal with a bounded, read-only status snapshot and keep same-thread follow-ups in monitor mode.
---

# Monitor

Use this skill only when the caller asks to inspect the state of one decomposed
goal. It is a read-only entry point and is not an implementation or workflow
continuation skill.

## Input contract

Accept exactly one issue key argument. It must be a supported issue key in the
form `PROJECT-123`, with an uppercase project prefix and decimal issue number.
If the argument is missing, extra, malformed, ambiguous, or unsupported, stop
and explain the refusal. Do not try implementation behavior as a fallback.

Resolve the canonical repository from the current repository context before the
status request. Use the Git top-level real path, and pass that path to the
status command. If the current directory is not inside a repository or the
repository cannot be resolved, stop without requesting status.

## One bounded snapshot

For each explicit monitor request, invoke exactly one read-only status snapshot:

```bash
skill-bill goal status PROJECT-123 --repo-root /absolute/path/to/repository --monitor
```

Substitute the caller's issue key and the resolved canonical repository path.
Do not invoke the command a second time to confirm, refresh, or compose a
summary. Report only the bounded result: complete, pending, and blocked counts;
current subtask and step; execution liveness; and resumable state. The
resumable state includes `paused` and `pause_requested` when a durable operator
pause is present. Keep the result to the command's bounded output and do not
replay child plans,
diagnostics, transcripts, logs, or large skill payloads.

The command is read-only. Do not launch a goal, resume a workflow, reset state,
accept a subtask, write to the repository or workflow database, poll, sleep,
tail logs, or invoke `skill-bill goal watch`.

You may offer this copyable command for the user to run themselves, but never
run it from this skill:

```bash
skill-bill goal watch PROJECT-123 --interval-seconds 5
```

## Same-thread follow-ups

Monitor mode remains active for the rest of the current thread. For an explicit
status follow-up, resolve no new context and perform exactly one new read-only
status snapshot for that request. Do not watch or poll between requests.

If the caller asks to implement, launch, resume, reset, accept, repair, or
otherwise mutate state while monitor mode is active, refuse the request and
state that monitor mode is read-only. Wait for the caller to explicitly say
`exit monitor mode`; exiting does not start implementation automatically. A
later explicit invocation is required to establish an implementation workflow.

The monitor contract does not carry implicitly into a new conversation. A new
conversation or turn must invoke `bill-monitor <issue-key>` again before asking
for monitor status.
