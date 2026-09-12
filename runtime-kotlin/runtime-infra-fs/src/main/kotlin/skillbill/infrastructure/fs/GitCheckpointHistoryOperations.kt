package skillbill.infrastructure.fs

import skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal object GitCheckpointHistoryOperations : CheckpointHistoryGitOperations {
  override fun createScopedCheckpoint(
    repoRoot: Path,
    paths: List<String>,
    message: String,
  ): WorkflowGitOperationResult {
    val normalized = paths.filter(String::isNotBlank).distinct().sorted()
    if (normalized.isEmpty()) return runGitCommand(repoRoot, "rev-parse", "HEAD")
    return withTemporaryIndex { environment ->
      val tree = writeScopedTree(repoRoot, normalized, environment)
      if (tree !is WorkflowGitOperationResult.Ok) return@withTemporaryIndex tree
      runGitCommandWithEnvironment(
        repoRoot,
        listOf("commit-tree", tree.value.orEmpty().trim(), "-p", "HEAD", "-m", message),
        environment + mapOf(
          "GIT_AUTHOR_DATE" to "@0 +0000",
          "GIT_COMMITTER_DATE" to "@0 +0000",
        ),
      )
    }
  }

  override fun currentScopedContentFingerprint(repoRoot: Path, paths: List<String>): WorkflowGitOperationResult =
    withTemporaryIndex { environment ->
      writeScopedTree(repoRoot, paths.filter(String::isNotBlank).distinct().sorted(), environment)
    }

  override fun retainedScopedContentFingerprint(repoRoot: Path, checkpointId: String): WorkflowGitOperationResult =
    runGitCommand(repoRoot, "rev-parse", "${checkpointId.trim()}^{tree}")

  override fun amendHeadCommit(
    repoRoot: Path,
    expectedOwnedHeadSha: String,
    replacementMessage: String?,
    allowUnchangedIndex: Boolean,
  ): WorkflowGitOperationResult {
    val expected = expectedOwnedHeadSha.trim()
    val precondition = gitCheckpointProtectedBranchFailure(repoRoot)
      ?: gitCheckpointOwnedHeadFailure(repoRoot, expected)
      ?: if (allowUnchangedIndex) null else gitCheckpointStagedContentFailure(repoRoot, expected)
    precondition?.let { return it }
    val message = replacementMessage?.trim()
    val amendArgs = if (message.isNullOrBlank()) {
      listOf("commit", "--amend", "--no-edit")
    } else {
      listOf("commit", "--amend", "-m", message)
    }
    val amended = runGitCommand(repoRoot, amendArgs)
    if (amended !is WorkflowGitOperationResult.Ok) return amended
    return runGitCommand(repoRoot, "rev-parse", "HEAD")
  }

  override fun headCommitMessage(repoRoot: Path): WorkflowGitOperationResult {
    val message = runGitCommand(repoRoot, "log", "-1", "--format=%B")
    if (message !is WorkflowGitOperationResult.Ok) return message
    return WorkflowGitOperationResult.Ok(value = message.value.orEmpty())
  }

  override fun updateRef(
    repoRoot: Path,
    namespacePrefix: String,
    refName: String,
    targetSha: String,
  ): WorkflowGitOperationResult {
    val ref = gitCheckpointValidatedRef(namespacePrefix, refName)
      ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val target = targetSha.trim()
    if (target.isBlank()) {
      return WorkflowGitOperationResult.Failed(error = "A target sha is required to write ref '$ref'.")
    }
    return runGitCommand(repoRoot, "update-ref", ref, target).withValue(ref)
  }

  override fun resolveRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult {
    val ref = gitCheckpointValidatedRef(namespacePrefix, refName)
      ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val resolved = runGitCommand(repoRoot, "for-each-ref", "--format=%(objectname)", ref)
    if (resolved !is WorkflowGitOperationResult.Ok) {
      return WorkflowGitOperationResult.Failed(
        error = "Ref '$ref' could not be looked up (${resolved.error}).",
      )
    }
    return WorkflowGitOperationResult.Ok(value = resolved.value.orEmpty().trim())
  }

  override fun listRefs(repoRoot: Path, namespacePrefix: String): WorkflowGitOperationResult {
    val prefix = namespacePrefix.trim()
    if (prefix.isBlank()) {
      return WorkflowGitOperationResult.Failed(error = "A ref namespace prefix is required.")
    }
    return runGitCommand(
      repoRoot,
      "for-each-ref",
      "--format=%(objectname)%00%(refname)%00",
      prefix,
    )
  }

  override fun deleteRef(repoRoot: Path, namespacePrefix: String, refName: String): WorkflowGitOperationResult {
    val ref = gitCheckpointValidatedRef(namespacePrefix, refName)
      ?: return gitCheckpointRejectedRef(namespacePrefix, refName)
    val existing = runGitCommand(repoRoot, "rev-parse", "--verify", "--quiet", ref)
    if (existing !is WorkflowGitOperationResult.Ok || existing.value.orEmpty().isBlank()) {
      return WorkflowGitOperationResult.Ok(value = ref)
    }
    return runGitCommand(repoRoot, "update-ref", "-d", ref).withValue(ref)
  }

  private fun writeScopedTree(
    repoRoot: Path,
    paths: List<String>,
    environment: Map<String, String>,
  ): WorkflowGitOperationResult {
    val read = runGitCommandWithEnvironment(repoRoot, listOf("read-tree", "HEAD"), environment)
    if (read !is WorkflowGitOperationResult.Ok) return read
    if (paths.isNotEmpty()) {
      val tracked = runGitCommandWithEnvironment(
        repoRoot,
        listOf("--literal-pathspecs", "ls-files", "--cached", "-z", "--") + paths,
        environment,
      )
      if (tracked !is WorkflowGitOperationResult.Ok) return tracked
      val trackedPaths = tracked.value.orEmpty().split('\u0000').toSet()
      val present = paths.filter { it in trackedPaths || Files.exists(repoRoot.resolve(it), LinkOption.NOFOLLOW_LINKS) }
      val staged = if (present.isEmpty()) {
        WorkflowGitOperationResult.Ok("")
      } else {
        runGitCommandWithEnvironment(
          repoRoot,
          listOf("--literal-pathspecs", "add", "--all", "--") + present,
          environment,
        )
      }
      if (staged !is WorkflowGitOperationResult.Ok) return staged
    }
    return runGitCommandWithEnvironment(repoRoot, listOf("write-tree"), environment)
  }

  private fun <T> withTemporaryIndex(action: (Map<String, String>) -> T): T {
    val index = Files.createTempFile("skill-bill-audit-repair-index-", ".tmp")
    Files.deleteIfExists(index)
    return try {
      action(mapOf("GIT_INDEX_FILE" to index.toString()))
    } finally {
      Files.deleteIfExists(index)
    }
  }
}
