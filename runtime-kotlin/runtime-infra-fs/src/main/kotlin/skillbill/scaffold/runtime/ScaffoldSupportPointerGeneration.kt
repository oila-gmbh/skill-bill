package skillbill.scaffold.runtime

import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.platformpack.FEATURE_TASK_ADDON_CONSUMER
import skillbill.scaffold.platformpack.SKILL_CLASSES_DIR
import skillbill.scaffold.platformpack.discoverSkillClasses
import skillbill.scaffold.platformpack.resolveSkillClass
import java.nio.file.Files
import java.nio.file.Path

internal fun featureAddonPointerSpecsFor(
  skillName: String,
  selectedPlatformManifests: List<PlatformManifest>,
): List<PointerSpec> {
  if (skillName != "bill-feature") {
    return emptyList()
  }
  return selectedPlatformManifests.flatMap(::featureAddonPointersForManifest).distinctBy { spec -> spec.name }
}

private fun featureAddonPointersForManifest(manifest: PlatformManifest): List<PointerSpec> {
  val pointersByName = manifest.pointers
    .filter { spec -> spec.skillRelativeDir == FEATURE_TASK_ADDON_CONSUMER }
    .associateBy { it.name }
  return manifest.featureAddonUsage
    .filter { usage -> usage.consumer == FEATURE_TASK_ADDON_CONSUMER }
    .flatMap { usage -> usage.addons.flatMap { addon -> listOf(addon.entrypoint) + addon.companionPointers } }
    .mapNotNull(pointersByName::get)
}

internal fun requiredSupportingFilesForSkill(
  skillName: String,
  repoRoot: Path,
  selectedPlatformManifests: List<PlatformManifest> = emptyList(),
): List<String> {
  if (!Files.isDirectory(repoRoot.resolve(SKILL_CLASSES_DIR))) return emptyList()
  val skillClass = resolveSkillClass(skillName, discoverSkillClasses(repoRoot))
  val classPointers = skillClass?.pointers?.map { "$it.md" }.orEmpty()
  return classPointers + featureAddonPointerSpecsFor(skillName, selectedPlatformManifests).map { it.name }
}
