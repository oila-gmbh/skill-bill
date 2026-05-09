package skillbill.scaffold

import skillbill.error.ShellContentContractException
import skillbill.error.SkillBillRuntimeException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal fun mutateContent(repoRoot: Path, target: AuthoringTarget, replacementText: String): Map<String, Any?> {
  // Guard against an orphaned content.md (no sibling SKILL.md). Without this, reading the wrapper
  // bytes for the rollback snapshot would throw an opaque NoSuchFileException from the JVM. Mirror
  // the wording emitted by validateTarget so callers see the same message regardless of entrypoint.
  if (!Files.isRegularFile(target.skillFile)) {
    throw SkillBillRuntimeException("${target.skillFile}: SKILL.md is missing")
  }
  val contentBefore = Files.readAllBytes(target.contentFile)
  val wrapperBefore = Files.readAllBytes(target.skillFile)
  return runWithContentRollback(target, contentBefore, wrapperBefore) {
    Files.writeString(target.contentFile, replacementText)
    val upgradePayload = AuthoringOperations.upgrade(repoRoot, listOf(target.skillName), validate = true)
    val regenerated = upgradePayload["regenerated_files"] as? List<*> ?: emptyList<String>()
    statusPayload(repoRoot, target, "none") + mapOf(
      "wrapper_regenerated" to regenerated.map { entry -> entry.toString() }.contains(target.skillFile.toString()),
    )
  }
}

internal fun validateTarget(target: AuthoringTarget): List<String> {
  val issues = mutableListOf<String>()
  when {
    !Files.isRegularFile(target.skillFile) -> issues += "${target.skillFile}: SKILL.md is missing"
    !Files.isRegularFile(target.contentFile) -> issues += "${target.contentFile}: content.md is missing"
    else -> collectTargetIssues(target, issues)
  }
  return issues
}

private fun <T> runWithContentRollback(
  target: AuthoringTarget,
  contentBefore: ByteArray,
  wrapperBefore: ByteArray,
  block: () -> T,
): T = try {
  block()
} catch (error: SkillBillRuntimeException) {
  restoreContentFiles(target, contentBefore, wrapperBefore)
  throw error
} catch (error: IOException) {
  restoreContentFiles(target, contentBefore, wrapperBefore)
  throw error
} catch (error: IllegalArgumentException) {
  restoreContentFiles(target, contentBefore, wrapperBefore)
  throw error
}

private fun restoreContentFiles(target: AuthoringTarget, contentBefore: ByteArray, wrapperBefore: ByteArray) {
  Files.write(target.contentFile, contentBefore)
  Files.write(target.skillFile, wrapperBefore)
}

private fun collectTargetIssues(target: AuthoringTarget, issues: MutableList<String>) {
  val contentText = Files.readString(target.contentFile)
  if (!contentText.contains("name: ${target.skillName}\n")) {
    issues += "${target.contentFile}: frontmatter name does not match directory '${target.skillName}'"
  }
  requiredSupportingFilesForSkill(target.skillName).forEach { fileName ->
    val sidecar = target.contentFile.resolveSibling(fileName)
    if (!Files.isRegularFile(sidecar)) {
      issues += "${target.contentFile}: required ceremony sidecar '$fileName' is missing"
    }
  }
  // Validate content.md frontmatter only — content.md is the authored surface and may contain
  // rich body markdown (fenced code, tables, H1s) that the wrapper rules reject.
  try {
    validateSkillMdShape(target.contentFile, validateBodyShape = false)
  } catch (error: ShellContentContractException) {
    issues += error.message.orEmpty()
  }
  // Wrapper SKILL.md still on disk through subtasks 1–3 — keep enforcing the canonical wrapper
  // body shape until subtask 4 retires the wrapper.
  if (Files.isRegularFile(target.skillFile)) {
    try {
      validateSkillMdShape(target.skillFile, validateBodyShape = true)
    } catch (error: ShellContentContractException) {
      issues += error.message.orEmpty()
    }
  }
  if (contentText.isBlank()) {
    issues += "${target.contentFile}: content.md must not be empty"
  }
  if (hasUnresolvedPlaceholder(contentText)) {
    issues += "${target.contentFile}: content.md contains an unresolved TODO/FIXME placeholder"
  }
}
