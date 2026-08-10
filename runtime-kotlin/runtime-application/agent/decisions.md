# runtime-application decisions

## 2026-08-10 — Validate-phase gate execution is runtime-owned (SKILL-180)

**Context.** Validate previously instructed the agent to invoke `bill-code-check`, so gate-run
count, batching, and terminal cache-bypass evidence were unobservable and unenforceable.

**Decision.** The runtime resolves a pack-declared `validation_gate`, runs argv in the repository
root, projects findings for agent repair, reruns the gate to verify, and persists measured
`gate_run_count` / `gate_runs`. The validate agent receives bounded findings and must not invoke
the gate or any quality-check skill. Audit and repair evidence stay read-only repository facts.

**Alternatives considered.** Agent-reported gate_run_count (rejected: self-reporting cannot detect
runaway reruns). Hardcoded Gradle `--no-build-cache` in runtime (rejected: stack-specific; packs
declare cache-bypass argv).
