package skillbill.scaffold

import skillbill.error.SkillBillRuntimeException
import java.nio.file.Files
import java.nio.file.Path

internal const val AUTHORING_EXPLANATION =
  "Governed skills split author-owned behavior into content.md and generated runtime wiring into SKILL.md. " +
    "Use CLI commands to keep that boundary intact."

data class AuthoringTarget(
  val skillName: String,
  val packageName: String,
  val platform: String,
  val displayName: String,
  val family: String,
  val area: String,
  val skillFile: Path,
  val contentFile: Path,
)

object AuthoringOperations {
  fun list(repoRoot: Path, skillNames: List<String>): Map<String, Any?> {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val targets = selectedTargets(resolvedRoot, skillNames)
    return mapOf(
      "repo_root" to resolvedRoot.toString(),
      "skill_count" to targets.size,
      "skills" to targets.map { target -> statusPayload(resolvedRoot, target, "none") },
    )
  }

  fun show(repoRoot: Path, skillName: String, contentMode: String): Map<String, Any?> {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName)
    return statusPayload(resolvedRoot, target, contentMode)
  }

  fun explain(repoRoot: Path, skillName: String?): Map<String, Any?> {
    val payload =
      mutableMapOf<String, Any?>(
        "explanation" to AUTHORING_EXPLANATION,
        "editable_surface" to listOf("content.md"),
        "generated_surface" to listOf("SKILL.md"),
        "governed_sidecars" to listOf("shell-ceremony.md"),
        "normal_workflow" to listOf(
          "skill-bill new --payload <file>",
          "skill-bill fill <skill-name>",
          "skill-bill validate --skill-name <skill-name>",
          "skill-bill render --skill-name <skill-name>",
        ),
        "notes" to listOf(
          "Author behavior changes in content.md.",
          "Regenerate wrappers with render instead of hand-editing SKILL.md.",
          "Use show to inspect completion, drift, and next commands.",
        ),
      )
    if (skillName != null) {
      val resolvedRoot = repoRoot.toAbsolutePath().normalize()
      val target = resolveTarget(resolvedRoot, skillName)
      payload["skill"] =
        mapOf(
          "skill_name" to target.skillName,
          "skill_file" to target.skillFile.toString(),
          "content_file" to target.contentFile.toString(),
          "recommended_commands" to recommendedCommands(
            resolvedRoot,
            target,
            completionStatus = contentCompletionStatus(Files.readString(target.contentFile)),
            generationDrift = hasGenerationDrift(target),
            issues = emptyList(),
          ),
        )
    }
    return payload
  }

  fun validate(repoRoot: Path, skillNames: List<String>): Map<String, Any?> {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    if (skillNames.isEmpty()) {
      val issues =
        discoverTargets(resolvedRoot).values.flatMap { target ->
          validateTarget(target)
        }
      return mapOf(
        "repo_root" to resolvedRoot.toString(),
        "mode" to "repo",
        "status" to if (issues.isEmpty()) "pass" else "fail",
        "issues" to issues,
      )
    }
    val issues = selectedTargets(resolvedRoot, skillNames).flatMap { target -> validateTarget(target) }
    val suggestedCommands =
      skillNames.flatMap { skillName ->
        val target = resolveTarget(resolvedRoot, skillName)
        recommendedCommands(
          resolvedRoot,
          target,
          completionStatus = contentCompletionStatus(Files.readString(target.contentFile)),
          generationDrift = hasGenerationDrift(target),
          issues = issues,
        )
      }.distinct()
    return mapOf(
      "repo_root" to resolvedRoot.toString(),
      "mode" to "selected",
      "skill_names" to skillNames,
      "status" to if (issues.isEmpty()) "pass" else "fail",
      "issues" to issues,
      "suggested_commands" to suggestedCommands,
    )
  }

  fun upgrade(repoRoot: Path, skillNames: List<String>, validate: Boolean): Map<String, Any?> {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val targets = selectedTargets(resolvedRoot, skillNames)
    val originalBytes = mutableMapOf<Path, ByteArray>()
    val regenerated = mutableListOf<Path>()
    return runWithUpgradeRollback(originalBytes) {
      targets.forEach { target ->
        val rendered = renderWrapper(target)
        val renderedBytes = rendered.toByteArray(Charsets.UTF_8)
        val currentBytes = Files.readAllBytes(target.skillFile)
        if (!currentBytes.contentEquals(renderedBytes)) {
          originalBytes[target.skillFile] = currentBytes
          Files.write(target.skillFile, renderedBytes)
          regenerated.add(target.skillFile)
        }
      }
      if (validate) {
        val issues = targets.flatMap { target -> validateTarget(target) }
        if (issues.isNotEmpty()) {
          throw SkillBillRuntimeException("Validator failed after upgrade:\n${issues.joinToString("\n")}")
        }
      }
      mapOf(
        "repo_root" to resolvedRoot.toString(),
        "regenerated_count" to regenerated.size,
        "regenerated_files" to regenerated.map { path -> path.toString() },
        "content_md_touched" to false,
        "shell_ceremony_touched" to false,
        "validator_ran" to validate,
      )
    }
  }

  fun fill(repoRoot: Path, skillName: String, body: String, sectionName: String?): Map<String, Any?> {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName)
    val replacement =
      if (sectionName == null) {
        coerceFullContentText(target, body)
      } else {
        replaceSectionBody(Files.readString(target.contentFile), sectionName, body)
      }
    return mutateContent(resolvedRoot, target, replacement) + mapOf(
      "updated_section" to sectionName?.let(::sectionHeadingLabel),
      "validator_ran" to true,
    )
  }

  fun editWithBodyFile(repoRoot: Path, skillName: String, body: String, sectionName: String?): Map<String, Any?> {
    val resolvedRoot = repoRoot.toAbsolutePath().normalize()
    val target = resolveTarget(resolvedRoot, skillName)
    val replacement =
      if (sectionName == null) {
        coerceFullContentText(target, body)
      } else {
        replaceSectionBody(Files.readString(target.contentFile), sectionName, body)
      }
    return mapOf(
      "used_editor" to false,
      "guided_sections" to emptyList<String>(),
      "updated_section" to sectionName?.let(::sectionHeadingLabel),
      "validator_ran" to true,
    ) + mutateContent(resolvedRoot, target, replacement)
  }

  fun retiredInteractiveMessage(command: String, replacement: String): String =
    "$command interactive mode was retired in SKILL-32; use `$replacement` instead."

  fun retiredEditorMessage(command: String, replacement: String): String =
    "$command editor mode was retired in SKILL-32; use `$replacement` instead."
}
