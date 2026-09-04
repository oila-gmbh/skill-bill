package skillbill.install.scaffold

import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTransaction
import skillbill.install.plan.InstallContext
import skillbill.install.plan.detectAgents
import skillbill.install.plan.installSkill
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.platformpack.discoverPlatformPackManifests
import skillbill.scaffold.policy.scaffold.SKILL_KIND_ADD_ON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_AGENT_ADDON
import skillbill.scaffold.policy.scaffold.SKILL_KIND_PLATFORM_PACK
import skillbill.scaffold.runtime.ADD_ON_INSTALL_NOTE
import skillbill.scaffold.runtime.PLATFORM_PACK_INSTALL_NOTE
import skillbill.scaffold.runtime.ScaffoldPlan
import skillbill.scaffold.runtime.ScaffoldTransaction
import skillbill.scaffold.runtime.noAgentsNote
import java.nio.file.Files
import java.nio.file.Path

internal fun performScaffoldInstall(
  txn: ScaffoldTransaction,
  plan: ScaffoldPlan,
  repoRoot: Path,
): Pair<List<Path>, List<String>> {
  val agents = detectAgents()
  val installTx = InstallTransaction()
  val internalPlatformSkills = internalPlatformInstallSkills(plan)
  val installPaths = when (plan.kind) {
    SKILL_KIND_ADD_ON -> emptyList()
    SKILL_KIND_AGENT_ADDON -> plan.agentAddonConsumers.map { consumer -> repoRoot.resolve("skills").resolve(consumer) }
    SKILL_KIND_PLATFORM_PACK -> platformPackInstallPaths(plan, repoRoot, internalPlatformSkills)
    else -> listOf(plan.skillPath)
  }
  val packsRoot = repoRoot.resolve("platform-packs")
  val manifests = if (Files.isDirectory(packsRoot)) discoverPlatformPackManifests(packsRoot) else emptyList()
  val context = InstallContext(
    repoRoot = repoRoot,
    manifests = manifests,
    selectedPackSkills = internalPlatformSkills,
  )
  val targets =
    installPaths.flatMap { installPath ->
      installSkill(installPath, agents, transaction = installTx, context = context)
    }
  txn.installTargets += targets
  val notes = when {
    plan.kind == SKILL_KIND_ADD_ON -> listOf(ADD_ON_INSTALL_NOTE)
    plan.kind == SKILL_KIND_AGENT_ADDON -> listOf("Agent add-on consumers rendered and installed atomically.")
    agents.isEmpty() -> listOf(noAgentsNote())
    plan.kind == SKILL_KIND_PLATFORM_PACK -> listOf(PLATFORM_PACK_INSTALL_NOTE)
    else -> emptyList()
  }
  return targets to notes
}

internal fun internalPlatformInstallSkills(plan: ScaffoldPlan): List<InstallPlanSkill> {
  if (plan.kind != SKILL_KIND_PLATFORM_PACK) {
    return emptyList()
  }
  return plan.installPaths.mapNotNull { installPath ->
    val internalFor = parseInternalForFrontmatter(installPath.resolve("content.md")) ?: return@mapNotNull null
    InstallPlanSkill(
      name = installPath.fileName.toString(),
      sourceDir = installPath.toAbsolutePath().normalize(),
      kind = InstallPlanSkillKind.PLATFORM_PACK,
      platformSlug = plan.platform,
      internalFor = internalFor,
    )
  }
}

internal fun platformPackInstallPaths(
  plan: ScaffoldPlan,
  repoRoot: Path,
  internalPlatformSkills: List<InstallPlanSkill>,
): List<Path> {
  val internalSkillDirs = internalPlatformSkills.map { skill -> skill.sourceDir }.toSet()
  val listedPaths = plan.installPaths.filterNot { installPath ->
    installPath.toAbsolutePath().normalize() in internalSkillDirs
  }
  val parentPaths = internalPlatformSkills
    .mapNotNull(InstallPlanSkill::internalFor)
    .distinct()
    .map { parent -> repoRoot.resolve("skills").resolve(parent) }
  return (listedPaths + parentPaths).distinctBy { path -> path.toAbsolutePath().normalize() }
}
