@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "ThrowsCount")

package skillbill.scaffold.platformpack

import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidFallbackCapabilityError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.MissingManifestError
import skillbill.scaffold.model.GovernedAddonFile
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.runtime.SHELL_CONTRACT_VERSION
import skillbill.scaffold.validation.ReviewSkillStructureValidator
import skillbill.scaffold.validation.validateReviewSkillStructure
import java.nio.file.Files
import java.nio.file.Path

internal fun loadPlatformManifest(packRoot: Path, enforceContractVersion: Boolean = true): PlatformManifest {
  val resolvedPackRoot = packRoot.toAbsolutePath().normalize()
  val slug = resolvedPackRoot.fileName?.toString().orEmpty()
  val manifestPath = resolvedPackRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw MissingManifestError("Platform pack '$slug': expected manifest at '$manifestPath' but it is missing.")
  }
  val raw = readManifest(manifestPath, slug)
  return buildPack(slug, resolvedPackRoot, manifestPath, raw, enforceContractVersion)
}

internal fun loadPlatformPack(packRoot: Path, enforceGovernedReviewStructure: Boolean = false): PlatformManifest {
  val pack = loadPlatformManifest(packRoot)
  val closure = loadCompositionClosure(pack)
  validatePlatformPackCompositions(closure)
  validatePlatformPackFallbacks(closure)
  validatePlatformPack(pack, SHELL_CONTRACT_VERSION)
  pack.declaredQualityCheckFile?.let { loadQualityCheckContent(pack) }
  if (enforceGovernedReviewStructure) {
    ReviewSkillStructureValidator.validate(pack.packRoot)
  }
  return pack
}

internal fun discoverPlatformPacks(platformPacksRoot: Path): List<PlatformManifest> {
  val packs = childDirectories(platformPacksRoot).map(::loadPlatformManifest)
  validatePlatformPackCompositions(packs)
  validatePlatformPackFallbacks(packs)
  packs.forEach { pack ->
    validatePlatformPack(pack, SHELL_CONTRACT_VERSION)
    pack.declaredQualityCheckFile?.let { loadQualityCheckContent(pack) }
  }
  return packs
}

internal fun discoverPlatformPackManifests(
  platformPacksRoot: Path,
  enforceContractVersion: Boolean = true,
): List<PlatformManifest> {
  val packs = childDirectories(platformPacksRoot).map { packRoot ->
    loadPlatformManifest(packRoot, enforceContractVersion)
  }
  validatePlatformPackCompositions(packs)
  validatePlatformPackFallbacks(packs)
  return packs
}

internal fun validatePlatformPackFallbacks(packs: List<PlatformManifest>) {
  packs.flatMap { pack -> pack.fallbackCapabilities.map { it to pack } }
    .groupBy({ it.first }, { it.second })
    .forEach { (capability, owners) ->
      if (owners.size > 1) {
        throw InvalidFallbackCapabilityError(
          "Fallback capability '$capability' has multiple owners: ${owners.map { it.slug }.sorted()}.",
        )
      }
      val owner = owners.single()
      if (capability == CODE_REVIEW_FALLBACK_CAPABILITY && owner.declaredFiles.baseline == null) {
        throw InvalidFallbackCapabilityError(
          "Platform pack '${owner.slug}' declares fallback capability '$capability' without a code-review baseline.",
        )
      }
    }
}

fun discoverGovernedAddonFiles(repoRoot: Path): List<GovernedAddonFile> {
  val packsRoot = repoRoot.toAbsolutePath().normalize().resolve("platform-packs")
  if (!Files.isDirectory(packsRoot)) {
    return emptyList()
  }
  return childDirectories(packsRoot).flatMap { packDir ->
    val addonsRoot = packDir.resolve("addons")
    if (!Files.isDirectory(addonsRoot)) {
      emptyList()
    } else {
      childMarkdownFiles(addonsRoot).map { addon -> GovernedAddonFile(packDir.fileName.toString(), addon) }
    }
  }
}

internal fun validatePlatformPack(
  pack: PlatformManifest,
  contractVersion: String,
  enforceContractVersion: Boolean = true,
) {
  // F-009: defense-in-depth. The canonical schema validator (run from
  // `buildPack` via `loadPlatformManifest`) already raises
  // `ContractVersionMismatchError` for any version drift, so callers that
  // skip the contract gate (e.g. `loadPlatformManifest`) still loud-fail.
  // Keep this duplicate check so any future caller that constructs a
  // `PlatformManifest` directly (bypassing the schema validator) is still
  // gated here.
  if (enforceContractVersion && pack.contractVersion != contractVersion) {
    throw ContractVersionMismatchError(
      buildString {
        append("Platform pack '${pack.slug}': declares contract_version '${pack.contractVersion}' ")
        append("but the shell expects '$contractVersion'.")
      },
    )
  }

  val declaredAreaFiles = pack.declaredFiles.areas
  val missingAreaSlots = pack.declaredCodeReviewAreas.toSet() - declaredAreaFiles.keys
  if (missingAreaSlots.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '${pack.slug}': declared_files.areas is missing entries for ${missingAreaSlots.sorted()}.",
    )
  }

  pack.declaredFiles.baseline?.let { baseline ->
    validateGovernedSkill(pack, "baseline", baseline, "code-review", "")
  }
  pack.declaredCodeReviewAreas.forEach { area ->
    validateGovernedSkill(pack, "areas.$area", declaredAreaFiles.getValue(area), "code-review", area)
  }
  validateReviewSkillStructure(pack)
}
