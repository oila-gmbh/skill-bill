package skillbill.scaffold.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile

internal object RepoValidationRuntimeReleasePolicy {
  private const val NORMALIZED_TRANSITIONAL_LICENSE_SHA256 =
    "4c6d42f5a704b5722d707f92e1f0312cacc6cbee1058171987cc46393ad8f8f3"
  private const val LICENSE_IDENTIFIER_MARKER = "Identifier:"
  private const val STABLE_LICENSE_APPROVAL_PATH = "docs/release-successor-license-approval.md"
  private const val APPROVED_LICENSE_STATUS = "Status: Approved"
  private const val APPROVED_LICENSE_IDENTIFIER_PREFIX = "Approved License Identifier: "
  private const val APPROVED_LICENSE_SHA256_PREFIX = "Approved LICENSE SHA-256: "
  private const val APPROVED_LICENSE_HOLDER = "Approved by: Braian Gapur"
  private const val APPROVED_LICENSE_LOCATION_PREFIX = "Approval location: "
  private const val UNSIGNED_BYTE_MASK = 0xff
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
        "Manual staging references must carry a SemVer prerelease identifier; stable tags cannot be forced into staging.",
      )
    }
    validateReleaseLicensePolicy(repoRoot.toAbsolutePath().normalize(), parsed)
    return parsed
  }

  private fun validateReleaseLicensePolicy(repoRoot: Path, metadata: ReleaseRefMetadata) {
    when {
      metadata.isHistoricalReleaseLine() -> return
      metadata.isCoveredPreOneRelease() ->
        requireTransitionalPolicy(repoRoot.resolve("LICENSE"), metadata)
      metadata.isNonTriggeringV1Release() ->
        requireTransitionalPolicy(repoRoot.resolve("LICENSE"), metadata)
      else -> requirePostOneLicenseDecision(
        repoRoot.resolve("LICENSE"),
        repoRoot.resolve(STABLE_LICENSE_APPROVAL_PATH),
        metadata,
      )
    }
  }

  private fun ReleaseRefMetadata.isHistoricalReleaseLine(): Boolean =
    major == 0 && (minor < 1 || (minor == 1 && patch < 2))

  private fun ReleaseRefMetadata.isCoveredPreOneRelease(): Boolean = major == 0 && !isHistoricalReleaseLine()

  private fun ReleaseRefMetadata.isNonTriggeringV1Release(): Boolean =
    major == 1 && minor == 0 && patch == 0 && prerelease

  private fun requireTransitionalPolicy(licenseFile: Path, metadata: ReleaseRefMetadata) {
    if (!licenseFile.isRegularFile()) {
      throw ReleaseLicensePolicyError(
        "Release ${metadata.tag} requires root LICENSE with ${RepoValidationRuntime.PRE_1_LICENSE_IDENTIFIER}, but LICENSE is missing.",
      )
    }
    if (!isCurrentTransitionalLicense(Files.readString(licenseFile))) {
      throw ReleaseLicensePolicyError(
        "Release ${metadata.tag} requires the complete current Skill Bill use license policy.",
      )
    }
  }

  private fun requirePostOneLicenseDecision(licenseFile: Path, approvalFile: Path, metadata: ReleaseRefMetadata) {
    if (!licenseFile.isRegularFile()) {
      throw ReleaseLicensePolicyError(
        "Release ${metadata.tag} requires a root LICENSE that records the deliberate v1.0 licensing decision.",
      )
    }
    val licenseText = Files.readString(licenseFile)
    if (!approvalFile.isRegularFile() || !isApprovedStableLicense(licenseText, Files.readString(approvalFile))) {
      throw ReleaseLicensePolicyError(
        "Release ${metadata.tag} requires the explicitly approved stable license policy.",
      )
    }
  }

  private fun isCurrentTransitionalLicense(licenseText: String): Boolean {
    return normalizedLicenseSha256(licenseText) == NORMALIZED_TRANSITIONAL_LICENSE_SHA256
  }

  private fun isApprovedStableLicense(licenseText: String, approvalText: String): Boolean {
    val normalized = normalizeLicense(licenseText)
    val identifier = Regex(
      "(?m)^${Regex.escape(LICENSE_IDENTIFIER_MARKER)} ([A-Za-z0-9][A-Za-z0-9.+:-]*)$",
    ).find(normalized)?.groupValues?.get(1) ?: return false
    return isCurrentTransitionalLicense(licenseText) &&
      approvalText.lineSequence().any { it == APPROVED_LICENSE_STATUS } &&
      approvalText.lineSequence().any { it == "$APPROVED_LICENSE_IDENTIFIER_PREFIX$identifier" } &&
      approvalText.lineSequence().any {
        it == "$APPROVED_LICENSE_SHA256_PREFIX${normalizedLicenseSha256(licenseText)}"
      } &&
      approvalText.lineSequence().any { it == APPROVED_LICENSE_HOLDER } &&
      approvalText.lineSequence().any {
        it.startsWith(APPROVED_LICENSE_LOCATION_PREFIX) &&
          it.removePrefix(APPROVED_LICENSE_LOCATION_PREFIX).isNotBlank()
      }
  }

  private fun normalizedLicenseSha256(licenseText: String): String = MessageDigest.getInstance("SHA-256")
    .digest(normalizeLicense(licenseText).encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte.toInt() and UNSIGNED_BYTE_MASK) }

  private fun normalizeLicense(licenseText: String): String = licenseText.replace("\r\n", "\n").trimEnd()

}
