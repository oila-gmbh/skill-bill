# SKILL-195 Subtask 4 — Governed MCP evidence transport

## Scope

Give `NativeReviewOperationProtocol` the adapter its own contract requires, so a review worker
reaches repository content only through the broker.

The evidence models are already request/response shaped for this:
`ReviewEvidenceBatchRequest` carries a lane and a list of requests with `offset` / `limit` /
`paginationToken`; `ReviewEvidenceResult` returns `content`, `bytes`, `cumulativeBytes`,
`expansionCount`, `budgetExceeded`, and `forbidden`
(`runtime-ports/.../review/model/ReviewEvidenceModels.kt:24-60`). That is an MCP tool surface
already; only the transport is missing.

Deliver:

- An in-process MCP endpoint, bound per launch to that lane's broker, exposing two governed
  operations: read admitted evidence, and request an expansion. The endpoint is a thin adapter over
  `BrokerBackedNativeReviewOperationProtocol` — it must not re-implement reachability policy, byte
  budgeting, the expansion ledger, or lane termination.
- A per-launch MCP config written for the child process, and `--mcp-config` plus
  `--strict-mcp-config` on the Claude launch so the worker sees the governed server **and no
  host-registered MCP server**.
- Worker tools reduced to the governed operations. The `Read,Grep,Glob,Bash` grant from `6f47e771a`
  is removed on the Claude governed-review path; delegated fan-out retains `Agent,Task` for
  specialist launches but loses raw filesystem access on the same terms.
- Endpoint lifecycle owned by `AgentRunProcessRunner`, which today only asserts the broker is
  present (`:47-48`, `:65-68`): bind before launch, tear down on completion, timeout, or crash.
- A refusal path: a `forbidden` or `budgetExceeded` result is returned to the worker as a tool
  response, and the operation does not execute.

Loopback binding with a per-launch credential; the endpoint must not be reachable by anything other
than that launch.

## Acceptance Criteria

1. A governed Claude review launch carries `--mcp-config` and `--strict-mcp-config`, and its tool
   list names only governed operations — no `Read`, `Grep`, `Glob`, or `Bash`.
2. `authorizeExpansion` and `read` are reached on every governed review that consumes evidence.
3. A worker request for a path outside the assignment surface returns a refusal and reads nothing;
   the refusal is recorded.
4. Byte-budget exhaustion mid-review terminates the lane through the existing `ReviewBudgetOutcome`
   path.
5. `evidence_bytes` and `expansions` in review accounting are non-zero for a review that consumed
   evidence, and the subtask 3 unexercised-boundary record stops appearing on healthy runs.
6. The endpoint is torn down on normal completion, timeout, and crash; no endpoint outlives its
   launch.
7. A worker cannot reach a host-registered MCP server from inside a governed review.
8. Locators continue to be shipped in the packet; bodies are pulled on demand.
9. `(cd runtime-kotlin && ./gradlew check)` passes.

## Non-Goals

- Codex, Cursor, and Junie parity — subtask 5.
- Re-implementing any policy inside the MCP layer.
- Replacing locators with inlined bodies. The SKILL-146 least-context property is retained.
- Adding governed operations beyond read and expansion.

## Dependency Notes

Depends on subtasks 1-3. Subtask 3's unexercised-boundary record is the acceptance instrument for
criterion 5 — without it, "the boundary is now real" is unmeasurable.

## Validation Strategy

Concentrate coverage on the governance boundary; this is an authorization and metering path, so it
earns real tests.

- A read for an unreachable path is refused and returns no content. Catches the boundary being a
  pass-through.
- Cumulative reads past the byte budget terminate the lane rather than continuing to serve.
- A completed governed review reports non-zero `evidence_bytes` and `expansions`, and emits no
  unexercised-boundary record.
- Endpoint teardown after a killed launch leaves nothing bound.
- The launch command for a governed Claude review contains `--strict-mcp-config` and no raw
  filesystem tool.

End-to-end: re-run the SKILL-194 subtask-1 delta and require a parsed register with non-zero
evidence counters. Then `(cd runtime-kotlin && ./gradlew check)`.

## Next Path

```bash
skill-bill goal SKILL-195
```
