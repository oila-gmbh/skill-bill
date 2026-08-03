# Follow-up specification: delegated review documentation

**Order:** 8 of 9  
**Depends on:** `spec_followup_telemetry.md` and `spec_followup_installation.md`  
**Purpose:** keep user-facing provider policy, reliability boundaries, and
telemetry guidance aligned with the executable contracts.

## Scope and targets

- `.feature-specs/SKILL-145-delegated-code-review-reliability/decision.md`
- `.feature-specs/SKILL-145-delegated-code-review-reliability/reliability-contract.md`
- `orchestration/review-orchestrator/PLAYBOOK.md`
- `orchestration/review-delegation/PLAYBOOK.md`
- `docs/review-telemetry.md`

Documentation must name Codex, Claude, Cursor, Junie, Copilot, Opencode, and
Zcode with independent classifications; cite the historical ledger and
provider evidence; state all falsifiable promotion criteria; explain deadline
and evidence-retention rationale. The fixed promotion bounds are 120 seconds
p95 for 1–2 areas, 300 seconds for 3–5 areas, and 600 seconds for 6 or more
areas, with at most 256 evidence events, 1,048,576 aggregate UTF-8 bytes, and
30 days of retention per review. The promotion protocol requires 20 launched
canaries per provider and size class within one 30-consecutive-UTC-day window,
uses nearest-rank p95, and counts failed or timed-out canaries as retained
failed samples rather than dropping or replacing them. Persistence cleanup,
not schema or telemetry rejection, deletes evidence at the 30-day boundary.
Preserve inline as default and state that
`auto` remains inline. Every unresolved historical item must point to an owner,
evidence status, and rationale in the ledger.

The playbooks must describe unsupported-provider termination, no implicit
inline fallback for explicit delegated requests, provider strategy isolation,
and the separation between durable progress and liveness observations.

## Exclusions

Do not claim promotion from a single run. Keep documentation consistent with
the authoritative schemas and injected-strategy boundaries.
