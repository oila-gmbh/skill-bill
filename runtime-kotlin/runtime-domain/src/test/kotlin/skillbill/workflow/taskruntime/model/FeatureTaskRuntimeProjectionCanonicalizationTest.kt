@file:Suppress("MaxLineLength")

package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import skillbill.workflow.taskruntime.model.FeatureTaskRuntimeProjectionCanonicalizationTransform as Transform

class FeatureTaskRuntimeProjectionCanonicalizationTest {
  // --- task ids (task 2) ------------------------------------------------------------------------

  @Test
  fun `task id is lowercased, separator runs become one hyphen, and invalid chars are stripped`() {
    assertEquals("t1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("T1"))
    assertEquals("task-1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("Task_1"))
    assertEquals("task-1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("  task   1  "))
    assertEquals("task-1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("task__--  1"))
    assertEquals("ab1", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("A!B@1"))
  }

  @Test
  fun `a task id that reduces to empty is left empty for the schema gate to reject`() {
    assertEquals("", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("!!!"))
    assertEquals("", FeatureTaskRuntimeProjectionCanonicalizer.canonicalizeTaskId("   "))
  }

  @Test
  fun `declared task id and its depends_on reference canonicalize to the same value (task 3)`() {
    val produced = mapOf(
      "projection_kind" to "executable_plan",
      "tasks" to listOf(
        taskMap(taskId = "T1"),
        taskMap(taskId = "Task_2", dependsOn = listOf("T1")),
      ),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val tasks = result.canonical["tasks"] as List<*>
    assertEquals("t1", (tasks[0] as Map<*, *>)["task_id"])
    assertEquals("task-2", (tasks[1] as Map<*, *>)["task_id"])
    assertEquals(listOf("t1"), (tasks[1] as Map<*, *>)["depends_on"])
  }

  @Test
  fun `list order and cardinality are preserved in every id position (task 3)`() {
    val produced = mapOf(
      "tasks" to listOf(taskMap("C"), taskMap("A"), taskMap("B", dependsOn = listOf("C", "A"))),
      "completed_task_ids" to listOf("C", "A", "B"),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val tasks = result.canonical["tasks"] as List<*>
    assertEquals(listOf("c", "a", "b"), tasks.map { (it as Map<*, *>)["task_id"] })
    assertEquals(listOf("c", "a"), (tasks[2] as Map<*, *>)["depends_on"])
    assertEquals(listOf("c", "a", "b"), result.canonical["completed_task_ids"])
  }

  // --- compact summaries (task 2) ---------------------------------------------------------------

  @Test
  fun `compact summary collapses tab runs and strips backticks, trimming boundary whitespace`() {
    val produced = mapOf(
      "tasks" to listOf(taskMap("t1", description = "call\t\t`fn()`\tnow ")),
      "deviations" to listOf(mapOf("ref" to "AC-001", "note" to " see `x`\tand\ty ")),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val task = (result.canonical["tasks"] as List<*>)[0] as Map<*, *>
    assertEquals("call fn() now", task["description"])
    val deviation = (result.canonical["deviations"] as List<*>)[0] as Map<*, *>
    assertEquals("see x and y", deviation["note"])
  }

  @Test
  fun `compact summary never removes an interior line break, so a multi-line paste stays rejectable`() {
    // Collapsing CR/LF would flatten a multi-line body into a single line the schema's no-line-break
    // guard then accepts, and slide the diff marker off its line start. The interior break must survive.
    val produced = mapOf("tasks" to listOf(taskMap("t1", description = "changes:\ndiff --git a/x b/x")))

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    val task = (result.canonical["tasks"] as List<*>)[0] as Map<*, *>
    assertEquals("changes:\ndiff --git a/x b/x", task["description"])
  }

  // --- nonBlank trims (task 2) ------------------------------------------------------------------

  @Test
  fun `nonBlank scalar and array string fields are trimmed without touching interior content`() {
    val produced = mapOf(
      "affected_boundaries" to listOf("  runtime-domain  ", "runtime-application"),
      "rollout" to mapOf("flag_required" to false, "notes" to "  no flag  "),
      "reconciliation_evidence" to mapOf("reconciled" to true, "evidence" to "  at target  "),
      "repository_checkpoint" to mapOf("fingerprint" to "  abc  ", "base_ref" to " main "),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals(listOf("runtime-domain", "runtime-application"), result.canonical["affected_boundaries"])
    assertEquals("no flag", (result.canonical["rollout"] as Map<*, *>)["notes"])
    assertEquals("at target", (result.canonical["reconciliation_evidence"] as Map<*, *>)["evidence"])
    val checkpoint = result.canonical["repository_checkpoint"] as Map<*, *>
    assertEquals("abc", checkpoint["fingerprint"])
    assertEquals("main", checkpoint["base_ref"])
  }

  @Test
  fun `non-string and unexpected shapes pass through untouched, never coerced or dropped`() {
    val produced = mapOf(
      "tasks" to "not-a-list",
      "completed_task_ids" to listOf("T1", 42, null),
      "rollout" to listOf("array-not-object"),
    )

    val result = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced)

    assertEquals("not-a-list", result.canonical["tasks"])
    assertEquals(listOf("t1", 42, null), result.canonical["completed_task_ids"])
    assertEquals(listOf("array-not-object"), result.canonical["rollout"])
  }

  // --- diagnostics (task 4) ---------------------------------------------------------------------

  @Test
  fun `an id canonicalization records the field path plus original and canonical values`() {
    val produced = mapOf("tasks" to listOf(taskMap("T1", dependsOn = listOf("T1"))))

    val records = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics

    val declaration = records.single { it.fieldPath == "tasks[0].task_id" }
    assertEquals(listOf(Transform.TASK_ID_NORMALIZED), declaration.transforms)
    assertEquals("T1", declaration.originalId)
    assertEquals("t1", declaration.canonicalId)
    val reference = records.single { it.fieldPath == "tasks[0].depends_on[0]" }
    assertEquals("t1", reference.canonicalId)
  }

  @Test
  fun `a compact-summary canonicalization records the field path and transform kinds but never the text`() {
    val produced = mapOf("tasks" to listOf(taskMap("t1", description = "call\t`fn`\tnow ")))

    val record = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics
      .single { it.fieldPath == "tasks[0].description" }

    assertEquals(
      listOf(Transform.TABS_TO_SPACE, Transform.BACKTICKS_STRIPPED, Transform.TRIMMED),
      record.transforms,
    )
    assertNull(record.originalId, "a compact-summary record must not carry the field text")
    assertNull(record.canonicalId, "a compact-summary record must not carry the field text")
  }

  @Test
  fun `an already-canonical projection produces no diagnostics`() {
    val produced = mapOf(
      "tasks" to listOf(taskMap("task-1", dependsOn = emptyList(), description = "add contract")),
      "affected_boundaries" to listOf("runtime-domain"),
    )

    assertTrue(FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics.isEmpty())
  }

  @Test
  fun `the record count and recorded id length are bounded regardless of projection size`() {
    val manyTasks = (1..(MAX_CANONICALIZATION_RECORDS + 50)).map { taskMap("T$it") }
    val produced = mapOf("tasks" to manyTasks)

    val records = FeatureTaskRuntimeProjectionCanonicalizer.canonicalize(produced).diagnostics

    assertTrue(records.size <= MAX_CANONICALIZATION_RECORDS, "record count must be capped")
    val longId = "T".repeat(MAX_RECORDED_ID_LENGTH + 40)
    val longRecord = FeatureTaskRuntimeProjectionCanonicalizer
      .canonicalize(mapOf("tasks" to listOf(taskMap(longId)))).diagnostics.single()
    assertTrue(
      (longRecord.originalId?.length ?: 0) <= MAX_RECORDED_ID_LENGTH,
      "a recorded id value must be length-bounded",
    )
  }

  private fun taskMap(taskId: String, dependsOn: List<String>? = null, description: String = "d"): Map<String, Any?> =
    buildMap {
      put("task_id", taskId)
      if (dependsOn != null) put("depends_on", dependsOn)
      put("description", description)
      put("criterion_refs", listOf("AC-001"))
      put("test_obligations", listOf("t"))
    }
}
