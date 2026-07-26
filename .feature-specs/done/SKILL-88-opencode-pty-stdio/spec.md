# SKILL-88 — Durable fix for opencode failing under the runtime's JVM-piped stdout spawn

Status: Complete

## Summary

The Bun-compiled `opencode` binary exits with status `1` in ~2-3 seconds, emitting
non-JSON stdout, **only** when the skill-bill runtime spawns it with its stdout connected
to a JVM `ProcessBuilder` pipe. The same invocation runs normally when opencode's stdout is
a regular file. This breaks every `feature-task-runtime` phase that uses `--agent opencode`:
the phase agent never does real work, the phase schema-gate rejects the non-JSON output
(`<root> must be an object`), the bounded fix loop burns its retries on the same fast
failure, and the phase lands in a sticky `blocked` state. Implement a durable runtime fix so
opencode runs correctly under the runtime's own spawn, and surface the child's stderr in the
phase failure reason so this class of failure stops being opaque.

## Background — how it surfaced (PS-24)

Subtask 8 of an unrelated decomposed goal (PS-24, "Post revisions + audit log") got stuck in
a sticky `blocked` state at the preplan phase. The goal loop correctly stopped and refused to
advance. The failure chain inside the runtime:

1. The goal runner spawns the feature-task child for the subtask (`skill-bill feature-task
   run …`, via `goalContinuationCommand`).
2. The feature-task runtime spawns `opencode run --dir <repo> --dangerously-skip-permissions
   <preplan briefing>` to run the preplan phase (`OpencodeAgentRunCommandBuilder`).
3. The opencode child exits status `1` in ~2-3s, emitting ~629 chars of non-JSON stdout.
4. The preplan schema-gate rejects the output (`<root> must be an object`).
5. The bounded fix loop retries 3× — identical fast failure each time — then marks the phase
   `blocked`.
6. `blocked` is sticky: resume will not retry once `attempt_count` exceeds the cap.

PS-24 itself was soft-reset (subtasks 1-7 complete, subtask 8 reverted to pending) and is not
part of this fix.

## Reproduction & evidence

- The runtime invokes opencode correctly: parent process is `java`, argv is well-formed,
  `--dir` points at a real existing directory, the prompt positional is non-blank.
- Connect opencode's stdout to a JVM-owned pipe (the current path) → opencode exits ~2-3s,
  status 1, non-JSON output → schema-gate failure.
- Insert an `opencode` wrapper on `PATH` that redirects opencode's stdout to a **file** →
  opencode runs normally (full LLM execution; hit a 120s harness timeout mid-preplan, no
  block).

The determining factor is the **type of opencode's stdout fd**: pipe (fails) vs file (works).
opencode is a Bun-compiled native binary and misbehaves under the runtime's piped-stdio
spawn specifically.

## Ruled out (with evidence)

- **opencode binary / flags / config** — all valid. `--dir` and
  `--dangerously-skip-permissions` exist in opencode 1.15.3; `zai-coding/glm-5.2` auth works.
  The exact builder invocation runs fine from a normal shell (exit 0).
- **Preplan briefing content** — fed the exact stored ~1520-byte briefing to `opencode run`
  directly; it ran fine.
- **`OPENCODE_*` inherited env vars** — stripped them; failure persisted.
- **Goal-continuation env** — reproduced with all `SKILL_BILL_GOAL_*` vars set; opencode ran
  fine.
- **Shell vs no-shell exec** — a direct (no-shell) subprocess spawn with identical argv also
  succeeded.
- **Empty/blank prompt, bad `--dir`, bad `--model`** — the three fast opencode exit-1 modes
  are all excluded: `promptOverride` is validated non-blank
  (`AgentRunLauncherModels.kt:28`), `--dir` resolves to the real repo dir (goal mode reuses
  `request.repoRoot`; no per-subtask worktree is created), and the opencode builder never
  passes `--model`.

## Root cause

The Bun-compiled `opencode` binary does not tolerate having its stdout connected to a JVM
`ProcessBuilder` pipe and aborts early (status 1) before doing real work. The runtime's
`JvmAgentRunProcessRunner` currently reads the child's stdout/stderr from
`process.inputStream` / `process.errorStream` (JVM pipes) via `CappedUtf8Drain`. A file-backed
stdout avoids the abort but is not a drop-in: a plain file redirect removes the live stdout
signal the watchdog relies on, which would risk false idle-kills on read-only phases (preplan
makes no file edits and would emit no pipe output during a multi-minute run).

## Intended outcome

`feature-task-runtime` phases run with `--agent opencode` complete normally under the
runtime's own spawn — no ~2-3s status-1 abort, no spurious schema-gate failures, no sticky
`blocked`. When a phase agent does exit non-zero for a real reason, the failure reason names
the child's actual error instead of a bare exit code.

## Proposed approach

Give the opencode child a **PTY (pseudo-terminal)** for its stdio so its stdout is a TTY-like
fd it handles correctly, while the runtime still reads output live for capture, output-sink
streaming, and liveness. This is preferred over a plain file redirect because it preserves the
live-output signal the idle watchdog depends on, avoiding false idle-kills on read-only phases.

Scope the PTY path to the agents that need it (opencode), carried as a flag on the process
request so the launcher selects PTY-backed stdio. The JVM has no built-in PTY; add a PTY
dependency (e.g. `pty4j`) for the runtime-infra-fs module.

Companion fix (independent, related): `infraFailureReason`
(`FeatureTaskRuntimeRunner.kt:1594`) currently reports only `agent exited with non-zero status
N` and drops `facts.stderr`, which holds the child's actual error line. Append a bounded
stderr (and/or stdout) excerpt — reuse the existing `stderrExcerpt` /
`STDERR_EXCERPT_MAX_CHARS` pattern used by `GoalRunnerObservabilityEmitter` — so the failure
reason is self-diagnosing.

### Alternatives considered (and why not)

- **File-redirect + `cat` wrapper in the command builder.** Proven to make opencode work, but
  flushes stdout to the JVM only at exit, removing the live-output signal → risks false
  idle-kills on read-only phases (preplan). Rejected as the primary fix; the watchdog
  signal-profile change would have to be reworked anyway.
- **`opencode run --format json`.** Emits opencode's own event-stream JSON, not the agent's
  final phase-contract JSON object the schema-gate extracts. Would break extraction.
- **Switch the default child agent to claude/codex.** They deliver the prompt via stdin and
  did not show the Bun-pipe symptom, but this is an operational workaround, not a fix, and
  drops opencode support in runtime mode.

## Acceptance Criteria

1. The runtime spawns the opencode `feature-task-runtime` phase child over a PTY-backed stdio
   path (not a plain JVM stdout pipe), and a real preplan phase run with `--agent opencode`
   completes through the phase schema-gate instead of failing with a ~2-3s status-1 non-JSON
   exit.
2. Child output from the PTY path is still drained live, capped at the existing output limit,
   streamed to the output sink, and delivered to the schema-gate identically to the current
   pipe path — no regression in captured stdout/stderr for any agent.
3. Liveness/idle-watchdog semantics are preserved: a long-running but live opencode phase is
   not false-killed by the progress-idle watchdog, and a genuinely hung child is still
   detected and terminated. No regression to `JvmAgentRunProcessRunner` timeout, idle, or
   declared-progress behavior.
4. `infraFailureReason` includes a bounded excerpt of the child's `stderr` (and/or stdout)
   when a phase agent exits non-zero, so the reason names the child's actual error rather than
   only `agent exited with non-zero status N`.
5. The claude, codex, and junie launch paths are unchanged in behavior and continue to pass
   their existing launcher tests; the PTY path is scoped so it does not regress them.
6. Tests cover the opencode PTY stdio launch path, the preserved drain/cap/output-sink/
   liveness behavior, and the `infraFailureReason` stderr surfacing.
7. `./gradlew check` passes.

## Non-goals

- Changing opencode's prompt delivery (stays the argv positional), model selection, or the
  `--dir` / `--dangerously-skip-permissions` flags.
- Fixing the upstream Bun/opencode binary; the runtime adapts to it.
- Reworking prose-mode (it bypasses the subprocess spawn entirely and is an available
  workaround, not part of this fix).
- Migrating claude/codex/junie to PTY-backed stdio (only opencode requires it; any broader
  rollout is out of scope unless trivially free).
- Re-running or unblocking PS-24.

## Affected files / anchors

- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/process/JvmAgentRunProcessRunner.kt`
  — PTY-backed stdio spawn + drain (`startProcess`, `CappedUtf8Drain`, the wait/liveness loop).
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/process/AgentRunProcessRunner.kt`
  — `AgentRunProcessRequest` model: carry a "needs PTY stdio" flag.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunCommandBuilders.kt`
  — `OpencodeAgentRunCommandBuilder` (and the `AgentRunCommand` model) to signal the PTY need.
- `runtime-kotlin/runtime-infra-fs/src/main/kotlin/skillbill/launcher/agentrun/AgentRunAdapters.kt`
  — thread the PTY flag from command into `AgentRunProcessRequest`.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/featuretask/FeatureTaskRuntimeRunner.kt:1594`
  — `infraFailureReason` stderr surfacing.
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/goalrunner/GoalRunnerObservabilityEmitter.kt`
  — reuse `stderrExcerpt` / `STDERR_EXCERPT_MAX_CHARS`.
- `runtime-kotlin/runtime-infra-fs/build.gradle.kts` (and version catalog) — PTY dependency.
- Tests: `runtime-kotlin/runtime-infra-fs/src/test/kotlin/skillbill/launcher/AgentRunLauncherTest.kt`
  and related runtime/launcher tests.

## Validation strategy

- Unit/integration tests for the PTY stdio launch path and the preserved drain/cap/liveness
  behavior (see Acceptance Criteria 2, 3, 6).
- A test asserting `infraFailureReason` carries a bounded stderr excerpt on non-zero exit.
- Manual confirmation: run a real `feature-task-runtime` preplan phase with `--agent opencode`
  from the runtime and confirm it advances past preplan (no ~2-3s status-1 abort).
- `./gradlew check` green.

## Risks

- PTY dependency portability across the runtime's target platforms (Linux/macOS); confirm the
  chosen library covers them.
- Subtle differences in how a PTY presents EOF/exit vs a pipe could affect the wait/drain loop;
  covered by Acceptance Criteria 2 and 3.

## Next path

```bash
Run bill-feature-task on .feature-specs/SKILL-88-opencode-pty-stdio/spec.md
```
