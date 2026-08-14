package skillbill.review.plan

import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.PlatformManifest

object ReviewAddonSelectionPolicy {
  fun select(manifest: PlatformManifest, specialistSkillName: String): List<GovernedAddonSelection> {
    val consumer = "code-review/$specialistSkillName"
    val baselineConsumer = manifest.routedSkillName?.let { name -> "code-review/$name" }
    val area = specialistArea(manifest.routedSkillName, specialistSkillName)
    val declared = manifest.addonUsage
      .filter { usage -> usage.skillRelativeDir == consumer }
      .flatMap { usage -> usage.addons }
    val inherited = if (area == null) {
      emptyList()
    } else {
      manifest.addonUsage
        .filter { usage -> usage.skillRelativeDir == baselineConsumer }
        .flatMap { usage -> usage.addons.filter { addon -> area in addon.specialistAreas } }
    }
    val selected = linkedMapOf<String, GovernedAddonSelection>()
    (declared + inherited).forEach { addon -> selected.putIfAbsent(addon.slug, addon) }
    return selected.values.toList()
  }

  private fun specialistArea(baselineSkillName: String?, specialistSkillName: String): String? {
    if (baselineSkillName == null || specialistSkillName == baselineSkillName) {
      return null
    }
    val prefix = "$baselineSkillName-"
    if (!specialistSkillName.startsWith(prefix)) {
      return null
    }
    return specialistSkillName.removePrefix(prefix).takeIf(String::isNotEmpty)
  }
}
