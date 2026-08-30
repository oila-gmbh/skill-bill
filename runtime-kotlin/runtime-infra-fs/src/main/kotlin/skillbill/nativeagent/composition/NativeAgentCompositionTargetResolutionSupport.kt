package skillbill.nativeagent.composition

import skillbill.infrastructure.fs.FileSystemRepoLocalConfig
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.platformpack.loadPlatformPack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo

private const val FRONTMATTER_OPEN_LENGTH = 4

internal fun composedAgentBudgetBytes(repoRoot: Path): Long = FileSystemRepoLocalConfig()
  .readRepoLocalConfig(ReadRepoLocalConfigRequest(repoRoot.toAbsolutePath().normalize()))
  .config
  .reviewContextBudget
  .maxLaneLaunchBytes

internal fun resolvePlatformManifestContentTarget(
  repoRoot: Path,
  packRoot: Path,
  sourcePath: Path,
  source: NativeAgentSource,
): NativeAgentCompositionTarget? {
  val pack = loadPlatformPack(packRoot)
  return declaredContentPaths(pack)
    .firstOrNull { path -> path.parent?.name == source.name }
    ?.also { contentPath ->
      require(Files.isRegularFile(contentPath)) {
        "${displayPath(repoRoot, sourcePath)}: native agent compose target is missing at " +
          "'${displayPath(repoRoot, contentPath)}'"
      }
    }
    ?.let { contentPath ->
      NativeAgentCompositionTarget(
        contentPath = contentPath,
        source = NativeAgentCompositionTargetSource.PlatformManifest,
        manifest = pack,
      )
    }
}

internal fun resolveSiblingContentTarget(sourcePath: Path, source: NativeAgentSource): NativeAgentCompositionTarget? =
  sourcePath.parent
    ?.parent
    ?.resolve("content.md")
    ?.toAbsolutePath()
    ?.normalize()
    ?.takeIf(Files::isRegularFile)
    ?.takeIf { contentPath -> readContentFrontmatterName(contentPath) == source.name }
    ?.let { contentPath ->
      NativeAgentCompositionTarget(
        contentPath = contentPath,
        source = NativeAgentCompositionTargetSource.SiblingContent,
      )
    }

internal fun platformPackRoot(repoRoot: Path, sourcePath: Path): Path? {
  val packsRoot = repoRoot.resolve("platform-packs")
  val canonicalPacksRoot = canonicalize(packsRoot)
  val canonicalSourcePath = canonicalize(sourcePath)
  if (!canonicalSourcePath.startsWith(canonicalPacksRoot)) {
    return null
  }
  return runCatching { canonicalSourcePath.relativeTo(canonicalPacksRoot) }
    .getOrNull()
    ?.firstOrNull()
    ?.toString()
    ?.let(packsRoot::resolve)
}

internal fun displayPath(root: Path, path: Path): String {
  val resolvedRoot = root.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString() }
    .getOrDefault(resolvedPath.toString())
}

private fun canonicalize(path: Path): Path {
  val normalized = path.toAbsolutePath().normalize()
  return runCatching { normalized.toRealPath() }.getOrDefault(normalized)
}

private fun declaredContentPaths(pack: PlatformManifest): List<Path> = listOfNotNull(pack.declaredFiles.baseline) +
  pack.declaredFiles.areas.values.sortedBy { it.toString() } +
  listOfNotNull(pack.declaredQualityCheckFile)

private fun readContentFrontmatterName(contentPath: Path): String? {
  val text = Files.readString(contentPath).replace("\r\n", "\n")
  val end = text.indexOf("\n---\n", startIndex = FRONTMATTER_OPEN_LENGTH)
  return if (!text.startsWith("---\n") || end < 0) {
    null
  } else {
    text.substring(FRONTMATTER_OPEN_LENGTH, end)
      .lineSequence()
      .firstOrNull { line -> line.startsWith("name:") }
      ?.substringAfter(':')
      ?.trim()
      ?.trim('"', '\'')
      ?.takeIf { it.isNotBlank() }
  }
}
