package skillbill.application

import skillbill.application.featuretask.FeatureTaskExecutionIdentityPolicy
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FeatureTaskExecutionIdentityPolicyTest {
  @Test
  fun `lookup request accepts a canonical repository identity`() {
    assertEquals(
      "SKILL-129",
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
        issueKey = " skill-129 ",
        repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
      ),
    )
  }

  @Test
  fun `bare absolute path is rejected with the required prefix and the received value`() {
    val error = assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest("SKILL-129", "/srv/repo")
    }

    assertContains(error.reason, FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX)
    assertContains(error.reason, "'/srv/repo'")
  }

  @Test
  fun `malformed issue key names the expected pattern and the received value`() {
    val error = assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
        issueKey = "not an issue key",
        repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
      )
    }

    assertContains(error.reason, FeatureTaskExecutionIdentityPolicy.ISSUE_KEY_PATTERN.pattern)
    assertContains(error.reason, "not an issue key")
  }

  @Test
  fun `echoed value keeps newline injection out of the failure message`() {
    val error = assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest("SKILL-129", "/srv/repo\nrepository_identity is fine")
    }

    assertFalse(error.reason.contains('\n'), error.reason)
    assertContains(error.reason, "\\n")
  }
}
