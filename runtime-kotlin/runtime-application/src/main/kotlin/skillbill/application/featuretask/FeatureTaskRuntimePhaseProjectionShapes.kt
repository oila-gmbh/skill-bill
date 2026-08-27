package skillbill.application.featuretask

import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION
import skillbill.contracts.workflow.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.workflow.taskruntime.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.model.REPAIR_RECEIPT_MAX_ENTRIES
import skillbill.workflow.taskruntime.model.REPAIR_RECEIPT_MAX_INTENT_UTF8_BYTES
import skillbill.workflow.taskruntime.model.REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES
import skillbill.workflow.taskruntime.model.REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES

internal object FeatureTaskRuntimePhaseProjectionShapes {
  fun exampleFor(phaseId: String, agentRunValidateFallback: Boolean = false): String = when (phaseId) {
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN -> PREPLAN
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN -> PLAN
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT -> IMPLEMENT
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX -> IMPLEMENT_FIX
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE ->
      if (agentRunValidateFallback) {
        VALIDATION
      } else {
        VALIDATION + VALIDATION_FULL_RUNTIME_OWNED_REPAIR
      }
    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD -> BUILD
    else -> ""
  }

  private fun prosePhaseOutputShape(innerJsonExample: String, trailingNotes: String): String =
    "\n    - Required produced_outputs shape: non-blank value string, optional prompt. Put the JSON " +
      "object below INSIDE value as a JSON string; do not emit those fields as sibling keys on " +
      "produced_outputs. The runtime does not validate the inner shape; the next phase reads value " +
      "as structured prose and interprets it. Extra keys beside value are allowed.\n" +
      "      ```json\n" +
      "      { \"value\": \"<JSON string: inner object below>\", \"prompt\": \"<optional directive>\" }\n" +
      "      ```\n" +
      "      Inner object to stuff into value:\n" +
      "      ```json\n" +
      innerJsonExample +
      "      ```\n" +
      trailingNotes

  private val PREPLAN: String = prosePhaseOutputShape(
    innerJsonExample =
    "      { \"projection_kind\": \"preplanning_digest\",\n" +
      "        \"contract_version\": \"0.2\",\n" +
      "        \"affected_boundaries\": [\"<module or boundary touched>\"], \"patterns_and_decisions\": [],\n" +
      "        \"risks\": [\"<concrete risk>\"],\n" +
      "        \"rollout\": { \"flag_required\": false, \"flag_pattern\": \"none\",\n" +
      "          \"notes\": \"<rollout note, or N/A>\" },\n" +
      "        \"validation_strategy\": [\"<how the change is validated>\"],\n" +
      "        \"unresolved_questions\": [], \"evidence_refs\": [],\n" +
      "        \"selected_boundary_headings\": [\"<heading_id copied verbatim from the boundary catalog>\"] }\n",
    trailingNotes =
    "      flag_pattern is one of none, simple_conditional, di_switch, legacy. Walk boundary_memory " +
      "headings for relevance; weave context into the stuffed object rather than listing headings only " +
      "outside value.",
  )

  private val PLAN: String = prosePhaseOutputShape(
    innerJsonExample =
    "      { \"projection_kind\": \"executable_plan\",\n" +
      "        \"contract_version\": \"0.2\",\n" +
      "        \"mode\": \"direct\",\n" +
      "        \"tasks\": [ { \"task_id\": \"task-1\", \"depends_on\": [], \"description\": \"<imperative task>\",\n" +
      "          \"criterion_refs\": [\"AC-001\"], \"target_paths_or_symbols\": [\"path/or/Symbol\"],\n" +
      "          \"test_obligations\": [\"<test to add or run>\"], \"constraints\": [] } ],\n" +
      "        \"validation_strategy\": [\"<how the plan is validated>\"] }\n",
    trailingNotes =
    "      Upstream preplan value is structured prose carrying the digest JSON; read and interpret it. " +
      "task_id MUST match ^[a-z][a-z0-9-]*\$ (lowercase kebab; \"T1\" is wrong — use \"task-1\"); " +
      "criterion_refs use the AC-### form.",
  )

  private val IMPLEMENT: String = prosePhaseOutputShape(
    innerJsonExample =
    "      { \"projection_kind\": \"implementation_receipt\",\n" +
      "        \"contract_version\": \"0.2\",\n" +
      "        \"completed_task_ids\": [\"task-1\"], \"changed_paths\": [\"path/Changed.kt\"],\n" +
      "        \"tests_added\": [], \"tests_updated\": [],\n" +
      "        \"tests_executed\": [],\n" +
      "        \"deviations\": [ { \"ref\": \"AC-001\", \"note\": \"<one-line what deviated and why>\" } ],\n" +
      "        \"unresolved_items\": [],\n" +
      "        \"reconciliation_evidence\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" },\n" +
      "        \"reconciled_state\": { \"reconciled\": true, \"evidence\": \"<tree at target>\" } }\n",
    trailingNotes =
    "      Upstream plan value is structured prose carrying the executable_plan JSON; read and interpret it. " +
      "repository_checkpoint is runtime-owned: omit it entirely. Never invent a fingerprint. " +
      "Compilation and test execution belong exclusively to the validate phase; tests_executed stays []. " +
      "changed_paths are repository-relative; deviations entries are objects { \"ref\", \"note\" }.",
  )

  private val IMPLEMENT_FIX: String =
    "\n    - Required produced_outputs.repair_receipt shape. Emit one entry per carried finding,\n" +
      "      named by finding_id (aliases finding_ref, id, ref accepted). Coverage matches on\n" +
      "      finding_id alone; label and text are optional decoration.\n" +
      "      constructs are Type or Type.member with an optional file basename, never a bare path;\n" +
      "      symbol is an identifier only — no spaces and no Kotlin backtick / JUnit display names\n" +
      "      (use ClassName or ClassName.camelCaseMember; put prose in intent);\n" +
      "      intent is one line with no diff hunk, source body, or line number. A finding that needed\n" +
      "      no edit still needs its no_edit_required entry, and a finding you attempted and could not\n" +
      "      close needs outcome attempted_unresolved with unresolved_reason and the constructs you\n" +
      "      touched. HARD SIZE LIMITS, enforced by the schema: at most $REPAIR_RECEIPT_MAX_ENTRIES\n" +
      "      entries; intent at most $REPAIR_RECEIPT_MAX_INTENT_UTF8_BYTES characters;\n" +
      "      no_edit_reason at most $REPAIR_RECEIPT_MAX_NO_EDIT_REASON_UTF8_BYTES characters;\n" +
      "      unresolved_reason at most $REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES characters.\n" +
      "      Count the characters before you emit and compress to fit: one bounded sentence per field,\n" +
      "      naming the decision rather than arguing it. An over-length field is rejected on content\n" +
      "      that was otherwise correct. The round number and the pre-fix checkpoint sha are runtime-owned:\n" +
      "      omit them, never guess them from a briefing hash:\n" +
      "      ```json\n" +
      "      { \"repair_receipt\": {\n" +
      "          \"contract_version\": \"$FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION\",\n" +
      "          \"entries\": [ { \"finding_id\": \"F-001\", \"severity\": \"blocker\",\n" +
      "            \"outcome\": \"addressed\",\n" +
      "            \"constructs\": [ { \"symbol\": \"Type.member\", \"file\": \"Type.kt\" } ],\n" +
      "            \"intent\": \"<one-line repair intent>\" } ] } }\n" +
      "      ```\n" +
      "      Compilation and test execution belong exclusively to the validate phase. Do NOT build,\n" +
      "      compile, run tests, or invoke `./gradlew check` / the pack collect-all gate here."

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
      "            \"cache_mode\": \"forced_full\", \"executed_work_units\": 1 } ]\n" +
      "        } }\n" +
      "      ```\n" +
      "      gate_run_count and gate_runs are runtime-measured evidence; never invent or overwrite them\n" +
      "      from agent claims. Never introduce suppressions (@Suppress, @file:Suppress, baselines,\n" +
      "      disabled rules, or skipped tests) to silence findings; fix root causes instead."

  private const val VALIDATION_FULL_RUNTIME_OWNED_REPAIR: String =
    "\n      You run only the pack-declared collect-all command and the same command once to confirm.\n" +
      "      Do not run skill-bill validate, agnix, or validate_agent_configs.\n" +
      "      The runtime may record one cache-bypassing verify afterward; gate_run_count and\n" +
      "      gate_runs stay runtime-measured — never invent them. Never add @Suppress, @file:Suppress,\n" +
      "      baselines, disabled rules, or skipped tests to silence findings; fix root causes instead."

  private const val BUILD: String =
    "\n    - Required produced_outputs shape: emit a build_receipt OBJECT with contract_version\n" +
      "      \"$FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION\":\n" +
      "      ```json\n" +
      "      { \"build_receipt\": {\n" +
      "          \"contract_version\": \"$FEATURE_TASK_RUNTIME_BUILD_RECEIPT_CONTRACT_VERSION\",\n" +
      "          \"validation_status\": \"passed\",\n" +
      "          \"checks\": [],\n" +
      "          \"repository_checkpoint\": { \"fingerprint\": \"<checkpoint fingerprint>\" },\n" +
      "          \"gate_run_count\": 1,\n" +
      "          \"gate_runs\": [ { \"duration_ms\": 1, \"outcome\": \"passed\",\n" +
      "            \"cache_mode\": \"forced_full\", \"executed_work_units\": 1 } ]\n" +
      "        } }\n" +
      "      ```\n" +
      "      Run only the pack build_command. Do not run collect_all_full_gate_command, check " + "--" + "continue,\n" +
      "      skill-bill validate, or bill-code-check. gate_run_count and gate_runs are runtime-measured."
}
