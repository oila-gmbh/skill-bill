package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimePriorGapMemoryTest {
  @Test
  fun `toProjectionFields renders exactly the declared fields`() {
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 2,
      priorAuditValues = listOf(
        """{"gaps":[{"criterion":"AC-001","note":"missing projection"}]}""",
        """{"gaps":[{"criterion":"AC-003","note":"audit re-justification"}]}""",
      ),
    )
    assertEquals(FeatureTaskRuntimePriorGapMemory.DECLARED_FIELD_NAMES, memory.toProjectionFields().map { it.name })
    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValue.Text("2"),
      memory.toProjectionFields().single { it.name == FeatureTaskRuntimePriorGapMemory.FIELD_ROUND }.value,
    )
    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValue.TextList(memory.priorAuditValues),
      memory.toProjectionFields()
        .single { it.name == FeatureTaskRuntimePriorGapMemory.FIELD_PRIOR_AUDIT_VALUES }.value,
    )
  }

  @Test
  fun `a stuffed value longer than the old per-note cap is accepted`() {
    val stuffed = "x".repeat(FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS + 1)
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 1,
      priorAuditValues = listOf(stuffed),
    )
    assertEquals(listOf(stuffed), memory.priorAuditValues)
  }

  @Test
  fun `an over-budget prior audit value is rejected so a bounded projection never leaks unbounded prose`() {
    val tooLong = "x".repeat(FeatureTaskRuntimeHandoffProjectionBudget.PLANNING_PROJECTION.maxUtf8Bytes + 1)
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePriorGapMemory(
        round = 1,
        priorAuditValues = listOf(tooLong),
      )
    }
  }

  @Test
  fun `an over-cap list is rejected and empty lists are valid`() {
    val overCap = List(FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT + 1) { "AC-001 value $it" }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePriorGapMemory(
        round = 1,
        priorAuditValues = overCap,
      )
    }
    val empty = FeatureTaskRuntimePriorGapMemory(
      round = 1,
      priorAuditValues = emptyList(),
    )
    assertEquals(
      listOf(FeatureTaskRuntimePriorGapMemory.FIELD_PRIOR_AUDIT_VALUES),
      empty.toProjectionFields().filter { it.value.itemCount == 0 }.map { it.name },
    )
  }

  @Test
  fun `decode round-trips the fields and reuses construction bounds`() {
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 3,
      priorAuditValues = listOf("""{"gaps":[{"criterion":"AC-004","note":"degrade gracefully"}]}"""),
    )
    val decoded = FeatureTaskRuntimePriorGapMemory.fromMap(
      memory.toProjectionFields().associate { it.name to it.value.wireDecode() },
    )
    assertEquals(memory, decoded)
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimePriorGapMemory.fromMap(mapOf()) }
  }

  @Test
  fun `over-budget values are dropped whole rather than sliced`() {
    val overBudget = """{"gaps":[{"criterion":"AC-7","note":"abcdefghij"}]}"""
    val bounded = boundPriorGapNotes(listOf(overBudget), maxUtf8Bytes = 8, maxItems = 8)
    assertEquals(emptyList(), bounded.values)
    assertEquals(1, bounded.droppedForUtf8Budget)
    assertEquals(0, bounded.droppedForListCap)
  }

  @Test
  fun `newest values that fit are kept when packing overflows the utf8 budget`() {
    val bounded = boundPriorGapNotes(
      listOf("oldest-aaaaaa", "middle-bbbbbb", "newest-cccccc"),
      maxUtf8Bytes = 13,
      maxItems = 8,
    )
    assertEquals(listOf("newest-cccccc"), bounded.values)
    assertEquals(2, bounded.droppedForUtf8Budget)
  }
}

private fun FeatureTaskRuntimeHandoffProjectionValue.wireDecode(): Any? = when (this) {
  is FeatureTaskRuntimeHandoffProjectionValue.Text -> text
  is FeatureTaskRuntimeHandoffProjectionValue.TextList -> items
  is FeatureTaskRuntimeHandoffProjectionValue.CompactReference -> value
}
