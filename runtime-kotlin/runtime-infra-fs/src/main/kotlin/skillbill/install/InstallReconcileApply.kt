package skillbill.install

import skillbill.error.ReconciliationApplyRefusedError
import skillbill.error.ReconciliationConflictError
import skillbill.install.model.BaselineManifest
import skillbill.install.model.SkillReconciliationOutcome
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/*
 * SKILL-76 Subtask 2: runtime-owned per-skill reconcile APPLY (file operations). Split
 * out of [computeReconciliationPlan]'s policy file so the file-IO helpers do not push the
 * policy file over detekt's function-count threshold. Reuses the policy's
 * [enumerateSkills] + [classifyReconciliation] so apply derives the SAME plan the compute
 * path produces (never a second classification scheme). [ReconcileApplyOutput] lives in
 * the policy file alongside the other reconcile data models.
 */

/**
 * Recompute the plan ONCE from the same upstream/local/baseline inputs the compute path
 * uses, gate on conflicts, then replace ONLY the changed skill dirs in the live (`local`)
 * tree from upstream. Because the live tree is the base and untouched skills are never
 * written, keep-local and locally-authored skills are preserved by construction
 * (locally-authored is NEVER deleted). Returns the plan + the list of skill-relative paths
 * actually installed.
 *
 * Atomicity: each skill dir is replaced individually via [replaceSkillDirAtomically]
 * (stage upstream into a temp sibling under the live skill's parent, RENAME the existing
 * live dir aside to a backup, move the staged dir into place, then drop the backup —
 * restoring the backup if the move-in fails) so an interrupted apply never destroys the
 * live skill and never performs an all-or-nothing whole-tree `rm`.
 */
internal fun applyReconciliation(
  upstream: ReconcileSourceRoots,
  local: ReconcileSourceRoots,
  home: Path,
  baseline: BaselineManifest,
  acceptConflicts: Boolean,
): ReconcileApplyOutput {
  val upstreamSkills = enumerateSkills(upstream, home)
  val localSkills = enumerateSkills(local, home)
  val plan = classifyReconciliation(upstreamSkills, localSkills, baseline)

  if (plan.hasConflicts && !acceptConflicts) {
    throw ReconciliationApplyRefusedError(plan.conflicts.map { it.skillRelativePath })
  }

  val installedPaths = mutableListOf<String>()
  plan.outcomes.forEach { outcome ->
    if (!outcomeInstallsUpstream(outcome)) {
      return@forEach
    }
    val skillPath = outcome.skillRelativePath
    val upstreamDir = upstreamSkills[skillPath]?.sourceDir
      ?: throw ReconciliationConflictError(
        skillRelativePath = skillPath,
        reason = "apply requires the upstream skill dir but it was not enumerated.",
      )
    val liveDir = liveSkillDir(local, skillPath)
    replaceSkillDirAtomically(upstreamDir, liveDir)
    installedPaths.add(skillPath)
  }
  // The per-skill swap above owns ALL platform-pack skill dirs. The remaining platform-pack
  // files (platform.yaml, addon markdown, pack-level metadata) are shared, non-skill-keyed
  // metadata with no baseline; adopt them always from upstream (idempotent: a byte-identical
  // copy is a no-op). The shell no longer blanket-copies the pack tree, so this is the SOLE
  // writer of the platform-packs tree.
  adoptPlatformPackNonSkillFiles(upstream, local, upstreamSkills)
  return ReconcileApplyOutput(plan = plan, installedPaths = installedPaths)
}

/**
 * Copy every file under the UPSTREAM platform-packs tree that is NOT inside an enumerated
 * skill's sourceDir into the LOCAL platform-packs tree. Enumerated platform-pack skill dirs
 * are excluded because the per-skill swap is their sole writer (a blanket copy would defeat
 * keep-local/conflict). Non-skill files have no baseline and are adopt-always; a copy that
 * lands byte-identical bytes is an idempotent no-op. Best-effort and non-fatal: a missing
 * upstream pack tree just means nothing to adopt.
 */
private fun adoptPlatformPackNonSkillFiles(
  upstream: ReconcileSourceRoots,
  local: ReconcileSourceRoots,
  upstreamSkills: Map<String, ReconcileSkillEntry>,
) {
  val upstreamPacks = upstream.platformPacksRoot.toAbsolutePath().normalize()
  if (!Files.isDirectory(upstreamPacks)) {
    return
  }
  val packSkillDirs = upstreamSkills.values
    .map { it.sourceDir }
    .filter { it.startsWith(upstreamPacks) }
  val livePacks = local.platformPacksRoot.toAbsolutePath().normalize()
  Files.walk(upstreamPacks).use { stream ->
    stream.forEach { path ->
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        return@forEach
      }
      if (packSkillDirs.any { skillDir -> path.startsWith(skillDir) }) {
        // Inside an enumerated pack skill: owned by the per-skill swap, skip.
        return@forEach
      }
      val dest = livePacks.resolve(upstreamPacks.relativize(path).toString())
      dest.parent?.let(Files::createDirectories)
      Files.copy(
        path,
        dest,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
        LinkOption.NOFOLLOW_LINKS,
      )
    }
  }
}

/** adopt / new-upstream / (accepted) conflict all install the upstream skill dir. */
private fun outcomeInstallsUpstream(outcome: SkillReconciliationOutcome): Boolean = when (outcome) {
  is SkillReconciliationOutcome.Adopt -> true
  is SkillReconciliationOutcome.NewUpstream -> true
  is SkillReconciliationOutcome.Conflict -> true
  is SkillReconciliationOutcome.KeepLocal -> false
  is SkillReconciliationOutcome.LocallyAuthored -> false
}

private const val SKILLS_PREFIX = "skills/"
private const val PLATFORM_PACKS_PREFIX = "platform-packs/"

/**
 * Resolve the live (target) skill dir for a skill-relative path under the LOCAL roots.
 * Base skills key off the skills root, platform-pack skills off the platform-packs root,
 * each under a `skills/` or `platform-packs/` category prefix.
 */
private fun liveSkillDir(local: ReconcileSourceRoots, skillRelativePath: String): Path = when {
  skillRelativePath.startsWith(SKILLS_PREFIX) ->
    local.skillsRoot.resolve(skillRelativePath.removePrefix(SKILLS_PREFIX))
  skillRelativePath.startsWith(PLATFORM_PACKS_PREFIX) ->
    local.platformPacksRoot.resolve(skillRelativePath.removePrefix(PLATFORM_PACKS_PREFIX))
  else -> throw ReconciliationConflictError(
    skillRelativePath = skillRelativePath,
    reason = "unrecognized skill-relative category prefix.",
  )
}

/**
 * Replace [liveDir] with a deep copy of [upstreamDir] WITHOUT a destructive delete window.
 * The upstream tree is first copied into a temp sibling under the live dir's PARENT (same
 * filesystem, so ATOMIC_MOVE works). Then, if a live dir exists, it is RENAMED ASIDE to a
 * sibling backup (never deleted up front) and only the staged copy is moved into place. On
 * success the backup is removed in `finally`; if the move-in fails, the backup is moved
 * back to [liveDir] (best-effort restore) before the error propagates — so a crash or
 * failure never leaves the live skill destroyed. A best-effort plain-move fallback handles
 * filesystems that refuse ATOMIC_MOVE.
 */
private fun replaceSkillDirAtomically(upstreamDir: Path, liveDir: Path) {
  val parent = liveDir.toAbsolutePath().normalize().parent
    ?: throw ReconciliationConflictError(
      skillRelativePath = liveDir.toString(),
      reason = "live skill dir has no parent directory.",
    )
  Files.createDirectories(parent)
  val staged = Files.createTempDirectory(parent, ".reconcile-stage-")
  val stagedSkill = staged.resolve(liveDir.fileName.toString())
  copyTreeDeep(upstreamDir, stagedSkill)
  val hasLive = Files.exists(liveDir, LinkOption.NOFOLLOW_LINKS)
  val backup = if (hasLive) Files.createTempDirectory(parent, ".reconcile-backup-") else null
  // createTempDirectory makes the backup target dir; remove it so the rename-aside lands
  // the live dir AT that path rather than nesting inside it.
  backup?.let(Files::deleteIfExists)
  try {
    if (hasLive && backup != null) {
      moveDir(liveDir, backup)
    }
    try {
      moveDir(stagedSkill, liveDir)
    } catch (error: IOException) {
      // Restore the renamed-aside live dir so a failed swap never destroys the install.
      if (backup != null && Files.exists(backup, LinkOption.NOFOLLOW_LINKS) &&
        !Files.exists(liveDir, LinkOption.NOFOLLOW_LINKS)
      ) {
        runCatching { moveDir(backup, liveDir) }
      }
      throw error
    }
  } finally {
    deleteTreeRecursively(staged)
    backup?.let(::deleteTreeRecursively)
  }
}

private fun copyTreeDeep(source: Path, target: Path) {
  Files.walk(source).use { stream ->
    stream.forEach { path ->
      val rel = source.relativize(path)
      val dest = target.resolve(rel.toString())
      if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectories(dest)
      } else {
        dest.parent?.let(Files::createDirectories)
        Files.copy(
          path,
          dest,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.COPY_ATTRIBUTES,
          LinkOption.NOFOLLOW_LINKS,
        )
      }
    }
  }
}

private fun moveDir(source: Path, target: Path) {
  try {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(source, target)
  }
}

private fun deleteTreeRecursively(root: Path) {
  if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  Files.walk(root).use { stream ->
    stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}
