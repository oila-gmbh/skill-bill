package skillbill.scaffold

import skillbill.error.SkillBillRuntimeException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal fun mutateContent(repoRoot: Path, target: AuthoringTarget, replacementText: String): Map<String, Any?> {
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
  val skillText = Files.readString(target.skillFile)
  if (!skillText.contains("name: ${target.skillName}\n")) {
    issues += "${target.skillFile}: frontmatter name does not match directory '${target.skillName}'"
  }
  REQUIRED_GOVERNED_SECTIONS.forEach { heading ->
    if (!skillText.contains("$heading\n")) {
      issues += "${target.skillFile}: SKILL.md is missing required section '$heading'"
    }
  }
  val contentText = Files.readString(target.contentFile)
  if (contentText.isBlank()) {
    issues += "${target.contentFile}: content.md must not be empty"
  }
  if (hasUnresolvedPlaceholder(contentText)) {
    issues += "${target.contentFile}: content.md contains an unresolved TODO/FIXME placeholder"
  }
}
