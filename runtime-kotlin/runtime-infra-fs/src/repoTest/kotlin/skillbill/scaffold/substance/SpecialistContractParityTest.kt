@file:Suppress("MaxLineLength", "ktlint:standard:max-line-length")

package skillbill.scaffold.substance

import skillbill.scaffold.runtime.RepoValidationRuntime
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecialistContractParityTest {
  @Test
  fun `delegated specialist subset exactly matches canonical sections`() {
    val root = repoRootFromTest()
    val canonical = Files.readString(root.resolve("orchestration/review-orchestrator/PLAYBOOK.md"))
    val specialist = Files.readString(root.resolve("orchestration/review-orchestrator/specialist-contract.md"))
    listOf("Shared Contract For Every Specialist", "Shared Report Structure").forEach { heading ->
      assertEquals(
        RepoValidationRuntime.extractH2(canonical, heading),
        RepoValidationRuntime.extractH2(specialist, heading),
      )
    }
  }
}
