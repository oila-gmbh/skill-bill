package skillbill.application.featuretask

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_PLAN_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition

/**
 * The preplan, plan, and implement phases each emit a bounded planning projection that the NEXT
 * phase's launch seam parses with additionalProperties:false against
 * feature-task-runtime-planning-projections-schema.yaml. Naming the fields in prose (as the phase
 * directive does) is not enough to hit the shape: the projection lives DIRECTLY on produced_outputs
 * (never nested under a projection_kind-named key), rollout and each deviations entry are OBJECTS,
 * and task_id is lowercase-kebab. An agent left to infer the shape emits a nested wrapper, a prose
 * rollout string, a free-text deviation, or "T1" and is rejected at the seam. Each example mirrors
 * PlanningProjectionFixtures so the guidance and the gate cannot drift.
 */
internal object FeatureTaskRuntimePhaseProjectionShapes {
  fun exampleFor(phaseId: String, agentRunValidateFallback: Boolean = false): String = when (phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN -> PREPLAN
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN -> PLAN
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT -> IMPLEMENT
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_FIX -> PLAN_FIX
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> IMPLEMENT_FIX
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
      if (agentRunValidateFallback) {
        VALIDATION
      } else {
        VALIDATION + VALIDATION_FULL_RUNTIME_OWNED_REPAIR
      }
    else -> ""
  }

  private val PREPLAN: String =
    "\n    - Required produced_outputs shape: emit these fields DIRECTLY on produced_outputs — do NOT\n" +
      "      nest them under a \"preplanning_digest\" key — and \"rollout\" is an OBJECT, never a string:\n" +
      "      ```json\n" +
      "      { \"projection_kind\": \"preplanning_digest\",\n" +
      "        \"contract_version\": \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\",\n" +
      "        \"affected_boundaries\": [\"<module or boundary touched>\"], \"patterns_and_decisions\": [],\n" +
      "        \"risks\": [\"<concrete risk>\"],\n" +
      "        \"rollout\": { \"flag_required\": false, \"flag_pattern\": \"none\",\n" +
      "          \"notes\": \"<rollout note, or N/A>\" },\n" +
      "        \"validation_strategy\": [\"<how the change is validated>\"],\n" +
      "        \"unresolved_questions\": [], \"evidence_refs\": [],\n" +
      "        \"selected_boundary_headings\": [\"<heading_id copied verbatim from the boundary catalog>\"] }\n" +
      "      ```\n" +
      "      flag_pattern is one of none, simple_conditional, di_switch, legacy. Optional arrays may be\n" +
      "      omitted or []; every listed string must be non-empty."

  private val PLAN: String =
    "\n    - Required produced_outputs shape: emit these fields DIRECTLY on produced_outputs. Every\n" +
      "      task_id MUST match ^[a-z][a-z0-9-]*\$ (lowercase kebab; \"T1\" is REJECTED — use \"task-1\") and\n" +
      "      criterion_refs use the AC-### form:\n" +
      "      ```json\n" +
      "      { \"projection_kind\": \"executable_plan\",\n" +
      "        \"contract_version\": \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\",\n" +
      "        \"mode\": \"direct\",\n" +
      "        \"tasks\": [ { \"task_id\": \"task-1\", \"depends_on\": [], \"description\": \"<imperative task>\",\n" +
      "          \"criterion_refs\": [\"AC-001\"], \"target_paths_or_symbols\": [\"path/or/Symbol\"],\n" +
      "          \"test_obligations\": [\"<test to add or run>\"], \"constraints\": [] } ],\n" +
      "        \"validation_strategy\": [\"<how the plan is validated>\"] }\n" +
      "      ```"

  private val IMPLEMENT: String =
    "\n    - Required produced_outputs shape: emit the implementation_receipt fields DIRECTLY on\n" +
      "      produced_outputs (the bounded claim audit consumes) alongside the reconciled_state report.\n" +
      "      completed_task_ids reuse the plan's task_ids; changed_paths are repository-relative; every\n" +
      "      deviations entry is an OBJECT { \"ref\", \"note\" }, never a free-text string:\n" +
      "      ```json\n" +
      "      { \"projection_kind\": \"implementation_receipt\",\n" +
      "        \"contract_version\": \"$FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION\",\n" +
      "        \"completed_task_ids\": [\"task-1\"], \"changed_paths\": [\"path/Changed.kt\"],\n" +
      "        \"tests_added\": [], \"tests_updated\": [],\n" +
      "        \"tests_executed\": [],\n" +
      "        \"deviations\": [ { \"ref\": \"task-1\", \"note\": \"<one-line what deviated and why>\" } ],\n" +
      "        \"unresolved_items\": [],\n" +
      "        \"reconciliation_evidence\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" },\n" +
      "        \"repository_checkpoint\": { \"fingerprint\": \"<checkpoint fingerprint>\" },\n" +
      "        \"reconciled_state\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" } }\n" +
      "      ```\n" +
      "      Compilation and test execution belong exclusively to the validate phase. Do NOT build,\n" +
      "      compile, or run tests here: write the tests the plan obligates and leave them unexecuted.\n" +
      "      tests_executed stays [] in this phase; validate runs them and owns their outcomes.\n" +
      "      deviations may be []; each note is a single line without backticks or pasted JSON/diff\n" +
      "      payloads.\n" +
      "      Two rules decide whether a 'completed' receipt advances, so satisfy them here rather than\n" +
      "      learning them from a rejection:\n" +
      "      - completed_task_ids must close EVERY task id the delivered plan declared. Closing fewer\n" +
      "        does not advance; report 'blocked' or 'failed' instead of narrowing the obligation.\n" +
      "      - unresolved_items must be EMPTY on a 'completed' receipt: it means work this phase leaves\n" +
      "        open, and completion plus an open item cannot both be true. It is NOT a notes field —\n" +
      "        anything you merely want the next phase to know goes in deviations or the summary, and\n" +
      "        work the phase contract assigns elsewhere (a build or test run, which belongs to\n" +
      "        validate) is not open work at all. Populate it only under a 'blocked' or 'failed'\n" +
      "        envelope, as a plain line or the same { \"ref\", \"note\" } pair deviations uses."

  private val PLAN_FIX: String =
    "\n    - Required produced_outputs.repair_plan shape. One entry per carried finding; root_cause and\n" +
      "      minimal_change are one line each with no diff hunk, source body, or line number.\n" +
      "      prior_round_remedy_ref is admissible only on a design_symptom entry:\n" +
      "      ```json\n" +
      "      { \"repair_plan\": {\n" +
      "          \"contract_version\": \"$FEATURE_TASK_RUNTIME_REPAIR_PLAN_CONTRACT_VERSION\",\n" +
      "          \"round_number\": 1,\n" +
      "          \"entries\": [ { \"finding_ref\": \"<finding id or label>\",\n" +
      "            \"root_cause\": \"<why the defect exists>\",\n" +
      "            \"minimal_change\": \"<smallest change that closes it>\",\n" +
      "            \"classification\": \"local_patch_site\" } ] } }\n" +
      "      ```"

  private val IMPLEMENT_FIX: String =
    "\n    - Required produced_outputs.repair_receipt shape. Emit one entry per carried finding;\n" +
      "      constructs are Type or Type.member with an optional file basename, never a bare path;\n" +
      "      intent is one line with no diff hunk, source body, or line number. A finding that needed\n" +
      "      no edit still needs its no_edit_required entry. Add disturbed_remedies only when this round\n" +
      "      removed or materially rewrote a construct a resolved repair_ledger entry names; omit it\n" +
      "      otherwise. The round number and the pre-fix checkpoint sha are runtime-owned: omit them,\n" +
      "      never guess them from a briefing hash:\n" +
      "      ```json\n" +
      "      { \"repair_receipt\": {\n" +
      "          \"contract_version\": \"$FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION\",\n" +
      "          \"entries\": [ { \"severity\": \"blocker\", \"label\": \"<TypeOrSymbol>\",\n" +
      "            \"text\": \"<sanitized finding text>\", \"outcome\": \"addressed\",\n" +
      "            \"constructs\": [ { \"symbol\": \"Type.member\", \"file\": \"Type.kt\" } ],\n" +
      "            \"intent\": \"<one-line repair intent>\" } ],\n" +
      "          \"disturbed_remedies\": [ { \"finding_ref\": \"<ledger finding_ref>\",\n" +
      "            \"reason\": \"<one line on why the settled construct had to change>\" } ] } }\n" +
      "      ```"

  private const val VALIDATION: String =
    "\n    - Required produced_outputs shape: emit a validation_result OBJECT. Its repository_checkpoint\n" +
      "      is also an OBJECT containing fingerprint — never a prefixed string such as\n" +
      "      \"repository_checkpoint=<hash>\":\n" +
      "      ```json\n" +
      "      { \"validation_result\": {\n" +
      "          \"validation_status\": \"passed\",\n" +
      "          \"checks\": [ { \"name\": \"<check name>\", \"status\": \"passed\" } ],\n" +
      "          \"repository_checkpoint\": { \"fingerprint\": \"<checkpoint fingerprint>\" },\n" +
      "          \"gate_run_count\": 1,\n" +
      "          \"gate_runs\": [ { \"duration_ms\": 1, \"outcome\": \"passed\",\n" +
      "            \"cache_mode\": \"forced_full\", \"executed_work_units\": 1 } ],\n" +
      "          \"suppression_justifications\": [ {\n" +
      "            \"path\": \"<repo-relative path>\",\n" +
      "            \"silenced_rule_or_check\": \"<rule or check id>\",\n" +
      "            \"rationale\": \"<short why a root-cause fix was not possible>\"\n" +
      "          } ]\n" +
      "        } }\n" +
      "      ```\n" +
      "      gate_run_count and gate_runs are runtime-measured evidence; never invent or overwrite them\n" +
      "      from agent claims. suppression_justifications is optional and required only when the\n" +
      "      runtime measures a non-zero suppression delta; omit it on clean runs. Each entry needs\n" +
      "      path, silenced_rule_or_check, and a short rationale — never raw command output,\n" +
      "      transcripts, or telemetry."

  private const val VALIDATION_FULL_RUNTIME_OWNED_REPAIR: String =
    "\n      FULL runtime-owned repair also emits produced_outputs.validation_repair_plan and\n" +
      "      produced_outputs.substantiation_receipts (not on validation_result): one receipt per\n" +
      "      discovery identity with identity, root_cause, changed_paths_or_symbols, and a short\n" +
      "      rationale. The runtime owns collect-all execution and confirmation identity closure;\n" +
      "      do not invoke the gate or any quality-check skill."
}
