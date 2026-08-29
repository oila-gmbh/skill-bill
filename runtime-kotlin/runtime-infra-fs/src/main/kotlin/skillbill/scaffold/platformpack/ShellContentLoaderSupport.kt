@file:Suppress("MaxLineLength", "TooGenericExceptionCaught", "ThrowsCount")

package skillbill.scaffold.platformpack

import org.yaml.snakeyaml.Yaml
import skillbill.error.ContractVersionMismatchError
import skillbill.error.InvalidFallbackCapabilityError
import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidValidationGateDeclarationError
import skillbill.error.MissingContentFileError
import skillbill.error.MissingManifestError
import skillbill.error.MissingRequiredSectionError
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.FeatureAddonUsage
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonFile
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.scaffold.rendering.defaultAreaFocus
import skillbill.scaffold.runtime.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.runtime.CONTENT_BODY_FILENAME
import skillbill.scaffold.runtime.SHELL_CONTRACT_VERSION
import skillbill.scaffold.validation.parseSkillFrontmatter
import skillbill.scaffold.validation.validateAuthoredContent
import skillbill.scaffold.validation.validateReviewSkillStructure
import skillbill.scaffold.validation.validateSkillMdShape
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.InvalidPathException
import skillbill.scaffold.validation.ReviewSkillStructureValidator

internal fun requireMappingField(manifest: Map<*, *>, slug: String, key: String): Map<*, *> = manifest[key] as? Map<*, *>
  ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest field '$key' must be a mapping.")

internal fun requireField(manifest: Map<*, *>, slug: String, key: String): Any =
  manifest[key] ?: throw InvalidManifestSchemaError("Platform pack '$slug': manifest is missing required field '$key'.")

internal fun requireStringField(manifest: Map<*, *>, slug: String, key: String): String {
  val value = requireField(manifest, slug, key) as? String
    ?: throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a string.")
  if (value.isBlank()) {
    throw InvalidManifestSchemaError("Platform pack '$slug': '$key' must be a non-empty string.")
  }
  return value
}

internal fun parseStringList(slug: String, value: Any?, fieldLabel: String, required: Boolean): List<String> {
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

internal fun validateGovernedSkill(
  pack: PlatformManifest,
  slot: String,
  skillPath: Path,
  @Suppress("UNUSED_PARAMETER") family: String,
  @Suppress("UNUSED_PARAMETER") area: String,
) {
  if (skillPath.fileName?.toString() != CONTENT_BODY_FILENAME) {
    throw InvalidManifestSchemaError(
      "Platform pack '${pack.slug}': declared content file for slot '$slot' must end in " +
        "'$CONTENT_BODY_FILENAME' but was '${displayPackPath(pack, skillPath)}'.",
    )
  }
  if (!Files.isRegularFile(skillPath)) {
    throw MissingContentFileError(
      "Platform pack '${pack.slug}': declared content file for slot '$slot' is missing at '$skillPath'.",
    )
  }
  val text = Files.readString(skillPath)
  validateSkillMdShape(skillPath, validateBodyShape = false)
  // SKILL-105: declared quality-check pack skills are dispatch targets, not user commands.
  if (family == "quality-check") {
    val internalFor = parseSkillFrontmatter(text)["internal-for"]
    if (internalFor != "bill-code-check") {
      throw InvalidManifestSchemaError(
        "Platform pack '${pack.slug}': declared content file for slot '$slot' must declare " +
          "'internal-for: bill-code-check' so stack-specific quality-check overrides install as " +
          "sidecars of bill-code-check.",
      )
    }
  }
  ensureValidAuthoredContent(pack.slug, skillPath, text)
}

internal fun ensureValidAuthoredContent(slug: String, skillPath: Path, text: String) {
  val authoredIssues = validateAuthoredContent(skillPath, text)
  if (authoredIssues.isNotEmpty()) {
    throw MissingRequiredSectionError(
      "Platform pack '$slug': ${authoredIssues.first()}",
    )
  }
}

internal fun displayPackPath(pack: PlatformManifest, path: Path): String = runCatching {
  pack.packRoot.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
    .toString()
    .replace('\\', '/')
}.getOrDefault(path.toString())

internal fun childDirectories(root: Path): List<Path> {
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

internal fun childMarkdownFiles(root: Path): List<Path> {
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
