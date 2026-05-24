package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.scaffold.manifest.ScaffoldManifestPersistencePort
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestAppendCodeReviewAreaRequest
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestReadResult
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestRegisterGovernedAddonRequest
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestRenderPlatformPackRequest
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestSetDeclaredQualityCheckRequest
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestSnapshot
import skillbill.ports.scaffold.manifest.model.ScaffoldManifestWriteRequest
import skillbill.scaffold.appendCodeReviewArea
import skillbill.scaffold.appendGovernedAddonManifestRegistration
import skillbill.scaffold.renderGovernedAddonManifestRegistration
import skillbill.scaffold.setDeclaredQualityCheckFile
import java.nio.file.Files
import java.nio.file.Path
import skillbill.scaffold.policy.renderPlatformPackManifest as policyRenderPlatformPackManifest

/**
 * Filesystem adapter for [ScaffoldManifestPersistencePort]. IO ownership stays here — the
 * adapter delegates to the existing infra-fs YAML mutators
 * (`appendCodeReviewArea`, `setDeclaredQualityCheckFile`,
 * `appendGovernedAddonManifestRegistration`) and to the pure-policy renderer in
 * `runtime-domain` for fresh-content generation. The scaffold legacy seam (`scaffold(...)`)
 * still owns the rollback transaction; this adapter is a typed alternative for pure-policy
 * callers that does NOT yet replace the legacy entry point in subtask 2.
 */
@Inject
class FileSystemScaffoldManifestPersistence : ScaffoldManifestPersistencePort {
  override fun read(manifestPath: Path): ScaffoldManifestReadResult = ScaffoldManifestReadResult(
    manifestPath = manifestPath,
    content = Files.readString(manifestPath),
  )

  override fun snapshot(manifestPath: Path): ScaffoldManifestSnapshot = ScaffoldManifestSnapshot(
    manifestPath = manifestPath,
    originalBytes = Files.readAllBytes(manifestPath),
  )

  override fun restore(snapshot: ScaffoldManifestSnapshot) {
    Files.write(snapshot.manifestPath, snapshot.originalBytes)
  }

  override fun write(request: ScaffoldManifestWriteRequest) {
    Files.writeString(request.manifestPath, request.content)
  }

  override fun renderPlatformPackManifest(request: ScaffoldManifestRenderPlatformPackRequest): String =
    policyRenderPlatformPackManifest(
      platform = request.platform,
      displayName = request.displayName,
      strongSignals = request.strongSignals,
      tieBreakers = request.tieBreakers,
      declaredCodeReviewAreas = request.declaredCodeReviewAreas,
      baselineContentPath = request.baselineContentPath,
      declaredAreaFiles = request.declaredAreaFiles,
      declaredQualityCheckFile = request.declaredQualityCheckFile,
      areaMetadata = request.areaMetadata,
      baselineLayers = request.baselineLayers,
    )

  override fun appendCodeReviewArea(request: ScaffoldManifestAppendCodeReviewAreaRequest) {
    appendCodeReviewArea(
      manifestPath = request.manifestPath,
      area = request.area,
      relativeContentPath = request.relativeContentPath,
      areaFocus = request.areaFocus,
    )
  }

  override fun setDeclaredQualityCheckFile(request: ScaffoldManifestSetDeclaredQualityCheckRequest) {
    setDeclaredQualityCheckFile(
      manifestPath = request.manifestPath,
      relativeContentPath = request.relativeContentPath,
    )
  }

  override fun registerGovernedAddon(request: ScaffoldManifestRegisterGovernedAddonRequest) {
    appendGovernedAddonManifestRegistration(
      manifestPath = request.manifestPath,
      platform = request.platform,
      skillRelativeDirs = request.skillRelativeDirs,
      addonSlug = request.addonSlug,
    )
  }

  override fun renderGovernedAddonRegistrationPreview(
    currentText: String,
    request: ScaffoldManifestRegisterGovernedAddonRequest,
  ): String = renderGovernedAddonManifestRegistration(
    text = currentText,
    platform = request.platform,
    skillRelativeDirs = request.skillRelativeDirs,
    addonSlug = request.addonSlug,
  )
}
