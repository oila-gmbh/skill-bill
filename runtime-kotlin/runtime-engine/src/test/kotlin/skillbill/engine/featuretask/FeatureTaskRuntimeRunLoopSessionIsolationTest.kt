package skillbill.engine.featuretask

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class FeatureTaskRuntimeRunLoopSessionIsolationTest {
  @Test
  fun `run loop sessions do not share per-run mutable flags`() {
    val sessionOne = FeatureTaskRuntimeRunLoopSession(
      operatorBlockRetry = null,
      initialPendingReentry = null,
    )
    val sessionTwo = FeatureTaskRuntimeRunLoopSession(
      operatorBlockRetry = null,
      initialPendingReentry = null,
    )
    sessionOne.resolvedBranch = "feature-branch"
    sessionOne.recordRejectionSettlementPending = true
    sessionOne.phaseContentIdentities["implement"] = mapOf("src/Foo.kt" to "abc")
    assertNull(sessionTwo.resolvedBranch)
    assertEquals(false, sessionTwo.recordRejectionSettlementPending)
    assertEquals(emptyMap(), sessionTwo.phaseContentIdentities)
    assertNotSame(sessionOne, sessionTwo)
  }
}
