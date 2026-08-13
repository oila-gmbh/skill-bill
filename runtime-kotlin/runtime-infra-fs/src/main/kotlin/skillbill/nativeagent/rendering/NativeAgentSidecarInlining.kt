package skillbill.nativeagent.rendering

import skillbill.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.nativeagent.composition.displayPath
import skillbill.nativeagent.composition.platformPackRoot
import skillbill.scaffold.authoring.normalizeMarkdownLineEndings
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.platformpack.loadPlatformPack
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

private val LOCAL_MARKDOWN_LINK_PATTERN = Regex("""\[([^]\n]+)]\(([^)\s]+\.md(?:#[^)]*)?)\)""")

private val sidecarInliningLog: Logger =
  Logger.getLogger("skillbill.nativeagent.rendering.NativeAgentSidecarInlining")

internal class SidecarInliningSession(repoRoot: Path, target: NativeAgentCompositionTarget) {
  private val root = repoRoot.toAbsolutePath().normalize()
  private val resolver = sidecarResolver(repoRoot, target)
  private val skillRelativeDir = skillRelativeDirLabel(root, target)
  private val inlined = linkedMapOf<Path, String>()
  private val claimed = linkedSetOf<Path>()

  fun claim(path: Path) {
    claimed.add(path)
  }

  fun rewrite(text: String, ownerPath: Path): String =
    rewriteMarkdownLinks(text, ownerPath, resolver, inlined, claimed, onUnresolved = null)

  fun rewriteComposedAddon(text: String, ownerPath: Path): String =
    rewriteMarkdownLinks(text, ownerPath, resolver, inlined, claimed) { rawTarget, owner, cause ->
      sidecarInliningLog.warning(
        "native agent add-on composition degraded: seam=MarkdownSidecarResolver.resolveCatching " +
          "used=plain-label expected=inlined-sidecar owner=${displayPath(root, owner)} " +
          "target=$rawTarget skill_relative_dir=$skillRelativeDir cause=$cause",
      )
    }

  fun inlinedReferenceBlocks(): String = buildString {
    inlined.forEach { (path, text) ->
      append("\n\n")
      append("## Inlined Reference: ${path.fileName}")
      append("\n\n")
      append(text.trimEnd())
    }
  }
}

private fun rewriteMarkdownLinks(
  text: String,
  ownerPath: Path,
  resolver: MarkdownSidecarResolver,
  inlined: LinkedHashMap<Path, String>,
  claimed: LinkedHashSet<Path>,
  onUnresolved: ((rawTarget: String, ownerPath: Path, cause: String) -> Unit)?,
): String = LOCAL_MARKDOWN_LINK_PATTERN.replace(text) { match ->
  val label = match.groupValues[1]
  val rawTarget = match.groupValues[2]
  val sidecarPath = if (onUnresolved != null) {
    val resolved = resolver.resolveCatching(rawTarget, ownerPath)
    resolved.getOrElse { failure ->
      onUnresolved(rawTarget, ownerPath, failure.message ?: failure::class.java.name)
      return@replace label
    }
  } else {
    resolver.resolve(rawTarget, ownerPath)
  }
  if (claimed.add(sidecarPath)) {
    val sidecarText = normalizeMarkdownLineEndings(Files.readString(sidecarPath)).trimEnd()
    inlined[sidecarPath] = ""
    inlined[sidecarPath] = rewriteMarkdownLinks(sidecarText, sidecarPath, resolver, inlined, claimed, onUnresolved)
  }
  label
}

private fun skillRelativeDirLabel(root: Path, target: NativeAgentCompositionTarget): String {
  val contentDir = target.contentPath.toAbsolutePath().normalize().parent
  val base = platformPackRoot(root, target.contentPath.toAbsolutePath().normalize()) ?: root
  return base.relativize(contentDir).toString().replace('\\', '/')
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

  fun resolveCatching(rawTarget: String, ownerPath: Path): Result<Path> =
    runCatching { resolve(rawTarget, ownerPath) }
}
