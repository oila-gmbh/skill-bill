@file:Suppress("MaxLineLength", "ReturnCount")

package skillbill.cli

import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import me.tatarka.inject.annotations.Inject
import skillbill.domain.skillremove.SkillRemovalRefusedException
import skillbill.domain.skillremove.SkillRemove
import skillbill.domain.skillremove.SkillRemoveErrorSanitizer
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalResult
import skillbill.domain.skillremove.model.SkillRemovalTarget
import skillbill.skillremove.SkillRemoveJvmFileSystem
import java.nio.file.Path

/**
 * `skill-bill remove` — SKILL-46 AC7.
 *
 * Wraps the [SkillRemove] domain service so the CLI behaves identically to the desktop dialog.
 * The target argument follows a triple `<scope>:<value>` shape so we never have to guess at
 * intent:
 *
 *   skill-bill remove skill:bill-foo
 *   skill-bill remove platform:my-platform
 *   skill-bill remove addon:platform-packs/kmp/addons/my-addon.md
 *
 * Flags:
 * - `--dry-run` returns only the preview dossier; nothing on disk is mutated.
 * - `--allow-shipped` is required to remove the built-in `kotlin` / `kmp` surfaces. `.bill-shared`
 *   is never deletable regardless of flags.
 *
 * Exit codes:
 * - `0` — success (or dry-run preview returned).
 * - `1` — refusal or runtime failure.
 */
@Inject
class RemoveCliCommand(
  private val state: CliRunState,
) : DocumentedCliCommand(
  "remove",
  "Remove a horizontal skill, a platform pack, or a governed add-on, including manifest, README, " +
    "and agent-symlink cleanup.",
) {
  private val target by argument(
    help = "Removal target. Examples: 'skill:bill-foo', 'platform:my-platform', " +
      "'addon:platform-packs/kmp/addons/my-addon.md'.",
  )
  private val repoRoot by option(
    "--repo-root",
    help = "Repo root to operate on. Defaults to the current working directory.",
  )
    .default(".")
  private val dryRun by option("--dry-run", help = "Preview the removal cascade without touching disk.")
    .flag(default = false)
  private val allowShipped by option(
    "--allow-shipped",
    help = "Allow removal of built-in shipped surfaces (kotlin / kmp). '.bill-shared' is never removable.",
  ).flag(default = false)
  private val format by formatOption()

  override fun run() {
    val parsed = parseTarget(target, allowShipped)
      ?: run {
        state.result = errorResult(
          "Invalid remove target: '$target'. Expected one of: skill:<name>, platform:<slug>, addon:<path>.",
          format,
        )
        return
      }
    val absoluteRepoRoot = Path.of(repoRoot).toAbsolutePath().normalize().toString()
    val request = SkillRemovalRequest(target = parsed, repoRootAbsolutePath = absoluteRepoRoot)
    val service = SkillRemove(SkillRemoveJvmFileSystem(home = state.userHome))
    val outcome = try {
      if (dryRun) service.previewRemoval(request) else service.executeRemoval(request)
    } catch (refusal: SkillRemovalRefusedException) {
      // Refusal is part of the contract: emit a typed error and exit non-zero.
      // F-S04: scrub absolute paths from any refusal message before it reaches the user.
      state.result = errorResult(
        SkillRemoveErrorSanitizer.sanitize(refusal.message.orEmpty(), absoluteRepoRoot),
        format,
      )
      return
    }
    state.result = when (outcome) {
      is SkillRemovalResult.Preview -> previewResult(outcome, format)
      is SkillRemovalResult.Success -> successResult(outcome, format)
      is SkillRemovalResult.Failed -> failedResult(outcome, absoluteRepoRoot, format)
    }
  }

  private fun parseTarget(raw: String, allowShipped: Boolean): SkillRemovalTarget? {
    val (kind, value) = raw.substringBefore(':', missingDelimiterValue = "") to
      raw.substringAfter(':', missingDelimiterValue = "")
    if (kind.isBlank() || value.isBlank()) return null
    return when (kind) {
      "skill" -> SkillRemovalTarget.HorizontalSkill(skillName = value, allowShipped = allowShipped)
      "platform" -> SkillRemovalTarget.PlatformPack(platform = value, allowShipped = allowShipped)
      "addon" -> SkillRemovalTarget.AddOn(relativePath = value)
      else -> null
    }
  }

  private fun previewResult(preview: SkillRemovalResult.Preview, format: CliFormat): CliExecutionResult {
    val payload = mapOf(
      "status" to "preview",
      "filesystem_paths" to preview.preview.filesystemPaths,
      "manifest_edits" to preview.preview.manifestEdits.map {
        mapOf("manifest" to it.manifestPath, "kind" to it.editKind.name, "detail" to it.detail)
      },
      "agent_symlink_unlinks" to preview.preview.agentSymlinkUnlinks.map {
        mapOf("provider" to it.provider.name, "path" to it.path)
      },
      "readme_catalog_edits" to preview.preview.readmeCatalogEdits.map {
        mapOf("readme" to it.readmePath, "kind" to it.kind.name)
      },
      "cascaded_skill_names" to preview.preview.cascadedSkillNames,
      "skill_dir_root" to preview.preview.skillDirRoot,
    )
    return CliExecutionResult(exitCode = 0, stdout = CliOutput.emit(payload, format), payload = payload)
  }

  private fun successResult(success: SkillRemovalResult.Success, format: CliFormat): CliExecutionResult {
    val payload = mapOf(
      "status" to "ok",
      "removed_paths" to success.removedPaths,
      "edited_manifests" to success.editedManifests,
      "unlinked_symlinks" to success.unlinkedSymlinks,
    )
    return CliExecutionResult(exitCode = 0, stdout = CliOutput.emit(payload, format), payload = payload)
  }

  private fun failedResult(
    failed: SkillRemovalResult.Failed,
    repoRootAbsolutePath: String,
    format: CliFormat,
  ): CliExecutionResult {
    // F-S04: scrub absolute paths so the CLI output never leaks the user's repo location to a
    // shared log aggregator.
    val payload = mapOf(
      "status" to "error",
      "exception" to failed.exceptionName,
      "error" to SkillRemoveErrorSanitizer.sanitize(failed.exceptionMessage, repoRootAbsolutePath),
      "rollback_complete" to failed.rollbackComplete,
    )
    return CliExecutionResult(exitCode = 1, stdout = CliOutput.emit(payload, format), payload = payload)
  }

  private fun errorResult(message: String, format: CliFormat): CliExecutionResult {
    val payload = mapOf("status" to "error", "error" to message)
    return CliExecutionResult(exitCode = 1, stdout = CliOutput.emit(payload, format), payload = payload)
  }
}
