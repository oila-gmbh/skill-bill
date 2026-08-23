package skillbill.infrastructure.fs

import skillbill.ports.workflow.ScopedStagingGitOperations
import skillbill.ports.workflow.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.Path

internal const val GIT_NUL: Char = '\u0000'

// git's own removal record for `update-index --index-info`: mode 0 with the null blob drops the entry.
private const val INDEX_REMOVAL_MODE = "0"
private const val INDEX_REMOVAL_OBJECT = "0000000000000000000000000000000000000000"

// Keeps a single `git add` invocation clear of the platform argument-length limit. An inventory
// larger than this is staged across several invocations rather than being truncated.
private const val PATHSPEC_BATCH_SIZE = 200

/**
 * SKILL-150: stages, snapshots, and restores exactly the paths a checkpoint owns.
 *
 * Every path crosses the boundary as a literal argument after `--` or as a NUL-delimited record on
 * stdin. Nothing here interpolates a path into a shell string or a whitespace-joined argument, so
 * spaces, quotes, newlines, and non-ASCII bytes survive unchanged in both directions.
 */
internal object GitScopedStagingOperations : ScopedStagingGitOperations {
  override fun stagePaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult {
    val normalized = paths.filter(String::isNotBlank).distinct()
    if (normalized.isEmpty()) return WorkflowGitOperationResult(status = "ok", value = "")
    val resolved = resolveStageablePaths(repoRoot, normalized)
    if (!resolved.ok) return resolved
    val stageable = resolved.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank)
    stageable.chunked(PATHSPEC_BATCH_SIZE).forEach { batch ->
      // `--all` scoped to explicit pathspecs, so a deleted owned path still present in the index is
      // staged as a deletion instead of being silently skipped. Scope stays the listed paths; there
      // is no `-A` here.
      val staged = runGitCommand(repoRoot, listOf("add", "--all", "--") + batch)
      if (!staged.ok) return staged
    }
    return WorkflowGitOperationResult(status = "ok", value = "")
  }

  override fun captureIndexState(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult {
    val normalized = paths.filter(String::isNotBlank).distinct()
    if (normalized.isEmpty()) return WorkflowGitOperationResult(status = "ok", value = "")
    val entries = mutableListOf<String>()
    normalized.chunked(PATHSPEC_BATCH_SIZE).forEach { batch ->
      val listed = runGitCommand(repoRoot, listOf("ls-files", "--stage", "-z", "--") + batch)
      if (!listed.ok) return listed
      entries += listed.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank)
    }
    return WorkflowGitOperationResult(status = "ok", value = entries.joinToString(GIT_NUL.toString()))
  }

  override fun restoreIndexState(repoRoot: Path, paths: List<String>, snapshot: String): WorkflowGitOperationResult {
    val normalized = paths.filter(String::isNotBlank).distinct()
    if (normalized.isEmpty()) return WorkflowGitOperationResult(status = "ok", value = "")
    val entries = snapshot.split(GIT_NUL).filter(String::isNotBlank)
    val snapshotPaths = entries.mapNotNull(::indexEntryPath).toSet()
    // A path the snapshot does not carry was unstaged or absent before the checkpoint, so restoring
    // it means removing the entry the failed staging added — not leaving it behind.
    val removals = normalized.filterNot { it in snapshotPaths }
      .map { "$INDEX_REMOVAL_MODE $INDEX_REMOVAL_OBJECT\t$it" }
    val records = entries + removals
    if (records.isEmpty()) return WorkflowGitOperationResult(status = "ok", value = "")
    val stdin = records.joinToString(GIT_NUL.toString(), postfix = GIT_NUL.toString()).toByteArray()
    return runGitCommandWithStdin(repoRoot, listOf("update-index", "-z", "--index-info"), stdin)
  }

  override fun stagedPaths(repoRoot: Path): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "diff", "--cached", "--name-only", "-z")

  override fun pathContentIdentities(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult {
    val present = paths.filter(String::isNotBlank).distinct().sorted()
      .filter { Files.isRegularFile(repoRoot.resolve(it)) }
    if (present.isEmpty()) return WorkflowGitOperationResult(status = "ok", value = "")
    val records = mutableListOf<String>()
    present.chunked(PATHSPEC_BATCH_SIZE).forEach { batch ->
      val hashed = runGitCommand(repoRoot, listOf("hash-object", "--") + batch)
      if (!hashed.ok) return hashed
      val hashes = hashed.value.orEmpty().lineSequence().map(String::trim).filter(String::isNotBlank).toList()
      // One sha per input path, in input order. A short read means some path was not hashed, and
      // pairing the remainder by index would silently attribute one file's content to another.
      if (hashes.size != batch.size) {
        return WorkflowGitOperationResult(
          status = "error",
          error = "git hash-object returned ${hashes.size} identities for ${batch.size} paths.",
        )
      }
      records += batch.indices.map { index -> "${hashes[index]}\t${batch[index]}" }
    }
    return WorkflowGitOperationResult(status = "ok", value = records.joinToString(GIT_NUL.toString()))
  }

  // `ls-files --stage` records are `<mode> <sha> <stage>\t<path>`; the path is everything after the
  // first tab, so a path containing a tab still parses correctly.
  private fun indexEntryPath(entry: String): String? =
    entry.substringAfter('\t', missingDelimiterValue = "").takeIf(String::isNotBlank)

  private fun resolveStageablePaths(repoRoot: Path, normalized: List<String>): WorkflowGitOperationResult {
    // A pathspec that matches nothing makes `git add --all` abort the entire batch with exit 128
    // ("did not match any files"). That happens legitimately when a checkpoint owns a deletion an
    // earlier attempt already staged: the path is absent from both the worktree and the index, so it
    // has nothing left to stage. `ls-files` treats a non-matching pathspec as simply absent rather
    // than failing, so index presence is resolved here and no-op paths are filtered out first.
    // An untracked ignored file is the other abort: `git add --all -- ignored.md live.kt` exits 1
    // ("paths are ignored") even though a bare `--all` would skip the ignored path. Drop those
    // before the add; tracked files that match an ignore pattern stay stageable.
    val indexed = mutableSetOf<String>()
    for (batch in normalized.chunked(PATHSPEC_BATCH_SIZE)) {
      val listed = runGitCommand(repoRoot, listOf("ls-files", "--stage", "-z", "--") + batch)
      if (!listed.ok) return listed
      listed.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank).forEach { entry ->
        indexEntryPath(entry)?.let { indexed += it }
      }
    }
    val materialized = materializePathspecs(repoRoot, normalized)
    val presentOrIndexed = materialized.filter { Files.isRegularFile(repoRoot.resolve(it)) || it in indexed }
    val ignored = ignoredUntrackedPaths(repoRoot, presentOrIndexed)
    if (!ignored.ok) return ignored
    val ignoredSet = ignored.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank).toSet()
    val stageable = presentOrIndexed.filterNot { it in ignoredSet }
    return WorkflowGitOperationResult(status = "ok", value = stageable.joinToString(GIT_NUL.toString()))
  }

  /**
   * Porcelain may still hand us a trailing-slash directory (`?? owned/dir/`) when a caller does not
   * use `-uall`. Expand directories to their regular files so staging cannot silently drop a whole
   * untracked tree. Non-directory pathspecs pass through unchanged, including deletions that exist
   * only in the index.
   */
  private fun materializePathspecs(repoRoot: Path, paths: List<String>): List<String> {
    val materialized = LinkedHashSet<String>()
    for (raw in paths) {
      val relative = raw.trim().removeSuffix("/")
      if (relative.isEmpty()) continue
      val resolved = repoRoot.resolve(relative)
      if (Files.isDirectory(resolved)) {
        Files.walk(resolved).use { walk ->
          walk.filter { Files.isRegularFile(it) }.forEach { file ->
            materialized += repoRoot.relativize(file).toString().replace('\\', '/')
          }
        }
      } else {
        materialized += relative
      }
    }
    return materialized.toList()
  }

  private fun ignoredUntrackedPaths(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult {
    if (paths.isEmpty()) return WorkflowGitOperationResult(status = "ok", value = "")
    val ignored = mutableListOf<String>()
    for (batch in paths.chunked(PATHSPEC_BATCH_SIZE)) {
      val stdin = batch.joinToString(separator = GIT_NUL.toString(), postfix = GIT_NUL.toString()).toByteArray()
      val parsed = parseCheckIgnore(runGitProcess(repoRoot, listOf("check-ignore", "-z", "--stdin"), stdin))
      if (!parsed.ok) return parsed
      ignored += parsed.value.orEmpty().split(GIT_NUL).filter(String::isNotBlank)
    }
    return WorkflowGitOperationResult(status = "ok", value = ignored.joinToString(GIT_NUL.toString()))
  }

  private fun parseCheckIgnore(result: GitProcessResult): WorkflowGitOperationResult = when {
    result.timedOut -> WorkflowGitOperationResult(
      status = "error",
      error = "git check-ignore timed out after ${GIT_TIMEOUT_SECONDS}s.",
    )
    result.readFailure != null -> WorkflowGitOperationResult(
      status = "error",
      error = result.readFailure.message.orEmpty(),
    )
    result.exitCode == 0 -> WorkflowGitOperationResult(status = "ok", value = result.output)
    result.exitCode == 1 -> WorkflowGitOperationResult(status = "ok", value = "")
    else -> WorkflowGitOperationResult(
      status = "error",
      error = "git check-ignore failed with exit code ${result.exitCode}: ${result.output}",
    )
  }
}
