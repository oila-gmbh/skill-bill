package skillbill.nativeagent.rendering

import skillbill.error.ComposedNativeAgentBudgetExceededError
import skillbill.error.MissingContentFileError
import skillbill.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.nativeagent.composition.displayPath
import skillbill.nativeagent.composition.platformPackRoot
import skillbill.review.plan.ReviewAddonSelectionPolicy
import skillbill.scaffold.authoring.normalizeMarkdownLineEndings
import skillbill.scaffold.model.GovernedAddonActivation
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.Path

private const val ENTRYPOINT_SLOT = "entrypoint"

private data class ComposedAddonTarget(
  val packSlug: String,
  val slug: String,
  val slot: String,
  val path: Path,
  val activation: GovernedAddonActivation?,
)

internal data class GovernedAgentComposition(
  val body: String,
  val composedAddonSlugs: List<String>,
)

internal fun composeGovernedAgentBody(
  repoRoot: Path,
  target: NativeAgentCompositionTarget,
  body: String,
): GovernedAgentComposition {
  val root = repoRoot.toAbsolutePath().normalize()
  val addonTargets = resolveDeclaredAddonTargets(root, target)
  val session = SidecarInliningSession(root, target)
  addonTargets.forEach { addon -> session.claim(addon.path) }
  val rewrittenBody = session.rewrite(body, target.contentPath)
  claimExcludedAddonPointerTargets(root, target, session)
  val addonBlocks = addonTargets.map { addon ->
    addon to session.rewrite(readAddonFile(root, addon), addon.path)
  }
  val composedBody = buildString {
    append(rewrittenBody.trimEnd())
    append(session.inlinedReferenceBlocks())
    if (addonBlocks.isNotEmpty()) {
      append("\n\n## Composed Add-Ons\n\n")
      addonBlocks.forEachIndexed { index, (addon, text) ->
        if (index > 0) {
          append("\n\n")
        }
        append("### Add-On: ${addon.slug} (${addon.path.fileName})\n\n")
        activationScope(addon.activation)?.let { scope ->
          append(scope)
          append("\n\n")
        }
        append(text.trimEnd())
      }
    }
  }.trimEnd()
  return GovernedAgentComposition(
    body = composedBody,
    composedAddonSlugs = addonTargets.map { it.slug }.distinct(),
  )
}

private fun activationScope(activation: GovernedAddonActivation?): String? {
  if (activation == null) {
    return null
  }
  val signals = buildList {
    signal("changed paths matching any of", activation.anyPath)?.let(::add)
    signal("changed content matching any of", activation.anyContent)?.let(::add)
    signal("changed content matching all of", activation.allContent)?.let(::add)
    activation.anyOfAllContent
      .mapNotNull { group -> signal("changed content matching all of", group) }
      .takeIf { it.isNotEmpty() }
      ?.let { groups -> add("any one of these content groups: ${groups.joinToString("; ")}") }
    signal("never when changed paths match", activation.excludePath)?.let(::add)
    signal("never when changed content matches", activation.excludeContent)?.let(::add)
  }
  if (signals.isEmpty()) {
    return null
  }
  return "Declared scope: apply this rubric only to changes with ${signals.joinToString(", and ")}. " +
    "When the change under review does not match, skip it and report nothing from it."
}

private fun signal(prefix: String, values: List<String>): String? = values
  .takeIf { it.isNotEmpty() }
  ?.joinToString(", ") { value -> "`$value`" }
  ?.let { rendered -> "$prefix $rendered" }

private fun readAddonFile(root: Path, addon: ComposedAddonTarget): String {
  val bytes = runCatching { Files.readAllBytes(addon.path) }.getOrElse { failure ->
    throw MissingContentFileError(addonFailureMessage(root, addon, "is unreadable"), failure)
  }
  return normalizeMarkdownLineEndings(String(bytes, Charsets.UTF_8))
}

internal fun enforceComposedAgentBudget(
  root: Path,
  target: NativeAgentCompositionTarget,
  rendered: String,
  maxBytes: Long,
) {
  val bytes = rendered.toByteArray(Charsets.UTF_8).size
  if (bytes > maxBytes) {
    val packRoot = platformPackRoot(root, target.contentPath.toAbsolutePath().normalize())
    throw ComposedNativeAgentBudgetExceededError(
      "pack '${packRoot?.fileName ?: displayPath(root, target.contentPath)}' skill directory " +
        "'${skillRelativeDir(packRoot, target.contentPath)}': rendered native agent is $bytes bytes, " +
        "over the $maxBytes byte review context launch budget",
    )
  }
}

private fun claimExcludedAddonPointerTargets(
  root: Path,
  target: NativeAgentCompositionTarget,
  session: SidecarInliningSession,
) {
  if (target.source != NativeAgentCompositionTargetSource.PlatformManifest) {
    return
  }
  val pack = target.manifest ?: return
  val contentPath = target.contentPath.toAbsolutePath().normalize()
  val packRoot = platformPackRoot(root, contentPath) ?: return
  val skillRelativeDir = skillRelativeDir(packRoot, contentPath)
  val addonPointerNames = pack.addonUsage
    .flatMap { usage ->
      usage.addons.flatMap { addon -> listOf(addon.entrypoint) + addon.companionPointers }
    }
    .toSet()
  pack.pointers
    .filter { pointer -> pointer.skillRelativeDir == skillRelativeDir && pointer.name in addonPointerNames }
    .forEach { pointer ->
      session.claim(root.resolve(pointer.target).toAbsolutePath().normalize())
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
  val pack = requireNotNull(target.manifest) {
    "${displayPath(root, contentPath)}: platform-pack native agent composition requires a parsed platform.yaml manifest"
  }
  val skillName = contentPath.parent.name
  val selections = ReviewAddonSelectionPolicy.select(pack, skillName)
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
      val addon = resolveAddonTarget(
        pack.slug,
        root,
        declared,
        skillRelativeDir,
        selection.slug,
        slot,
        pointerName,
        selection.activation,
      )
      resolved.putIfAbsent(addon.path, addon)
    }
  }
  enforceAddonProjectionParity(root, pack, skillRelativeDir, declared, selections, resolved.values.toList())
  return resolved.values.toList()
}

@Suppress("LongParameterList")
private fun resolveAddonTarget(
  packSlug: String,
  root: Path,
  declared: Map<String, PointerSpec>,
  skillRelativeDir: String,
  slug: String,
  slot: String,
  pointerName: String,
  activation: GovernedAddonActivation?,
): ComposedAddonTarget {
  val pointer = declared[pointerName]
    ?: throw MissingContentFileError(
      "pack '$packSlug' add-on '$slug' slot '$slot': '$pointerName' is not declared in platform.yaml pointers for " +
        "'$skillRelativeDir'",
    )
  val path = root.resolve(pointer.target).toAbsolutePath().normalize()
  val addon = ComposedAddonTarget(
    packSlug = packSlug,
    slug = slug,
    slot = slot,
    path = path,
    activation = activation,
  )
  if (!Files.isRegularFile(path)) {
    throw MissingContentFileError(addonFailureMessage(root, addon, "is missing"))
  }
  return addon
}

internal fun enforceAddonProjectionParity(
  pack: PlatformManifest,
  specialistSkillName: String,
  composedAddonSlugs: List<String>,
) {
  val selections = ReviewAddonSelectionPolicy.select(pack, specialistSkillName)
  val projected = selections.map { it.slug }
  if (projected == composedAddonSlugs) {
    return
  }
  val missing = selections.firstOrNull { selection -> selection.slug !in composedAddonSlugs }
  val extra = composedAddonSlugs.firstOrNull { slug -> slug !in projected }
  val slug = missing?.slug ?: extra.orEmpty()
  val pointerName = missing?.entrypoint ?: extra.orEmpty()
  val consumer = "code-review/$specialistSkillName"
  val pointer = pack.pointers.firstOrNull { spec ->
    spec.skillRelativeDir == consumer && spec.name == pointerName
  }
  val repoRoot = pack.packRoot.parent?.takeIf { parent -> parent.fileName.toString() == "platform-packs" }?.parent
  val path = pointer?.let { spec -> repoRoot?.resolve(spec.target)?.toAbsolutePath()?.normalize() }
  throw MissingContentFileError(
    "pack '${pack.slug}' add-on '$slug' slot '$ENTRYPOINT_SLOT': declared target did not compose at " +
      "'${path ?: pointerName}'",
  )
}

@Suppress("LongParameterList")
private fun enforceAddonProjectionParity(
  root: Path,
  pack: PlatformManifest,
  skillRelativeDir: String,
  declared: Map<String, PointerSpec>,
  selections: List<GovernedAddonSelection>,
  composed: List<ComposedAddonTarget>,
) {
  val composedSlugs = composed.map { it.slug }.distinct()
  val projectedSlugs = selections.map { it.slug }
  if (composedSlugs == projectedSlugs) {
    return
  }
  val missing = selections.firstOrNull { selection -> selection.slug !in composedSlugs }
  if (missing != null) {
    val addon = resolveAddonTarget(
      pack.slug,
      root,
      declared,
      skillRelativeDir,
      missing.slug,
      ENTRYPOINT_SLOT,
      missing.entrypoint,
      missing.activation,
    )
    throw MissingContentFileError(addonFailureMessage(root, addon, "did not compose"))
  }
  val extra = composed.first { addon -> addon.slug !in projectedSlugs }
  throw MissingContentFileError(addonFailureMessage(root, extra, "is unprojected"))
}

private fun addonFailureMessage(root: Path, addon: ComposedAddonTarget, problem: String): String =
  "pack '${addon.packSlug}' add-on '${addon.slug}' slot '${addon.slot}': declared target $problem at '${addon.path}' " +
    "(repository path '${displayPath(root, addon.path)}')"

private fun skillRelativeDir(packRoot: Path?, contentPath: Path): String = packRoot
  ?.toAbsolutePath()
  ?.normalize()
  ?.relativize(contentPath.toAbsolutePath().normalize().parent)
  ?.toString()
  ?.replace('\\', '/')
  .orEmpty()
