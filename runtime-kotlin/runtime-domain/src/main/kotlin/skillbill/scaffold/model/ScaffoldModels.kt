package skillbill.scaffold.model

import java.nio.file.Path

data class RoutingSignals(
  val strong: List<String>,
  val tieBreakers: List<String>,
)

data class DeclaredFiles(
  val baseline: Path,
  val areas: Map<String, Path>,
)

data class PointerSpec(
  val skillRelativeDir: String,
  val name: String,
  val target: String,
)

data class PlatformManifest(
  val slug: String,
  val packRoot: Path,
  val contractVersion: String,
  val routingSignals: RoutingSignals,
  val declaredCodeReviewAreas: List<String>,
  val declaredFiles: DeclaredFiles,
  val areaMetadata: Map<String, String>,
  val displayName: String? = null,
  val notes: String? = null,
  val declaredQualityCheckFile: Path? = null,
  val pointers: List<PointerSpec> = emptyList(),
) {
  val routedSkillName: String = "bill-$slug-code-review"
}

data class GovernedAddonFile(
  val packSlug: String,
  val addonPath: Path,
) {
  val addonSlug: String = addonPath.fileName.toString().removeSuffix(".md")
}

data class ScaffoldResult(
  val kind: String,
  val skillName: String,
  val skillPath: Path,
  val createdFiles: List<Path> = emptyList(),
  val manifestEdits: List<Path> = emptyList(),
  val symlinks: List<Path> = emptyList(),
  val installTargets: List<Path> = emptyList(),
  val notes: List<String> = emptyList(),
)

/**
 * Match rule for a skill class. The renderer picks the first class file whose matcher list
 * resolves the candidate skill name; within a file, an `exact` match wins over a `pattern` match.
 * `excludeExact` removes skill names that would otherwise match a `pattern`, used when a more
 * specific class wants to opt out of a broader regex declared in another file.
 */
data class SkillClassMatcher(
  val exact: String? = null,
  val pattern: Regex? = null,
  val excludeExact: List<String> = emptyList(),
)

/**
 * Framework-owned section inserted between the generated `## Descriptor` and the authored
 * `## Execution` body. Body is written verbatim, no template substitution.
 */
data class SkillClassSection(
  val heading: String,
  val body: String,
)

/**
 * A single class manifest read from `orchestration/skill-classes/<class>.yaml`. Captures
 * framework behavior for a category of skills (e.g. code-review-shell, quality-check-leaf) so
 * authored `content.md` files can stay free of ceremony and renderer-owned prose.
 */
data class SkillClassManifest(
  val classId: String,
  val classFile: Path,
  val contractVersion: String,
  val matchers: List<SkillClassMatcher>,
  val pointers: List<String>,
  val sections: List<SkillClassSection>,
  val ceremonyLines: List<String>,
)
