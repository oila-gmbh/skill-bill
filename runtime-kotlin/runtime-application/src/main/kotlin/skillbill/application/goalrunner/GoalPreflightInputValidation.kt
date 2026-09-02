package skillbill.application.goalrunner

import skillbill.application.continuation.FeatureTaskExecutionIdentityPolicy
import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import java.nio.file.Path

object GoalPreflightInputValidation {
  fun requireInvokedAgentId(invokedAgentId: String) {
    if (invokedAgentId.isBlank()) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "preflight request",
        "invoked_agent_id is required",
      )
    }
  }

  fun requireOptionalIdentity(field: String, value: String?) {
    if (value?.isBlank() == true) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "preflight request",
        "$field must be omitted when blank",
      )
    }
  }

  fun resolveRepositoryRoot(repoRoot: Path): Path = runCatching {
    repoRoot.toAbsolutePath().normalize().toRealPath()
  }.getOrElse {
    throw InvalidFeatureTaskExecutionIdentitySchemaError(
      "preflight request",
      "repository root '$repoRoot' cannot be resolved",
      it,
    )
  }

  fun normalizeIssueKey(issueKey: String): String {
    val normalized = issueKey.trim().uppercase()
    if (!FeatureTaskExecutionIdentityPolicy.ISSUE_KEY_PATTERN.matches(normalized)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "preflight request",
        "issue_key '$issueKey' is malformed",
      )
    }
    return normalized
  }

  fun requireManifestIssueKey(manifestIssueKey: String, requestedIssueKey: String) {
    if (manifestIssueKey != requestedIssueKey) {
      throw InvalidDecompositionManifestSchemaError(
        sourceLabel = requestedIssueKey,
        reason = "manifest issue_key '$manifestIssueKey' does not match the requested issue key.",
        failureCode = "issue_key_mismatch",
      )
    }
  }
}
