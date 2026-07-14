package skillbill.application.featuretask

import skillbill.contracts.workflow.FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION
import skillbill.error.InvalidFeatureTaskExecutionIdentitySchemaError
import skillbill.ports.persistence.model.FeatureTaskExecutionIdentity

object FeatureTaskExecutionIdentityPolicy {
  const val REPOSITORY_IDENTITY_PREFIX: String = "repo-root-realpath-v1:"
  val ISSUE_KEY_PATTERN: Regex = Regex("^[A-Z][A-Z0-9]*-[0-9]+$")

  fun validate(identity: FeatureTaskExecutionIdentity, sourceLabel: String = identity.workflowId) {
    val failure = when {
      identity.contractVersion != FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION ->
        "contract_version must be $FEATURE_TASK_EXECUTION_IDENTITY_CONTRACT_VERSION"
      identity.workflowId.isBlank() -> "workflow_id is malformed"
      !ISSUE_KEY_PATTERN.matches(identity.normalizedIssueKey) -> "normalized_issue_key is malformed"
      !validRepositoryIdentity(identity.repositoryIdentity) -> "repository_identity is malformed"
      !validGovernedSpecPath(identity.governedSpecPath) -> "governed_spec_path is malformed"
      else -> null
    }
    failure?.let { throw InvalidFeatureTaskExecutionIdentitySchemaError(sourceLabel, it) }
  }

  fun validateLookupRequest(issueKey: String, repositoryIdentity: String): String {
    val normalizedIssueKey = issueKey.trim().uppercase()
    if (!ISSUE_KEY_PATTERN.matches(normalizedIssueKey)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError("lookup request", "issue_key is malformed")
    }
    if (!validRepositoryIdentity(repositoryIdentity)) {
      throw InvalidFeatureTaskExecutionIdentitySchemaError("lookup request", "repository_identity is malformed")
    }
    return normalizedIssueKey
  }

  private fun validRepositoryIdentity(value: String): Boolean = value.startsWith(REPOSITORY_IDENTITY_PREFIX) &&
    value.length > REPOSITORY_IDENTITY_PREFIX.length &&
    value.substring(REPOSITORY_IDENTITY_PREFIX.length).startsWith('/') &&
    value.none { it == '\r' || it == '\n' }

  private fun validGovernedSpecPath(value: String): Boolean = value.startsWith(".feature-specs/") &&
    value.endsWith(".md") &&
    value.none { it == '\r' || it == '\n' } &&
    value.split('/').none { it.isBlank() || it == "." || it == ".." }
}
