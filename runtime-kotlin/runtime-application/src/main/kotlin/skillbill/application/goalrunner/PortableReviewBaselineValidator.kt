package skillbill.application.goalrunner

import skillbill.application.goalrunner.model.PortableReviewBaselineValidation
import skillbill.application.goalrunner.model.PortableReviewBaselineValidationRequest
import skillbill.error.InvalidPortableReviewBaselineSchemaError
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.goal.model.PortableReviewBaseline
import skillbill.workflow.goal.model.PortableReviewBaselineBlockedReason
import java.nio.file.Path

object PortableReviewBaselineValidator {
  fun validateStoredArtifact(request: PortableReviewBaselineValidationRequest): PortableReviewBaselineValidation {
    val subtask = requireNotNull(request.subtask)
    return when (val integrity = validateArtifactIntegrity(request)) {
      is PortableReviewBaselineValidation.Blocked -> integrity
      is PortableReviewBaselineValidation.Valid -> {
        if (hasImplementationEvidence(subtask)) {
          blocked(
            PortableReviewBaselineBlockedReason.IMPLEMENTATION_EVIDENCE,
            "Goal subtask '${request.subtaskId}' has durable execution evidence; " +
              "portable review state cannot be rehydrated.",
            integrity.artifact,
          )
        } else {
          integrity
        }
      }
    }
  }

  fun validateArtifactIntegrity(request: PortableReviewBaselineValidationRequest): PortableReviewBaselineValidation {
    val path = PortableReviewBaselinePaths.artifactPath(request.repoRoot, request.manifest, request.subtaskId)
    val artifact = when (val loaded = loadArtifact(request, path)) {
      is ArtifactLoad.Missing -> return blocked(
        PortableReviewBaselineBlockedReason.ARTIFACT_MISSING,
        "Portable review baseline is missing at '${path.toRelativePath(request.repoRoot)}'.",
      )
      is ArtifactLoad.Blocked -> return loaded.validation
      is ArtifactLoad.Ready -> loaded.artifact
    }
    return validateDecodedArtifact(request, artifact)
  }

  private sealed interface ArtifactLoad {
    data object Missing : ArtifactLoad
    data class Blocked(val validation: PortableReviewBaselineValidation.Blocked) : ArtifactLoad
    data class Ready(val artifact: PortableReviewBaseline) : ArtifactLoad
  }

  private fun loadArtifact(request: PortableReviewBaselineValidationRequest, path: Path): ArtifactLoad {
    val artifact = runCatching { request.persistence.read(path) }.getOrElse { error ->
      val message = error.message.orEmpty()
      val reason = if (error is InvalidPortableReviewBaselineSchemaError && message.contains("integrity_digest")) {
        PortableReviewBaselineBlockedReason.DIGEST_MISMATCH
      } else {
        PortableReviewBaselineBlockedReason.ARTIFACT_MALFORMED
      }
      return ArtifactLoad.Blocked(blocked(reason, message))
    }
    if (artifact == null) return ArtifactLoad.Missing
    return ArtifactLoad.Ready(artifact)
  }

  private fun validateDecodedArtifact(
    request: PortableReviewBaselineValidationRequest,
    artifact: PortableReviewBaseline,
  ): PortableReviewBaselineValidation {
    val identityMismatch = identityMismatch(request, artifact)
    if (identityMismatch != null) return identityMismatch
    if (!isBaseReachable(request.repoRoot, artifact.reviewBaseSha, request.expectedBranch, request.gitOperations)) {
      return blocked(
        PortableReviewBaselineBlockedReason.UNREACHABLE_BASE,
        "Portable review baseline review_base_sha '${artifact.reviewBaseSha}' " +
          "is not reachable on '${request.expectedBranch}'.",
        artifact,
      )
    }
    return PortableReviewBaselineValidation.Valid(
      artifact,
      PortableReviewBaselineMapping.toReviewBaseline(artifact),
    )
  }

  private fun identityMismatch(
    request: PortableReviewBaselineValidationRequest,
    artifact: PortableReviewBaseline,
  ): PortableReviewBaselineValidation.Blocked? = when {
    artifact.workflowId != request.expectedWorkflowId -> blocked(
      PortableReviewBaselineBlockedReason.ARTIFACT_MALFORMED,
      "Portable review baseline workflow_id '${artifact.workflowId}' " +
        "does not match child '${request.expectedWorkflowId}'.",
      artifact,
    )
    artifact.repositoryIdentity != request.expectedRepositoryIdentity -> blocked(
      PortableReviewBaselineBlockedReason.REPOSITORY_MISMATCH,
      "Portable review baseline repository identity does not match this repository.",
      artifact,
    )
    artifact.goalBranch != request.expectedBranch -> blocked(
      PortableReviewBaselineBlockedReason.BRANCH_MISMATCH,
      "Portable review baseline goal_branch '${artifact.goalBranch}' " +
        "does not match '${request.expectedBranch}'.",
      artifact,
    )
    else -> null
  }

  private fun hasImplementationEvidence(subtask: DecompositionSubtask): Boolean = !subtask.commitSha.isNullOrBlank() ||
    subtask.lastResumableStep?.let { it != "create_branch" && it != "preplan" } == true

  private fun isBaseReachable(
    repoRoot: Path,
    reviewBaseSha: String,
    expectedBranch: String,
    gitOperations: WorkflowGitOperations,
  ): Boolean {
    val branch = gitOperations.currentBranch(repoRoot)
    if (!branch.ok || branch.value.trim() != expectedBranch.trim()) return false
    val head = gitOperations.headCommitSha(repoRoot)
    if (!head.ok || head.value.isBlank()) return false
    val exists = gitOperations.resolveCommit(repoRoot, reviewBaseSha)
    if (!exists.ok || exists.value.isBlank()) return false
    val ancestor = gitOperations.isCommitAncestor(repoRoot, reviewBaseSha, head.value)
    return ancestor.ok && ancestor.value == "true"
  }

  private fun blocked(
    reason: PortableReviewBaselineBlockedReason,
    detail: String,
    artifact: PortableReviewBaseline? = null,
  ): PortableReviewBaselineValidation.Blocked = PortableReviewBaselineValidation.Blocked(reason, detail, artifact)

  private fun Path.toRelativePath(repoRoot: Path): String = repoRoot.toAbsolutePath().normalize()
    .relativize(toAbsolutePath().normalize())
    .joinToString("/")
}
