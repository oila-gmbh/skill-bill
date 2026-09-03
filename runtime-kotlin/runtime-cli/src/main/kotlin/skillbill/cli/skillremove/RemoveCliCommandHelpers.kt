
package skillbill.cli.skillremove

import skillbill.cli.kernel.CliOutput
import skillbill.cli.model.CliExecutionResult
import skillbill.cli.model.CliFormat
import skillbill.domain.skillremove.SkillRemovalRefusedException
import skillbill.domain.skillremove.SkillRemoveErrorSanitizer
import skillbill.domain.skillremove.model.SkillRemovalRefusalReason
import skillbill.domain.skillremove.model.SkillRemovalRequest
import skillbill.domain.skillremove.model.SkillRemovalResult
import skillbill.domain.skillremove.model.SkillRemovalTarget
import java.nio.file.Path

internal fun executeRemoveCommand(request: RemoveCommandExecutionRequest): CliExecutionResult {
  if (request.rawTarget == null) {
    return errorResult(removeUsageMessage(), request.format)
  }
  val parsed = parseRemoveTarget(request.rawTarget, request.allowShipped)
    ?: return errorResult(
      "Invalid remove target: '${request.rawTarget}'.\n\n${removeUsageMessage()}",
      request.format,
    )
  val absoluteRepoRoot = Path.of(request.repoRoot).toAbsolutePath().normalize().toString()
  val removalRequest = SkillRemovalRequest(
    target = parsed,
    repoRootAbsolutePath = absoluteRepoRoot,
    userHomeAbsolutePath = request.inputs.userHome.toAbsolutePath().normalize().toString(),
    environment = request.inputs.environment,
  )
  val outcome = try {
    if (request.dryRun) {
      request.skillRemoveService.previewRemoval(removalRequest)
    } else {
      request.skillRemoveService.executeRemoval(removalRequest)
    }
  } catch (refusal: SkillRemovalRefusedException) {
    return errorResult(
      refusalErrorMessage(refusal, request.rawTarget, absoluteRepoRoot),
      request.format,
    )
  }
  return when (outcome) {
    is SkillRemovalResult.Preview -> previewResult(outcome, request.format)
    is SkillRemovalResult.Success -> successResult(outcome, request.format)
    is SkillRemovalResult.Failed -> failedResult(outcome, absoluteRepoRoot, request.format)
  }
}

internal fun parseRemoveTarget(raw: String, allowShipped: Boolean): SkillRemovalTarget? {
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

internal fun refusalErrorMessage(
  refusal: SkillRemovalRefusedException,
  rawTarget: String,
  repoRootAbsolutePath: String,
): String {
  val sanitized = SkillRemoveErrorSanitizer.sanitize(refusal.message.orEmpty(), repoRootAbsolutePath)
  if (refusal.refusalReason != SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED) {
    return sanitized
  }
  return """
    $sanitized

    Why this is protected:
      bill-* skills are shipped product surfaces.
      Removing them is a maintainer-only operation because it changes the workflow set installed for every agent.

    To preview the maintainer removal:
      skill-bill remove $rawTarget --dry-run --allow-shipped

    To remove it after reviewing the preview:
      skill-bill remove $rawTarget --allow-shipped
  """.trimIndent()
}

internal fun previewResult(preview: SkillRemovalResult.Preview, format: CliFormat): CliExecutionResult {
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

internal fun successResult(success: SkillRemovalResult.Success, format: CliFormat): CliExecutionResult {
  val payload = mapOf(
    "status" to "ok",
    "removed_paths" to success.removedPaths,
    "edited_manifests" to success.editedManifests,
    "unlinked_symlinks" to success.unlinkedSymlinks,
  )
  return CliExecutionResult(exitCode = 0, stdout = CliOutput.emit(payload, format), payload = payload)
}

internal fun failedResult(
  failed: SkillRemovalResult.Failed,
  repoRootAbsolutePath: String,
  format: CliFormat,
): CliExecutionResult {
  val payload = mapOf(
    "status" to "error",
    "exception" to failed.exceptionName,
    "error" to SkillRemoveErrorSanitizer.sanitize(failed.exceptionMessage, repoRootAbsolutePath),
    "rollback_complete" to failed.rollbackComplete,
  )
  return CliExecutionResult(exitCode = 1, stdout = CliOutput.emit(payload, format), payload = payload)
}

internal fun errorResult(message: String, format: CliFormat): CliExecutionResult {
  val payload = mapOf("status" to "error", "error" to message)
  return CliExecutionResult(exitCode = 1, stdout = CliOutput.emit(payload, format), payload = payload)
}

internal fun removeUsageMessage(): String = """
  Missing remove target.

  Examples:
    skill-bill remove skill:bill-my-skill --dry-run
    skill-bill remove platform:my-platform --dry-run
    skill-bill remove addon:platform-packs/kmp/addons/my-addon.md --dry-run

  Target forms:
    skill:<name>
    platform:<slug>
    addon:<path>

  Use --dry-run first to preview the exact files, README edits, and agent links that will be removed.
""".trimIndent()
