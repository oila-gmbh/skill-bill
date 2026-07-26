# SKILL-145: Delegated code-review reliability investigation

## Intended Outcome

Determine whether delegated code review can operate predictably across supported
agents, and define the smallest enforceable contract required before it can be
considered reliable. The investigation must use reproducible evidence rather than
assuming that a live parent process, spawned worker, or MCP heartbeat represents
meaningful review progress.

The result is a provider-aware go/no-go decision with measured limits, failure
classifications, and an implementation-ready remediation plan. Inline remains the
default; delegated review remains explicit opt-in while this work is unresolved.

## Acceptance Criteria

1. The investigation records a reproducible lifecycle trace for coordinator startup, routing, worker launch, worker progress, worker completion, aggregation, and terminal review output.
2. Durable status distinguishes workers launched, running, completed, failed, timed out, and aggregated; process or MCP heartbeats alone never count as specialist progress.
3. Review scope is proven immutable and bounded from the caller-selected base and head, with tests preventing unrelated historical changes from expanding routed areas.
4. Capacity behavior is measured for one lane and for deterministic multi-wave execution, including coordinator slot ownership and the maximum supported concurrent workers.
5. Worker failures, unavailable native agents, invalid outputs, interrupted coordinators, aggregation failures, progress-idle expiry, and hard-deadline expiry each produce an explicit terminal classification.
6. Infrastructure or lifecycle failures durably block and retain bounded transcript evidence; only a normally completed but schema-invalid response may enter the schema repair loop.
7. Provider behavior is evaluated independently for Codex, Claude, and every other agent that claims delegated support; no provider-specific mitigation changes another provider's launch or liveness policy.
8. Token, elapsed-time, process-count, MCP-startup, and result-completeness measurements are captured for representative small, medium, and multi-area diffs.
9. The investigation defines bounded startup, progress-idle, per-worker, aggregation, and whole-review deadlines with a documented rationale and deterministic watchdog behavior.
10. The final decision states whether delegated review is supportable now, supportable behind explicit experimental opt-in, or unsupported, with falsifiable promotion criteria.
11. Any recommended implementation work is decomposed into schema, runtime, provider-adapter, telemetry, and test changes without changing inline review’s default or full-area checklist.

## Constraints

- Inline code review remains the default, and `auto` resolves inline.
- Delegated review is selected only by an explicit delegated argument.
- The investigation must not weaken required review-area coverage.
- Durable workflow state, not in-memory coordinator state, is authoritative.
- Provider-specific behavior stays behind injected command-builder/process strategies.
- Claude, Junie, OpenCode, Copilot, and other agents retain existing behavior unless evidence for that provider justifies an explicit, tested change.
- No generated skill wrappers, provider-native outputs, or support pointers are committed.

## Known Failure Modes

The investigation must reproduce or conclusively disposition every item below.
Items marked **observed** occurred during SKILL-143 on 2026-07-25. Items marked
**known contract risk** are already represented by runtime guards or prior tests
but still require end-to-end delegated-review evidence.

### Installation and native-agent identity

1. **Source-checkout-coupled preflight — observed.** Native-agent preflight recomputed the expected installed generation from `repoRoot/skills` and `repoRoot/platform-packs`. Reviewing an unrelated repository, or removing the Skill Bill source checkout after installation, produced `recorded target is not the current installed generation` even though the durable installed inventory and symlink were valid.
2. **Selected-pack/native-agent mismatch — observed.** A review routed to a pack outside the user's installed iOS/KMP/Kotlin selection and failed with `managed inventory entry is missing`. Delegated preflight required an agent the user never selected.
3. **Stale runtime process after reinstall — observed.** Reinstalling files did not update already-running MCP JVMs. Old processes continued executing old preflight code until the Codex session or MCP process restarted, making a repaired installation appear broken.
4. **CLI/MCP runtime generation skew — observed.** Updating the CLI distribution without replacing the MCP distribution's copied dependency JAR left two runtime surfaces on different generations.
5. **Repository-presence assumption — observed.** Installation behavior implicitly required the Skill Bill repository to remain present after `./install.sh --from-source`, violating the expectation that an installed runtime is self-contained.

### Routing and scope integrity

6. **Content-only false ownership — observed.** Routing allowed a pack with zero path score to win from broad content tokens. PHP signals such as `function ` and `use ` matched prose or unrelated source and caused a non-PHP project to require PHP review agents.
7. **Ambiguous or unsupported stack without generic fallback — observed.** With no valid concrete owner, routing selected a false concrete language instead of a generic review path or an explicit unsupported result.
8. **Historical scope expansion — observed.** The delegated review packet's selected base SHA included older SKILL-142 changes, so an ostensibly small SKILL-143 review examined a broader Kotlin surface and routed six areas. Reported working-tree diff size did not communicate the actual immutable packet size.
9. **Scope metric disagreement — observed.** Goal status reported four files and 81 insertions while the review transcript inspected a materially broader base-to-HEAD delta. Operators could not tell which measurement governed routing.
10. **Baseline-untracked contamination — known contract risk.** Incorrectly including or excluding baseline-untracked paths can add unrelated specs to the packet or hide caller-owned scope.
11. **Worker rediscovery divergence — known contract risk.** A worker that re-derives the diff, stack, add-ons, learnings, or rubric instead of consuming its digest-backed assignment can review a different scope from its coordinator.
12. **Layered-pack over-routing — observed.** KMP/Kotlin composition plus false language ownership can multiply baseline and specialist lanes rather than selecting one coherent composition.

### Coordinator planning and capacity

13. **Setup dominates a small review — observed.** The coordinator spent roughly four and a half minutes loading the review skill, routing rules, telemetry contracts, and learnings before launching its first worker.
14. **Excessive lane selection — observed.** The broadened scope selected architecture, platform correctness, persistence, API contracts, testing, and security for a change the operator expected to be small.
15. **Coordinator consumes a worker slot — observed.** With four total Codex collaboration slots, the coordinator left capacity for only three specialists. Six selected lanes therefore required deterministic waves.
16. **Late-wave starvation — observed.** Platform correctness, API contracts, and security started only after earlier waiting, extending latency even though no durable earlier-lane result was visible.
17. **Full provider bootstrap per worker — observed.** Each isolated Codex specialist launched a complete Codex session and its configured MCP servers. Process count and startup cost scaled with worker count.
18. **Worker launch without bounded admission — known contract risk.** A coordinator can select more lanes than fit within its latency or token budget without rejecting, narrowing, or reporting the predicted number of waves before launch.
19. **Nested orchestration risk — known contract risk.** A routed worker may launch its own workers or baseline orchestrator if flattening and operation boundaries are not enforced, multiplying capacity and obscuring ownership.
20. **Broad global worker polling — observed.** The coordinator used `list_agents` during normal execution instead of tracking only returned worker IDs, contrary to the delegated-review contract and vulnerable to unrelated-agent interference.

### Progress, liveness, and deadlines

21. **No durable specialist lifecycle — observed.** Worker launches and in-session progress existed in the Codex rollout but Skill Bill durable status recorded zero specialist completions and zero findings throughout the run.
22. **Coordinator-local evidence loss — observed.** The coordinator reported that three defects were corroborated, but those partial results were not persisted where the runtime or operator could inspect them.
23. **Heartbeat treated as review progress — observed.** Codex read-only runs used `HEARTBEAT_EXTENDED`; live Codex and MCP subprocesses kept the idle watchdog disarmed without any declared specialist or aggregation progress.
24. **No effective whole-review deadline — observed.** The review launch carried a progress-idle timeout but no independent hard timeout. Heartbeat extension could therefore keep the process alive indefinitely.
25. **Unstreamed-output blind spot — observed.** The parent runtime received no usable stdout until the Codex process terminated, so it could not distinguish reading, worker execution, repeated waits, or aggregation.
26. **Wait-loop activity without progress — observed.** The coordinator repeatedly issued 20–30 second `wait_agent` calls and global listings; these showed that the session was alive but did not advance durable review state.
27. **Worker progress cannot renew the correct lease — known contract risk.** A worker may be productive while its progress is visible only in a nested provider session, leaving the owning workflow unable to distinguish progress from a hang.
28. **No per-worker deadline — known contract risk.** One non-terminal specialist can hold aggregation open even after every other lane finishes.
29. **Cancellation propagation gap — known contract risk.** Interrupting or timing out the coordinator may leave native workers, MCP servers, or leases alive without a durable cancelled state.

### Completion, aggregation, and failure classification

30. **Zero completed results at interruption — observed.** At termination, all tracked workers still appeared running and no complete specialist result had reached durable review accounting.
31. **Partial findings not safely recoverable — observed.** The coordinator had identified material defects, but termination discarded them because aggregation had not emitted a terminal envelope.
32. **Interrupted output entered schema repair — observed.** After coordinator termination, the captured response failed the phase-output schema at six root requirements and the runtime entered `review` fix-loop attempt 2. An interrupted or non-zero launch must instead block as `PROCESS_FAILURE`; schema repair is valid only after a normal zero-exit response.
33. **Root-only schema diagnostics — observed.** The warning rendered six violations as repeated `<root>` locations, preventing the operator from seeing which required fields were absent.
34. **Malformed terminal envelope masks process context — observed.** Schema-gate output did not make the preceding cancellation and incomplete specialist set the primary failure reason.
35. **Missing-result aggregation — known contract risk.** A coordinator can produce an apparently complete merged review without proving one result exists for every selected lane.
36. **Duplicate or conflicting findings — known contract risk.** Parallel lanes may report the same root cause with different severity, confidence, or ownership; an unstable merge can lose evidence or inflate counts.
37. **Worker identity/result mismatch — known contract risk.** Results need a durable binding to assignment digest, provider, agent ID, area, scope digest, and attempt so stale or cross-run output cannot be accepted.
38. **Coordinator crash before terminal persistence — known contract risk.** Workers may all finish but the workflow can remain running if aggregation or terminal write crashes.
39. **Retry duplicates completed work — known contract risk.** A resumed coordinator may relaunch already completed specialists when per-lane completion is not durable and idempotent.
40. **Schema repair relaunches expensive review — known contract risk.** Treating aggregation-format defects as a whole-review retry can repeat all specialists instead of repairing only the terminal envelope.

### Telemetry, operator visibility, and trust

41. **Misleading top-level status — observed.** `current_phase: review`, `findings_in_scope: 0`, and no latest liveness signal suggested no work even while six specialist sessions were active.
42. **No predicted or actual wave count — observed.** Status did not show selected lanes, active wave, queued lanes, completed lanes, or aggregation readiness.
43. **No bounded transcript pointer — observed.** Operators had to discover raw Codex rollout JSONL files manually to explain the delay.
44. **Process activity mistaken for correctness — observed.** CPU use, futex waits, and MCP child processes proved only that processes existed, not that review coverage or aggregation advanced.
45. **Provider behavior leakage — known contract risk.** A global liveness change intended for Codex could regress Claude or other agents with different streaming and heartbeat capabilities.
46. **Token and startup amplification invisible — observed.** Each worker loaded substantial system, repository, skill, and MCP context, but runtime status exposed neither cumulative token use nor bootstrap cost.
47. **No reliability promotion gate — known contract risk.** Delegated mode lacked explicit thresholds for bounded latency, complete area coverage, silent-worker-loss rate, terminal-state determinism, and provider-specific conformance.

## Non-Goals

- Re-enabling delegated review as a default.
- Treating more generous timeouts as proof of reliability.
- Counting subprocess existence, CPU use, file reads, or MCP startup as completed review work.
- Redesigning inline review.
- Implementing every remediation discovered by the investigation in this issue.

## Validation Strategy

- Use deterministic fake launchers and clocks for lifecycle and watchdog tests.
- Capture provider-specific command-builder tests proving behavioral isolation.
- Exercise real delegated canaries on bounded fixture diffs when the provider is available.
- Compare durable progress records with process transcripts and final aggregated output.
- Run the appropriate Kotlin module checks plus `skill-bill validate`, `npx --yes agnix --strict .`, and `scripts/validate_agent_configs`.

## Delivery Plan

1. Build the lifecycle evidence model and reproduce current failure modes.
2. Evaluate provider-specific liveness, scope, capacity, and aggregation behavior.
3. Produce the go/no-go decision and an implementation-ready reliability contract.
