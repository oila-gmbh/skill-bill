package skillbill.workflow.goal.model

import skillbill.contracts.workflow.GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION
import skillbill.error.InvalidPortableReviewBaselineSchemaError

internal object PortableReviewBaselineCodecFields {
  val ARTIFACT_FIELDS = setOf(
    "contract_version",
    "workflow_id",
    "repository_identity",
    "goal_branch",
    "review_base_sha",
    "baseline_untracked_paths",
    "owned_pathspec",
    "integrity_digest",
  )
  private val GIT_SHA = Regex("^[0-9a-f]{40}(?:[0-9a-f]{24})?$")
  private val DIGEST_SHA = Regex("^[0-9a-f]{64}$")

  fun requireArtifactFields(keys: Set<String>) {
    if (keys != ARTIFACT_FIELDS && keys != ARTIFACT_FIELDS - "owned_pathspec") {
      throw InvalidPortableReviewBaselineSchemaError("Portable review baseline fields are invalid.")
    }
  }

  fun requireContractVersion(version: String) {
    if (version != GOAL_PORTABLE_REVIEW_BASELINE_CONTRACT_VERSION) {
      throw InvalidPortableReviewBaselineSchemaError(
        "Portable review baseline contract version '$version' is unsupported.",
      )
    }
  }

  fun requireGitSha(reviewBaseSha: String) {
    if (!GIT_SHA.matches(reviewBaseSha)) {
      throw InvalidPortableReviewBaselineSchemaError("Portable review baseline review_base_sha is invalid.")
    }
  }

  fun requireMatchingDigest(integrityDigest: String, body: Map<String, Any?>) {
    if (!DIGEST_SHA.matches(integrityDigest)) {
      throw InvalidPortableReviewBaselineSchemaError("Portable review baseline integrity_digest is invalid.")
    }
    if (PortableReviewBaselineCodec.digest(body) != integrityDigest) {
      throw InvalidPortableReviewBaselineSchemaError("Portable review baseline integrity_digest does not match.")
    }
  }

  fun validateRepositoryPaths(paths: List<String>, field: String) {
    paths.forEach { path ->
      if (path.isBlank() || path.startsWith("/") || path.contains("..")) {
        throw InvalidPortableReviewBaselineSchemaError("Portable review baseline $field entry '$path' is unsafe.")
      }
    }
  }

  fun Map<String, Any?>.stringField(key: String, coerce: ((Any?) -> String?)? = null): String {
    val value = this[key]
    val asString = coerce?.invoke(value) ?: (value as? String)?.takeIf(String::isNotBlank)
    return asString?.takeIf(String::isNotBlank)
      ?: throw InvalidPortableReviewBaselineSchemaError("Portable review baseline $key is invalid.")
  }

  fun Map<String, Any?>.stringListField(key: String, defaultEmpty: Boolean = false): List<String> {
    val value = this[key]
    if (value == null && defaultEmpty) return emptyList()
    val entries = value as? List<*>
      ?: throw InvalidPortableReviewBaselineSchemaError("Portable review baseline $key is invalid.")
    return entries.map { entry ->
      (entry as? String)?.takeIf(String::isNotBlank)
        ?: throw InvalidPortableReviewBaselineSchemaError("Portable review baseline $key entry is invalid.")
    }
  }
}
