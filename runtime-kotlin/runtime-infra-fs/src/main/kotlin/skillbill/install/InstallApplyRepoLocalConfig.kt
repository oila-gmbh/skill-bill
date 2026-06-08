package skillbill.install

import skillbill.config.model.RepoLocalConfigKey
import skillbill.install.model.InstallApplyIssue
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallPlan
import java.nio.file.Files
import java.nio.file.Path

/**
 * Scaffolds the repo-local config under `plan.request.repoRoot`. Both side effects are
 * idempotent and non-destructive: `.skill-bill/config.yaml` is created only when absent (a
 * user-edited config is never clobbered), and `.gitignore` gains an anchored `/.skill-bill/`
 * entry only when that exact line is not already present.
 *
 * Scaffolding is additive, so failures are collected as warnings rather than hard install
 * failures (mirroring orchestration-link outcome handling).
 */
internal fun applyRepoLocalConfigScaffold(plan: InstallPlan, warnings: MutableList<InstallApplyIssue>) {
  val repoRoot = plan.request.repoRoot.toAbsolutePath().normalize()
  scaffoldStep(InstallApplyIssueKind.REPO_LOCAL_CONFIG_SCAFFOLD_FAILED, warnings) {
    writeDefaultConfigIfAbsent(repoRoot)
  }
  scaffoldStep(InstallApplyIssueKind.REPO_LOCAL_CONFIG_SCAFFOLD_FAILED, warnings) {
    appendGitignoreEntryIfAbsent(repoRoot)
  }
}

private inline fun scaffoldStep(
  kind: InstallApplyIssueKind,
  warnings: MutableList<InstallApplyIssue>,
  step: () -> Path,
) {
  runCatching { step() }.onFailure { error ->
    warnings.add(
      InstallApplyIssue(
        kind = kind,
        message = error.message.orEmpty(),
        causeClass = error::class.qualifiedName,
      ),
    )
  }
}

private fun writeDefaultConfigIfAbsent(repoRoot: Path): Path {
  val configPath = repoRoot.resolve(".skill-bill").resolve("config.yaml")
  if (Files.exists(configPath)) {
    return configPath
  }
  Files.createDirectories(configPath.parent)
  Files.writeString(configPath, defaultConfigContent())
  return configPath
}

private fun defaultConfigContent(): String = RepoLocalConfigKey.entries
  .joinToString(separator = "\n", postfix = "\n") { key -> "${key.key}: ${key.builtinDefault}" }

private fun appendGitignoreEntryIfAbsent(repoRoot: Path): Path {
  val gitignorePath = repoRoot.resolve(".gitignore")
  val existing = if (Files.exists(gitignorePath)) Files.readString(gitignorePath) else ""
  val alreadyPresent = existing.lineSequence().any { line -> line.trim() == GITIGNORE_ENTRY }
  if (alreadyPresent) {
    return gitignorePath
  }
  val updated = buildString {
    append(existing)
    if (existing.isNotEmpty() && !existing.endsWith("\n")) {
      append("\n")
    }
    append(GITIGNORE_ENTRY)
    append("\n")
  }
  Files.writeString(gitignorePath, updated)
  return gitignorePath
}

private const val GITIGNORE_ENTRY = "/.skill-bill/"
