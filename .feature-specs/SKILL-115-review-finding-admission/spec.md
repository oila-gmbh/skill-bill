# SKILL-115 - Review Finding Admission And Comment Quality

Status: ready for implementation.

## Outcome

Every Skill Bill code-review lane applies one provider-neutral admission gate
before emitting a finding. A finding is reported only when it identifies a
discrete, actionable defect introduced or materially worsened by the reviewed
change, demonstrates the concrete affected behavior with evidence, and is
likely to be useful to the author. Findings state the triggering conditions,
observable consequence, and minimal repair concisely; speculative,
pre-existing, intentional, stylistic, and non-actionable observations are
suppressed.

The change strengthens the shared review contract consumed by inline and
delegated reviewers without replacing Skill Bill's routing, specialist packs,
F-XXX risk register, severity/confidence vocabulary, telemetry, learnings, or
verdict model.

## Background And Motivation

The existing shared specialist contract already requires meaningful issues,
evidence, observable consequences, minimal fixes, and a higher threshold for
Minor or lower-confidence findings. It does not state several important
finding-admission rules precisely enough:

- whether the reviewed change introduced or materially worsened the problem
- whether a claimed downstream effect is demonstrated rather than speculative
- whether the behavior is an intentional part of the change
- whether the finding is one discrete root cause the author can act on
- which scenario, environment, state, or input makes the failure occur
- whether the cited location is the smallest useful changed location
- whether reviewers should continue after finding the first qualifying issue

These gaps permit technically plausible but low-value findings and make output
quality depend too heavily on a provider's built-in review prompt. Skill Bill
needs one governed, provider-neutral admission contract inherited by every
maintained pack and native specialist.

## Decided Behavior

### Finding admission gate

A candidate finding is emitted only when all applicable statements are true:

1. It concerns behavior introduced or materially worsened by the reviewed
   change. A pre-existing defect is out of scope unless the change makes it
   newly reachable, increases its impact, or an explicit acceptance criterion
   requires the change to correct it.
2. It has a meaningful correctness, security, privacy, persistence,
   concurrency/lifecycle, performance, accessibility, testing/quality-gate,
   API/contract, architecture, or maintainability consequence recognized by
   the existing shared and platform-specific contracts.
3. It is a discrete root cause with a bounded repair. Broad codebase concerns,
   multi-issue bundles, and redesign preferences are not findings.
4. It is supported by concrete evidence from the diff and relevant repository
   context. A possible downstream break is reportable only when the affected
   caller, consumer, contract, or execution path is identified.
5. It does not depend on an unstated assumption about repository behavior or
   author intent. Uncertainty that can be resolved from local code, tests,
   manifests, configuration, or documented contracts must be investigated
   before emission.
6. It is not merely an intentional behavior change. A documented or clearly
   encoded intentional change remains reportable only when it violates a
   stronger repository, compatibility, safety, or acceptance contract.
7. It is actionable and likely to be repaired if the author sees it. Questions,
   optional improvements, and observations without a necessary change are
   excluded from the risk register.

When no candidate passes the gate, the reviewer emits an empty risk register
and an approving verdict as allowed by the existing output contract. Reviewers
must prefer silence over a speculative or low-value finding.

### Finding construction

Every admitted finding must:

- represent one distinct root cause; overlapping manifestations are
  coalesced, preserving the highest justified severity and confidence
- identify the scenario, environment, state, or input required to trigger the
  defect when the consequence is conditional
- explain the concrete user-visible, externally observable, operational, or
  contract consequence without overstating severity
- cite the smallest useful `file:line` location in the reviewed diff; omission
  defects cite the changed line that creates the unmet obligation
- use a concise, matter-of-fact description that can be understood without
  close reading or unnecessary location prose
- include a minimal concrete repair consistent with the existing specialist
  contract, without generating or applying a patch

Reviewers report every distinct candidate that passes the admission gate and
do not stop at the first finding. The existing per-specialist seven-finding cap
is replaced by strict admission plus root-cause coalescing; the final action
item limit remains unchanged. Merge behavior continues to deduplicate findings
across specialists and parallel lanes.

## Scope

1. Update `orchestration/review-orchestrator/PLAYBOOK.md` so the canonical
   `Shared Contract For Every Specialist` owns the complete finding-admission
   and finding-construction rules.
2. Update `orchestration/review-orchestrator/specialist-contract.md` through
   the governed exact-parity path so delegated specialists receive the same
   rules byte-for-byte.
3. Reconcile existing shared rules with the new gate instead of appending
   contradictory guidance. Preserve the explicit repository comments-policy
   rule, the Minor/lower-confidence threshold, and mandatory reporting of
   evidence-backed Blocker/Major defects.
4. Remove the arbitrary seven-findings-per-specialist limit from both canonical
   shared sections. Preserve cross-specialist root-cause deduplication,
   severity/confidence preservation, and final action-item prioritization.
5. Add focused contract tests that fail when either shared surface omits or
   weakens the introduction/worsening rule, evidence rule, affected-consumer
   rule, intentional-change exclusion, actionable-author-value rule,
   trigger-condition rule, minimal diff-location rule, empty-review preference,
   all-qualifying-findings rule, or root-cause coalescing rule.
6. Preserve and exercise the existing exact-parity validation between the full
   orchestrator and compact specialist contract. Add acceptance and rejection
   fixtures for the admission contract where the current conformance-test
   architecture supports governed prose fixtures.
7. Refresh installed staging with `./install.sh` after the governed source and
   tests pass so local agents consume the new shared contract.
8. Record a reusable SKILL-115 entry in
   `orchestration/review-orchestrator/agent/history.md` after implementation.

## Acceptance Criteria

1. The canonical shared specialist contract explicitly requires defects to be
   introduced or materially worsened by the reviewed change, with narrow
   exceptions for newly reachable/increased impact and explicit acceptance
   criteria requiring correction.
2. The contract rejects speculative downstream impact unless the affected
   caller, consumer, contract, or execution path is identified from evidence.
3. The contract rejects bundled, non-actionable, intentional, assumption-based,
   style-only, and author-unlikely-to-repair observations without weakening
   existing reportable correctness, security, persistence, lifecycle, testing,
   accessibility, architecture, maintainability, or contract categories.
4. Every admitted conditional finding must state its triggering scenario,
   environment, state, or input and its concrete consequence at an accurately
   calibrated severity.
5. Every finding cites the smallest useful changed `file:line`; omission
   findings cite the changed line that establishes the missing obligation.
6. Finding descriptions remain concise and matter-of-fact, contain one root
   cause, avoid redundant location prose, and include a minimal concrete repair
   without applying a patch.
7. Reviewers prefer an empty risk register over speculative or low-value
   output and report every distinct qualifying finding after root-cause
   coalescing rather than stopping early or enforcing the old seven-finding
   specialist cap.
8. `PLAYBOOK.md` and `specialist-contract.md` retain exact byte parity for the
   shared specialist and report-structure sections, and focused tests protect
   every new admission/construction invariant from silent removal.
9. Skill Bill's F-XXX risk-register syntax, `Blocker | Major | Minor` severity,
   `High | Medium | Low` confidence, review session/run identifiers, telemetry,
   triage, learnings, merge provenance, action items, and verdict contracts are
   unchanged.
10. `skill-bill validate`, `(cd runtime-kotlin && ./gradlew check)`,
    `npx --yes agnix --strict .`, and `scripts/validate_agent_configs` pass;
    `./install.sh` refreshes local staging; no generated wrappers, support
    pointers, provider-specific agents, or install artifacts are committed.

## Non-Goals

- Do not adopt Codex's JSON review schema, P0-P3 priority vocabulary, binary
  patch-correctness verdict, imperative finding titles, or provider-specific
  inline-review protocol.
- Do not change Skill Bill's severity or confidence enums, finding parser,
  telemetry schema, feedback/learning storage, or runtime review verdict
  derivation.
- Do not normalize existing `Critical`, `Nit`, or `Blocker` vocabulary drift
  outside the canonical shared specialist contract; severity cleanup is a
  separate change.
- Do not rewrite platform-specific review rubrics or move platform knowledge
  into shared orchestration.
- Do not implement automatic fixes or post review comments to an external PR.
- Do not add a new runtime contract version or shell contract version.

## Constraints

- Universal admission behavior belongs in
  `orchestration/review-orchestrator/`; platform packs retain only behavior
  whose review meaning changes by language, runtime, framework, or toolchain.
- `specialist-contract.md` remains the compact delegated-worker subset and its
  governed sections must exactly match the canonical `PLAYBOOK.md` sections.
- Authored source and generated-output boundaries in
  `docs/skill-source-generation.md` remain unchanged.
- Existing explicit repository policy treating comments that merely restate
  code as a Minor maintainability defect remains enforceable.
- Tests should assert semantic contract clauses with stable focused markers or
  structured fixtures, not duplicate the entire prose outside the established
  parity test.
- Comments added during implementation must follow the repository comments
  policy.

## Validation Strategy

Run focused parity and admission-contract tests first, followed by the full
repository gates and install refresh:

```bash
(cd runtime-kotlin && ./gradlew :runtime-infra-fs:test --tests '*SpecialistContractParityTest' --tests '*ReviewFindingAdmissionContractTest')
skill-bill validate
(cd runtime-kotlin && ./gradlew check)
npx --yes agnix --strict .
scripts/validate_agent_configs
./install.sh
git status --short
```

The final status check must show only authored SKILL-115 implementation,
specification, tests, and boundary history changes; generated runtime/install
outputs must remain untracked and uncommitted.
