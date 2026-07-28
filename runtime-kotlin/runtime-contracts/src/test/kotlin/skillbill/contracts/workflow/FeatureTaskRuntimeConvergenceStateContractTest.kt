package skillbill.contracts.workflow

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FeatureTaskRuntimeConvergenceStateContractTest {
  @Test
  fun `schema resource version matches Kotlin contract version and excludes private bodies`() {
    val schema = checkNotNull(
      javaClass.classLoader.getResourceAsStream(
        FEATURE_TASK_RUNTIME_CONVERGENCE_STATE_SCHEMA_RESOURCE,
      ),
    ).bufferedReader().use { it.readText() }

    assertContains(schema, "const: \"$FEATURE_TASK_RUNTIME_CONVERGENCE_STATE_CONTRACT_VERSION\"")
    assertFalse(schema.lineSequence().any { it.trim().startsWith("raw_prompt:") })
    assertFalse(schema.lineSequence().any { it.trim().startsWith("raw_phase_output:") })
    assertFalse(schema.lineSequence().any { it.trim().startsWith("complete_diff:") })
    assertEquals("0.1", FEATURE_TASK_RUNTIME_CONVERGENCE_STATE_CONTRACT_VERSION)
  }
}
