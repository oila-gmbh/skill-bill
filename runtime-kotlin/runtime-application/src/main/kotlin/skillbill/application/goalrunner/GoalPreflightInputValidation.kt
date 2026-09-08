package skillbill.application.goalrunner

import skillbill.error.InvalidDecompositionManifestSchemaError
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.continuation.FeatureTaskExecutionIdentityPolicy
import skillbill.ports.repository.RepositoryEnclosingRootPort
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

  fun resolveRepositoryRoot(repoRoot: Path, repositoryEnclosingRootPort: RepositoryEnclosingRootPort): Path =
    runCatching {
      repositoryEnclosingRootPort.canonicalPath(repoRoot.toAbsolutePath().normalize())
    }.getOrElse {
      throw InvalidFeatureTaskExecutionIdentitySchemaError(
        "preflight request",
        "repository root '$repoRoot' cannot be resolved",
        it,
      )
    }

  fun normalizeIssueKey(issueKey: String): String =
    FeatureTaskExecutionIdentityPolicy.normalizeIssueKey(issueKey, "preflight request")

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
