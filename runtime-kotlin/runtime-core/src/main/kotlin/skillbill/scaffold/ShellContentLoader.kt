@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "ThrowsCount", "TooManyFunctions")

package skillbill.scaffold

import org.yaml.snakeyaml.Yaml
import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidCeremonySectionError
import skillbill.error.InvalidDescriptorSectionError
import skillbill.error.InvalidExecutionSectionError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.MissingContentFileError
import skillbill.error.MissingManifestError
import skillbill.error.MissingRequiredSectionError
import skillbill.error.MissingShellCeremonyFileError
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.GovernedAddonFile
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import java.nio.file.Files
import java.nio.file.Path

internal fun loadPlatformManifest(packRoot: Path): PlatformManifest {
  val resolvedPackRoot = packRoot.toAbsolutePath().normalize()
  val slug = resolvedPackRoot.fileName?.toString().orEmpty()
  val manifestPath = resolvedPackRoot.resolve("platform.yaml")
  if (!Files.isRegularFile(manifestPath)) {
    throw MissingManifestError("Platform pack '$slug': expected manifest at '$manifestPath' but it is missing.")
  }
  val raw = readManifest(manifestPath, slug)
  return buildPack(slug, resolvedPackRoot, manifestPath, raw)
}

internal fun loadPlatformPack(packRoot: Path): PlatformManifest {
  val pack = loadPlatformManifest(packRoot)
  validatePlatformPack(pack, SHELL_CONTRACT_VERSION)
  pack.declaredQualityCheckFile?.let { loadQualityCheckContent(pack) }
  return pack
}

internal fun discoverPlatformPacks(platformPacksRoot: Path): List<PlatformManifest> =
  childDirectories(platformPacksRoot).map(::loadPlatformPack)

internal fun discoverPlatformPackManifests(platformPacksRoot: Path): List<PlatformManifest> =
  childDirectories(platformPacksRoot).map(::loadPlatformManifest)

internal fun discoverGovernedAddonFiles(repoRoot: Path): List<GovernedAddonFile> {
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

internal fun validatePlatformPack(pack: PlatformManifest, contractVersion: String) {
  if (pack.contractVersion != contractVersion) {
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

  validateGovernedSkill(pack, "baseline", pack.declaredFiles.baseline, "code-review", "")
  pack.declaredCodeReviewAreas.forEach { area ->
    validateGovernedSkill(pack, "areas.$area", declaredAreaFiles.getValue(area), "code-review", area)
  }
}

internal fun loadQualityCheckContent(pack: PlatformManifest): Path {
  val filePath = pack.declaredQualityCheckFile
    ?: throw MissingContentFileError(
      "Platform pack '${pack.slug}': declared_quality_check_file not set " +
        "(call is only valid after checking pack.declaredQualityCheckFile is not null).",
    )
  validateGovernedSkill(pack, "quality-check", filePath, "quality-check", "")
  return filePath.resolveSibling(CONTENT_BODY_FILENAME)
}

private fun readManifest(manifestPath: Path, slug: String): Any? = try {
  Yaml().load<Any?>(Files.readString(manifestPath))
} catch (error: Exception) {
  throw InvalidManifestSchemaError(
    "Platform pack '$slug': manifest '$manifestPath' is not valid YAML: ${error.message}",
    error,
  )
}

private fun buildPack(slug: String, packRoot: Path, manifestPath: Path, raw: Any?): PlatformManifest {
  val manifest = requireManifestMap(slug, manifestPath, raw)
  val declaredPlatform = requireStringField(manifest, slug, "platform")
  if (declaredPlatform != slug) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': manifest 'platform' field is '$declaredPlatform', " +
        "expected '$slug' to match the directory name.",
    )
  }

  val contractVersion = requireStringField(manifest, slug, "contract_version")
  val routingSignals = parseRoutingSignals(manifest, slug)
  val declaredAreas = parseDeclaredAreas(manifest, slug)
  val declaredFiles = parseDeclaredFiles(manifest, slug, packRoot, declaredAreas)
  val areaMetadata = parseAreaMetadata(manifest, slug, declaredAreas)
  val displayName = parseOptionalString(manifest, slug, "display_name")
  val notes = parseOptionalString(manifest, slug, "notes")
  val declaredQualityCheckFile = parseOptionalPath(manifest, slug, "declared_quality_check_file", packRoot)

  return PlatformManifest(
    slug = slug,
    packRoot = packRoot,
    contractVersion = contractVersion,
    routingSignals = routingSignals,
    declaredCodeReviewAreas = declaredAreas,
    declaredFiles = declaredFiles,
    areaMetadata = areaMetadata,
    displayName = displayName,
    notes = notes,
    declaredQualityCheckFile = declaredQualityCheckFile,
  )
}

private fun requireManifestMap(slug: String, manifestPath: Path, raw: Any?): Map<*, *> = raw as? Map<*, *>
  ?: throw InvalidManifestSchemaError(
    "Platform pack '$slug': manifest '$manifestPath' must be a YAML mapping at the top level.",
  )

private fun parseRoutingSignals(manifest: Map<*, *>, slug: String): RoutingSignals {
  val routing = requireMappingField(manifest, slug, "routing_signals")
  val strongRaw = routing["strong"]
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest field 'routing_signals.strong' is required.")
  return RoutingSignals(
    strong = parseStringList(slug, strongRaw, "routing_signals.strong", required = true),
    tieBreakers = parseStringList(slug, routing["tie_breakers"], "routing_signals.tie_breakers", required = false),
  )
}

private fun parseDeclaredAreas(manifest: Map<*, *>, slug: String): List<String> {
  val rawAreas = requireField(manifest, slug, "declared_code_review_areas")
  if (rawAreas !is List<*>) {
    throw InvalidManifestSchemaError("Platform pack '$slug': 'declared_code_review_areas' must be a list.")
  }
  return rawAreas.map { entry ->
    val area = entry as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': every entry in 'declared_code_review_areas' must be a string.",
      )
    if (area !in APPROVED_CODE_REVIEW_AREAS) {
      throw InvalidManifestSchemaError(
        "Platform pack '$slug': declared area '$area' is not approved; " +
          "must be one of ${APPROVED_CODE_REVIEW_AREAS.sorted()}.",
      )
    }
    area
  }
}

private fun parseDeclaredFiles(
  manifest: Map<*, *>,
  slug: String,
  packRoot: Path,
  declaredAreas: List<String>,
): DeclaredFiles {
  val rawFiles = requireMappingField(manifest, slug, "declared_files")
  val baselineRaw = rawFiles["baseline"] as? String
    ?: throw InvalidManifestSchemaError(
      "Platform pack '$slug': manifest is missing required field 'declared_files.baseline'.",
    )
  if (baselineRaw.isBlank()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'declared_files.baseline' must be a non-empty path string.",
    )
  }

  val rawAreaFiles = rawFiles["areas"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
  val areaFiles = rawAreaFiles.entries.associate { (key, value) ->
    val area = key as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'declared_files.areas' entries must be string->string.",
      )
    val relativePath = value as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': 'declared_files.areas' entries must be string->string.",
      )
    area to packRoot.resolve(relativePath).normalize()
  }

  val extraAreaKeys = areaFiles.keys - declaredAreas.toSet()
  if (extraAreaKeys.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'declared_files.areas' contains entries ${extraAreaKeys.sorted()} " +
        "that are not listed in 'declared_code_review_areas'.",
    )
  }
  val missingAreaKeys = declaredAreas.toSet() - areaFiles.keys
  if (missingAreaKeys.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': 'declared_files.areas' is missing entries for ${missingAreaKeys.sorted()}.",
    )
  }

  return DeclaredFiles(
    baseline = packRoot.resolve(baselineRaw).normalize(),
    areas = areaFiles,
  )
}

private fun parseAreaMetadata(manifest: Map<*, *>, slug: String, declaredAreas: List<String>): Map<String, String> {
  val rawMetadata = requireMappingField(manifest, slug, "area_metadata")
  val areaMetadata = mutableMapOf<String, String>()
  val extraAreaMetadata = mutableSetOf<String>()
  for ((key, value) in rawMetadata) {
    val area = key as? String
      ?: throw InvalidManifestSchemaError("Platform pack '$slug': area_metadata entries must be string -> mapping.")
    if (area !in declaredAreas) {
      extraAreaMetadata += area
      continue
    }
    val metadata = value as? Map<*, *>
      ?: throw InvalidManifestSchemaError("Platform pack '$slug': area_metadata['$area'] must be a mapping.")
    val focus = metadata["focus"] as? String
      ?: throw InvalidManifestSchemaError(
        "Platform pack '$slug': area_metadata['$area'].focus must be a non-empty string.",
      )
    areaMetadata[area] = focus
  }
  if (extraAreaMetadata.isNotEmpty()) {
    throw InvalidManifestSchemaError(
      "Platform pack '$slug': area_metadata contains entries ${extraAreaMetadata.sorted()} " +
        "that are not listed in 'declared_code_review_areas'.",
    )
  }
  declaredAreas.forEach { declaredArea -> areaMetadata.putIfAbsent(declaredArea, defaultAreaFocus(declaredArea)) }
  return areaMetadata
}

private fun parseOptionalString(manifest: Map<*, *>, slug: String, key: String): String? = manifest[key]?.let {
  it as? String ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a string when provided.")
}

private fun parseOptionalPath(manifest: Map<*, *>, slug: String, key: String, packRoot: Path): Path? {
  val raw = manifest[key] ?: return null
  val value = raw as? String
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a non-empty path string when provided.")
  if (value.isBlank()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a non-empty path string when provided.")
  }
  return packRoot.resolve(value).normalize()
}

private fun requireMappingField(manifest: Map<*, *>, slug: String, key: String): Map<*, *> = manifest[key] as? Map<*, *>
  ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest field '$key' must be a mapping.")

private fun requireField(manifest: Map<*, *>, slug: String, key: String): Any =
  manifest[key] ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest is missing required field '$key'.")

private fun requireStringField(manifest: Map<*, *>, slug: String, key: String): String {
  val value = requireField(manifest, slug, key) as? String
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a string.")
  if (value.isBlank()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a non-empty string.")
  }
  return value
}

private fun parseStringList(slug: String, value: Any?, fieldLabel: String, required: Boolean): List<String> {
  if (value == null) {
    return emptyList()
  }
  if (value !is List<*>) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldLabel' must be a list of strings.")
  }
  val parsed = value.map { entry ->
    entry as? String
      ?: throw InvalidManifestSchemaError("Platform pack '$slug': every entry in '$fieldLabel' must be a string.")
  }
  if (required && parsed.isEmpty()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$fieldLabel' must contain at least one routing signal.")
  }
  return parsed
}

private fun validateGovernedSkill(
  pack: PlatformManifest,
  slot: String,
  skillPath: Path,
  family: String,
  area: String,
) {
  if (!Files.isRegularFile(skillPath)) {
    throw MissingContentFileError(
      "Platform pack '${pack.slug}': declared content file for slot '$slot' is missing at '$skillPath'.",
    )
  }

  val sections = collectTopLevelH2Sections(Files.readString(skillPath))
  ensureRequiredSections(pack.slug, skillPath, sections)
  ensureSiblingFiles(pack.slug, slot, skillPath)

  val context = governedContext(pack, skillPath.parent.fileName.toString(), family, area)
  val expectedDescriptor =
    renderDescriptorSection(context, pack.areaMetadata.getValueOrDefault(area, defaultAreaFocus(area)))
  if (sections.getValue("## Execution") != CANONICAL_EXECUTION_SECTION) {
    throw InvalidExecutionSectionError(
      "Platform pack '${pack.slug}': skill file '$skillPath' has a drifted ## Execution section.",
    )
  }
  if (sections.getValue("## Ceremony") != CANONICAL_CEREMONY_SECTION) {
    throw InvalidCeremonySectionError(
      "Platform pack '${pack.slug}': skill file '$skillPath' has a drifted ## Ceremony section.",
    )
  }
  if (sections.getValue("## Descriptor") != expectedDescriptor) {
    throw InvalidDescriptorSectionError(
      "Platform pack '${pack.slug}': skill file '$skillPath' has a drifted ## Descriptor section.",
    )
  }
}

private fun ensureRequiredSections(slug: String, skillPath: Path, sections: Map<String, String>) {
  REQUIRED_GOVERNED_SECTIONS.forEach { required ->
    if (required !in sections) {
      throw MissingRequiredSectionError(
        "Platform pack '$slug': skill file '$skillPath' is missing required section '$required'.",
      )
    }
  }
}

private fun ensureSiblingFiles(slug: String, slot: String, skillPath: Path) {
  val contentPath = skillPath.resolveSibling(CONTENT_BODY_FILENAME)
  if (!Files.isRegularFile(contentPath)) {
    throw MissingContentFileError(
      "Platform pack '$slug': sibling content file for slot '$slot' is missing at '$contentPath'.",
    )
  }

  val ceremonyPath = skillPath.resolveSibling("shell-ceremony.md")
  if (!Files.isRegularFile(ceremonyPath)) {
    throw MissingShellCeremonyFileError(
      "Platform pack '$slug': sibling shell ceremony file for slot '$slot' is missing at '$ceremonyPath'.",
    )
  }
}

private fun collectTopLevelH2Sections(text: String): Map<String, String> {
  val visibleLines = mutableListOf<String>()
  var inFence = false
  for (line in text.lineSequence()) {
    if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
      inFence = !inFence
      continue
    }
    if (!inFence) {
      visibleLines += line
    }
  }
  val visibleText = visibleLines.joinToString("\n")
  val headingRegex = Regex("^##\\s+[^\\n]+$", RegexOption.MULTILINE)
  val matches = headingRegex.findAll(visibleText).toList()
  val sections = linkedMapOf<String, String>()
  matches.forEachIndexed { index, match ->
    val heading = match.value.trim()
    val end = matches.getOrNull(index + 1)?.range?.first ?: visibleText.length
    sections[heading] = visibleText.substring(match.range.first, end).trimEnd() + "\n"
  }
  return sections
}

private fun childDirectories(root: Path): List<Path> {
  if (!Files.isDirectory(root)) {
    return emptyList()
  }
  return Files.list(root).use { stream ->
    stream
      .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
      .toList()
      .sortedBy { it.fileName.toString() }
  }
}

private fun childMarkdownFiles(root: Path): List<Path> {
  if (!Files.isDirectory(root)) {
    return emptyList()
  }
  return Files.list(root).use { stream ->
    stream
      .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".md") }
      .toList()
      .sortedBy { it.fileName.toString() }
  }
}

private fun Map<String, String>.getValueOrDefault(key: String, default: String): String = this[key] ?: default

private fun governedContext(pack: PlatformManifest, skillName: String, family: String, area: String): TemplateContext =
  TemplateContext(
    skillName = skillName,
    family = family,
    platform = pack.slug,
    area = area,
    displayName = pack.displayName ?: pack.slug.replace('-', ' ').replaceFirstChar { it.uppercaseChar() },
  )
