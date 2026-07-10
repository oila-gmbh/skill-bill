@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package skillbill.install.staging

import skillbill.error.InternalSkillSidecarCollisionError
import skillbill.install.model.InstallPlanSkill
import skillbill.scaffold.authoring.discoverTargets
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.authoring.renderWrapper
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * SKILL-102 (PD2/PD6): one internal child of a parent skill, carrying the rendered governed
 * wrapper that lands at `<skill-name>.md`. The wrapper is rendered once at discovery time and
 * shared by hash computation and sidecar writing, so a staging operation runs a single authoring
 * discovery walk regardless of child count or how many times the hash is recomputed.
 */
internal data class InternalSidecarTarget(
  val skillName: String,
  val sourceDir: Path,
  val renderedWrapper: String,
  val authoredCompanions: List<InternalSidecarCompanion>,
)

internal data class InternalSidecarCompanion(
  val name: String,
  val bytes: ByteArray,
)

internal fun internalSidecarStagingNames(children: List<InternalSidecarTarget>): Set<String> =
  children.flatMap { child ->
    listOf("${child.skillName}.md") + child.authoredCompanions.map { companion -> companion.name }
  }.toSet()

/**
 * Discovers the internal skills that declare [parentSkillName] as their parent, sorted by skill
 * name so staging is deterministic. The install-plan seam already validated the classification;
 * this lookup is read-only and trusts that validation.
 *
 * SKILL-104 (PD3): the union of (a) skills-root children with matching `internal-for` (today's
 * scan) and (b) selected pack skills from the plan whose `internalFor` matches the parent. Pack
 * children contribute ONLY when their pack is selected, so an unselected pack stages nothing and
 * contributes no hash bytes (inertness). The same `discoverTargets` + `renderWrapper` path renders
 * both base and pack children (SKILL-102 PD6 parity: full governed wrapper, no trimmed body).
 */
internal fun discoverInternalSidecarTargets(
  repoRoot: Path,
  parentSkillName: String,
  skillsRoot: Path,
  selectedPackSkills: List<InstallPlanSkill> = emptyList(),
): List<InternalSidecarTarget> {
  val baseChildren = discoverBaseSkillSidecarTargets(parentSkillName, skillsRoot)
  val packChildren = selectedPackSkills
    .filter { skill -> skill.internalFor == parentSkillName && skill.name != parentSkillName }
    .sortedBy { skill -> skill.name }
  if (baseChildren.isEmpty() && packChildren.isEmpty()) {
    return emptyList()
  }
  val discovered = discoverTargets(repoRoot.toAbsolutePath().normalize())
  val byName = sortedMapOf<String, InternalSidecarTarget>()
  baseChildren.forEach { (skillName, sourceDir) ->
    byName[skillName] = InternalSidecarTarget(
      skillName = skillName,
      sourceDir = sourceDir,
      renderedWrapper = renderWrapper(discovered.getValue(skillName)),
      authoredCompanions = discoverAuthoredCompanions(sourceDir),
    )
  }
  packChildren.forEach { skill ->
    // A pack child that shares a name with a base child would be a duplicate; the plan's
    // `requireUniqueSkillNames` already forbids that, so a collision here is a programmer error.
    require(skill.name !in byName) {
      "Internal pack skill '${skill.name}' duplicates a base-skill sidecar name for parent " +
        "'$parentSkillName'."
    }
    byName[skill.name] = InternalSidecarTarget(
      skillName = skill.name,
      sourceDir = skill.sourceDir,
      renderedWrapper = renderWrapper(discovered.getValue(skill.name)),
      authoredCompanions = discoverAuthoredCompanions(skill.sourceDir),
    )
  }
  return byName.values.toList()
}

private fun discoverAuthoredCompanions(sourceDir: Path): List<InternalSidecarCompanion> {
  val normalizedSource = sourceDir.toAbsolutePath().normalize()
  val realSource = normalizedSource.toRealPath()
  return Files.list(normalizedSource).use { stream ->
    stream
      .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
      .filter { path -> path.fileName.toString().endsWith(".md") }
      .filter { path -> path.fileName.toString() != "content.md" }
      .sorted(Comparator.comparing { path -> path.fileName.toString() })
      .map { path ->
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.parent == normalizedSource && normalized.toRealPath().startsWith(realSource)) {
          "Authored companion '$path' escapes internal child source directory '$normalizedSource'."
        }
        InternalSidecarCompanion(path.fileName.toString(), Files.readAllBytes(path))
      }
      .toList()
  }
}

internal fun validateInternalSidecarFileNames(
  parentSourceDir: Path,
  children: List<InternalSidecarTarget>,
  reservedStagingNames: Set<String> = emptySet(),
) {
  val claimed = reservedStagingNames.associateWith { "generated staging output" }.toMutableMap()
  children.sortedBy { child -> child.skillName }.forEach { child ->
    val names = listOf("${child.skillName}.md") + child.authoredCompanions.map { companion -> companion.name }
    names.forEach { name ->
      val priorOwner = claimed.putIfAbsent(name, child.skillName)
      val parentCollision = Files.isRegularFile(parentSourceDir.resolve(name), LinkOption.NOFOLLOW_LINKS)
      if (priorOwner != null || parentCollision) {
        throw InternalSkillSidecarCollisionError(
          parentSkillName = parentSourceDir.fileName.toString(),
          internalSkillName = child.skillName,
          sidecarRelativePath = name,
        )
      }
    }
  }
}

private fun discoverBaseSkillSidecarTargets(parentSkillName: String, skillsRoot: Path): List<Pair<String, Path>> {
  if (!Files.isDirectory(skillsRoot)) {
    return emptyList()
  }
  return Files.list(skillsRoot).use { stream ->
    stream
      .filter { dir -> Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) }
      .filter { dir -> dir.fileName.toString() != parentSkillName }
      .sorted(Comparator.comparing { dir -> dir.fileName.toString() })
      .filter { dir ->
        val contentFile = dir.resolve("content.md")
        Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS) &&
          parseInternalForFrontmatter(contentFile) == parentSkillName
      }
      .map { dir -> dir.fileName.toString() to dir.toAbsolutePath().normalize() }
      .toList()
  }
}
