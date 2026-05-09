package skillbill.nativeagent

import skillbill.scaffold.loadPlatformPack
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo

private const val FRONTMATTER_OPEN_LENGTH = 4

data class NativeAgentCompositionTarget(
  val contentPath: Path,
  val source: NativeAgentCompositionTargetSource,
)

enum class NativeAgentCompositionTargetSource {
  PlatformManifest,
  SiblingContent,
}

internal fun parseCompositionDirective(rawValue: String?, label: String): NativeAgentCompositionDirective? =
  rawValue?.let { value ->
    require(value.isNotBlank()) {
      "$label: native agent compose directive is required when the compose key is present"
    }
    val kind = NativeAgentCompositionKind.entries.firstOrNull { it.wireValue == value }
      ?: throw IllegalArgumentException(
        "$label: unsupported native agent compose directive '$value'",
      )
    NativeAgentCompositionDirective(kind)
  }

fun resolveNativeAgentCompositionTarget(repoRoot: Path, source: NativeAgentSource): NativeAgentCompositionTarget? {
  if (source.composition == null) {
    return null
  }
  require(source.composition.kind == NativeAgentCompositionKind.GovernedContent) {
    "unsupported native agent compose directive '${source.composition.kind.wireValue}'"
  }
  val sourcePath = requireNotNull(source.path) {
    "native agent composition resolution requires a source path"
  }.toAbsolutePath().normalize()
  val root = repoRoot.toAbsolutePath().normalize()
  val packRoot = platformPackRoot(root, sourcePath)
  return if (packRoot != null) {
    resolvePlatformManifestContentTarget(root, packRoot, sourcePath, source)
  } else {
    resolveSiblingContentTarget(sourcePath, source)
  } ?: throw IllegalArgumentException(
    "${displayPath(root, sourcePath)}: native agent compose directive 'governed-content' " +
      "could not resolve a corresponding content.md for '${source.name}'",
  )
}

internal fun validateNativeAgentCompositionTarget(repoRoot: Path, source: NativeAgentSource): String? {
  if (source.composition == null) {
    return null
  }
  return runCatching {
    resolveNativeAgentCompositionTarget(repoRoot, source)
    null
  }.getOrElse { error -> error.message.orEmpty() }
}

private fun resolvePlatformManifestContentTarget(
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
      )
    }
}

private fun resolveSiblingContentTarget(sourcePath: Path, source: NativeAgentSource): NativeAgentCompositionTarget? =
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

private fun platformPackRoot(repoRoot: Path, sourcePath: Path): Path? {
  val packsRoot = repoRoot.resolve("platform-packs")
  return runCatching { sourcePath.relativeTo(packsRoot) }
    .getOrNull()
    ?.firstOrNull()
    ?.toString()
    ?.let(packsRoot::resolve)
}

private fun declaredContentPaths(pack: PlatformManifest): List<Path> = listOf(pack.declaredFiles.baseline) +
  pack.declaredFiles.areas.values.sortedBy { it.toString() } +
  listOfNotNull(pack.declaredQualityCheckFile)

private fun readContentFrontmatterName(contentPath: Path): String? = Files.readString(contentPath)
  .replace("\r\n", "\n")
  .takeIf { text -> text.startsWith("---\n") }
  ?.let(::frontmatterBody)
  ?.lineSequence()
  ?.firstOrNull { line -> line.startsWith("name:") }
  ?.substringAfter(':')
  ?.trim()
  ?.trim('"', '\'')
  ?.takeIf { it.isNotBlank() }

private fun frontmatterBody(text: String): String? {
  val end = text.indexOf("\n---\n", startIndex = FRONTMATTER_OPEN_LENGTH)
  return end.takeIf { it >= 0 }?.let { text.substring(FRONTMATTER_OPEN_LENGTH, it) }
}

private fun displayPath(root: Path, path: Path): String {
  val resolvedRoot = root.toAbsolutePath().normalize()
  val resolvedPath = path.toAbsolutePath().normalize()
  return runCatching { resolvedPath.relativeTo(resolvedRoot).toString() }
    .getOrDefault(resolvedPath.toString())
}
