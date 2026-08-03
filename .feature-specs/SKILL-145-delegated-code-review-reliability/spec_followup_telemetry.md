# Follow-up specification: delegated lifecycle telemetry

**Order:** 6 of 9  
**Depends on:** `spec_followup_provider-adapters.md`  
**Purpose:** record bounded lifecycle measurements without replacing durable
state with provider observations.

## Scope and targets

- `orchestration/contracts/telemetry-event-schema.yaml`
- `orchestration/telemetry-contract/PLAYBOOK.md`
- `runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/telemetry/model`
- `runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/telemetry`
- `runtime-kotlin/runtime-application/src/test/kotlin/skillbill/application/telemetry`

Telemetry covers elapsed time, token dimensions, process count, observable MCP
startup count, selected and completed areas, worker loss, predicted and actual
waves, deadline scope/outcome, and aggregation completeness. It records
provider observations as measurements only; durable worker progress and
terminal status remain authoritative.

Promotion telemetry must classify reviews as small (1–2 areas), medium (3–5
areas), or multi-area (6 or more areas) and evaluate p95 elapsed time against
120, 300, or 600 seconds respectively. It must also report per-review evidence
event count, aggregate UTF-8 evidence bytes, and retention age against limits
of 256 events, 1,048,576 bytes, and 30 days after terminal persistence.

## Acceptance and rejection cases

Accept bounded lifecycle events with stable review/packet/assignment/provider/
worker identities, positive timestamps, nonnegative metrics, and bounded
diagnostic references. Reject unknown event versions, negative counts,
incomplete identity, oversized fields, raw prompts/diffs/transcripts/tool
logs, missing size-class measurements, latency above the applicable bound,
evidence count or bytes above the retention limit, retention older than 30
days, and telemetry that claims completion from heartbeat or token activity.

Use representative small, medium, and multi-area fixtures. Keep live provider
canaries conditional on an installed authenticated provider and treat their
observations as evidence, never as progress authority.

## Exclusions

Do not emit a second review lifecycle or retain full provider output.
