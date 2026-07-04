package skillbill.scaffold.authoring

import skillbill.error.SkillBillRuntimeException
import skillbill.nativeagent.rendering.NativeAgentOperations
import skillbill.ports.scaffold.model.ScaffoldSkillStatus
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.GovernedAddonSelection
import java.nio.file.Files
import java.nio.file.Path

internal const val AUTHORING_EXPLANATION =
  "Governed skills split author-owned behavior into content.md and generated runtime wiring into " +
    "render/install output. " +
    "Use CLI commands to keep that boundary intact."

data class AuthoringTarget(
  val skillName: String,
  val packageName: String,
  val platform: String,
  val displayName: String,
  val family: String,
  val area: String,
  // skillFile remains for transient consumers until SKILL-40 subtask 4 deletes the wrapper.
  val skillFile: Path,
  val contentFile: Path,
  val codeReviewComposition: CodeReviewComposition? = null,
  val addonUsage: List<GovernedAddonSelection> = emptyList(),
  // SKILL-102 subtask 1 (PD1): presence of the `internal-for: <parent-skill-name>` frontmatter
  // key makes a skill internal (it installs as a sidecar inside its parent's staged directory
  // instead of getting its own skills_dir entry). Null means listed. Validated separately by
  // `validateInternalSkillClassification` once discovery has enumerated every skill name.
  val internalFor: String? = null,
)

object AuthoringOperations {
  internal fun list(repoRoot: Path, skillNames: List<String>): AuthoringListResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val targets = selectedTargets(resolvedRoot, skillNames)
    return AuthoringListResult(
      repoRoot = resolvedRoot.toString(),
      skillCount = targets.size,
      skills = targets.map { target -> skillStatus(resolvedRoot, target, "none") },
    )
  }

  internal fun show(repoRoot: Path, skillName: String, contentMode: String): ScaffoldSkillStatus {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName)
    return skillStatus(resolvedRoot, target, contentMode)
  }

  internal fun explain(repoRoot: Path, skillName: String?): AuthoringExplain {
    val skill = skillName?.let { name ->
      val resolvedRoot = repoRoot.toAbsolutePath().normalize()
      val target = resolveTarget(resolvedRoot, name)
      AuthoringExplainSkill(
        skillName = target.skillName,
        contentFile = target.contentFile.toString(),
        renderCommand = "skill-bill render ${target.skillName} --repo-root $resolvedRoot",
        recommendedCommands = recommendedCommands(
          resolvedRoot,
          target,
          completionStatus = contentCompletionStatus(Files.readString(target.contentFile)),
          issues = emptyList(),
        ),
      )
    }
    return AuthoringExplain(
      explanation = AUTHORING_EXPLANATION,
      editableSurface = listOf("content.md"),
      generatedSurface = listOf("SKILL.md", "platform.yaml pointer files"),
      governedSidecars = emptyList(),
      normalWorkflow = listOf(
        "skill-bill new --payload <file>",
        "skill-bill fill <skill-name>",
        "skill-bill validate --skill-name <skill-name>",
        "skill-bill render <skill-name>",
      ),
      notes = listOf(
        "Author behavior changes in content.md.",
        "Preview generated wrappers with render instead of hand-editing SKILL.md.",
        "Use show to inspect completion and next commands.",
      ),
      skill = skill,
    )
  }

  internal fun validate(repoRoot: Path, skillNames: List<String>): AuthoringValidateResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    if (skillNames.isEmpty()) {
      val issues =
        discoverTargets(resolvedRoot).values.flatMap { target ->
          validateTarget(target, resolvedRoot)
        }
      return AuthoringValidateResult(
        repoRoot = resolvedRoot.toString(),
        mode = "repo",
        skillNames = null,
        status = if (issues.isEmpty()) "pass" else "fail",
        issues = issues,
        suggestedCommands = null,
      )
    }
    val issues = selectedTargets(resolvedRoot, skillNames).flatMap { target -> validateTarget(target, resolvedRoot) }
    val suggestedCommands =
      skillNames.flatMap { skillName ->
        val target = resolveTarget(resolvedRoot, skillName)
        recommendedCommands(
          resolvedRoot,
          target,
          completionStatus = contentCompletionStatus(Files.readString(target.contentFile)),
          issues = issues,
        )
      }.distinct()
    return AuthoringValidateResult(
      repoRoot = resolvedRoot.toString(),
      mode = "selected",
      skillNames = skillNames,
      status = if (issues.isEmpty()) "pass" else "fail",
      issues = issues,
      suggestedCommands = suggestedCommands,
    )
  }

  internal fun upgrade(repoRoot: Path, skillNames: List<String>, validate: Boolean): AuthoringUpgradeResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val targets = selectedTargets(resolvedRoot, skillNames)
    val originalBytes = mutableMapOf<Path, ByteArray>()
    val createdPaths = mutableListOf<Path>()
    val regenerated = mutableListOf<Path>()
    return runWithUpgradeRollback(originalBytes, createdPaths) {
      targets.forEach { target ->
        renderAuthoringTarget(resolvedRoot, target)
      }
      val nativeRegeneration = NativeAgentOperations.regenerate(
        resolvedRoot,
        skillNames,
        originalBytes = originalBytes,
        createdPaths = createdPaths,
      )
      regenerated += nativeRegeneration.regeneratedFiles
      if (validate) {
        val issues = targets.flatMap { target -> validateTarget(target, resolvedRoot) }
        if (issues.isNotEmpty()) {
          throw SkillBillRuntimeException("Validator failed after upgrade:\n${issues.joinToString("\n")}")
        }
      }
      AuthoringUpgradeResult(
        repoRoot = resolvedRoot.toString(),
        regeneratedCount = regenerated.size,
        regeneratedFiles = regenerated.map { path -> path.toString() },
        contentMdTouched = false,
        shellCeremonyTouched = false,
        validatorRan = validate,
      )
    }
  }

  internal fun fill(repoRoot: Path, skillName: String, body: String, sectionName: String?): AuthoringFillResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName)
    val replacement =
      if (sectionName == null) {
        coerceFullContentText(target, body)
      } else {
        replaceSectionBody(Files.readString(target.contentFile), sectionName, body)
      }
    val mutation = mutateContent(resolvedRoot, target, replacement)
    return AuthoringFillResult(
      mutation = mutation,
      updatedSection = sectionName?.let(::sectionHeadingLabel),
      validatorRan = true,
    )
  }

  internal fun saveExactContent(repoRoot: Path, skillName: String, content: String): AuthoringSaveExactContentResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName)
    val mutation = mutateContent(resolvedRoot, target, content)
    return AuthoringSaveExactContentResult(
      mutation = mutation,
      validatorRan = true,
    )
  }

  internal fun editWithBodyFile(
    repoRoot: Path,
    skillName: String,
    body: String,
    sectionName: String?,
  ): AuthoringEditWithBodyFileResult {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName)
    val replacement =
      if (sectionName == null) {
        coerceFullContentText(target, body)
      } else {
        replaceSectionBody(Files.readString(target.contentFile), sectionName, body)
      }
    val mutation = mutateContent(resolvedRoot, target, replacement)
    return AuthoringEditWithBodyFileResult(
      usedEditor = false,
      guidedSections = emptyList(),
      updatedSection = sectionName?.let(::sectionHeadingLabel),
      validatorRan = true,
      mutation = mutation,
    )
  }

  fun retiredInteractiveMessage(command: String, replacement: String): String =
    "$command interactive mode was retired in SKILL-32; use `$replacement` instead."

  fun retiredEditorMessage(command: String, replacement: String): String =
    "$command editor mode was retired in SKILL-32; use `$replacement` instead."
}
