package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperationsProvider
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperations
import skillbill.ports.workflow.gitops.GoalSubtaskReviewGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperations
import skillbill.ports.workflow.gitops.RepositoryFingerprintGitOperationsProvider
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperations
import skillbill.ports.workflow.gitops.RepositoryOwnedPathsGitOperationsProvider
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperations
import skillbill.ports.workflow.gitops.RuntimePhaseFileManifestGitOperationsProvider
import skillbill.ports.workflow.gitops.ScopedStagingGitOperations
import skillbill.ports.workflow.gitops.ScopedStagingGitOperationsProvider
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperations
import skillbill.ports.workflow.gitops.SuppressionEvidenceGitOperationsProvider
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowScopedPathContent
import skillbill.ports.workflow.gitops.model.WorkflowScopedPathContentsResult
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksRequest
import skillbill.ports.workflow.gitops.model.WorkflowSelectedDiffHunksResult
import skillbill.ports.workflow.gitops.model.WorkflowWorktreeActivityResult
import skillbill.workflow.goal.model.GoalObservabilityChangedFileSummary
import skillbill.workflow.goal.model.GoalObservabilityDiffStat
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunk
import skillbill.workflow.goal.model.GoalObservabilitySelectedDiffHunks
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.io.BufferedReader


internal object GitRepositoryFingerprintOperations : RepositoryFingerprintGitOperations {
  override fun repositoryFingerprint(repoRoot: Path): WorkflowGitOperationResult {
    val head = runGitCommand(repoRoot, "rev-parse", "HEAD")
    val staged = runGitCommand(repoRoot, "diff", "--binary", "--cached")
    val unstaged = runGitCommand(repoRoot, "diff", "--binary")
    val untracked = runGitCommand(repoRoot, "ls-files", "--others", "--exclude-standard", "-z")
    val failure = listOf(head, staged, unstaged, untracked).firstOrNull { !it.ok }
    if (failure != null) return failure
    return runCatching {
      val digest = MessageDigest.getInstance("SHA-256")
      UntrackedFingerprintDigest.digestPart(digest, "head", head.value.orEmpty().toByteArray())
      UntrackedFingerprintDigest.digestPart(digest, "staged", staged.value.orEmpty().toByteArray())
      UntrackedFingerprintDigest.digestPart(digest, "unstaged", unstaged.value.orEmpty().toByteArray())
      val root = repoRoot.normalize()
      untracked.value.orEmpty().split('\u0000').filter(String::isNotBlank).sorted().forEach { path ->
        val resolved = root.resolve(path).normalize()
        require(resolved.startsWith(root)) { "Untracked path escapes repository root: $path" }
        UntrackedFingerprintDigest.digestUntrackedEntry(digest, path, resolved)
      }
      WorkflowGitOperationResult(status = "ok", value = digest.digest().joinToString("") { "%02x".format(it) })
    }.getOrElse { error ->
      WorkflowGitOperationResult(status = "error", error = "Could not fingerprint repository state: ${error.message}")
    }
  }

  override fun repositoryCheckpointFingerprint(
    repoRoot: Path,
    baseCommit: String?,
    headCommit: String,
    ownedPaths: List<String>,
  ): WorkflowGitOperationResult = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    UntrackedFingerprintDigest.digestPart(digest, "base", baseCommit.orEmpty().toByteArray())
    UntrackedFingerprintDigest.digestPart(digest, "head", headCommit.toByteArray())
    val root = repoRoot.normalize()
    ownedPaths.distinct().sorted().forEach { path ->
      val resolved = root.resolve(path).normalize()
      require(resolved.startsWith(root)) { "Checkpoint path escapes repository root: $path" }
      UntrackedFingerprintDigest.digestUntrackedEntry(digest, path, resolved)
    }
    WorkflowGitOperationResult(status = "ok", value = digest.digest().joinToString("") { "%02x".format(it) })
  }.getOrElse { error ->
    WorkflowGitOperationResult(
      status = "error",
      error = "Could not fingerprint workflow-owned repository checkpoint: ${error.message}",
    )
  }

  fun worktreeActivity(repoRoot: Path): WorkflowWorktreeActivityResult {
    val status = runGitCommand(repoRoot, "status", "--porcelain")
    if (!status.ok) {
      return WorkflowWorktreeActivityResult(status = "error", error = status.error)
    }
    val diff = combinedDiffStat(repoRoot)
    return WorkflowWorktreeActivityResult(
      status = "ok",
      changedFileSummary = parseChangedFileSummary(status.value),
      diffStat = diff,
    )
  }

  fun selectedDiffHunks(repoRoot: Path, request: WorkflowSelectedDiffHunksRequest): WorkflowSelectedDiffHunksResult {
    if (request.paths.isEmpty() || (!request.includeStaged && !request.includeUnstaged)) {
      return WorkflowSelectedDiffHunksResult(status = "ok")
    }
    val chunks = mutableListOf<GoalObservabilitySelectedDiffHunk>()
    val results = mutableListOf<WorkflowSelectedDiffHunksResult>()
    val budget = SelectedDiffBudget(request)
    if (request.includeUnstaged) {
      results += appendSelectedDiffHunks(repoRoot, request, staged = false, chunks = chunks, budget = budget)
    }
    if (
      request.includeStaged &&
      results.all(WorkflowSelectedDiffHunksResult::ok) &&
      results.none { result -> result.selectedDiffHunks.truncated }
    ) {
      results += appendSelectedDiffHunks(repoRoot, request, staged = true, chunks = chunks, budget = budget)
    }
    val errorResult = results.firstOrNull { result -> !result.ok }
    return errorResult ?: WorkflowSelectedDiffHunksResult(
      status = "ok",
      selectedDiffHunks = GoalObservabilitySelectedDiffHunks(
        hunks = chunks,
        truncated = results.any { result -> result.selectedDiffHunks.truncated },
      ),
    )
  }
}

internal object UntrackedFingerprintDigest {
  fun digestPart(digest: MessageDigest, label: String, bytes: ByteArray) {
    digestPartHeader(digest, label, bytes.size.toString())
    digest.update(bytes)
  }

  private fun digestPartHeader(digest: MessageDigest, label: String, length: String) {
    digest.update(label.toByteArray())
    digest.update(0)
    digest.update(length.toByteArray())
    digest.update(0)
  }

  // An untracked entry is fingerprinted, never trusted: symlinks and directories are recorded by
  // marker instead of followed, oversized artifacts by size and mtime instead of being read into the
  // heap, and an entry that disappeared between the `ls-files` listing and this read is a benign
  // marker rather than a failure that would block an otherwise successful phase.
  fun digestUntrackedEntry(digest: MessageDigest, path: String, resolved: Path) {
    val label = "untracked:$path"
    if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
      digestPart(digest, label, UNTRACKED_NON_REGULAR_MARKER.toByteArray())
      return
    }
    val attributes = try {
      Files.readAttributes(resolved, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (error: IOException) {
      digestPart(digest, label, "$UNTRACKED_UNREADABLE_MARKER:${error::class.simpleName}".toByteArray())
      return
    }
    if (attributes.size() > UNTRACKED_FINGERPRINT_CONTENT_MAX_BYTES) {
      digestPart(
        digest,
        label,
        "size=${attributes.size()};mtime=${attributes.lastModifiedTime().toMillis()}".toByteArray(),
      )
      return
    }
    digestUntrackedContent(digest, label, resolved, attributes.size())
  }

  private fun digestUntrackedContent(digest: MessageDigest, label: String, resolved: Path, declaredSize: Long) {
    try {
      Files.newInputStream(resolved).use { input ->
        digestPartHeader(digest, label, declaredSize.toString())
        val buffer = ByteArray(UNTRACKED_FINGERPRINT_BUFFER_BYTES)
        var read = input.read(buffer)
        while (read >= 0) {
          digest.update(buffer, 0, read)
          read = input.read(buffer)
        }
      }
    } catch (error: IOException) {
      digestPart(digest, label, "$UNTRACKED_UNREADABLE_MARKER:${error::class.simpleName}".toByteArray())
    }
  }
}

internal object GitRuntimePhaseFileManifestOperations : RuntimePhaseFileManifestGitOperations {
  override fun headCommit(repoRoot: Path): WorkflowGitOperationResult = runGitCommand(repoRoot, "rev-parse", "HEAD")

  override fun changedPathsBetweenCommits(
    repoRoot: Path,
    beforeCommit: String,
    afterCommit: String,
  ): WorkflowGitOperationResult = if (beforeCommit == afterCommit) {
    WorkflowGitOperationResult(status = "ok", value = "")
  } else {
    runGitCommand(repoRoot, "diff", "--name-only", beforeCommit, afterCommit)
  }
}

/**
 * Rename-aware base/HEAD content pairs for the validate suppression delta.
 * Inventory stays the caller's scoped path list — never porcelain-wide dirty siblings.
 */
internal object GitSuppressionEvidenceOperations : SuppressionEvidenceGitOperations {
  override fun scopedPathContentsAgainstBase(
    repoRoot: Path,
    baseRef: String,
    headPaths: List<String>,
  ): WorkflowScopedPathContentsResult {
    if (baseRef.isBlank()) {
      return WorkflowScopedPathContentsResult(
        status = "error",
        error = "Suppression evidence requires a non-blank base ref.",
      )
    }
    val scoped = headPaths.map(String::trim).filter(String::isNotEmpty).distinct()
    if (scoped.isEmpty()) {
      return WorkflowScopedPathContentsResult(status = "ok", pairs = emptyList())
    }
    val renameToBase = renameBasePaths(repoRoot, baseRef)
    if (renameToBase.status != "ok") {
      return WorkflowScopedPathContentsResult(status = "error", error = renameToBase.error)
    }
    val pairs = scoped.map { headPath ->
      val basePath = renameToBase.value[headPath] ?: headPath
      val headContent = readWorktreeContent(repoRoot, headPath)
      val baseContent = readContentAtRef(repoRoot, baseRef, basePath)
      WorkflowScopedPathContent(
        headPath = headPath,
        basePath = basePath.takeIf { baseContent != null },
        headContent = headContent,
        baseContent = baseContent,
      )
    }
    return WorkflowScopedPathContentsResult(status = "ok", pairs = pairs)
  }

  private data class RenameMapResult(
    val status: String,
    val value: Map<String, String> = emptyMap(),
    val error: String = "",
  )

  /** Maps HEAD path → base path for renames detected against [baseRef]. */
  private fun renameBasePaths(repoRoot: Path, baseRef: String): RenameMapResult {
    val diff = runGitCommand(repoRoot, "diff", "-M", "--name-status", "--find-renames", baseRef)
    if (!diff.ok) {
      return RenameMapResult(status = "error", error = diff.error.ifBlank { "git diff -M --name-status failed." })
    }
    val renames = linkedMapOf<String, String>()
    diff.value.lineSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .forEach { line ->
        val parts = line.split('\t')
        if (parts.size >= GIT_RENAME_NAME_STATUS_MIN_FIELDS && parts[0].startsWith("R")) {
          val oldPath = parts[1]
          val newPath = parts[2]
          if (oldPath.isNotBlank() && newPath.isNotBlank()) {
            renames[newPath] = oldPath
          }
        }
      }
    return RenameMapResult(status = "ok", value = renames)
  }

  private fun readWorktreeContent(repoRoot: Path, path: String): String? {
    val resolved = repoRoot.resolve(path).normalize()
    if (!resolved.startsWith(repoRoot.normalize())) return null
    return try {
      if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
        null
      } else {
        Files.readString(resolved)
      }
    } catch (_: IOException) {
      null
    }
  }

  private fun readContentAtRef(repoRoot: Path, baseRef: String, path: String): String? {
    val result = runGitCommand(repoRoot, "show", "$baseRef:$path")
    return if (result.ok) result.value else null
  }
}

internal fun combinedDiffStat(repoRoot: Path): GoalObservabilityDiffStat {
  val unstaged = runCatchingDiffStat(repoRoot, "diff", "--numstat")
  val staged = runCatchingDiffStat(repoRoot, "diff", "--cached", "--numstat")
  return GoalObservabilityDiffStat(
    filesChanged = unstaged.filesChanged + staged.filesChanged,
    insertions = unstaged.insertions + staged.insertions,
    deletions = unstaged.deletions + staged.deletions,
  )
}

internal fun runCatchingDiffStat(repoRoot: Path, vararg args: String): GoalObservabilityDiffStat {
  val result = runGitForActivity(repoRoot, args.toList())
  return if (result.ok) parseDiffStat(result.value) else GoalObservabilityDiffStat(0, 0, 0)
}

internal fun parseChangedFileSummary(statusOutput: String): GoalObservabilityChangedFileSummary {
  var added = 0
  var modified = 0
  var deleted = 0
  var renamed = 0
  var untracked = 0
  val paths = mutableListOf<String>()
  statusOutput.lineSequence()
    .map(String::trimEnd)
    .filter { line -> line.length >= GIT_STATUS_MIN_LENGTH }
    .forEach { line ->
      val status = line.take(GIT_STATUS_CODE_LENGTH)
      val path = line.drop(GIT_STATUS_PATH_OFFSET).substringAfterLast(" -> ").trim()
      if (path.isNotBlank()) paths += path
      when {
        status == "??" -> {
          added += 1
          untracked += 1
        }
        'R' in status -> renamed += 1
        'D' in status -> deleted += 1
        'A' in status -> added += 1
        'M' in status -> modified += 1
      }
    }
  return GoalObservabilityChangedFileSummary(
    total = paths.size,
    added = added,
    modified = modified,
    deleted = deleted,
    renamed = renamed,
    untracked = untracked,
    samplePaths = paths.take(GIT_CHANGED_FILE_SAMPLE_LIMIT),
  )
}

internal fun parseDiffStat(numstatOutput: String): GoalObservabilityDiffStat {
  var filesChanged = 0
  var insertions = 0
  var deletions = 0
  numstatOutput.lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .forEach { line ->
      val parts = line.split(Regex("\\s+"), limit = GIT_NUMSTAT_PART_LIMIT)
      if (parts.size >= GIT_NUMSTAT_PART_LIMIT) {
        filesChanged += 1
        insertions += parts[0].toIntOrNull() ?: 0
        deletions += parts[1].toIntOrNull() ?: 0
      }
    }
  return GoalObservabilityDiffStat(filesChanged = filesChanged, insertions = insertions, deletions = deletions)
}

