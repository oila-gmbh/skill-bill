@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package skillbill.install.staging

import skillbill.error.InternalSkillSidecarCollisionError
import skillbill.error.InvalidAuthoredSkillSidecarError
import skillbill.install.model.InstallPlanSkill
import skillbill.scaffold.authoring.discoverTargets
import skillbill.scaffold.authoring.parseInternalForFrontmatter
import skillbill.scaffold.authoring.renderWrapper
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale

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

internal data class InternalStagingPreparation(
  val repoRoot: Path,
  val parentSourceDir: Path,
  val parentSkillName: String,
  val skillsRoot: Path,
  val selectedPackSkills: List<InstallPlanSkill>,
  val platformManifests: List<PlatformManifest>?,
  val selectedPlatformManifests: List<PlatformManifest>,
  val parentSupportPointers: List<GeneratedSupportPointer>,
  val parentPointerNames: Set<String>,
)

internal data class PreparedInternalStaging(
  val children: List<InternalSidecarTarget>,
  val sidecarNames: Set<String>,
  val supportPointers: List<GeneratedSupportPointer>,
)

internal fun internalSidecarStagingNames(children: List<InternalSidecarTarget>): Set<String> =
  children.flatMap { child ->
    listOf("${child.skillName}.md") + child.authoredCompanions.map { companion -> companion.name }
  }.toSet()

internal fun prepareInternalStaging(request: InternalStagingPreparation): PreparedInternalStaging {
  val children = discoverInternalSidecarTargets(
    repoRoot = request.repoRoot,
    parentSkillName = request.parentSkillName,
    skillsRoot = request.skillsRoot,
    selectedPackSkills = request.selectedPackSkills,
  )
  val supportPointers = mergeInternalSupportPointers(request, children)
  val sidecarNames = internalSidecarStagingNames(children)
  validateInternalSidecarFileNames(
    parentSourceDir = request.parentSourceDir,
    children = children,
    reservedStagingNames = request.parentPointerNames + supportPointers.map { pointer -> pointer.name } +
      setOf("SKILL.md", ".content-hash"),
  )
  return PreparedInternalStaging(children, sidecarNames, supportPointers)
}

private fun mergeInternalSupportPointers(
  request: InternalStagingPreparation,
  children: List<InternalSidecarTarget>,
): List<GeneratedSupportPointer> {
  data class OwnedPointer(val owner: String, val pointer: GeneratedSupportPointer)

  val parentName = request.parentSourceDir.fileName.toString()
  val merged = linkedMapOf<String, OwnedPointer>()
  val candidates = buildList {
    request.parentSupportPointers.forEach { pointer -> add(OwnedPointer(parentName, pointer)) }
    children.forEach { child ->
      applicablePointers(request.repoRoot, child.sourceDir, request.platformManifests).forEach { (_, spec) ->
        val target = request.repoRoot.resolve(spec.target).normalize()
        add(OwnedPointer(child.skillName, GeneratedSupportPointer(spec.name, target)))
      }
      generatedSupportPointersFor(
        repoRoot = request.repoRoot,
        sourceSkillDir = child.sourceDir,
        skillName = child.skillName,
        skillsRoot = request.skillsRoot,
        selectedPlatformManifests = request.selectedPlatformManifests,
      ).forEach { pointer -> add(OwnedPointer(child.skillName, pointer)) }
    }
  }
  candidates.forEach { candidate ->
    val key = portableFileName(candidate.pointer.name)
    val existing = merged[key]
    if (existing == null) {
      merged[key] = candidate
    } else if (
      existing.pointer.target.toAbsolutePath().normalize() !=
      candidate.pointer.target.toAbsolutePath().normalize()
    ) {
      throw InternalSkillSidecarCollisionError(parentName, candidate.owner, candidate.pointer.name)
    }
  }
  return merged.values.map(OwnedPointer::pointer).sortedBy { pointer -> portableFileName(pointer.name) }
}

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
  val companions = Files.list(normalizedSource).use { stream ->
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
  if (companions.size > 1) {
    throw InvalidAuthoredSkillSidecarError(
      "Internal skill '${sourceDir.fileName}' may declare at most one authored Markdown rubric sidecar; " +
        "found ${companions.joinToString { companion -> companion.name }}.",
    )
  }
  companions.singleOrNull()?.let { companion -> validateAuthoredCompanion(normalizedSource, companion) }
  return companions
}

private fun validateAuthoredCompanion(sourceDir: Path, companion: InternalSidecarCompanion) {
  if (portableFileName(companion.name) in reservedGeneratedSidecarNames.map(::portableFileName)) {
    throw InvalidAuthoredSkillSidecarError(
      "Internal skill '${sourceDir.fileName}' authored sidecar '${companion.name}' uses a reserved generated " +
        "filename.",
    )
  }
  val content = Files.readString(sourceDir.resolve("content.md"))
  val link = Regex("\\]\\((?:\\./)?${Regex.escape(companion.name)}(?:[#?][^)]*)?\\)")
  if (!link.containsMatchIn(content)) {
    throw InvalidAuthoredSkillSidecarError(
      "Internal skill '${sourceDir.fileName}' authored sidecar '${companion.name}' must be explicitly linked " +
        "from content.md as its specialist rubric.",
    )
  }
}

internal fun validateInternalSidecarFileNames(
  parentSourceDir: Path,
  children: List<InternalSidecarTarget>,
  reservedStagingNames: Set<String> = emptySet(),
) {
  val claimed = reservedStagingNames.associate { portableFileName(it) to "generated staging output" }.toMutableMap()
  val authoredNames = Files.list(parentSourceDir).use { stream ->
    stream
      .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
      .map { path -> portableFileName(path.fileName.toString()) }
      .toList()
      .toSet()
  }
  children.sortedBy { child -> child.skillName }.forEach { child ->
    val names = listOf("${child.skillName}.md") + child.authoredCompanions.map { companion -> companion.name }
    names.forEach { name ->
      val key = portableFileName(name)
      val priorOwner = claimed.putIfAbsent(key, child.skillName)
      val parentCollision = key in authoredNames
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

internal fun portableFileName(name: String): String =
  Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

private val reservedGeneratedSidecarNames = setOf(
  "review-orchestrator.md",
  "review-delegation.md",
  "review-scope.md",
  "shell-ceremony.md",
  "specialist-contract.md",
  "stack-routing.md",
  "telemetry-contract.md",
)

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
