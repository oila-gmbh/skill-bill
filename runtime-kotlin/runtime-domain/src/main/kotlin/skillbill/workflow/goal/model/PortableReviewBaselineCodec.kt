package skillbill.workflow.goal.model

import skillbill.boundary.OpenBoundaryMap
import skillbill.contracts.JsonSupport
import skillbill.contracts.workflow.GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION
import java.security.MessageDigest

object PortableReviewBaselineCodec {
  @OpenBoundaryMap("Portable review-baseline YAML encode at the durable artifact seam")
  fun encode(baseline: PortableReviewBaseline): Map<String, Any?> {
    val body = linkedMapOf<String, Any?>(
      "contract_version" to GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION,
      "workflow_id" to baseline.workflowId,
      "repository_identity" to baseline.repositoryIdentity,
      "goal_branch" to baseline.goalBranch,
      "review_base_sha" to baseline.reviewBaseSha,
      "baseline_untracked_paths" to baseline.baselineUntrackedPaths.distinct().sorted(),
      "owned_pathspec" to baseline.ownedPathspec.distinct().sorted(),
    )
    return body + ("integrity_digest" to digest(body))
  }

  @OpenBoundaryMap("Portable review-baseline YAML decode at the durable artifact seam")
  fun decode(raw: Map<String, Any?>): PortableReviewBaseline {
    val normalized = normalizeRaw(raw)
    PortableReviewBaselineCodecFields.requireArtifactFields(normalized.keys)
    val version = with(PortableReviewBaselineCodecFields) {
      normalized.stringField("contract_version") { value ->
        when (value) {
          is String -> value.takeIf(String::isNotBlank)
          is Number -> value.toString().takeIf(String::isNotBlank)
          else -> null
        }
      }
    }
    PortableReviewBaselineCodecFields.requireContractVersion(version)
    val workflowId = with(PortableReviewBaselineCodecFields) { normalized.stringField("workflow_id") }
    val repositoryIdentity = with(PortableReviewBaselineCodecFields) {
      normalized.stringField("repository_identity")
    }
    val goalBranch = with(PortableReviewBaselineCodecFields) { normalized.stringField("goal_branch") }
    val reviewBaseSha = with(PortableReviewBaselineCodecFields) { normalized.stringField("review_base_sha") }
    PortableReviewBaselineCodecFields.requireGitSha(reviewBaseSha)
    val baselineUntrackedPaths = with(PortableReviewBaselineCodecFields) {
      normalized.stringListField("baseline_untracked_paths")
    }
    val ownedPathspec = with(PortableReviewBaselineCodecFields) {
      normalized.stringListField("owned_pathspec", defaultEmpty = true)
    }
    PortableReviewBaselineCodecFields.validateRepositoryPaths(baselineUntrackedPaths, "baseline_untracked_paths")
    PortableReviewBaselineCodecFields.validateRepositoryPaths(ownedPathspec, "owned_pathspec")
    val integrityDigest = with(PortableReviewBaselineCodecFields) { normalized.stringField("integrity_digest") }
    val body = normalized.filterKeys { it != "integrity_digest" }
    PortableReviewBaselineCodecFields.requireMatchingDigest(integrityDigest, body)
    return PortableReviewBaseline(
      workflowId = workflowId,
      repositoryIdentity = repositoryIdentity,
      goalBranch = goalBranch,
      reviewBaseSha = reviewBaseSha,
      baselineUntrackedPaths = baselineUntrackedPaths,
      ownedPathspec = ownedPathspec,
      integrityDigest = integrityDigest,
    )
  }

  private fun normalizeRaw(raw: Map<String, Any?>): Map<String, Any?> {
    val contractVersion = raw["contract_version"]
    if (contractVersion is Number) {
      return raw + ("contract_version" to contractVersion.toString())
    }
    return raw
  }

  @OpenBoundaryMap("Portable review-baseline integrity digest over the canonical YAML body")
  fun digest(body: Map<String, Any?>): String = MessageDigest.getInstance("SHA-256")
    .digest(JsonSupport.mapToJsonString(body).toByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }
}
