package skillbill.launcher

import skillbill.install.model.InstallAgent
import skillbill.launcher.agentrun.DelegatedReviewProviderCapabilityRegistry
import skillbill.ports.review.model.DelegatedReviewProviderStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelegatedReviewProviderCapabilityRegistryTest {
  @Test
  fun `matrix enumerates every provider and marks unsupported providers explicitly`() {
    val matrix = DelegatedReviewProviderCapabilityRegistry.matrix()
    assertEquals(InstallAgent.entries.map { it.id }.toSet(), matrix.map { it.providerId }.toSet())
    assertTrue(
      matrix.filter { it.status == DelegatedReviewProviderStatus.UNSUPPORTED }
        .all { !it.dimensions.allSatisfied },
    )
  }

  @Test
  fun `codex claude and cursor retain independent capability declarations`() {
    val matrix = DelegatedReviewProviderCapabilityRegistry.matrix().associateBy { it.providerId }
    listOf("codex", "claude", "cursor").forEach { provider ->
      val capability = requireNotNull(matrix[provider])
      assertEquals(DelegatedReviewProviderStatus.EXPERIMENTAL, capability.status)
      assertTrue(capability.dimensions.freshContextIsolation)
      assertTrue(capability.dimensions.workerTracking)
      assertTrue(capability.dimensions.terminalResult)
    }
  }
}
