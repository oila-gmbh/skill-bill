package skillbill.review

import skillbill.review.model.ImportedFinding
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewIssueCategoryTest {
  @Test
  fun `resolveReviewIssueCategory honors explicit routed classifier and fallback paths`() {
    val genericFinding =
      ImportedFinding(
        findingId = "F-001",
        severity = "Major",
        confidence = "High",
        location = "Auth.kt:12",
        description = "Token is logged with sensitive user data.",
        findingText = "Token is logged with sensitive user data.",
      )

    assertEquals(
      "data_persistence",
      resolveReviewIssueCategory("persistence", routedSkill = null, specialistReviews = emptyList(), genericFinding),
    )
    assertEquals(
      "testing_quality_gate",
      resolveReviewIssueCategory(null, routedSkill = null, specialistReviews = listOf("testing"), genericFinding),
    )
    assertEquals(
      "security_privacy",
      resolveReviewIssueCategory(
        explicitCategory = null,
        routedSkill = "bill-code-review",
        specialistReviews = emptyList(),
        finding = genericFinding,
      ),
    )
    assertEquals(
      "other",
      resolveReviewIssueCategory(
        explicitCategory = null,
        routedSkill = "bill-code-review",
        specialistReviews = emptyList(),
        finding =
        genericFinding.copy(
          location = "Example.kt:12",
          description = "Needs closer inspection.",
          findingText = "Needs closer inspection.",
        ),
      ),
    )
  }

  @Test
  fun `platform and scope dimensions normalize clean slugs without promoting prose`() {
    assertEquals("agent-config", normalizePlatformSlug("agent-config"))
    assertEquals("kmp", normalizePlatformSlug("KMP"))
    assertEquals("kotlin", normalizePlatformSlug(" kotlin "))
    assertEquals("kotlin", normalizePlatformSlug("backend kotlin"))
    assertEquals("kotlin", normalizePlatformSlug("backend-kotlin"))
    assertEquals("android", normalizePlatformSlug("android"))
    assertEquals("unknown", normalizePlatformSlug(null))
    assertEquals("unknown", normalizePlatformSlug("Custom Stack!"))

    assertEquals("branch_diff", normalizeScopeType("branch diff (main...HEAD)"))
    assertEquals("branch_diff", normalizeScopeType("branch_diff"))
    assertEquals("unstaged_changes", normalizeScopeType("unstaged changes"))
    assertEquals("files", normalizeScopeType("files"))
    assertEquals("custom_scope", normalizeScopeType("Custom Scope!"))
    assertEquals("unknown", normalizeScopeType(""))
  }
}
