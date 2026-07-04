@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package skillbill.install.staging

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
)

/**
 * Discovers the internal skills that declare [parentSkillName] as their parent, sorted by skill
 * name so staging is deterministic. The install-plan seam already validated the classification;
 * this lookup is read-only and trusts that validation.
 */
internal fun discoverInternalSidecarTargets(
  repoRoot: Path,
  parentSkillName: String,
  skillsRoot: Path,
): List<InternalSidecarTarget> {
  if (!Files.isDirectory(skillsRoot)) {
    return emptyList()
  }
  val childDirs = Files.list(skillsRoot).use { stream ->
    stream
      .filter { dir -> Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) }
      .filter { dir -> dir.fileName.toString() != parentSkillName }
      .sorted(Comparator.comparing { dir -> dir.fileName.toString() })
      .filter { dir ->
        val contentFile = dir.resolve("content.md")
        Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS) &&
          parseInternalForFrontmatter(contentFile) == parentSkillName
      }
      .toList()
  }
  if (childDirs.isEmpty()) {
    return emptyList()
  }
  val discovered = discoverTargets(repoRoot.toAbsolutePath().normalize())
  return childDirs.map { dir ->
    val skillName = dir.fileName.toString()
    InternalSidecarTarget(
      skillName = skillName,
      sourceDir = dir.toAbsolutePath().normalize(),
      renderedWrapper = renderWrapper(discovered.getValue(skillName)),
    )
  }
}
