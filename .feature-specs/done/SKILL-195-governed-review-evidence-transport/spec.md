# SKILL-195 — Govern the review evidence boundary and make its failures legible

## Context

`NativeReviewOperationProtocol` (`runtime-ports/.../review/NativeReviewOperationProtocol.kt:17-29`)
declares a governance boundary and states its own contract:

> Adapters must call this protocol **before** performing an operation; a rejected response is
> returned to the worker and the operation is not executed.

No adapter calls it. Every one of its six methods — `authorizeExpansion`, `read`, `tool`,
`modelTurn`, `laneResultChunk`, `providerUsage` — has exactly one production caller:
`FanOutReviewEvidenceBroker` (`runtime-application/.../review/FanOutReviewEvidenceBroker.kt:21-42`),
which is itself only a lane-dispatching delegator. `AgentRunProcessRunner` receives the broker and
the protocol and merely asserts they are supplied together (`:47-48`, `:65-68`); it never invokes
them. `ParallelCodeReviewRunner` constructs a `BrokerBackedNativeReviewOperationProtocol` per launch
(`:1200`) and hands it to a child process that has no channel back to it.

The boundary is therefore decorative. `FileSystemReviewEvidenceBroker` implements real governance —
reachability policy, byte budgets, an expansion ledger, lane termination
(`runtime-infra-fs/.../FileSystemReviewEvidenceBroker.kt:75-110`) — and none of it executes.

Meanwhile `parentPrompt` instructs the worker to "fetch bodies through the bound broker"
(`ParallelCodeReviewRunner.kt:1317`) and ships `hunk_id` / `content_digest` / `evidence_locator`
locators instead of content. The worker is told to pull evidence through a channel that does not
exist.

### The SKILL-194 incident

The failure is not theoretical. SKILL-194 subtask 1 sat `blocked` in its manifest with:

```yaml
blocked_reason: "Feature-task-runtime phase 'review' lane did not emit a findings
  register; zero [F-XXX] lines without NO_FINDINGS means the review did not execute"
last_resumable_step: "review"
```

The proximate cause was a separate defect — `AgentRunCommandBuilders.kt` passed an empty `--tools`
value on the non-fan-out path, which strips the toolset outright rather than leaving a default in
place. The inline worker reached one model turn, made zero tool calls, and returned ~700 bytes of
prose explaining it had no file tools. That is fixed on main in `6f47e771a`, which grants
`Read,Grep,Glob,Bash` and restores `--agent`. Output immediately rose from 697 B to 13.6 KB.

**That fix is a stopgap and this spec supersedes it.** It works by giving the worker *ungoverned*
filesystem access: no reachability check, no byte budget, no expansion ledger, no lane termination.
It trades an inert boundary for a bypassed one.

### Why the defect survived so long

Because every distinct failure collapses into one indistinguishable message. `attributeInlineFindings`
wraps the whole parse in `runCatching { }.getOrDefault(emptyList())`
(`ParallelCodeReviewRunner.kt:1473`), and `ParallelReviewFindingParser.parse` does the same per match
(`ParallelReviewFindingParser.kt:29-31`). A parser crash, a register in a near-miss format, and a
worker that genuinely never ran all produce the identical string: *"the review did not execute."*

`registerAbsenceReason` (`:1545`) then discards `rawOutput` entirely, though the runner holds it
(`:1250`). Diagnosing the SKILL-194 block required reading launcher source and reproducing the CLI
invocation by hand, because the runtime surfaced nothing about what the lane actually returned. That
is a swallowed failure with no record, against `docs/observability-policy.md`.

### Provider isolation is also unenforced

A repro of the pre-fix launch confirmed that `--tools ""` does not isolate the worker: the user's
own MCP servers remained available to it. Governed review launches pass `--mcp-config`/
`--strict-mcp-config` nowhere, so a review worker inherits whatever MCP surface the host account has
registered.

## Intended Outcome

The review evidence boundary is real: a worker reaches repository content only through the broker,
which authorizes each path, meters each byte, records each expansion, and can refuse. When the
boundary cannot be established, the run says so loudly instead of degrading silently. When a lane
returns something that is not an admissible register, the runtime distinguishes *did not run* from
*ran and drifted in format*, and shows what it received.

## Acceptance Criteria

1. A governed review launch reaches repository evidence exclusively through
   `NativeReviewOperationProtocol`. The worker receives no raw filesystem tool.
2. `authorizeExpansion` and `read` have production callers reached on every governed review; a test
   asserts a denied path is not readable by the worker.
3. Budget exhaustion mid-review terminates the lane through the broker's existing
   `ReviewBudgetOutcome` path, and the terminal outcome is recorded.
4. `expansions` and `evidence_bytes` in review accounting reflect actual worker reads; a review that
   consumed evidence never reports `0`.
5. A governed review launch is MCP-isolated: the worker sees the review evidence server and no
   host-registered MCP server.
6. `registerAbsenceReason` distinguishes at least three states — no register candidates, candidates
   present but none admissible, and parser fault — and names the offending line for the middle case.
7. The blanket `runCatching` in `attributeInlineFindings` is removed; a parser fault fails loudly.
8. Per-match rejections are retained as structured rejection records, not dropped.
9. Every degradation on this path emits a `ReviewStageDegradationMeasurement` with a typed reason.
10. A review whose broker could not be bound fails or degrades *on the record*; it never silently
    completes as if governed.

## Scope

- `runtime-ports/.../review/NativeReviewOperationProtocol.kt` and evidence models
- `runtime-application/.../review/ParallelCodeReviewRunner.kt` — launch wiring, absence diagnosis
- `runtime-domain/.../review/ParallelReviewFindingParser.kt` — parse result shape
- `runtime-domain/.../review/model/ReviewStageDegradation.kt` — new typed reasons
- `runtime-infra-fs/.../launcher/agentrun/AgentRunCommandBuilders.kt` — per-provider MCP + tool args
- `runtime-infra-fs/.../launcher/process/AgentRunProcessRunner.kt` — endpoint lifecycle
- `runtime-mcp/.../review/` — governed evidence tool surface
- `orchestration/contracts/review-context-schema.yaml` — accounting/contract bump

## Constraints

- The broker stays authoritative and in-process. The MCP endpoint is a transport in front of the
  existing `FileSystemReviewEvidenceBroker`; it must not re-implement policy, budget, or ledger.
- Enforcement must be pre-execution. Observing tool calls after the fact satisfies accounting but
  not the protocol's stated contract and is not sufficient for criteria 1-3.
- The locator design from SKILL-146 is retained. Bodies are pulled on demand; the packet keeps
  shipping locators.
- Loud-fail at every parse seam, per `AGENTS.md` and `docs/observability-policy.md`.
- Contract changes land in `orchestration/contracts/` first, then Kotlin constant, then parity test.

## Non-Goals

- Changing severity vocabulary, the finding admission gate, or the F-XXX register format.
- Reworking delegated specialist fan-out semantics.
- Resolving the output-format wording conflict between `parentPrompt` and
  `skills/bill-code-review-inline/content.md` beyond recording it (see Next Path).
- Retrofitting the transport onto agents that do not speak MCP.

## Diagnostic Evidence

Two consecutive pre-fix runs of the same bounded delta, `--execution-mode inline`, agent `claude`:

```
terminal_outcome: "register_absent"
model_turns: 1   tool_calls: 0   evidence_bytes: 0   expansions: 0
result_bytes: 1708 → 697
```

Post-stopgap, same delta:

```
terminal_outcome: "register_absent"
model_turns: 1   tool_calls: 0   evidence_bytes: 0   expansions: 0
result_bytes: 13631   output_tokens: 25263
```

`tool_calls`, `evidence_bytes`, and `expansions` are broker-fed and therefore structurally zero in
both. The counters that moved (`model_turns`, `provider_usage`) come from the decoded CLI JSON in
`AgentRunLaunchFacts`, not from the boundary. The stopgap made the worker review; it did not make
the boundary real, and the accounting cannot tell the difference.

Direct CLI repro of the toolset behaviour:

```
$ claude --print --tools "" ... <<< "List the files in the current directory using your tools"
"I don't have a file-listing tool available in this session — the tools I have are
 the PostHog exec proxy and the skill-bill telemetry/workflow tools..."
```

Both the empty-toolset stripping and the MCP leak are visible in that one response.

## Subtasks

1. **Parse result shape** — `ParallelReviewFindingParser` returns admitted findings plus structured
   rejections; a permissive `\[F-\d+\]` probe distinguishes absence from drift.
2. **Loud-fail the parse seam** — remove the blanket `runCatching` in `attributeInlineFindings`;
   `registerAbsenceReason` reports state, byte count, and offending line.
3. **Degradation records** — new `ReviewStageDegradationReason` values for an unbound broker and an
   inert boundary; emitted wherever this path degrades.
4. **Governed MCP evidence transport** — in-process loopback MCP endpoint over the existing broker,
   per-launch config, `--strict-mcp-config` isolation, worker tools reduced to governed operations.
5. **Provider parity and contract bump** — Codex/Cursor/Junie equivalents, review-context schema
   version, parity tests, and removal of the `6f47e771a` stopgap toolset.

Subtasks 1-3 are independently valuable and unblock diagnosis immediately. 4 depends on 1-3 for its
own verification. 5 depends on 4.

## Validation Strategy

- `(cd runtime-kotlin && ./gradlew check)` — note four pre-existing failures on main unrelated to
  this work: `GoalTelemetryStoreTest.kt` (detekt `MaxLineLength` ×3 + spotless), `parentPrompt`
  detekt `LongMethod` (63 > 60), and two `ReviewAccountingDurableRedactionTest` cases. Baseline
  confirmed by stashing and re-running.
- `skill-bill validate`
- End-to-end: the SKILL-194 subtask-1 delta must produce a parsed register with non-zero
  `evidence_bytes` and `expansions`.
- Negative: a path outside the assignment surface must be refused through the transport, and the
  refusal must appear in the accounting.

## Next Path

`parentPrompt` (`:1338-1344`) demands `specialist=... | path="..." | line=N` while
`skills/bill-code-review-inline/content.md` instructs the worker to emit `file:line`. The parser
accepts both, so this is not currently fatal — but two governed sources disagreeing on the output
contract is latent drift, and restoring `--agent` in `6f47e771a` put the agent-definition wording
back into effect. Reconcile in a follow-up once subtask 2 makes format drift observable.
