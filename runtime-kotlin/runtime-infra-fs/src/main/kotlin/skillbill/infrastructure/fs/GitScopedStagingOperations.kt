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
    normalized.chunked(PATHSPEC_BATCH_SIZE).forEach { batch ->
      // `--all` scoped to explicit pathspecs, so a deleted owned path is staged as a deletion
      // instead of being silently skipped. Scope stays the listed paths; there is no `-A` here.
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
}
