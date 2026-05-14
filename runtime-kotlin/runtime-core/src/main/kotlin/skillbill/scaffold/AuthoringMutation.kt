package skillbill.scaffold

import skillbill.error.ShellContentContractException
import skillbill.error.SkillBillRuntimeException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun mutateContent(repoRoot: Path, target: AuthoringTarget, replacementText: String): Map<String, Any?> {
  val contentBefore = Files.readAllBytes(target.contentFile)
  return runWithContentRollback(target, contentBefore) {
    Files.writeString(target.contentFile, replacementText)
    val issues = validateTarget(target, repoRoot)
    if (issues.isNotEmpty()) {
      throw SkillBillRuntimeException("Validator failed after content update:\n${issues.joinToString("\n")}")
    }
    renderAuthoringTarget(repoRoot, target)
    statusPayload(repoRoot, target, "none") + mapOf(
      "wrapper_regenerated" to false,
    )
  }
}

internal fun validateTarget(target: AuthoringTarget, repoRoot: Path? = null): List<String> {
  val issues = mutableListOf<String>()
  when {
    !Files.isRegularFile(target.contentFile) -> issues += "${target.contentFile}: content.md is missing"
    else -> collectTargetIssues(target, repoRoot, issues)
  }
  return issues
}

private fun <T> runWithContentRollback(target: AuthoringTarget, contentBefore: ByteArray, block: () -> T): T = try {
  block()
} catch (error: SkillBillRuntimeException) {
  restoreContentFiles(target, contentBefore)
  throw error
} catch (error: IOException) {
  restoreContentFiles(target, contentBefore)
  throw error
} catch (error: IllegalArgumentException) {
  restoreContentFiles(target, contentBefore)
  throw error
}

private fun restoreContentFiles(target: AuthoringTarget, contentBefore: ByteArray) {
  Files.write(target.contentFile, contentBefore)
}

private fun collectTargetIssues(target: AuthoringTarget, repoRoot: Path?, issues: MutableList<String>) {
  val contentText = Files.readString(target.contentFile)
  if (!contentText.contains("name: ${target.skillName}\n")) {
    issues += "${target.contentFile}: frontmatter name does not match directory '${target.skillName}'"
  }
  if (repoRoot != null && isSourceOwnedSkillTarget(repoRoot, target)) {
    collectSourceSidecarIssues(repoRoot, target, issues)
  }
  // Validate content.md frontmatter only — content.md is the authored surface and may contain
  // rich body markdown (fenced code, tables, H1s) that the wrapper rules reject.
  try {
    validateSkillMdShape(target.contentFile, validateBodyShape = false)
  } catch (error: ShellContentContractException) {
    issues += error.message.orEmpty()
  }
  issues += validateAuthoredContent(target.contentFile, contentText)
}

private fun isSourceOwnedSkillTarget(repoRoot: Path, target: AuthoringTarget): Boolean {
  val skillsRoot = repoRoot.toAbsolutePath().normalize().resolve("skills")
  return target.contentFile.toAbsolutePath().normalize().startsWith(skillsRoot)
}

private fun collectSourceSidecarIssues(repoRoot: Path, target: AuthoringTarget, issues: MutableList<String>) {
  requiredSupportingFilesForSkill(target.skillName, repoRoot).forEach { fileName ->
    val expectedTarget = supportingFileTargets(repoRoot)[fileName]
    if (expectedTarget == null) {
      issues += "${target.contentFile}: supporting sidecar '$fileName' has no registered target"
      return@forEach
    }
    if (!Files.exists(expectedTarget)) {
      issues += "${target.contentFile}: supporting sidecar '$fileName' target is missing at $expectedTarget"
    }
    val sidecar = target.contentFile.resolveSibling(fileName)
    val sidecarPath = sidecar.normalize().toAbsolutePath()
    val expectedPath = expectedTarget.normalize().toAbsolutePath()
    if (sidecarPath != expectedPath) {
      return@forEach
    }
    when {
      !Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) ->
        issues += "${target.contentFile}: required supporting sidecar '$fileName' is missing beside the skill"
      sidecarPath == expectedPath -> Unit
      Files.isSymbolicLink(sidecar) -> validateSourceSidecarSymlink(target, fileName, sidecar, expectedTarget, issues)
      isGitSymlinkPlaceholder(sidecar, expectedTarget) -> Unit
      else ->
        issues +=
          "${target.contentFile}: required supporting sidecar '$fileName' must be a symlink or git symlink placeholder"
    }
  }
}

private fun validateSourceSidecarSymlink(
  target: AuthoringTarget,
  fileName: String,
  sidecar: Path,
  expectedTarget: Path,
  issues: MutableList<String>,
) {
  val actual = runCatching { sidecar.toRealPath() }.getOrNull()
  val expected = runCatching { expectedTarget.toRealPath() }.getOrNull()
  if (actual != null && expected != null && actual != expected) {
    issues += "${target.contentFile}: supporting sidecar '$fileName' points to $actual instead of $expected"
  }
}

private fun isGitSymlinkPlaceholder(sidecar: Path, expectedTarget: Path): Boolean {
  var matches = false
  if (Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
    val rawTarget = Files.readString(sidecar).trim()
    if (rawTarget.isNotBlank()) {
      val actualTarget = sidecar.parent.resolve(rawTarget).normalize().toAbsolutePath()
      matches = actualTarget == expectedTarget.normalize().toAbsolutePath()
    }
  }
  return matches
}
