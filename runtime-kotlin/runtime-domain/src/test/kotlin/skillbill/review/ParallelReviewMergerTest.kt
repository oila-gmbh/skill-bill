package skillbill.review

import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewSeverity
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelReviewMergerTest {
  private fun laneResult(agentId: String, rawOutput: String) =
    ParallelReviewLaneResult(agentId = agentId, findings = emptyList(), rawOutput = rawOutput)

  private fun finding(
    id: String = "F-001",
    severity: String = "Major",
    confidence: String = "High",
    location: String = "Foo.kt:1",
    description: String = "A finding",
  ) = "- [$id] $severity | $confidence | $location | $description"

  @Test
  fun `both lanes empty produces empty result`() {
    val result = ParallelReviewMerger.merge(
      laneResult("claude", ""),
      laneResult("codex", ""),
    )

    assertTrue(result.findings.isEmpty())
    assertEquals("", result.formattedOutput)
  }

  @Test
  fun `one lane only produces single-lane provenance label`() {
    val output = finding(severity = "Major")
    val result = ParallelReviewMerger.merge(
      laneResult("claude", output),
      laneResult("codex", ""),
    )

    assertEquals(1, result.findings.size)
    assertEquals(listOf("claude"), result.findings[0].agentIds)
    assertContains(result.formattedOutput, "[claude]")
  }

  @Test
  fun `no shared findings produces two independent lists ordered by severity`() {
    val lane1Output = """
      - [F-001] Minor | Low | Foo.kt:10 | Minor issue
    """.trimIndent()
    val lane2Output = """
      - [F-001] Major | High | Bar.kt:5 | Major issue
    """.trimIndent()
    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", lane2Output),
    )

    assertEquals(2, result.findings.size)
    assertEquals(ParallelReviewSeverity.MAJOR, result.findings[0].severity)
    assertEquals(ParallelReviewSeverity.MINOR, result.findings[1].severity)
    assertEquals("F-001", result.findings[0].fNumber)
    assertEquals("F-002", result.findings[1].fNumber)
  }

  @Test
  fun `fully overlapping findings produces coalesced entries with both agent IDs first within tier`() {
    val sharedFinding = finding(severity = "Major", location = "Auth.kt:42", description = "Token exposed")
    val result = ParallelReviewMerger.merge(
      laneResult("claude", sharedFinding),
      laneResult("codex", sharedFinding),
    )

    assertEquals(1, result.findings.size)
    val f = result.findings[0]
    assertEquals(listOf("claude", "codex"), f.agentIds)
    assertEquals("F-001", f.fNumber)
    assertContains(result.formattedOutput, "[claude, codex]")
  }

  @Test
  fun `partial overlap has coalesced entries before single-lane entries within same severity tier`() {
    val shared = finding(id = "F-001", severity = "Major", location = "Auth.kt:1", description = "Shared issue")
    val onlyLane1 = finding(id = "F-002", severity = "Major", location = "Bar.kt:2", description = "Only in lane1")
    val onlyLane2 = finding(id = "F-003", severity = "Major", location = "Baz.kt:3", description = "Only in lane2")
    val lane1Output = "$shared\n$onlyLane1"
    val lane2Output = "$shared\n$onlyLane2"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", lane2Output),
    )

    assertEquals(3, result.findings.size)
    assertEquals(listOf("claude", "codex"), result.findings[0].agentIds)
    assertEquals(1, result.findings[1].agentIds.size)
    assertEquals(1, result.findings[2].agentIds.size)
  }

  @Test
  fun `severity disagreement on same finding uses higher severity`() {
    val location = "Auth.kt:99"
    val description = "Same issue different severity"
    val lane1Output = "- [F-001] Minor | Low | $location | $description"
    val lane2Output = "- [F-001] Major | High | $location | $description"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", lane2Output),
    )

    assertEquals(1, result.findings.size)
    assertEquals(ParallelReviewSeverity.MAJOR, result.findings[0].severity)
  }

  @Test
  fun `F-XXX renumbering is sequential from F-001 regardless of original IDs`() {
    val lane1Output = """
      - [F-999] Major | High | A.kt:1 | Finding A
      - [F-042] Minor | Low | B.kt:2 | Finding B
    """.trimIndent()

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", ""),
    )

    assertEquals("F-001", result.findings[0].fNumber)
    assertEquals("F-002", result.findings[1].fNumber)
  }

  @Test
  fun `Nit severity is parsed and sorted below Minor`() {
    val lane1Output = """
      - [F-001] Nit | Low | A.kt:1 | Nit issue
      - [F-002] Minor | Medium | B.kt:2 | Minor issue
    """.trimIndent()

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", ""),
    )

    assertEquals(2, result.findings.size)
    assertEquals(ParallelReviewSeverity.MINOR, result.findings[0].severity)
    assertEquals(ParallelReviewSeverity.NIT, result.findings[1].severity)
  }

  @Test
  fun `Critical maps to Blocker`() {
    val lane1Output = "- [F-001] Critical | High | A.kt:1 | Critical issue"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", ""),
    )

    assertEquals(1, result.findings.size)
    assertEquals(ParallelReviewSeverity.BLOCKER, result.findings[0].severity)
  }

  @Test
  fun `formattedOutput format matches spec AC11`() {
    val lane1Output = "- [F-001] Major | High | Auth.kt:10 | Token logged"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", ""),
    )

    assertEquals(
      "- [F-001] [claude] Major | High | Auth.kt:10 | Token logged",
      result.formattedOutput,
    )
  }

  @Test
  fun `fuzzy match on same file coalesces when token overlap above threshold`() {
    val lane1Output = "- [F-001] Major | High | Auth.kt:42 | token exposed in logs"
    val lane2Output = "- [F-001] Major | High | Auth.kt:42 | token exposed in logs here"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", lane2Output),
    )

    assertEquals(1, result.findings.size)
    assertEquals(listOf("claude", "codex"), result.findings[0].agentIds)
    assertContains(result.formattedOutput, "[claude, codex]")
  }

  @Test
  fun `same description on different files is never coalesced`() {
    val lane1Output = "- [F-001] Major | High | A.kt:1 | token exposed in logs"
    val lane2Output = "- [F-001] Major | High | B.kt:1 | token exposed in logs"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", lane2Output),
    )

    assertEquals(2, result.findings.size)
    assertEquals(1, result.findings[0].agentIds.size)
    assertEquals(1, result.findings[1].agentIds.size)
  }

  @Test
  fun `same file with disjoint descriptions below threshold is not coalesced`() {
    val lane1Output = "- [F-001] Major | High | Auth.kt:7 | token exposed in logs"
    val lane2Output = "- [F-001] Major | High | Auth.kt:7 | null pointer dereference here"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", lane2Output),
    )

    assertEquals(2, result.findings.size)
    assertEquals(1, result.findings[0].agentIds.size)
    assertEquals(1, result.findings[1].agentIds.size)
  }

  @Test
  fun `same file with partial overlap below threshold is not coalesced`() {
    // tokens: {token,exposed,in,logs,here} vs {token,missing,csrf,header,on,post} -> 1/10 = 0.1 < 0.6
    val lane1Output = "- [F-001] Major | High | Auth.kt:7 | token exposed in logs here"
    val lane2Output = "- [F-001] Major | High | Auth.kt:7 | token missing csrf header on post"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", lane2Output),
    )

    assertEquals(2, result.findings.size)
  }

  @Test
  fun `severity disagreement on fuzzy-coalesced pair resolves to higher severity`() {
    val lane1Output = "- [F-001] Minor | Low | Auth.kt:42 | token exposed in logs"
    val lane2Output = "- [F-001] Major | High | Auth.kt:42 | token exposed in logs here"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", lane1Output),
      laneResult("codex", lane2Output),
    )

    assertEquals(1, result.findings.size)
    assertEquals(ParallelReviewSeverity.MAJOR, result.findings[0].severity)
    assertEquals(listOf("claude", "codex"), result.findings[0].agentIds)
  }

  @Test
  fun `coalesced finding format matches spec AC12`() {
    val finding = "- [F-001] Major | High | Auth.kt:10 | Token logged"

    val result = ParallelReviewMerger.merge(
      laneResult("claude", finding),
      laneResult("codex", finding),
    )

    assertEquals(
      "- [F-001] [claude, codex] Major | High | Auth.kt:10 | Token logged",
      result.formattedOutput,
    )
  }
}
