@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package skillbill.nativeagent.validation

import skillbill.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.composition.displayPath
import skillbill.nativeagent.composition.nativeAgentCompositionRepoRoot
import skillbill.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.nativeagent.discovery.discoverNativeAgentSourceFiles
import skillbill.nativeagent.discovery.discoverNativeAgentSourceFilesInRoots
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.nativeagent.rendering.discoverRepoNativeAgentSourceFiles
import skillbill.review.plan.ReviewLaunchPlanPolicy
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import java.nio.file.Files
import java.nio.file.Path

data class NativeAgentValidationReport(
  val issues: List<String>,
) {
  val passed: Boolean = issues.isEmpty()
}

fun validateRepoNativeAgents(repoRoot: Path): NativeAgentValidationReport {
  val root = repoRoot.toAbsolutePath().normalize()
  val issues = mutableListOf<String>()
  val sourceFiles = discoverRepoNativeAgentSourceFiles(root)
  val sources = parseNativeAgentSourcesForValidation(root, sourceFiles, issues)
  validateNativeAgentSources(root, sources, issues)
  validatePlannedWorkerDeclarations(root, sources, issues)
  validateNoCheckedInGeneratedArtifacts(root, issues)
  return NativeAgentValidationReport(issues.sorted())
}

internal fun validateNativeAgentArtifactsForInstall(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
) {
  val sourceFiles = discoverNativeAgentSourceFiles(platformPacksRoot, skillsRoot, selectedPlatforms)
  validateNativeAgentSourceFilesForInstall(
    sourceFiles = sourceFiles,
    root = nativeAgentCompositionRepoRoot(platformPacksRoot, skillsRoot),
  )
}

internal fun validateNativeAgentArtifactsForInstall(sourceRoots: List<Path>, root: Path) {
  val sourceFiles = discoverNativeAgentSourceFilesInRoots(sourceRoots)
  validateNativeAgentSourceFilesForInstall(sourceFiles, root)
}

private fun validateNativeAgentSourceFilesForInstall(sourceFiles: List<Path>, root: Path) {
  if (sourceFiles.isEmpty()) {
    return
  }
  val issues = mutableListOf<String>()
  val sources = parseNativeAgentSourcesForValidation(root, sourceFiles, issues)
  validateNativeAgentSources(root, sources, issues)
  require(issues.isEmpty()) {
    "Native agent sources are invalid:\n${issues.sorted().joinToString("\n")}"
  }
}

private fun parseNativeAgentSourcesForValidation(
  root: Path,
  sourceFiles: List<Path>,
  issues: MutableList<String>,
): List<NativeAgentSource> = sourceFiles.flatMap { sourcePath ->
  runCatching { parseNativeAgentSourceFile(sourcePath) }
    .getOrElse { error ->
      issues += "${displayPath(root, sourcePath)}: ${error.message.orEmpty()}"
      emptyList()
    }
}

private fun validateNativeAgentSources(root: Path, sources: List<NativeAgentSource>, issues: MutableList<String>) {
  val seenNames = mutableMapOf<String, NativeAgentSource>()
  sources.forEach { source ->
    requireNotNull(source.path) { "validated native agent source requires a path" }
    val duplicate = seenNames.putIfAbsent(source.name, source)
    if (duplicate != null) {
      issues += "${nativeAgentSourceDisplay(root, source)}: native agent source name '${source.name}' duplicates " +
        nativeAgentSourceDisplay(root, duplicate)
    }
    if (containsProviderConditional(source.body)) {
      issues += "${nativeAgentSourceDisplay(root, source)}: " +
        "native agent bodies must be provider-agnostic; conditionals belong in the renderer"
    }
    val composed = runCatching { composeNativeAgentSource(root, source) }
      .getOrElse { error ->
        issues += "${nativeAgentSourceDisplay(root, source)}: ${error.message.orEmpty()}"
        return@forEach
      }
    if (composed !== source && containsProviderConditional(composed.body)) {
      issues += "${nativeAgentSourceDisplay(root, source)}: " +
        "composed native agent bodies must be provider-agnostic; conditionals belong in the renderer"
    }
    NativeAgentProvider.entries.forEach { provider ->
      runCatching { provider.render(composed) }.getOrElse { error ->
        issues += "${nativeAgentSourceDisplay(root, source)}: cannot render ${provider.directoryName}: " +
          error.message.orEmpty()
      }
    }
  }
}

private fun validatePlannedWorkerDeclarations(
  root: Path,
  sources: List<NativeAgentSource>,
  issues: MutableList<String>,
) {
  val packsRoot = root.resolve("platform-packs")
  if (!Files.isDirectory(packsRoot) || !Files.isDirectory(root.resolve("orchestration/contracts"))) return
  val manifests = runCatching { discoverPlatformPackManifests(packsRoot) }
    .getOrElse { error ->
      issues += "platform-packs: cannot derive native-agent worker set: ${error.message.orEmpty()}"
      return
    }
  val selectedAreas = manifests.flatMapTo(linkedSetOf()) { it.declaredCodeReviewAreas }
  val plannedNames = manifests.flatMap { manifest ->
    ReviewLaunchPlanPolicy.flatten(manifest.slug, manifests, selectedAreas).lanes.map { it.skillName }
  }.toSortedSet()
  val declarations = sources.groupBy { it.name }
  plannedNames.forEach { worker ->
    when (declarations[worker].orEmpty().size) {
      0 -> issues += "platform-packs: planned review worker '$worker' has no native-agent source declaration"
      1 -> Unit
      else -> issues += "platform-packs: planned review worker '$worker' has ambiguous native-agent declarations"
    }
  }
}

internal fun nativeAgentSourceDisplay(root: Path, source: NativeAgentSource): String {
  val sourcePath = requireNotNull(source.path) { "native agent source display requires a path" }
  val base = displayPath(root, sourcePath)
  return if (source.bundleEntryName == null) {
    base
  } else {
    "$base entry '${source.bundleEntryName}'"
  }
}

private val PROVIDER_CONDITIONAL_HANDLEBARS_REGEX: Regex = Regex(
  "\\{\\{\\s*#\\s*(${NativeAgentProvider.entries.joinToString("|") { it.name.lowercase() }})\\s*\\}\\}",
  RegexOption.IGNORE_CASE,
)

private val PROVIDER_CONDITIONAL_CASE_INSENSITIVE: List<String> = listOf(
  "if provider ==",
  "if (provider",
)

private fun containsProviderConditional(body: String): Boolean {
  if (PROVIDER_CONDITIONAL_HANDLEBARS_REGEX.containsMatchIn(body)) {
    return true
  }
  val lowered = body.lowercase()
  return PROVIDER_CONDITIONAL_CASE_INSENSITIVE.any { token -> token in lowered }
}

private fun validateNoCheckedInGeneratedArtifacts(root: Path, issues: MutableList<String>) {
  discoverNativeAgentGeneratedArtifactFiles(root)
    .forEach { artifact ->
      issues += "${displayPath(root, artifact)}: generated native agent artifacts must not be checked in; " +
        "keep the source in $NATIVE_AGENT_SOURCE_DIR/ and let install render provider files"
    }
}

fun discoverNativeAgentGeneratedArtifactFiles(repoRoot: Path): List<Path> {
  val root = repoRoot.toAbsolutePath().normalize()
  val providerDirs = NativeAgentProvider.entries.map { it.directoryName }.toSet()
  return listOf(root.resolve("skills"), root.resolve("platform-packs"))
    .filter(Files::isDirectory)
    .flatMap { scanRoot ->
      Files.walk(scanRoot).use { stream ->
        stream
          .filter(Files::isRegularFile)
          .filter { file -> file.parent?.fileName?.toString() in providerDirs }
          .toList()
      }
    }.sortedBy { it.toString() }
}
