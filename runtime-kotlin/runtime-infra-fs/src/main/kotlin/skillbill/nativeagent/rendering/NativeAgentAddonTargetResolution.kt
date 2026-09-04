package skillbill.nativeagent.rendering

import skillbill.error.MissingContentFileError
import skillbill.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.nativeagent.composition.displayPath
import skillbill.nativeagent.composition.platformPackRoot
import skillbill.nativeagent.platformpack.NativeAgentAddonSelectionPolicy
import skillbill.nativeagent.platformpack.NativeAgentGovernedAddonActivation
import skillbill.nativeagent.platformpack.NativeAgentGovernedAddonSelection
import skillbill.nativeagent.platformpack.NativeAgentPlatformPack
import skillbill.nativeagent.platformpack.NativeAgentPointerSpec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

internal const val NATIVE_AGENT_ADDON_ENTRYPOINT_SLOT = "entrypoint"

internal data class ComposedAddonTarget(
  val packSlug: String,
  val slug: String,
  val slot: String,
  val path: Path,
  val activation: NativeAgentGovernedAddonActivation?,
)

internal data class ResolvedAddonComposition(
  val targets: List<ComposedAddonTarget>,
  val composedAddonSlugs: List<String>,
)

internal data class GovernedAgentComposition(
  val body: String,
  val composedAddonSlugs: List<String>,
)

private data class AddonPointerLookup(
  val pack: NativeAgentPlatformPack,
  val root: Path,
  val declared: Map<String, NativeAgentPointerSpec>,
  val skillRelativeDir: String,
)

internal fun resolveDeclaredAddonTargets(root: Path, target: NativeAgentCompositionTarget): ResolvedAddonComposition {
  if (target.source != NativeAgentCompositionTargetSource.PlatformManifest) {
    return ResolvedAddonComposition(emptyList(), emptyList())
  }
  val contentPath = target.contentPath.toAbsolutePath().normalize()
  val packRoot = platformPackRoot(root, contentPath) ?: return ResolvedAddonComposition(emptyList(), emptyList())
  val pack = requireNotNull(target.manifest) {
    "${displayPath(root, contentPath)}: platform-pack native agent composition requires a parsed platform.yaml manifest"
  }
  val skillName = contentPath.parent.name
  val selections = NativeAgentAddonSelectionPolicy.select(pack, skillName)
  if (selections.isEmpty()) {
    return ResolvedAddonComposition(emptyList(), emptyList())
  }
  val skillRelativeDir = nativeAgentSkillRelativeDir(packRoot, contentPath)
  val lookup = AddonPointerLookup(
    pack = pack,
    root = root,
    declared = pack.pointers
      .filter { pointer -> pointer.skillRelativeDir == skillRelativeDir }
      .associateBy(NativeAgentPointerSpec::name),
    skillRelativeDir = skillRelativeDir,
  )
  val resolved = linkedMapOf<Path, ComposedAddonTarget>()
  selections.forEach { selection ->
    selectionSlots(selection).forEach { (slot, pointerName) ->
      val addon = resolveAddonTarget(lookup, selection, slot, pointerName)
      resolved.putIfAbsent(addon.path, addon)
    }
  }
  return ResolvedAddonComposition(
    resolved.values.toList(),
    enforceResolvedAddonProjectionParity(lookup, selections, resolved),
  )
}

internal fun readAddonFile(root: Path, addon: ComposedAddonTarget): String {
  val bytes = runCatching { Files.readAllBytes(addon.path) }.getOrElse { failure ->
    throw MissingContentFileError(addonFailureMessage(root, addon, "is unreadable"), failure)
  }
  return normalizeMarkdownLineEndings(String(bytes, Charsets.UTF_8))
}

internal fun addonFailureMessage(root: Path, addon: ComposedAddonTarget, problem: String): String =
  "pack '${addon.packSlug}' add-on '${addon.slug}' slot '${addon.slot}': declared target $problem at '${addon.path}' " +
    "(repository path '${displayPath(root, addon.path)}')"

private fun selectionSlots(selection: NativeAgentGovernedAddonSelection): List<Pair<String, String>> =
  listOf(NATIVE_AGENT_ADDON_ENTRYPOINT_SLOT to selection.entrypoint) +
    selection.companionPointers.map { pointer -> pointer to pointer }

private fun resolveAddonTarget(
  lookup: AddonPointerLookup,
  selection: NativeAgentGovernedAddonSelection,
  slot: String,
  pointerName: String,
): ComposedAddonTarget {
  val pointer = lookup.declared[pointerName]
    ?: throw MissingContentFileError(
      "pack '${lookup.pack.slug}' add-on '${selection.slug}' slot '$slot': '$pointerName' is not declared in " +
        "platform.yaml pointers for '${lookup.skillRelativeDir}' at " +
        "'${lookup.pack.packRoot.resolve("platform.yaml").toAbsolutePath().normalize()}'",
    )
  val path = lookup.root.resolve(pointer.target).toAbsolutePath().normalize()
  val addon = ComposedAddonTarget(
    packSlug = lookup.pack.slug,
    slug = selection.slug,
    slot = slot,
    path = path,
    activation = selection.activation,
  )
  if (!Files.isRegularFile(path)) {
    throw MissingContentFileError(addonFailureMessage(lookup.root, addon, "is missing"))
  }
  return addon
}

private fun enforceResolvedAddonProjectionParity(
  lookup: AddonPointerLookup,
  selections: List<NativeAgentGovernedAddonSelection>,
  composedByPath: Map<Path, ComposedAddonTarget>,
): List<String> {
  val composedPaths = composedByPath.keys
  val composedSlugs = selections.map { selection ->
    val missing = selectionSlots(selection).firstNotNullOfOrNull { (slot, pointerName) ->
      val addon = resolveAddonTarget(lookup, selection, slot, pointerName)
      addon.takeUnless { it.path in composedPaths }
    }
    if (missing != null) {
      throw MissingContentFileError(addonFailureMessage(lookup.root, missing, "did not compose"))
    }
    selection.slug
  }
  val extra = composedByPath.values.firstOrNull { addon -> addon.slug !in composedSlugs }
  if (extra != null) {
    throw MissingContentFileError(addonFailureMessage(lookup.root, extra, "is unprojected"))
  }
  return composedSlugs
}
