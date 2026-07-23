package skillbill.workflow.taskruntime.model

/**
 * Canned planning-projection `produced_outputs` maps that exercise every canonicalization field class:
 * task-id declarations and references, compact summaries, and nonBlank trims, across each projection
 * variant. Tests enumerate [ALL] so a new fixture is covered without editing the test body.
 */
internal object FeatureTaskRuntimeProjectionCanonicalizationFixtures {
  private val UPPERCASE_EXECUTABLE_PLAN: Map<String, Any?> = mapOf(
    "projection_kind" to "executable_plan",
    "contract_version" to "0.1",
    "mode" to "direct",
    "tasks" to listOf(
      mapOf(
        "task_id" to "T1",
        "description" to "add\t`contract`\n now ",
        "criterion_refs" to listOf("AC-001"),
        "target_paths_or_symbols" to listOf("  src/Foo.kt  "),
        "test_obligations" to listOf(" parity "),
      ),
      mapOf(
        "task_id" to "Task_2",
        "depends_on" to listOf("T1"),
        "description" to "wire it",
        "criterion_refs" to listOf("AC-002"),
        "test_obligations" to listOf("focused"),
        "constraints" to listOf("  closed-world  "),
      ),
    ),
    "validation_strategy" to listOf("  focused gradle  "),
  )

  private val CANONICAL_PREPLAN_DIGEST: Map<String, Any?> = mapOf(
    "projection_kind" to "preplanning_digest",
    "contract_version" to "0.1",
    "affected_boundaries" to listOf("  runtime-domain  "),
    "risks" to listOf("producer may omit fields"),
    "rollout" to mapOf("flag_required" to false, "notes" to "  no flag needed  "),
    "validation_strategy" to listOf("snapshot tests"),
  )

  private val PLAN_COMMITMENT: Map<String, Any?> = mapOf(
    "projection_kind" to "plan_commitment",
    "contract_version" to "0.1",
    "task_commitments" to listOf(
      mapOf(
        "task_id" to "T1",
        "criterion_refs" to listOf("AC-001"),
        "test_obligations" to listOf(" parity "),
      ),
    ),
  )

  private val IMPLEMENTATION_RECEIPT: Map<String, Any?> = mapOf(
    "projection_kind" to "implementation_receipt",
    "contract_version" to "0.1",
    "completed_task_ids" to listOf("T1", "Task_2"),
    "changed_paths" to listOf("src/Foo.kt"),
    "tests_executed" to listOf(mapOf("name" to "FooTest", "outcome" to "passed")),
    "deviations" to listOf(mapOf("ref" to "AC-001", "note" to "adjusted\t`x`\nslightly")),
    "unresolved_items" to listOf("  follow up  "),
    "reconciliation_evidence" to mapOf("reconciled" to true, "evidence" to "  tree at target  "),
    "repository_checkpoint" to mapOf("fingerprint" to "  abc  "),
  )

  val ALL: List<Map<String, Any?>> = listOf(
    UPPERCASE_EXECUTABLE_PLAN,
    CANONICAL_PREPLAN_DIGEST,
    PLAN_COMMITMENT,
    IMPLEMENTATION_RECEIPT,
  )
}
