@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package skillbill.install.staging

import skillbill.scaffold.authoring.parseInternalForFrontmatter
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * SKILL-102 subtask 1 (PD2/PD6): discover the internal skills that declare [parentSkillName] as
 * their parent. Returns one [InternalSidecarTarget] per internal child, sorted by skill name so
 * staging is deterministic. Walks the sibling skill directories under [skillsRoot] (the same root
 * discovery walks) and selects those whose `content.md` carries `internal-for: <parentSkillName>`.
 *
 * The install-plan seam already validated the classification (see validateInstallPlanInternalSkills);
 * this lookup is read-only and trusts that validation. It is independent of the plan so the
 * per-skill staging pipeline can render sidecars without plumbing the full plan through every call.
 *
 * Reads `internal-for` directly via the shared parse seam ([parseInternalForFrontmatter]) rather
 * than re-running full authoring discovery per sibling, so the per-skill staging cost stays linear
 * in the number of sibling skills (not quadratic in repo size).
 */
internal data class InternalSidecarTarget(
  val skillName: String,
  val sourceDir: Path,
  val contentFile: Path,
  val repoRoot: Path,
)

internal fun discoverInternalSidecarTargets(
  repoRoot: Path,
  parentSkillName: String,
  skillsRoot: Path,
): List<InternalSidecarTarget> {
  if (!Files.isDirectory(skillsRoot)) {
    return emptyList()
  }
  val targets = mutableListOf<InternalSidecarTarget>()
  Files.list(skillsRoot).use { stream ->
    stream
      .filter { dir -> Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) }
      .filter { dir -> dir.fileName.toString() != parentSkillName }
      .sorted(Comparator.comparing { dir -> dir.fileName.toString() })
      .forEach { dir ->
        val contentFile = dir.resolve("content.md")
        if (!Files.isRegularFile(contentFile, LinkOption.NOFOLLOW_LINKS)) {
          return@forEach
        }
        val internalFor = parseInternalForFrontmatter(contentFile)
        if (internalFor == parentSkillName) {
          targets += InternalSidecarTarget(
            skillName = dir.fileName.toString(),
            sourceDir = dir.toAbsolutePath().normalize(),
            contentFile = contentFile.toAbsolutePath().normalize(),
            repoRoot = repoRoot.toAbsolutePath().normalize(),
          )
        }
      }
  }
  return targets
}
