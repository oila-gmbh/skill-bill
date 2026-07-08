@file:Suppress("MaxLineLength", "MagicNumber", "ThrowsCount", "TooManyFunctions")

package skillbill.contracts.team

import skillbill.error.InvalidTeamBundleSchemaError
import skillbill.error.ShellContentContractException
import skillbill.install.staging.INSTALL_STAGING_CONTENT_HASH_FILENAME
import skillbill.scaffold.platformpack.FEATURE_TASK_ADDON_CONSUMER
import skillbill.scaffold.platformpack.loadPlatformManifest
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.runtime.supportingFileTargets
import skillbill.scaffold.validation.validateAuthoredContent
import skillbill.team.model.TeamBundleSourceCategory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo

object TeamBundleSourceValidator {
  fun validateSources(bundle: Map<String, Any?>, repoRoot: Path, sourceLabel: String): Map<String, Any?> {
    val root = repoRoot.toAbsolutePath().normalize()
    val sources = bundle["sources"] as? List<*>
      ?: throw InvalidTeamBundleSchemaError(sourceLabel, "sources", "sources must be an array.")
    val canonicalSources = sources.mapIndexed { index, raw ->
      val entry = raw as? Map<*, *>
        ?: throw InvalidTeamBundleSchemaError(sourceLabel, "sources[$index]", "source entry must be an object.")
      canonicalizeEntry(entry, index, root, sourceLabel)
    }
    return bundle + ("sources" to canonicalSources)
  }

  private fun canonicalizeEntry(entry: Map<*, *>, index: Int, root: Path, sourceLabel: String): Map<Any?, Any?> {
    val fieldPath = "sources[$index].path"
    val rawPath = entry["path"] as? String
      ?: throw InvalidTeamBundleSchemaError(sourceLabel, fieldPath, "source entry path must be a string.")
    val categoryValue = entry["category"] as? String
      ?: throw InvalidTeamBundleSchemaError(
        sourceLabel,
        "sources[$index].category",
        "source category must be a string.",
      )
    val category = TeamBundleSourceCategory.fromWireValue(categoryValue)
      ?: throw InvalidTeamBundleSchemaError(
        sourceLabel,
        "sources[$index].category",
        "unknown source category '$categoryValue'.",
      )
    val relativePath = normalizeRelativeSourcePath(rawPath, root, sourceLabel, fieldPath)
    val forbiddenReason = forbiddenEntryReason(relativePath, root)
    if (forbiddenReason != null) {
      throw InvalidTeamBundleSchemaError(sourceLabel, fieldPath, forbiddenReason)
    }
    validateCategoryParity(category, relativePath, sourceLabel, fieldPath)
    validateCategorySource(category, relativePath, root, sourceLabel, fieldPath)
    return entry + ("path" to relativePath.toString().replace('\\', '/'))
  }

  private fun normalizeRelativeSourcePath(rawPath: String, root: Path, sourceLabel: String, fieldPath: String): Path {
    val sourcePath = Path.of(rawPath)
    val resolved = if (sourcePath.isAbsolute) sourcePath.normalize() else root.resolve(sourcePath).normalize()
    if (!resolved.startsWith(root)) {
      throw InvalidTeamBundleSchemaError(sourceLabel, fieldPath, "source path escapes the repository root.")
    }
    return resolved.relativeTo(root)
  }

  private fun validateCategoryParity(
    category: TeamBundleSourceCategory,
    relativePath: Path,
    sourceLabel: String,
    fieldPath: String,
  ) {
    val path = relativePath.toString().replace('\\', '/')
    val matches = when (category) {
      TeamBundleSourceCategory.HORIZONTAL_SKILL -> path.startsWith("skills/")
      TeamBundleSourceCategory.PLATFORM_PACK -> path.startsWith("platform-packs/") && !path.contains("/addons/")
      TeamBundleSourceCategory.ADDON -> path.startsWith("platform-packs/") && path.contains("/addons/")
      TeamBundleSourceCategory.PLATFORM_OVERRIDE -> path.startsWith("platform-packs/") && !path.contains("/addons/")
      TeamBundleSourceCategory.NATIVE_AGENT_SOURCE -> path.contains("/native-agents/")
      TeamBundleSourceCategory.ORCHESTRATION_CONTRACT_OR_SUPPORT -> path.startsWith("orchestration/")
    }
    if (!matches) {
      throw InvalidTeamBundleSchemaError(
        sourceLabel,
        fieldPath,
        "source path '$path' does not match category '${category.wireValue}'.",
      )
    }
  }

  private fun validateCategorySource(
    category: TeamBundleSourceCategory,
    relativePath: Path,
    root: Path,
    sourceLabel: String,
    fieldPath: String,
  ) {
    when (category) {
      TeamBundleSourceCategory.HORIZONTAL_SKILL,
      TeamBundleSourceCategory.NATIVE_AGENT_SOURCE,
      -> validateGovernedSkillSource(relativePath, root, sourceLabel, fieldPath)
      TeamBundleSourceCategory.PLATFORM_PACK,
      TeamBundleSourceCategory.PLATFORM_OVERRIDE,
      -> validatePlatformPackGovernedOrPackSource(relativePath, root, sourceLabel, fieldPath)
      TeamBundleSourceCategory.ADDON,
      -> validatePlatformPackSource(relativePath, root, sourceLabel, fieldPath)
      TeamBundleSourceCategory.ORCHESTRATION_CONTRACT_OR_SUPPORT,
      -> requireExistingSource(relativePath, root, sourceLabel, fieldPath)
    }
  }

  private fun validatePlatformPackGovernedOrPackSource(
    relativePath: Path,
    root: Path,
    sourceLabel: String,
    fieldPath: String,
  ) {
    validatePlatformPackSource(relativePath, root, sourceLabel, fieldPath)
    if (platformSkillRoot(relativePath, root) != null) {
      validateGovernedSkillSource(relativePath, root, sourceLabel, fieldPath)
    }
  }

  private fun validateGovernedSkillSource(relativePath: Path, root: Path, sourceLabel: String, fieldPath: String) {
    val segments = relativePath.map { it.name }.toList()
    val skillRoot = when {
      segments.firstOrNull() == "skills" && segments.size >= 2 -> root.resolve("skills").resolve(segments[1])
      segments.firstOrNull() == "platform-packs" -> platformSkillRoot(relativePath, root)
      else -> null
    } ?: throw InvalidTeamBundleSchemaError(sourceLabel, fieldPath, "source path is not a governed skill source.")
    val contentFile = skillRoot.resolve("content.md")
    if (!Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS)) {
      throw InvalidTeamBundleSchemaError(
        sourceLabel,
        fieldPath,
        "governed skill source is missing required content.md.",
      )
    }
    val issues = validateAuthoredContent(contentFile, Files.readString(contentFile))
    if (issues.isNotEmpty()) {
      throw InvalidTeamBundleSchemaError(sourceLabel, fieldPath, issues.first())
    }
    requireExistingSource(relativePath, root, sourceLabel, fieldPath)
  }

  private fun platformSkillRoot(relativePath: Path, root: Path): Path? {
    val segments = relativePath.map { it.name }.toList()
    if (segments.size < 4 || segments.firstOrNull() != "platform-packs") return null
    return when (segments[2]) {
      "code-review", "quality-check" -> root.resolve(
        "platform-packs",
      ).resolve(segments[1]).resolve(segments[2]).resolve(segments[3])
      else -> null
    }
  }

  private fun validatePlatformPackSource(relativePath: Path, root: Path, sourceLabel: String, fieldPath: String) {
    val segments = relativePath.map { it.name }.toList()
    if (segments.size < 2 || segments.first() != "platform-packs") {
      throw InvalidTeamBundleSchemaError(
        sourceLabel,
        fieldPath,
        "platform pack source must live under platform-packs/<slug>.",
      )
    }
    val packRoot = root.resolve("platform-packs").resolve(segments[1])
    try {
      loadPlatformPack(packRoot)
    } catch (error: ShellContentContractException) {
      throw InvalidTeamBundleSchemaError(
        sourceLabel = sourceLabel,
        fieldPath = fieldPath,
        reason = error.message ?: "platform pack validation failed.",
        cause = error,
      )
    }
    requireExistingSource(relativePath, root, sourceLabel, fieldPath)
  }

  private fun requireExistingSource(relativePath: Path, root: Path, sourceLabel: String, fieldPath: String) {
    val resolved = root.resolve(relativePath).normalize()
    if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
      throw InvalidTeamBundleSchemaError(sourceLabel, fieldPath, "source path does not exist.")
    }
  }

  private fun forbiddenEntryReason(relativePath: Path, root: Path): String? =
    forbiddenGeneratedArtifactReason(relativePath, root) ?: forbiddenStateArtifactReason(relativePath)

  private fun forbiddenGeneratedArtifactReason(relativePath: Path, root: Path): String? {
    val path = relativePath.toString().replace('\\', '/')
    val segments = relativePath.map { it.name }.toList()
    return when {
      relativePath.name == "SKILL.md" && hasSiblingContent(root.resolve(relativePath)) ->
        "generated governed SKILL.md wrappers are not valid bundle sources."
      isGeneratedSupportPointerFile(relativePath, root) ->
        "generated support pointer files are not valid bundle sources."
      segments.any { it in PROVIDER_NATIVE_OUTPUT_DIRECTORIES } ->
        "provider-specific native-agent output is not a valid bundle source."
      relativePath.name == INSTALL_STAGING_CONTENT_HASH_FILENAME ->
        "installed staging content-hash markers are not valid bundle sources."
      segments.any { it in INSTALL_STAGING_SEGMENTS } ->
        "installed staging artifacts are not valid bundle sources."
      else -> null
    }
  }

  private fun forbiddenStateArtifactReason(relativePath: Path): String? {
    val path = relativePath.toString().replace('\\', '/')
    val segments = relativePath.map { it.name }.toList()
    return when {
      segments.any { it in WORKFLOW_STATE_SEGMENTS } || path.endsWith(".db") || path.endsWith(".sqlite") ->
        "workflow database state is not a valid bundle source."
      segments.any { it in DESKTOP_STATE_SEGMENTS } ->
        "desktop app state is not a valid bundle source."
      segments.any { it in TELEMETRY_OUTBOX_SEGMENTS } || path.endsWith("telemetry-outbox.jsonl") ->
        "telemetry outbox files are not valid bundle sources."
      segments.any { it in LOCAL_RECENTS_SEGMENTS } ->
        "desktop local-recents state is not a valid bundle source."
      else -> null
    }
  }

  private fun hasSiblingContent(path: Path): Boolean =
    Files.isRegularFile(path.resolveSibling("content.md"), LinkOption.NOFOLLOW_LINKS)

  private fun isGeneratedSupportPointerFile(relativePath: Path, root: Path): Boolean {
    val pointerTarget = supportingFileTargets(root)[relativePath.name]
    val resolved = root.resolve(relativePath).normalize().toAbsolutePath()
    if (pointerTarget != null && resolved != pointerTarget.normalize().toAbsolutePath()) return true
    return isGeneratedPlatformPointerFile(relativePath, root) ||
      isGeneratedFeatureTaskAddonPointerFile(relativePath, root)
  }

  private fun isGeneratedPlatformPointerFile(relativePath: Path, root: Path): Boolean {
    val segments = relativePath.map { it.name }.toList()
    if (segments.size < 4 || segments.firstOrNull() != "platform-packs") return false
    val packRoot = root.resolve("platform-packs").resolve(segments[1])
    val relativeText = relativePath.toString().replace('\\', '/')
    val pack = try {
      loadPlatformManifest(packRoot)
    } catch (_: ShellContentContractException) {
      return false
    }
    return pack.pointers.any { pointer ->
      relativeText == "platform-packs/${pack.slug}/${pointer.skillRelativeDir}/${pointer.name}"
    }
  }

  private fun isGeneratedFeatureTaskAddonPointerFile(relativePath: Path, root: Path): Boolean {
    val relativeText = relativePath.toString().replace('\\', '/')
    if (relativeText != "skills/bill-feature-task/${relativePath.name}") return false
    val platformPacksRoot = root.resolve("platform-packs")
    if (!Files.isDirectory(platformPacksRoot)) return false
    return Files.list(platformPacksRoot).use { packRoots ->
      packRoots
        .filter { packRoot -> Files.isDirectory(packRoot) }
        .anyMatch { packRoot ->
          val pack = try {
            loadPlatformManifest(packRoot)
          } catch (_: ShellContentContractException) {
            return@anyMatch false
          }
          val featureAddonPointerNames = pack.featureAddonUsage
            .filter { usage -> usage.consumer == FEATURE_TASK_ADDON_CONSUMER }
            .flatMap { usage ->
              usage.addons.flatMap { addon -> listOf(addon.entrypoint) + addon.companionPointers }
            }
            .toSet()
          pack.pointers.any { pointer ->
            pointer.skillRelativeDir == FEATURE_TASK_ADDON_CONSUMER &&
              pointer.name == relativePath.name &&
              pointer.name in featureAddonPointerNames
          }
        }
    }
  }

  private val PROVIDER_NATIVE_OUTPUT_DIRECTORIES =
    setOf("claude-agents", "codex-agents", "opencode-agents", "junie-agents")
  private val INSTALL_STAGING_SEGMENTS = setOf(".skill-bill", "staging", "installed", "install-staging")
  private val WORKFLOW_STATE_SEGMENTS = setOf("workflow", "workflows", "workflow-state", "workflow-states")
  private val DESKTOP_STATE_SEGMENTS = setOf("runtime-desktop-state", "desktop-state", ".desktop", ".compose")
  private val TELEMETRY_OUTBOX_SEGMENTS = setOf("telemetry-outbox", "outbox", "telemetry-outbox.jsonl")
  private val LOCAL_RECENTS_SEGMENTS = setOf("local-recents", "local-recents.json", "recent-repos.json")
}
