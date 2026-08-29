package skillbill.infrastructure.fs

import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Path


internal typealias DirName = Pair<String, String>
internal typealias DirTarget = Pair<String, String>
internal typealias DirSlug = Pair<String, String>

internal data class ExternalName(val platform: String, val dir: String, val name: String)
internal data class ExternalTarget(val platform: String, val dir: String, val basename: String)
internal data class ExternalSlug(val platform: String, val dir: String, val slug: String)

internal fun PointerSpec.dirName(): DirName = skillRelativeDir to name

internal fun basename(target: String): String = target.substringAfterLast('/').substringAfterLast('\\')

internal sealed interface PointerCollisionOutcome {
  data object AlreadyPresent : PointerCollisionOutcome
  data class NameCollision(val existingTarget: String) : PointerCollisionOutcome
  data class TargetCollision(val existingName: String, val origin: String) : PointerCollisionOutcome
  data object New : PointerCollisionOutcome
}

internal sealed interface AddonCollisionOutcome {
  data object AlreadyPresent : AddonCollisionOutcome
  data class Collision(val existing: GovernedAddonSelection) : AddonCollisionOutcome
  data object New : AddonCollisionOutcome
}

internal class CollisionIndex {
  private val installedByName = mutableMapOf<DirName, String>()
  private val installedByTarget = mutableMapOf<DirTarget, String>()
  private val externalByName = mutableMapOf<ExternalName, String>()
  private val externalByTarget = mutableMapOf<ExternalTarget, String>()
  private val installedAddons = mutableMapOf<DirSlug, GovernedAddonSelection>()
  private val externalAddons = mutableMapOf<ExternalSlug, GovernedAddonSelection>()

  fun mergeInstalled(pointers: List<PointerSpec>, addonUsage: List<GovernedAddonUsage> = emptyList()) {
    installedByName.clear()
    installedByTarget.clear()
    installedAddons.clear()
    pointers.forEach { pointer ->
      installedByName[pointer.skillRelativeDir to pointer.name] = pointer.target
      installedByTarget[pointer.skillRelativeDir to basename(pointer.target)] = pointer.name
    }
    addonUsage.forEach { usage ->
      usage.addons.forEach { selection ->
        installedAddons[usage.skillRelativeDir to selection.slug] = selection
      }
    }
  }

  fun recordExternalPointer(platform: String, nameKey: DirName, targetKey: DirTarget, pointer: PointerSpec) {
    externalByName[ExternalName(platform, nameKey.first, nameKey.second)] = pointer.target
    externalByTarget[ExternalTarget(platform, targetKey.first, targetKey.second)] = pointer.name
  }

  fun classifyPointer(
    platform: String,
    pointer: PointerSpec,
    nameKey: DirName,
    targetKey: DirTarget,
  ): PointerCollisionOutcome {
    val nameOwner = installedByName[nameKey] ?: externalByName[ExternalName(platform, nameKey.first, nameKey.second)]
    if (nameOwner != null) {
      return if (nameOwner == pointer.target) {
        PointerCollisionOutcome.AlreadyPresent
      } else {
        PointerCollisionOutcome.NameCollision(nameOwner)
      }
    }
    val targetOwner = installedByTarget[targetKey]
    val externalTargetOwner = externalByTarget[ExternalTarget(platform, targetKey.first, targetKey.second)]
    return when {
      targetOwner != null -> PointerCollisionOutcome.TargetCollision(targetOwner, "pack-owned")
      externalTargetOwner != null -> PointerCollisionOutcome.TargetCollision(externalTargetOwner, "external")
      else -> PointerCollisionOutcome.New
    }
  }

  fun recordExternalAddon(platform: String, dir: String, selection: GovernedAddonSelection) {
    externalAddons[ExternalSlug(platform, dir, selection.slug)] = selection
  }

  fun classifyAddon(platform: String, dir: String, selection: GovernedAddonSelection): AddonCollisionOutcome {
    val installed = installedAddons[dir to selection.slug]
    if (installed != null) {
      return resolveAddonOutcome(installed, selection)
    }
    val external = externalAddons[ExternalSlug(platform, dir, selection.slug)]
    if (external != null) {
      return resolveAddonOutcome(external, selection)
    }
    return AddonCollisionOutcome.New
  }

  private fun resolveAddonOutcome(
    existing: GovernedAddonSelection,
    incoming: GovernedAddonSelection,
  ): AddonCollisionOutcome =
    if (existing == incoming) AddonCollisionOutcome.AlreadyPresent else AddonCollisionOutcome.Collision(existing)

  companion object {
    fun empty(): CollisionIndex = CollisionIndex()
  }
}

internal data class SourcePlan(
  val platform: String,
  val sourcePath: Path,
  val installedManifestPath: Path,
  val packRoot: Path,
  val pointersToAppend: Map<String, List<PointerSpec>>,
  val addonsToAppend: Map<String, List<GovernedAddonSelection>>,
  val copiedFiles: Map<String, Path>,
)
