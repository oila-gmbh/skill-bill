package skillbill.install.plan

import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.scaffold.model.PlatformManifest

internal fun selectedPlatformSlugs(
  skills: List<InstallPlanSkill>,
  platformManifests: List<PlatformManifest>,
): Set<String> = skills
  .filter { skill -> skill.kind == InstallPlanSkillKind.PLATFORM_PACK }
  .mapNotNull { skill ->
    platformManifests.firstOrNull { manifest -> skill.sourceDir.startsWith(manifest.packRoot) }?.slug
  }
  .toSet()
