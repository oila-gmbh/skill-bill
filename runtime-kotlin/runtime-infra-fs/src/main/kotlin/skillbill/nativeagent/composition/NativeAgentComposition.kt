package skillbill.nativeagent.composition

import skillbill.nativeagent.platformpack.NativeAgentPlatformPack
import skillbill.nativeagent.platformpack.NativeAgentPlatformPackLoader
import skillbill.nativeagent.rendering.composeGovernedAgentBody
import skillbill.nativeagent.rendering.enforceAddonProjectionParity
import skillbill.nativeagent.rendering.enforceComposedAgentBudget
import java.nio.file.Path

data class NativeAgentCompositionTarget(
  val contentPath: Path,
  val source: NativeAgentCompositionTargetSource,
  val manifest: NativeAgentPlatformPack? = null,
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

fun resolveNativeAgentCompositionTarget(
  repoRoot: Path,
  source: NativeAgentSource,
  packLoader: NativeAgentPlatformPackLoader,
): NativeAgentCompositionTarget? {
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
    resolvePlatformManifestContentTarget(root, packRoot, sourcePath, source, packLoader)
  } else {
    resolveSiblingContentTarget(sourcePath, source)
  } ?: throw IllegalArgumentException(
    "${displayPath(root, sourcePath)}: native agent compose directive 'governed-content' " +
      "could not resolve a corresponding content.md for '${source.name}'",
  )
}

internal fun nativeAgentCompositionRepoRoot(platformPacksRoot: Path, skillsRoot: Path?): Path {
  val platformRoot = platformPacksRoot.toAbsolutePath().normalize()
  val skillRoot = skillsRoot?.toAbsolutePath()?.normalize()
  return if (skillRoot != null && platformRoot.parent == skillRoot.parent) {
    platformRoot.parent
  } else if (platformRoot.fileName?.toString() == "platform-packs") {
    platformRoot.parent ?: platformRoot
  } else {
    platformRoot
  }
}

fun composeNativeAgentSource(
  repoRoot: Path,
  source: NativeAgentSource,
  reviewContextBudgetBytes: Long,
  renderGovernedBody: (Path, String) -> String,
  packLoader: NativeAgentPlatformPackLoader,
): NativeAgentSource {
  val target = resolveNativeAgentCompositionTarget(repoRoot, source, packLoader) ?: return source
  val governedBody = renderGovernedBody(target.contentPath, source.name).trimEnd()
  val localFraming = source.body.trim()
  val composedBody = buildString {
    if (localFraming.isNotBlank()) {
      append(localFraming)
      append("\n\n")
    }
    append(governedBody)
  }.trimEnd()
  val governed = composeGovernedAgentBody(repoRoot, target, composedBody)
  val composed = source.copy(
    body = governed.body,
    composition = null,
    composedAddonSlugs = governed.composedAddonSlugs,
  )
  target.manifest?.let { pack ->
    enforceAddonProjectionParity(pack, source.name, composed.composedAddonSlugs)
  }
  enforceComposedAgentBudget(
    repoRoot.toAbsolutePath().normalize(),
    target,
    renderNativeAgentSource(composed),
    reviewContextBudgetBytes,
  )
  return composed
}

fun renderComposedNativeAgentSource(
  repoRoot: Path,
  source: NativeAgentSource,
  reviewContextBudgetBytes: Long,
  renderGovernedBody: (Path, String) -> String,
  packLoader: NativeAgentPlatformPackLoader,
): String = renderNativeAgentSource(
  composeNativeAgentSource(
    repoRoot,
    source,
    reviewContextBudgetBytes,
    renderGovernedBody,
    packLoader,
  ),
)
