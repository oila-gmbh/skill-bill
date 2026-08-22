package skillbill.workflow.taskruntime.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FeatureTaskRuntimePriorGapMemoryTest {
  @Test
  fun `toProjectionFields renders exactly the four declared fields`() {
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 2,
      priorUnmetCriteria = listOf("AC-001: missing the bounded projection", "AC-003: audit re-justification"),
      lastImplementClaims = listOf("AC-001"),
      stickyIds = listOf("AC-001"),
    )
    assertEquals(FeatureTaskRuntimePriorGapMemory.DECLARED_FIELD_NAMES, memory.toProjectionFields().map { it.name })
    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValue.Text("2"),
      memory.toProjectionFields().single { it.name == FeatureTaskRuntimePriorGapMemory.FIELD_ROUND }.value,
    )
    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValue.TextList(memory.priorUnmetCriteria),
      memory.toProjectionFields()
        .single { it.name == FeatureTaskRuntimePriorGapMemory.FIELD_PRIOR_UNMET_CRITERIA }.value,
    )
    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValue.TextList(memory.lastImplementClaims),
      memory.toProjectionFields()
        .single { it.name == FeatureTaskRuntimePriorGapMemory.FIELD_LAST_IMPLEMENT_CLAIMS }.value,
    )
    assertEquals(
      FeatureTaskRuntimeHandoffProjectionValue.TextList(memory.stickyIds),
      memory.toProjectionFields().single { it.name == FeatureTaskRuntimePriorGapMemory.FIELD_STICKY_IDS }.value,
    )
  }

  @Test
  fun `an over-length prior unmet note is rejected so a bounded projection never leaks an unbounded audit note`() {
    val tooLong = "x".repeat(FEATURE_TASK_RUNTIME_AUDIT_NOTE_MAX_CHARS + 1)
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePriorGapMemory(
        round = 1,
        priorUnmetCriteria = listOf("AC-001: $tooLong"),
        lastImplementClaims = emptyList(),
        stickyIds = emptyList(),
      )
    }
  }

  @Test
  fun `an over-cap list is rejected and empty lists are valid`() {
    val overCap = List(FEATURE_TASK_RUNTIME_PROJECTION_LIST_MAX_COUNT + 1) { "AC-001" }
    assertFailsWith<IllegalArgumentException> {
      FeatureTaskRuntimePriorGapMemory(
        round = 1,
        priorUnmetCriteria = overCap,
        lastImplementClaims = emptyList(),
        stickyIds = emptyList(),
      )
    }
    // Empty lists are the AC-004 degradation shape: absent memory, never a rejection.
    val empty = FeatureTaskRuntimePriorGapMemory(
      round = 1,
      priorUnmetCriteria = emptyList(),
      lastImplementClaims = emptyList(),
      stickyIds = emptyList(),
    )
    assertEquals(
      listOf(
        FeatureTaskRuntimePriorGapMemory.FIELD_PRIOR_UNMET_CRITERIA,
        FeatureTaskRuntimePriorGapMemory.FIELD_LAST_IMPLEMENT_CLAIMS,
        FeatureTaskRuntimePriorGapMemory.FIELD_STICKY_IDS,
      ),
      empty.toProjectionFields().filter { it.value.itemCount == 0 }.map { it.name },
    )
  }

  @Test
  fun `decode round-trips the four fields and reuses construction bounds`() {
    val memory = FeatureTaskRuntimePriorGapMemory(
      round = 3,
      priorUnmetCriteria = listOf("AC-004: degrade gracefully"),
      lastImplementClaims = listOf("AC-004"),
      stickyIds = listOf("AC-004"),
    )
    val decoded = FeatureTaskRuntimePriorGapMemory.fromMap(
      memory.toProjectionFields().associate { it.name to it.value.wireDecode() },
    )
    assertEquals(memory, decoded)
    assertFailsWith<IllegalArgumentException> { FeatureTaskRuntimePriorGapMemory.fromMap(mapOf()) }
  }
}

private fun FeatureTaskRuntimeHandoffProjectionValue.wireDecode(): Any? = when (this) {
  is FeatureTaskRuntimeHandoffProjectionValue.Text -> text
  is FeatureTaskRuntimeHandoffProjectionValue.TextList -> items
  is FeatureTaskRuntimeHandoffProjectionValue.CompactReference -> value
}
