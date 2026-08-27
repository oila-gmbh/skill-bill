package skillbill.workflow.taskruntime.model

object FeatureTaskRuntimeProjectionCanonicalizationFixtures {
  private val UPPERCASE_RECEIPT: Map<String, Any?> = mapOf(
    "projection_kind" to "implementation_receipt",
    "contract_version" to FeatureTaskRuntimePlanningProjectionContract.VERSION,
    "completed_task_ids" to listOf("Task-01"),
    "changed_paths" to listOf("src/Foo.kt"),
    "tests_executed" to listOf(mapOf("name" to "FooTest.kt", "outcome" to "passed")),
    "reconciliation_evidence" to mapOf("reconciled" to true, "evidence" to "ok"),
  )

  val ALL: List<Map<String, Any?>> = listOf(
    UPPERCASE_RECEIPT,
  )
}
