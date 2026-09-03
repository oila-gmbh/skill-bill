package skillbill.application

import skillbill.ports.continuation.FeatureTaskExecutionIdentityPolicy
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
  fun `lookup request accepts a digit-leading tracker key`() {
    assertEquals(
      "0AC-11",
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
        issueKey = " 0ac-11 ",
        repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
      ),
    )
  }

  @Test
  fun `lookup request accepts a tracker key that is not prefix-number`() {
    assertEquals(
      "BACKLOG-ITEM",
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
        issueKey = "backlog-item",
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
  fun `control-bearing issue key names the bound and the received value`() {
    val error = assertFailsWith<InvalidFeatureTaskExecutionIdentitySchemaError> {
      FeatureTaskExecutionIdentityPolicy.validateLookupRequest(
        issueKey = "SKILL-129\nspoofed",
        repositoryIdentity = "${FeatureTaskExecutionIdentityPolicy.REPOSITORY_IDENTITY_PREFIX}/srv/repo",
      )
    }

    assertContains(error.reason, "no control characters")
    assertContains(error.reason, "SKILL-129")
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
