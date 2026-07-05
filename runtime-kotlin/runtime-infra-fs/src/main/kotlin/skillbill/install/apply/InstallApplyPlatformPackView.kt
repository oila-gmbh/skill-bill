package skillbill.install.apply

import skillbill.install.model.InstallAppliedSkill
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.support.createNewSymlinkWithGuidance
import skillbill.install.support.createReplacementSymlinkWithGuidance
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val MANAGED_INSTALL_MARKER = ".skill-bill-install"
private const val PLATFORM_PACKS_DIR = "platform-packs"

internal fun materializeAgentPlatformPackViews(
  plan: InstallPlan,
  platformManifests: List<PlatformManifest>,
  appliedSkills: List<InstallAppliedSkill>,
  failures: MutableList<InstallApplyIssue>,
) {
  if (plan.selectedPlatformSlugs.isEmpty()) {
    cleanupManagedPlatformPackViews(plan, failures)
    return
  }
  val selectedManifests = platformManifests
    .filter { manifest -> manifest.slug in plan.selectedPlatformSlugs }
    .sortedBy(PlatformManifest::slug)
  val stagedPlatformSkills = appliedSkills
    .filter { skill -> skill.kind == InstallPlanSkillKind.PLATFORM_PACK }
    .filter { skill -> skill.staging.status == InstallSkillStagingStatus.STAGED }
    .associateBy { skill -> skill.sourceDir.toAbsolutePath().normalize() }
  val internalPlatformSkillDirs = plan.skills
    .filter { skill -> skill.kind == InstallPlanSkillKind.PLATFORM_PACK && skill.internalFor != null }
    .map { skill -> skill.sourceDir.toAbsolutePath().normalize() }
    .toSet()
  plan.agents.forEach { agentTarget ->
    runCatching {
      val root = agentTarget.path.toAbsolutePath().normalize().resolve(PLATFORM_PACKS_DIR)
      replaceManagedPlatformPackView(root)
      selectedManifests.forEach { manifest ->
        materializeOnePack(root, manifest, stagedPlatformSkills, internalPlatformSkillDirs)
      }
    }.getOrElse { error ->
      failures.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
          message = error.message.orEmpty(),
          agent = agentTarget.agent,
          path = agentTarget.path.resolve(PLATFORM_PACKS_DIR),
          causeClass = error::class.qualifiedName,
        ),
      )
    }
  }
}

internal fun cleanupManagedPlatformPackViews(plan: InstallPlan, failures: MutableList<InstallApplyIssue>) {
  plan.agents.forEach { agentTarget ->
    runCatching {
      val root = agentTarget.path.toAbsolutePath().normalize().resolve(PLATFORM_PACKS_DIR)
      if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.exists(root.resolve(MANAGED_INSTALL_MARKER))) {
        deleteTree(root)
      }
    }.getOrElse { error ->
      failures.add(
        InstallApplyIssue(
          kind = InstallApplyIssueKind.SKILL_LINK_FAILED,
          message = error.message.orEmpty(),
          agent = agentTarget.agent,
          path = agentTarget.path.resolve(PLATFORM_PACKS_DIR),
          causeClass = error::class.qualifiedName,
        ),
      )
    }
  }
}

private fun replaceManagedPlatformPackView(root: Path) {
  if (Files.isSymbolicLink(root)) {
    error("Existing symlink at $root was preserved.")
  }
  if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
    require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && Files.exists(root.resolve(MANAGED_INSTALL_MARKER))) {
      "Existing non-managed platform-packs path at $root was preserved."
    }
    deleteTree(root)
  }
  Files.createDirectories(root)
  Files.writeString(root.resolve(MANAGED_INSTALL_MARKER), "")
}

private fun materializeOnePack(
  agentPacksRoot: Path,
  manifest: PlatformManifest,
  stagedPlatformSkills: Map<Path, InstallAppliedSkill>,
  internalPlatformSkillDirs: Set<Path>,
) {
  val packRoot = manifest.packRoot.toAbsolutePath().normalize()
  val destinationPackRoot = agentPacksRoot.resolve(manifest.slug).normalize()
  require(destinationPackRoot.startsWith(agentPacksRoot)) {
    "Platform pack '${manifest.slug}' escapes agent platform-packs root '$agentPacksRoot'."
  }
  val skillDirs = platformSkillDirs(manifest)
  copyPackNonSkillFiles(packRoot, destinationPackRoot, skillDirs)
  skillDirs.forEach { skillDir ->
    if (skillDir in internalPlatformSkillDirs) {
      return@forEach
    }
    val staged = stagedPlatformSkills[skillDir]?.staging?.stagingDir
      ?: error("Selected platform pack '${manifest.slug}' skill '$skillDir' was not staged.")
    val relative = packRoot.relativize(skillDir).toString()
    val linkPath = destinationPackRoot.resolve(relative).normalize()
    require(linkPath.startsWith(destinationPackRoot)) {
      "Platform pack '${manifest.slug}' skill link '$linkPath' escapes pack root '$destinationPackRoot'."
    }
    linkPath.parent?.let(Files::createDirectories)
    createOrReplaceManagedSkillSymlink(linkPath, staged)
  }
}

private fun platformSkillDirs(manifest: PlatformManifest): Set<Path> = (
  listOfNotNull(manifest.declaredFiles.baseline, manifest.declaredQualityCheckFile) +
    manifest.declaredFiles.areas.values
  )
  .map { contentFile -> contentFile.toAbsolutePath().normalize().parent }
  .toSet()

private fun copyPackNonSkillFiles(packRoot: Path, destinationPackRoot: Path, skillDirs: Set<Path>) {
  Files.walk(packRoot).use { stream ->
    stream.forEach { source ->
      if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
        return@forEach
      }
      val resolvedSource = source.toAbsolutePath().normalize()
      if (skillDirs.any { skillDir -> resolvedSource.startsWith(skillDir) }) {
        return@forEach
      }
      val destination = destinationPackRoot.resolve(packRoot.relativize(resolvedSource).toString()).normalize()
      require(destination.startsWith(destinationPackRoot)) {
        "Platform pack file '$source' escapes destination pack root '$destinationPackRoot'."
      }
      destination.parent?.let(Files::createDirectories)
      Files.copy(
        source,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
        LinkOption.NOFOLLOW_LINKS,
      )
    }
  }
}

private fun createOrReplaceManagedSkillSymlink(linkPath: Path, stagingDir: Path) {
  val target = stagingDir.toAbsolutePath().normalize()
  require(Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
    "Staged platform skill target '$target' is not a directory."
  }
  if (Files.isSymbolicLink(linkPath)) {
    createReplacementSymlinkWithGuidance(linkPath, target)
  } else if (Files.exists(linkPath, LinkOption.NOFOLLOW_LINKS)) {
    error("Existing non-symlink platform skill path at $linkPath was preserved.")
  } else {
    createNewSymlinkWithGuidance(linkPath, target)
  }
}

private fun deleteTree(root: Path) {
  if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  Files.walk(root).use { stream ->
    stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}
