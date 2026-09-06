# SKILL-236 - telemetry-truth-and-runtime-reliability

## Mode

decomposed

## Intended Outcome

Skill Bill counts each logical telemetry event once despite delivery retries, reports measured lifecycle state without fabricated zeroes or misleading exhaustion flags, and removes reproducible review and generated-evidence ownership failures while preserving evidence and approval boundaries.

## Overview

Deliver three independently shippable increments: durable delivery identity and replay recovery; truthful metrics and correlated diagnostics; review execution and generated-evidence ownership recovery. Each increment includes its production integration, contract changes, migration, documentation, and regression coverage. This is spec preparation only; implementation starts separately through the goal runtime.

## Evidence and Interpretation

Read-only analysis of SkillBill PostHog project 152739 covered August 6, 2026 00:00 through September 6, 2026 14:01:33 Europe/Brussels. Query bounds were timestamp >= 2026-08-05 22:00:00 UTC and timestamp < 2026-09-06 12:01:33 UTC. Exclude missing/blank install IDs and test-install-id. Latest runtime outcomes use install plus session ID; logical goals use install plus issue key; historical exact payload signatures are an analytical approximation, not the future event identity design.

- Eight observed install IDs supplied 352 runtime sessions: 189 completed, 117 blocked, 21 paused, 14 stale, 11 latest error. One install supplied 278 sessions; 329 sessions used SNAPSHOT versions. These are not representative global user rates.
- Exactly 30 payload signatures each arrived 32,630 times: 26 projection measurements, two goal starts, one shared-evidence record, and one runtime start. The three start signatures were ingested across about 60 hours. This strongly suggests batch replay; the initiating producer, acknowledgement, queue, concurrency, or receiver defect remains to be established. Current sending code alone does not prove historical cause.
- Separately, 1,016 distinct rejection payloads map to 1,016 distinct install/workflow/phase/iteration combinations across 190 workflow IDs. Maximum reported iteration was 247, not 247 runs. Transport deduplication must preserve legitimate attempts, including identical-looking payloads emitted as different logical events.
- Output/handoff contracts explain 38 of 117 blocked runtime sessions; Git/ownership/finalization 29; review execution/admission 14; validation/build 14; process/provider 13; substantive prerequisites nine. Output/receipt redesign is already owned by SKILL-235.
- Review degradation spans 183 review-run IDs. Of 98 worker-failure runs, 94 identify verification and four adjudication. The category also includes unparseable output, not only process crashes. 110 review runs report 684 refused operations, including 462 repeated-read refusals. 41 runs have rejected candidates; 24 have no authorized evidence reads. Worker failures and refusals overlap in 51 runs; correlation is not proof of cause.
- Eight of nine runtime sessions blocked for paths outside the owned inventory mention .skill-bill/run-evidence. Other ownership blockers include another issue's spec paths, staged paths, checkout obstruction, and finalization failures. Only runtime-owned artifact accounting belongs to the targeted ownership fix; unrelated changes remain protected.
- Missing terminal outcome appears in 35 goal invocations across 20 issue keys; ten later completed and nine are last observed blocked. PR failure appears in 13 invocations across 11 issue keys; six later completed and five are last observed blocked. SKILL-235 owns decision/publication and receipt reconciliation; preserve these as cross-spec recovery verification targets.
- 25 completed sessions carry review_fix_cap_exhausted=true. Current loadFindingVerificationTelemetry derives that flag from review-fix iteration count >= 1, conflating loop use with exhaustion. Completion with a historical exhausted budget is not inherently contradictory; define and emit the intended meaning explicitly.
- Recent 0.3.1-SNAPSHOT, 0.3.2-SNAPSHOT and 0.3.3-SNAPSHOT records include 16 sessions with audit loops but zero attempted/resolved repair counts. Current lifecycle emission hardcodes recurring/new/attempted/resolved counters to zero.
- All 1,023 raw rejection records report exhausted_fix_loop=false despite terminal output-budget exhaustion messages. All 35 paused/stale runtime sessions lack a reason. Eleven latest unhandled-error sessions lack useful terminal exception identity; the one runtime_exception event is an unrelated legacy-prose lookup. Diagnostic persistence itself degraded 32 times across 11 workflow IDs.
- Fifteen stale quality checks report final_failure_count=0, including one initially failing session. Unknown results must not be interpreted as a measured clean gate.

Only 18 non-test standalone review completions are visible, and lifecycle/rejection identifiers cannot be joined directly. No reliable overall review failure rate, provider comparison, or compute cost can be inferred. The 60-hour replay is distinct from the August 17–23 correction-loop spike. Historical data may arrive late; current uncommitted changes are not historical evidence.

## Scope and Existing Ownership

SKILL-236 owns transport identity/outbox replay, telemetry metric semantics and failure correlation, and targeted review/evidence/ownership defects established by regression fixtures. Review refusal counts alone do not authorize weakening policy. Use existing broker accounting and cached evidence first; if supported rereads currently contradict a declared contract, change that contract explicitly and test both admission and rejection.

SKILL-235 owns prose publication, phase decisions, structured projection/repair-receipt redesign, disposition census, and commit/push/PR receipts. Reuse its final interfaces when available; do not recreate a parallel publication service or duplicate its acceptance. When a reproduced failure falls entirely under SKILL-235, record the acceptance mapping and evidence instead of patching the same subsystem twice. SKILL-233 owns architecture/module relocation; SKILL-234 covers cross-machine recovery. Recheck their state at implementation time and follow responsibility owners rather than stale file paths.

## Acceptance Criteria

1. Each new logical telemetry event receives a durable origin-scoped identity once, retained across batching, concurrent drain attempts, restart, acknowledgement loss and transport retries. Receiver identity is mapped to a supported deduplication mechanism with documented retention/limitations; separate logical attempts are never merged by timestamp or payload equality.
2. A committed event remains recoverable until acknowledged; acknowledged entries do not replay indefinitely. Reproduce the observed replay class at queue/transport boundaries and fix the responsible path, including safe concurrent-drain behavior. An unknown acknowledgement outcome cannot discard the event or generate a fresh identity. Delivery failure cannot block or recursively flood normal workflow telemetry.
3. Existing pending outbox rows acquire persistent identity through a governed migration without loss or identity collision across databases/installs. Document legacy receiver compatibility and rollout order; unsupported combinations emit explicit capability failures. Existing remote events are not deleted, rewritten, or reclassified automatically.
4. Review-fix and output-correction exhaustion fields come from the actual named budget and persisted transition, not merely entering a repair loop. Define whether each field means exhaustion at this transition or ever in the session and preserve that meaning after recovery. Ordinary repair does not imply exhaustion; genuine exhaustion is recorded even if subsequent authorized resume completes.
5. Audit new/recurring/attempted/resolved counts derive from authoritative durable audit state with documented grain and recurrence semantics. Missing or retired measurements are explicitly unavailable rather than zero. Paused, stale and interrupted quality checks distinguish unknown final findings from a completed zero-finding measurement in local stats, remote payloads and documented queries.
6. Applicable lifecycle, rejection, review and diagnostic events carry stable content-free correlation for logical goal/subtask, workflow, session, phase attempt/generation and event identity, plus runtime/contract version and actual provider/model when known. Absent context is explicitly unknown, never inferred from user text or fabricated. Anonymous-mode linkage preserves the same privacy-safe identity across event families.
7. Pauses, stale reconciliation, process failures, output admission, actual budget exhaustion, errors and diagnostic-write degradation preserve normalized actionable causes. Distinguish provider execution, invalid publication/parse, evidence policy refusal, genuine rejected finding and infrastructure persistence failure. Recording a diagnostic failure does not replace the primary error or require a successful write to the same failing store; bounded local fallback is observable without recursive event emission.
8. Reproducible verification failures at process/admission boundaries recover through supported existing policy or return a precise actionable non-success. Supported reads of unchanged assigned evidence can be fulfilled without repeated refusal loops through governed cache/retrieval semantics; stale, unassigned, cross-run or expanded-scope reads remain rejected. Missing or invalid findings never become NO_FINDINGS, verified, or approved by default.
9. Runtime-generated run-evidence artifacts alone cannot cause outside-owned-inventory blockage. Derive ownership from runtime provenance and the active workflow/checkpoint, not a blanket directory exemption; preserve another workflow's evidence and unrelated staged/worktree changes. Resume/finalization respects ignored/deleted paths and restores index state after refusal. Do not force-add artifacts or relax ownership just to make a run pass.
10. Production/test/unknown source and metric availability are explicit, and versioned local/remote aggregate definitions distinguish deliveries, logical events, attempts, sessions, goal invocations and logical goals. Small deterministic fixtures prove exact expected counts, recovery semantics and privacy. Historical queries retain deduplication caveats and never turn missing terminal events into proven abandonment or stale zeroes into pass.

## Decomposition and Acceptance Ownership

1. Durable telemetry delivery identity and replay recovery owns criteria 1–3 and delivery portions of 7 and 10. It independently prevents duplicate logical ingestion without waiting for metric redesign.
2. Truthful lifecycle metrics and correlated diagnostics owns criteria 4–7 and metric portions of 10. It independently fixes misleading measurements; it uses the event identity from subtask 1 when integrated, without requiring payload signatures or a new store.
3. Review execution and generated-evidence ownership recovery owns criteria 8–9 and incident/verification portions of 7 and 10. It ships targeted execution improvements rather than another telemetry-only foundation.

No intrinsic schema dependency requires a later subtask to wait for a predecessor's design; all are specified now and are independently useful. The goal executes them in listed order on one branch. Preserve prior increments and the existing ownership exclusions.

## Constraints

- Prepare local artifacts only now. No implementation, goal launch, reinstall, commit, push, tracker issue, deployment or PostHog mutation is authorized by preparation.
- During implementation preserve all unrelated dirty work and active workflow state. Do not reset existing manifests or reconstruct overwritten files from historical references.
- New/changed runtime contracts start under orchestration/contracts with governed version changes, Kotlin parity, typed errors and loud-fail parse seams. Use existing outbox, lifecycle, broker and workflow ownership boundaries; no parallel analytics pipeline or generic workflow framework.
- Read docs/code-principles.md, docs/observability-policy.md, docs/telemetry-privacy.md and relevant contracts. Read docs/skill-source-generation.md before any source-skill/rendering changes; authored content.md remains canonical and ./install.sh is required only when those changes call for it.
- Preserve telemetry off/anonymous/full consent and queue-clearing semantics. Do not add prompts, raw reports, arbitrary exception messages, repository paths, issue text, credentials or personal data to remote diagnostic payloads. A new transport ID must not resurrect explicitly discarded telemetry.
- Use few high-value regression tests named after the real failure they catch. bill-unit-test-value-check remains the test-value gate. No new source comments. Broader architecture cleanup and model/provider replacement are out of scope.
- The goal runtime owns one final commit per subtask and push bookkeeping. Feature branch metadata is preparation intent, not authorization to switch branches during this task.

## Non-Goals

- Reimplementing SKILL-235's prose/receipt/decision migration or SKILL-233/234 architecture and cross-machine recovery work.
- Silently allowing forbidden evidence reads, widening owned paths, inferring approvals, suppressing findings, or replacing failed quality checks with success.
- Claiming every historical refusal or legitimate user prerequisite is a defect, or promising a numerical global success-rate improvement from eight installations.
- Deleting historical PostHog duplicates, backfilling personal data, changing telemetry consent, or deploying the relay without the applicable authorization.
- Implementing a durable completion/PR reconciliation redesign under the guise of telemetry instrumentation.

## Starting Points

Follow current owners of TelemetrySyncRuntime, TelemetryOutboxStore, TelemetryProxyPayloadMappers, TelemetryProxyContracts, docs/cloudflare-telemetry-proxy/worker.js, FeatureTaskRuntimeLifecycleTelemetryEmission, loadFindingVerificationTelemetry, rejection measurement production, lifecycle reconciliation, ReviewStageDegradationSelection, governed review evidence accounting, and runtime-owned artifact inventory/finalization. Update observability/privacy and aggregate-query documentation with the final semantics.

## Validation Strategy

Preparation validates the entire manifest against Draft 2020-12 decomposition contract 0.5, coherence constraints, unique existing spec paths and executable acceptance headings before publishing the bundle. Implementation uses deterministic fake-transport/receiver tests plus real SQLite/restart/concurrency and broker/index boundary fixtures; no live PostHog writes are needed. For any receiver-specific deduplication claim, verify the supported API contract and state its retention limitation; a producer-only test is insufficient proof of end-to-end idempotency.

Follow bill-code-check and the selected pack route during implementation. A build-selected goal child runs only the pack-declared buildability gate and its prescribed cache-bypassing confirmation; do not substitute a repository-wide check or another agent gate. Report checks not executed under the selected route. Run targeted privacy and aggregate acceptance/rejection fixtures where permitted. No build or application tests are necessary for this spec-only preparation.

## Next Path

skill-bill goal SKILL-236
