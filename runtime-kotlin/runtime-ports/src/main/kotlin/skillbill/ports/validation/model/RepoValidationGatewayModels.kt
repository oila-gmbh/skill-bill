package skillbill.ports.validation.model

import skillbill.boundary.OpenBoundaryMap

data class RepoValidationReport(
  val issues: List<String>,
  val skillCount: Int,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
  val structuredIssues: List<RepoValidationIssue> = issues.map(RepoValidationIssue::fromRawIssue),
) {
  val passed: Boolean = issues.isEmpty()

  @OpenBoundaryMap("Wire-shape serializer for repo-validation report")
  fun toPayload(): Map<String, Any?> = mapOf(
    "status" to if (passed) "passed" else "failed",
    "skill_count" to skillCount,
    "governed_addon_count" to addonCount,
    "platform_pack_count" to platformPackCount,
    "native_agent_count" to nativeAgentCount,
    "issues" to issues,
  )
}

data class RepoValidationIssue(
  val severity: RepoValidationIssueSeverity,
  val message: String,
  val sourcePath: String?,
  val code: String? = null,
  val name: String? = null,
  val exceptionName: String? = null,
) {
  companion object {
    fun fromRawIssue(raw: String): RepoValidationIssue {
      val separator = raw.indexOf(": ")
      return if (separator > 0 && raw.substring(0, separator).isNotBlank() &&
        !raw.substring(0, separator).contains(' ')
      ) {
        RepoValidationIssue(
          severity = RepoValidationIssueSeverity.ERROR,
          sourcePath = raw.substring(0, separator),
          message = raw.substring(separator + 2),
        )
      } else {
        RepoValidationIssue(
          severity = RepoValidationIssueSeverity.ERROR,
          sourcePath = null,
          message = raw,
        )
      }
    }
  }
}

enum class RepoValidationIssueSeverity {
  ERROR,
  WARNING,
  INFO,
}

data class ReleaseRefMetadata(
  val tag: String,
  val version: String,
  val major: Int,
  val minor: Int,
  val patch: Int,
  val prerelease: Boolean,
  val prereleaseIdentifier: String?,
  val buildMetadata: String?,
) {
  @OpenBoundaryMap("Wire-shape serializer for release-ref metadata")
  fun toPayload(): Map<String, Any?> = mapOf(
    "tag" to tag,
    "version" to version,
    "major" to major,
    "minor" to minor,
    "patch" to patch,
    "prerelease" to prerelease,
    "prerelease_identifier" to prereleaseIdentifier,
    "build_metadata" to buildMetadata,
  )
}
