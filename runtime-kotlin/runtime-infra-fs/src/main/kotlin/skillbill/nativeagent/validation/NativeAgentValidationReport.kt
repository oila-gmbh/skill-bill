package skillbill.nativeagent.validation

import skillbill.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.nativeagent.composition.NativeAgentCompositionContext
import skillbill.nativeagent.composition.NativeAgentSource
import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.composition.displayPath
import skillbill.nativeagent.composition.nativeAgentCompositionRepoRoot
import skillbill.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.nativeagent.composition.resolveNativeAgentCompositionTarget
import skillbill.nativeagent.discovery.discoverNativeAgentSourceFiles
import skillbill.nativeagent.discovery.discoverNativeAgentSourceFilesInRoots
import skillbill.nativeagent.rendering.NativeAgentProvider
import skillbill.nativeagent.rendering.discoverRepoNativeAgentSourceFiles
import skillbill.nativeagent.rendering.enforceAddonProjectionParity
import java.nio.file.Files
import java.nio.file.Path

data class NativeAgentValidationReport(
  val issues: List<String>,
) {
  val passed: Boolean = issues.isEmpty()
}

fun validateRepoNativeAgents(
  repoRoot: Path,
  compositionContext: NativeAgentCompositionContext,
): NativeAgentValidationReport {
  val root = repoRoot.toAbsolutePath().normalize()
  val issues = mutableListOf<String>()
  val sourceFiles = discoverRepoNativeAgentSourceFiles(root)
  val sources = parseNativeAgentSourcesForValidation(root, sourceFiles, issues)
  validateNativeAgentSources(root, sources, issues, compositionContext)
  validateNoCheckedInGeneratedArtifacts(root, issues)
  return NativeAgentValidationReport(issues.sorted())
}

internal fun validateNativeAgentArtifactsForInstall(
  platformPacksRoot: Path,
  skillsRoot: Path?,
  selectedPlatforms: List<String>?,
  compositionContext: NativeAgentCompositionContext,
) {
  val sourceFiles = discoverNativeAgentSourceFiles(platformPacksRoot, skillsRoot, selectedPlatforms)
  validateNativeAgentSourceFilesForInstall(
    sourceFiles = sourceFiles,
    root = nativeAgentCompositionRepoRoot(platformPacksRoot, skillsRoot),
    compositionContext = compositionContext,
  )
}

internal fun validateNativeAgentArtifactsForInstall(
  sourceRoots: List<Path>,
  root: Path,
  compositionContext: NativeAgentCompositionContext,
) {
  val sourceFiles = discoverNativeAgentSourceFilesInRoots(sourceRoots)
  validateNativeAgentSourceFilesForInstall(sourceFiles, root, compositionContext)
}

private fun validateNativeAgentSourceFilesForInstall(
  sourceFiles: List<Path>,
  root: Path,
  compositionContext: NativeAgentCompositionContext,
) {
  if (sourceFiles.isEmpty()) {
    return
  }
  val issues = mutableListOf<String>()
  val sources = parseNativeAgentSourcesForValidation(root, sourceFiles, issues)
  validateNativeAgentSources(root, sources, issues, compositionContext)
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

private fun validateNativeAgentSources(
  root: Path,
  sources: List<NativeAgentSource>,
  issues: MutableList<String>,
  compositionContext: NativeAgentCompositionContext,
) {
  val seenNames = mutableMapOf<String, NativeAgentSource>()
  sources.forEach { source ->
    requireNotNull(source.path) { "validated native agent source requires a path" }
    val duplicate = seenNames.putIfAbsent(source.name, source)
    if (duplicate != null) {
      issues += "${nativeAgentSourceDisplay(root, source)}: native agent source name '${source.name}' duplicates " +
        nativeAgentSourceDisplay(root, duplicate)
    }
    if (containsNativeAgentProviderConditional(source.body)) {
      issues += "${nativeAgentSourceDisplay(root, source)}: " +
        "native agent bodies must be provider-agnostic; conditionals belong in the renderer"
    }
    val composed = runCatching {
      composeNativeAgentSource(
        root,
        source,
        compositionContext.reviewContextBudgetBytes,
        compositionContext.renderGovernedBody,
        compositionContext.packLoader,
      )
    }.getOrElse { error ->
      issues += "${nativeAgentSourceDisplay(root, source)}: ${error.message.orEmpty()}"
      return@forEach
    }
    if (composed !== source && containsNativeAgentProviderConditional(composed.body)) {
      issues += "${nativeAgentSourceDisplay(root, source)}: " +
        "composed native agent bodies must be provider-agnostic; conditionals belong in the renderer"
    }
    runCatching {
      val pack = resolveNativeAgentCompositionTarget(root, source, compositionContext.packLoader)?.manifest
      if (pack != null) {
        enforceAddonProjectionParity(pack, composed.name, composed.composedAddonSlugs)
      }
    }.onFailure { error ->
      issues += "${nativeAgentSourceDisplay(root, source)}: ${error.message.orEmpty()}"
    }
    NativeAgentProvider.entries.forEach { provider ->
      runCatching { provider.render(composed) }.getOrElse { error ->
        issues += "${nativeAgentSourceDisplay(root, source)}: cannot render ${provider.directoryName}: " +
          error.message.orEmpty()
      }
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
