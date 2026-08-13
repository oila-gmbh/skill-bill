package skillbill.nativeagent.rendering

import skillbill.error.MissingContentFileError
import skillbill.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.nativeagent.composition.displayPath
import skillbill.nativeagent.composition.platformPackRoot
import skillbill.scaffold.authoring.normalizeMarkdownLineEndings
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.platformpack.addonUsageFor
import skillbill.scaffold.platformpack.loadPlatformPack
import java.nio.file.Files
import java.nio.file.Path

private const val ENTRYPOINT_SLOT = "entrypoint"

private const val MAX_COMPOSED_AGENT_BYTES = 512 * 1024L

private const val MAX_COMPOSED_ADDON_FILE_BYTES = 128 * 1024L

private const val ACTIVATION_DEFERRAL = "Every add-on below is composed unconditionally. The declared activation " +
  "conditions are diff-time signals over changed files and are not evaluable while rendering, so the runtime " +
  "activation logic remains the decider for which composed add-ons apply to a given change."

private data class ComposedAddonTarget(val slug: String, val slot: String, val path: Path)

internal fun composeGovernedAgentBody(
  repoRoot: Path,
  target: NativeAgentCompositionTarget,
  body: String,
): String {
  val root = repoRoot.toAbsolutePath().normalize()
  val addonTargets = resolveDeclaredAddonTargets(root, target)
  val session = SidecarInliningSession(root, target)
  addonTargets.forEach { addon -> session.claim(addon.path) }
  val rewrittenBody = session.rewrite(body, target.contentPath)
  val addonBlocks = addonTargets.map { addon ->
    addon to session.rewriteComposedAddon(readAddonFile(root, addon), addon.path)
  }
  val composed = buildString {
    append(rewrittenBody.trimEnd())
    append(session.inlinedReferenceBlocks())
    if (addonBlocks.isNotEmpty()) {
      append("\n\n## Composed Add-Ons\n\n")
      append(ACTIVATION_DEFERRAL)
      addonBlocks.forEach { (addon, text) ->
        append("\n\n### Add-On: ${addon.slug} (${addon.path.fileName})\n\n")
        append(text.trimEnd())
      }
    }
  }.trimEnd()
  enforceComposedAgentBudget(root, target, composed)
  return composed
}

private fun readAddonFile(root: Path, addon: ComposedAddonTarget): String {
  val bytes = runCatching { Files.readAllBytes(addon.path) }.getOrElse { failure ->
    throw MissingContentFileError(addonFailureMessage(root, addon, "is unreadable"), failure)
  }
  if (bytes.size > MAX_COMPOSED_ADDON_FILE_BYTES) {
    throw MissingContentFileError(
      addonFailureMessage(
        root,
        addon,
        "is ${bytes.size} bytes, larger than the $MAX_COMPOSED_ADDON_FILE_BYTES byte cap",
      ),
    )
  }
  return normalizeMarkdownLineEndings(String(bytes, Charsets.UTF_8))
}

private fun enforceComposedAgentBudget(root: Path, target: NativeAgentCompositionTarget, composed: String) {
  val bytes = composed.toByteArray(Charsets.UTF_8).size
  if (bytes > MAX_COMPOSED_AGENT_BYTES) {
    val packRoot = platformPackRoot(root, target.contentPath.toAbsolutePath().normalize())
    throw MissingContentFileError(
      "pack '${packRoot?.fileName ?: displayPath(root, target.contentPath)}' skill directory " +
        "'${skillRelativeDir(packRoot, target.contentPath)}': composed native agent is $bytes bytes, " +
        "over the $MAX_COMPOSED_AGENT_BYTES byte budget",
    )
  }
}

private fun resolveDeclaredAddonTargets(
  root: Path,
  target: NativeAgentCompositionTarget,
): List<ComposedAddonTarget> {
  if (target.source != NativeAgentCompositionTargetSource.PlatformManifest) {
    return emptyList()
  }
  val contentPath = target.contentPath.toAbsolutePath().normalize()
  val packRoot = platformPackRoot(root, contentPath) ?: return emptyList()
  val pack = loadPlatformPack(packRoot)
  val selections = pack.addonUsageFor(contentPath)
  if (selections.isEmpty()) {
    return emptyList()
  }
  val skillRelativeDir = skillRelativeDir(packRoot, contentPath)
  val declared = pack.pointers
    .filter { pointer -> pointer.skillRelativeDir == skillRelativeDir }
    .associateBy(PointerSpec::name)
  val resolved = linkedMapOf<Path, ComposedAddonTarget>()
  selections.forEach { selection ->
    val slots = listOf(ENTRYPOINT_SLOT to selection.entrypoint) +
      selection.companionPointers.map { pointer -> pointer to pointer }
    slots.forEach { (slot, pointerName) ->
      val addon = resolveAddonTarget(root, declared, skillRelativeDir, selection.slug, slot, pointerName)
      resolved.putIfAbsent(addon.path, addon)
    }
  }
  return resolved.values.toList()
}

private fun resolveAddonTarget(
  root: Path,
  declared: Map<String, PointerSpec>,
  skillRelativeDir: String,
  slug: String,
  slot: String,
  pointerName: String,
): ComposedAddonTarget {
  val pointer = declared[pointerName]
    ?: throw MissingContentFileError(
      "add-on '$slug' slot '$slot': '$pointerName' is not declared in platform.yaml pointers for " +
        "'$skillRelativeDir'",
    )
  val path = root.resolve(pointer.target).toAbsolutePath().normalize()
  val addon = ComposedAddonTarget(slug = slug, slot = slot, path = path)
  if (!Files.isRegularFile(path)) {
    throw MissingContentFileError(addonFailureMessage(root, addon, "is missing"))
  }
  return addon
}

private fun addonFailureMessage(root: Path, addon: ComposedAddonTarget, problem: String): String =
  "add-on '${addon.slug}' slot '${addon.slot}': declared target $problem at '${addon.path}' " +
    "(repository path '${displayPath(root, addon.path)}')"

private fun skillRelativeDir(packRoot: Path?, contentPath: Path): String = packRoot
  ?.toAbsolutePath()
  ?.normalize()
  ?.relativize(contentPath.toAbsolutePath().normalize().parent)
  ?.toString()
  ?.replace('\\', '/')
  .orEmpty()
