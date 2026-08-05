package skillbill.review

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewFindingProvenanceParsingTest {
  @Test
  fun `a finding line carrying runtime provenance yields its lane and an unchanged description`() {
    val line = "- [F-001] Major | High | Repo.kt:12 | Transaction is not rolled back. | " +
      "specialists=bill-kmp-code-review-persistence,bill-kmp-code-review-architecture; origins=kmp->kotlin"

    val finding = parseBulletFindings("### 2. Risk Register\n$line").single()

    assertEquals("bill-kmp-code-review-persistence", finding.laneSkillName)
    assertEquals("Transaction is not rolled back.", finding.description)
    assertEquals("Repo.kt:12", finding.location)
  }

  @Test
  fun `a provenance segment carrying only origins leaves the finding unattributed`() {
    val line = "- [F-001] Minor | Low | Repo.kt:12 | Naming is inconsistent. | origins=kmp->kotlin"

    val finding = parseBulletFindings(line).single()

    assertNull(finding.laneSkillName)
    assertEquals("Naming is inconsistent.", finding.description)
  }

  @Test
  fun `a legacy finding line without provenance parses unchanged with no lane`() {
    val line = "- [F-001] Major | High | README.md:12 | README wording is stale after the routing change."

    val finding = parseBulletFindings(line).single()

    assertNull(finding.laneSkillName)
    assertEquals("README wording is stale after the routing change.", finding.description)
    assertEquals("README.md:12", finding.location)
  }
}
