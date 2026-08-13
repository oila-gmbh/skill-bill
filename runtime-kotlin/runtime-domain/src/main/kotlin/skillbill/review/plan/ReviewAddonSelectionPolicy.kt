package skillbill.review.plan

import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.PlatformManifest

object ReviewAddonSelectionPolicy {
  fun select(manifest: PlatformManifest, specialistSkillName: String): List<GovernedAddonSelection> {
    val consumer = "code-review/$specialistSkillName"
    val baselineConsumer = manifest.routedSkillName?.let { name -> "code-review/$name" }
    val area = specialistArea(manifest.routedSkillName, specialistSkillName)
    val selected = linkedMapOf<String, GovernedAddonSelection>()
    manifest.addonUsage.forEach { usage ->
      val addons = when (usage.skillRelativeDir) {
        consumer -> usage.addons
        baselineConsumer -> area?.let { declaredArea ->
          usage.addons.filter { declaredArea in it.specialistAreas }
        }.orEmpty()
        else -> emptyList()
      }
      addons.forEach { addon -> selected.putIfAbsent(addon.slug, addon) }
    }
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
