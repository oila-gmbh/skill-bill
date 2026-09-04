package skillbill.scaffold.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile

internal fun RepoValidationRuntimeReleasePolicy.validateReleaseLicensePolicy(
  repoRoot: Path,
  metadata: ReleaseRefMetadata,
) {
  when {
    metadata.isHistoricalReleaseLine() -> return
    metadata.isCoveredPreOneRelease() ->
      requireTransitionalPolicy(repoRoot.resolve("LICENSE"), metadata)
    metadata.isNonTriggeringV1Release() ->
      requireTransitionalPolicy(repoRoot.resolve("LICENSE"), metadata)
    else -> requirePostOneLicenseDecision(
      repoRoot.resolve("LICENSE"),
      repoRoot.resolve(RepoValidationRuntimeReleasePolicy.STABLE_LICENSE_APPROVAL_PATH),
      metadata,
    )
  }
}

internal fun ReleaseRefMetadata.isHistoricalReleaseLine(): Boolean =
  major == 0 && (minor < 1 || (minor == 1 && patch < 2))

internal fun ReleaseRefMetadata.isCoveredPreOneRelease(): Boolean = major == 0 && !isHistoricalReleaseLine()

internal fun ReleaseRefMetadata.isNonTriggeringV1Release(): Boolean =
  major == 1 && minor == 0 && patch == 0 && prerelease

internal fun requireTransitionalPolicy(licenseFile: Path, metadata: ReleaseRefMetadata) {
  if (!licenseFile.isRegularFile()) {
    throw ReleaseLicensePolicyError(
      "Release ${metadata.tag} requires root LICENSE with " +
        "${RepoValidationRuntime.PRE_1_LICENSE_IDENTIFIER}, but LICENSE is missing.",
    )
  }
  if (!isCurrentTransitionalLicense(Files.readString(licenseFile))) {
    throw ReleaseLicensePolicyError(
      "Release ${metadata.tag} requires the complete current Skill Bill use license policy.",
    )
  }
}

internal fun requirePostOneLicenseDecision(licenseFile: Path, approvalFile: Path, metadata: ReleaseRefMetadata) {
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

internal fun isCurrentTransitionalLicense(licenseText: String): Boolean =
  normalizedLicenseSha256(licenseText) == RepoValidationRuntimeReleasePolicy.NORMALIZED_TRANSITIONAL_LICENSE_SHA256

internal fun isApprovedStableLicense(licenseText: String, approvalText: String): Boolean {
  val normalized = normalizeLicense(licenseText)
  val identifier = Regex(
    "(?m)^${Regex.escape(RepoValidationRuntimeReleasePolicy.LICENSE_IDENTIFIER_MARKER)} " +
      "([A-Za-z0-9][A-Za-z0-9.+:-]*)$",
  ).find(normalized)?.groupValues?.get(1) ?: return false
  return isCurrentTransitionalLicense(licenseText) &&
    approvalText.lineSequence().any { it == RepoValidationRuntimeReleasePolicy.APPROVED_LICENSE_STATUS } &&
    approvalText.lineSequence().any {
      it == "${RepoValidationRuntimeReleasePolicy.APPROVED_LICENSE_IDENTIFIER_PREFIX}$identifier"
    } &&
    approvalText.lineSequence().any {
      it == "${RepoValidationRuntimeReleasePolicy.APPROVED_LICENSE_SHA256_PREFIX}" +
        normalizedLicenseSha256(licenseText)
    } &&
    approvalText.lineSequence().any { it == RepoValidationRuntimeReleasePolicy.APPROVED_LICENSE_HOLDER } &&
    approvalText.lineSequence().any {
      it.startsWith(RepoValidationRuntimeReleasePolicy.APPROVED_LICENSE_LOCATION_PREFIX) &&
        it.removePrefix(RepoValidationRuntimeReleasePolicy.APPROVED_LICENSE_LOCATION_PREFIX).isNotBlank()
    }
}

internal fun normalizedLicenseSha256(licenseText: String): String = MessageDigest.getInstance("SHA-256")
  .digest(normalizeLicense(licenseText).encodeToByteArray())
  .joinToString("") { byte -> "%02x".format(byte.toInt() and RepoValidationRuntimeReleasePolicy.UNSIGNED_BYTE_MASK) }

internal fun normalizeLicense(licenseText: String): String = licenseText.replace("\r\n", "\n").trimEnd()
