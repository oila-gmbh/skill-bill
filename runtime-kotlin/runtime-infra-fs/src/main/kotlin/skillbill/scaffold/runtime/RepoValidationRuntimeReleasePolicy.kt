package skillbill.scaffold.runtime

import java.nio.file.Path

internal object RepoValidationRuntimeReleasePolicy {
  internal const val NORMALIZED_TRANSITIONAL_LICENSE_SHA256 =
    "4c6d42f5a704b5722d707f92e1f0312cacc6cbee1058171987cc46393ad8f8f3"
  internal const val LICENSE_IDENTIFIER_MARKER = "Identifier:"
  internal const val STABLE_LICENSE_APPROVAL_PATH = "docs/release-successor-license-approval.md"
  internal const val APPROVED_LICENSE_STATUS = "Status: Approved"
  internal const val APPROVED_LICENSE_IDENTIFIER_PREFIX = "Approved License Identifier: "
  internal const val APPROVED_LICENSE_SHA256_PREFIX = "Approved LICENSE SHA-256: "
  internal const val APPROVED_LICENSE_HOLDER = "Approved by: Braian Gapur"
  internal const val APPROVED_LICENSE_LOCATION_PREFIX = "Approval location: "
  internal const val UNSIGNED_BYTE_MASK = 0xff
  private val semverTagPattern =
    Regex(
      "^v(?<major>0|[1-9]\\d*)\\.(?<minor>0|[1-9]\\d*)\\.(?<patch>0|[1-9]\\d*)" +
        "(?:-(?<prerelease>(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)" +
        "(?:\\.(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*))*))?" +
        "(?:\\+(?<build>[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
    )

  fun parseReleaseRef(rawValue: String): ReleaseRefMetadata {
    val candidate = rawValue.trim().removePrefix("refs/tags/")
    val match = semverTagPattern.matchEntire(candidate)
      ?: throw IllegalArgumentException(
        "Release tag must match canonical vMAJOR.MINOR.PATCH with optional SemVer prerelease/build metadata.",
      )
    return ReleaseRefMetadata(
      tag = candidate,
      version = candidate.removePrefix("v"),
      major = match.groups["major"]!!.value.toInt(),
      minor = match.groups["minor"]!!.value.toInt(),
      patch = match.groups["patch"]!!.value.toInt(),
      prerelease = match.groups["prerelease"] != null,
      prereleaseIdentifier = match.groups["prerelease"]?.value,
      buildMetadata = match.groups["build"]?.value,
    )
  }

  fun validateReleaseRef(repoRoot: Path, rawValue: String, forcePrerelease: Boolean = false): ReleaseRefMetadata {
    val parsed = parseReleaseRef(rawValue)
    if (forcePrerelease && !parsed.prerelease) {
      throw ReleaseLicensePolicyError(
        "Manual staging references must carry a SemVer prerelease identifier; " +
          "stable tags cannot be forced into staging.",
      )
    }
    validateReleaseLicensePolicy(repoRoot.toAbsolutePath().normalize(), parsed)
    return parsed
  }
}
