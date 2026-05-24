package skillbill.nativeagent

import skillbill.scaffold.loadPlatformPack
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.normalizeMarkdownLineEndings
import java.nio.file.Files
import java.nio.file.Path

private val LOCAL_MARKDOWN_LINK_PATTERN = Regex("""\[([^]\n]+)]\(([^)\s]+\.md(?:#[^)]*)?)\)""")

internal fun inlineDeclaredMarkdownSidecars(
  repoRoot: Path,
  target: NativeAgentCompositionTarget,
  body: String,
): String {
  val resolver = sidecarResolver(repoRoot, target)
  val inlined = linkedMapOf<Path, String>()
  val rewrittenBody = rewriteMarkdownLinks(
    text = body,
    ownerPath = target.contentPath,
    resolver = resolver,
    inlined = inlined,
  )
  if (inlined.isEmpty()) {
    return rewrittenBody.trimEnd()
  }
  return buildString {
    append(rewrittenBody.trimEnd())
    inlined.forEach { (path, text) ->
      append("\n\n")
      append("## Inlined Reference: ${path.fileName}")
      append("\n\n")
      append(text.trimEnd())
    }
  }.trimEnd()
}

private fun rewriteMarkdownLinks(
  text: String,
  ownerPath: Path,
  resolver: MarkdownSidecarResolver,
  inlined: LinkedHashMap<Path, String>,
): String = LOCAL_MARKDOWN_LINK_PATTERN.replace(text) { match ->
  val label = match.groupValues[1]
  val rawTarget = match.groupValues[2]
  val sidecarPath = resolver.resolve(rawTarget, ownerPath)
  if (sidecarPath !in inlined) {
    val sidecarText = normalizeMarkdownLineEndings(Files.readString(sidecarPath)).trimEnd()
    inlined[sidecarPath] = ""
    inlined[sidecarPath] = rewriteMarkdownLinks(sidecarText, sidecarPath, resolver, inlined)
  }
  label
}

private fun sidecarResolver(repoRoot: Path, target: NativeAgentCompositionTarget): MarkdownSidecarResolver =
  when (target.source) {
    NativeAgentCompositionTargetSource.PlatformManifest -> platformPointerSidecarResolver(repoRoot, target.contentPath)
    NativeAgentCompositionTargetSource.SiblingContent -> siblingMarkdownSidecarResolver(repoRoot, target.contentPath)
  }

private fun platformPointerSidecarResolver(repoRoot: Path, contentPath: Path): MarkdownSidecarResolver {
  val root = repoRoot.toAbsolutePath().normalize()
  val packRoot = requireNotNull(platformPackRoot(root, contentPath.toAbsolutePath().normalize())) {
    "${displayPath(root, contentPath)}: platform-pack native agent composition requires a platform.yaml manifest"
  }
  val pack = loadPlatformPack(packRoot)
  val skillRelativeDir = packRoot.relativize(contentPath.parent).toString().replace('\\', '/')
  val declared = pack.pointers
    .filter { pointer -> pointer.skillRelativeDir == skillRelativeDir }
    .associateBy(PointerSpec::name)
  return MarkdownSidecarResolver(root) { rawTarget, ownerPath ->
    val linkPath = linkPathWithoutFragment(rawTarget)
    if ('/' !in linkPath && '\\' !in linkPath) {
      val siblingPath = contentPath.parent.resolve(linkPath).toAbsolutePath().normalize()
      if (Files.isRegularFile(siblingPath)) {
        return@MarkdownSidecarResolver siblingPath
      }
    }
    val linkName = linkPath.substringAfterLast('/')
    val pointer = declared[linkName]
      ?: throw IllegalArgumentException(
        "${displayPath(root, ownerPath)}: local markdown link '$rawTarget' is not declared in " +
          "platform.yaml pointers for '$skillRelativeDir'",
      )
    val sidecarPath = root.resolve(pointer.target).toAbsolutePath().normalize()
    require(Files.isRegularFile(sidecarPath)) {
      "${displayPath(root, ownerPath)}: declared markdown sidecar '$rawTarget' is missing at " +
        "'${displayPath(root, sidecarPath)}'"
    }
    sidecarPath
  }
}

private fun siblingMarkdownSidecarResolver(repoRoot: Path, contentPath: Path): MarkdownSidecarResolver {
  val root = repoRoot.toAbsolutePath().normalize()
  val contentDir = contentPath.parent.toAbsolutePath().normalize()
  return MarkdownSidecarResolver(root) { rawTarget, ownerPath ->
    val linkPath = linkPathWithoutFragment(rawTarget)
    require('/' !in linkPath && '\\' !in linkPath && ".." !in linkPath) {
      "${displayPath(root, ownerPath)}: local markdown link '$rawTarget' must resolve to a sibling file"
    }
    val sidecarPath = contentDir.resolve(linkPath).toAbsolutePath().normalize()
    require(Files.isRegularFile(sidecarPath)) {
      "${displayPath(root, ownerPath)}: local markdown link '$rawTarget' is unresolved"
    }
    sidecarPath
  }
}

private fun linkPathWithoutFragment(rawTarget: String): String = rawTarget.substringBefore('#')

private class MarkdownSidecarResolver(
  private val repoRoot: Path,
  private val resolvePath: (rawTarget: String, ownerPath: Path) -> Path,
) {
  fun resolve(rawTarget: String, ownerPath: Path): Path = resolvePath(rawTarget, ownerPath)
    .also { path ->
      require(Files.isRegularFile(path)) {
        "${displayPath(repoRoot, ownerPath)}: local markdown link '$rawTarget' is unresolved"
      }
    }
}
