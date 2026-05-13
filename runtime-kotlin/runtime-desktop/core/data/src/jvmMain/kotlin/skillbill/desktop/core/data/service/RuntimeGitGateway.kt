package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.ChangedFile
import skillbill.desktop.core.domain.model.ChangedFileGroup
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
import skillbill.desktop.core.domain.model.GitAheadBehind
import skillbill.desktop.core.domain.model.GitOperationResult
import skillbill.desktop.core.domain.model.GitPublishingStatus
import skillbill.desktop.core.domain.model.GitPushTarget
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.SourceControlStatus
import skillbill.desktop.core.domain.service.GitGateway
import skillbill.scaffold.discoverGeneratedArtifactFiles
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.relativeTo

@Inject
@SingleIn(UserScope::class)
class RuntimeGitGateway : GitGateway {
  override fun statusFor(session: RepoSession?): SourceControlStatus {
    // F-C701: this is a pure derivation; it does NOT shell out to git. The VM holds a cached branch
    // label sourced from ChangesSnapshot.branchLabel (populated by snapshotFor) and only calls this
    // method as a convenience when assembling state. If invoked off a snapshot, the branch label
    // falls back to "Repository loaded" until the next snapshotFor finishes.
    if (session == null) {
      return SourceControlStatus.empty
    }
    if (!session.isRecognizedSkillBillRepo) {
      return SourceControlStatus(
        branchLabel = "Invalid repository",
        summary = session.loadStatus.message,
      )
    }
    val issueSummary =
      if (session.loadStatus.issueCount == 0) {
        "Runtime validation passed"
      } else {
        "Runtime validation reported ${session.loadStatus.issueCount} issue(s)"
      }
    return SourceControlStatus(branchLabel = "Repository loaded", summary = issueSummary)
  }

  override fun snapshotFor(session: RepoSession?): ChangesSnapshot {
    val root = sessionRoot(session) ?: return ChangesSnapshot.empty
    return runCatching { readSnapshot(root) }
      .fold(
        onSuccess = { snapshot -> snapshot },
        onFailure = { error ->
          // AC11: surface error without changing any other app state. The caller observes a snapshot
          // with errorMessage populated; the rest of the slice can be reset by the VM.
          ChangesSnapshot(files = emptyList(), errorMessage = describe(error))
        },
      )
  }

  override fun diffFor(session: RepoSession?, path: String, staged: Boolean): String {
    val root = sessionRoot(session) ?: return ""
    if (path.isBlank()) {
      return ""
    }
    return runCatching { readDiff(root, path, staged) }.getOrDefault("")
  }

  override fun recentCommits(session: RepoSession?, limit: Int, pathFilter: String?): List<CommitEntry> {
    val root = sessionRoot(session) ?: return emptyList()
    if (limit <= 0) {
      return emptyList()
    }
    return runCatching { readCommits(root, limit, pathFilter?.trim()?.takeIf(String::isNotEmpty)) }
      .getOrDefault(emptyList())
  }

  override fun stage(session: RepoSession?, paths: List<String>): ChangesSnapshot {
    val root = sessionRoot(session) ?: return ChangesSnapshot.empty
    if (paths.isEmpty()) {
      return snapshotFor(session)
    }
    val outcome = runCatching { runGit(root, listOf("add", "--") + paths) }
    return outcome.fold(
      onSuccess = { result ->
        if (result.exitCode == 0) {
          // Success: re-read the snapshot so the new groupings reflect the stage.
          snapshotFor(session)
        } else {
          // F-A02: on stage failure, do NOT re-call snapshotFor (double-fork hides deeper failures).
          // Return a failure-marker snapshot that the VM overlays onto its existing files.
          ChangesSnapshot.failed(describeGitFailure(result))
        }
      },
      // F-A02: on process-level failure, surface the error only; the VM keeps its prior file list.
      onFailure = { error -> ChangesSnapshot.failed(describe(error)) },
    )
  }

  override fun unstage(session: RepoSession?, paths: List<String>): ChangesSnapshot {
    val root = sessionRoot(session) ?: return ChangesSnapshot.empty
    if (paths.isEmpty()) {
      return snapshotFor(session)
    }
    val outcome = runCatching { runGit(root, listOf("restore", "--staged", "--") + paths) }
    return outcome.fold(
      onSuccess = { result ->
        if (result.exitCode == 0) {
          snapshotFor(session)
        } else {
          // F-A02: see stage() above.
          ChangesSnapshot.failed(describeGitFailure(result))
        }
      },
      onFailure = { error -> ChangesSnapshot.failed(describe(error)) },
    )
  }

  override fun publishingStatus(session: RepoSession?): GitPublishingStatus {
    val root = sessionRoot(session) ?: return GitPublishingStatus.empty
    return runCatching { readPublishingStatus(root) }
      .getOrElse { error -> GitPublishingStatus(errorMessage = describe(error)) }
  }

  override fun commit(session: RepoSession?, message: String): GitOperationResult {
    val root = sessionRoot(session) ?: return GitOperationResult.failed("Open a Git repository before committing.")
    val trimmed = message.trim()
    if (trimmed.isBlank()) {
      return GitOperationResult.failed("Commit message is required.")
    }
    val result = runCatching { runGit(root, listOf("commit", "-m", trimmed)) }
      .getOrElse { error -> return GitOperationResult.failed(describe(error)) }
    return if (result.exitCode == 0) {
      GitOperationResult.success
    } else {
      GitOperationResult.failed(describeGitFailure(result))
    }
  }

  override fun push(session: RepoSession?, target: GitPushTarget): GitOperationResult {
    val root = sessionRoot(session) ?: return GitOperationResult.failed("Open a Git repository before pushing.")
    if (!isSafeGitRemoteName(target.remoteName)) {
      return GitOperationResult.failed("Push remote name is invalid.")
    }
    if (!isSafeGitBranchName(target.branchName)) {
      return GitOperationResult.failed("Push branch name is invalid.")
    }
    val currentBranch = currentBranch(root)
    if (currentBranch != target.expectedCurrentBranch) {
      return GitOperationResult.failed(
        "Current branch changed from ${target.expectedCurrentBranch} to ${currentBranch ?: "detached HEAD"}. " +
          "Refresh Git status before pushing.",
      )
    }
    val result = runCatching {
      runGit(
        root,
        listOf("push", target.remoteName, "HEAD:refs/heads/${target.branchName}"),
        hardeningFlags = GIT_PUSH_HARDENING_FLAGS,
      )
    }.getOrElse { error -> return GitOperationResult.failed(describe(error)) }
    return if (result.exitCode == 0) {
      GitOperationResult.success
    } else {
      GitOperationResult.failed(describePushFailure(root, target, result))
    }
  }

  private fun sessionRoot(session: RepoSession?): Path? {
    if (session == null || !session.isRecognizedSkillBillRepo) {
      return null
    }
    return resolveRepoPath(session.repoPath)
  }

  private fun readSnapshot(root: Path): ChangesSnapshot {
    val branchLabel = currentBranch(root) ?: "Repository loaded"
    val result = runGit(root, listOf("status", "--porcelain=v1", "-z"))
    if (result.exitCode != 0) {
      return ChangesSnapshot(
        files = emptyList(),
        branchLabel = branchLabel,
        errorMessage = describeGitFailure(result),
      )
    }
    val generatedSet = runCatching {
      discoverGeneratedArtifactFiles(root)
        .map { artifact -> artifact.path.toAbsolutePath().normalize().relativeTo(root).portablePath() }
        .toSet()
    }.getOrDefault(emptySet())
    val files = parsePorcelain(result.stdout, generatedSet)
    return ChangesSnapshot(files = files, branchLabel = branchLabel)
  }

  // Parse `git status --porcelain=v1 -z` output. Each entry is `XY <SP> path` followed by a NUL.
  // For rename/copy (X = R or C), the source path appears as an additional NUL-terminated record
  // after the destination path.
  private fun parsePorcelain(stdout: String, generated: Set<String>): List<ChangedFile> {
    if (stdout.isEmpty()) {
      return emptyList()
    }
    val records = stdout.split(NUL).filter { it.isNotEmpty() }
    val files = mutableListOf<ChangedFile>()
    var index = 0
    while (index < records.size) {
      val record = records[index]
      if (record.length < 3) {
        index += 1
        continue
      }
      val xy = record.take(2)
      val pathPart = record.substring(3)
      // Renames/copies emit two records: destination then source. Skip the source.
      val isRenameOrCopy = xy[0] == 'R' || xy[0] == 'C'
      index += if (isRenameOrCopy && index + 1 < records.size) 2 else 1

      val staged = xy[0]
      val unstaged = xy[1]
      val isUntracked = xy == "??"
      val baseGroup = when {
        isUntracked -> ChangedFileGroup.UNTRACKED
        staged != ' ' && staged != '?' -> ChangedFileGroup.STAGED
        unstaged != ' ' && unstaged != '?' -> ChangedFileGroup.UNSTAGED
        else -> ChangedFileGroup.UNSTAGED
      }
      val statusCode = when {
        isUntracked -> "??"
        baseGroup == ChangedFileGroup.STAGED -> staged.toString()
        else -> unstaged.toString()
      }
      val normalizedPath = pathPart.replace('\\', '/')
      val isGenerated = normalizedPath in generated
      val group = if (isGenerated) ChangedFileGroup.GENERATED else baseGroup
      files += ChangedFile(
        path = normalizedPath,
        group = group,
        statusCode = statusCode,
        isGenerated = isGenerated,
      )
    }
    return files
  }

  private fun readDiff(root: Path, path: String, staged: Boolean): String {
    val args = buildList {
      add("diff")
      // F-S02: --no-ext-diff disables external diff helpers configured in .git/config. The global
      // `-c diff.external=` flag does not work here (git treats empty as a real command), so we
      // suppress externals at the diff subcommand level instead.
      add("--no-ext-diff")
      if (staged) {
        add("--cached")
      }
      add("--")
      add(path)
    }
    val result = runGit(root, args)
    return if (result.exitCode == 0) result.stdout else ""
  }

  private fun readCommits(root: Path, limit: Int, pathFilter: String?): List<CommitEntry> {
    // Custom field/record separators inside --pretty=format keep us safe against tabs, pipes, and
    // newlines inside subjects/authors. The leading GS marker before %H lets us split the whole
    // stream by GS to get one block per commit; without a leading marker, --pretty=format plus
    // --name-only -z interleaves commit N's path records with commit N+1's header.
    val args = mutableListOf(
      "log",
      "-n",
      limit.toString(),
      "--no-color",
      "--pretty=format:$COMMIT_LOG_FORMAT",
      "--name-only",
      "-z",
    )
    if (pathFilter != null) {
      args.add("--")
      args.add(pathFilter)
    }
    val result = runGit(root, args)
    if (result.exitCode != 0) {
      return emptyList()
    }
    return parseCommitLog(result.stdout)
  }

  private fun parseCommitLog(stdout: String): List<CommitEntry> {
    if (stdout.isBlank()) {
      return emptyList()
    }
    return stdout
      .split(COMMIT_RECORD_SEPARATOR)
      .map { it.trim(NUL, '\n', '\r') }
      .filter { it.isNotEmpty() && it.contains(FIELD_SEPARATOR) }
      .map { record -> parseCommitRecord(record) }
  }

  private fun parseCommitRecord(record: String): CommitEntry {
    // The header (5 FIELD_SEPARATOR-delimited fields) ends at the first newline that git inserts
    // between --pretty=format output and the --name-only path block. Remaining bytes are
    // NUL-separated path records emitted by --name-only -z. We tolerate either a newline or a NUL
    // as the boundary so the parser is robust across git versions.
    val boundary = record.indexOfFirst { it == '\n' || it == NUL }
    val header = if (boundary >= 0) record.substring(0, boundary) else record
    val tail = if (boundary >= 0) record.substring(boundary + 1) else ""
    val parts = header.split(FIELD_SEPARATOR)
    val full = parts.getOrNull(0)?.trim().orEmpty()
    val short = parts.getOrNull(1)?.trim().orEmpty()
    val author = parts.getOrNull(2)?.trim().orEmpty()
    val isoDate = parts.getOrNull(3)?.trim().orEmpty()
    val subject = parts.getOrNull(4)?.trim().orEmpty()
    val paths = tail
      .split(NUL, '\n')
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .map { it.replace('\\', '/') }
    return CommitEntry(
      shortHash = short,
      fullHash = full,
      author = author,
      isoDate = isoDate,
      subject = subject,
      changedPaths = paths,
    )
  }

  private fun currentBranch(root: Path): String? = runCatching {
    val result = runGit(root, listOf("branch", "--show-current"))
    if (result.exitCode == 0 && result.stdout.isNotBlank()) result.stdout.trim() else null
  }.getOrNull()

  private fun readPublishingStatus(root: Path): GitPublishingStatus {
    val branch = currentBranch(root) ?: return GitPublishingStatus(errorMessage = "Detached HEAD has no push target.")
    val remotes = readRemotes(root)
    if (remotes.isEmpty()) {
      return GitPublishingStatus(errorMessage = "No Git remotes are configured.")
    }
    val targetRemote =
      when {
        remotes.any { it.name == "origin" } -> "origin"
        else -> remotes.first().name
      }
    val targetBranch = branch
    val targetRemoteInfo = remotes.firstOrNull { it.name == targetRemote }
    val likelyCanonical = targetRemoteInfo?.let { isLikelyCanonicalRemote(it, remotes) } ?: true
    val target = GitPushTarget(
      remoteName = targetRemote,
      branchName = targetBranch,
      expectedCurrentBranch = branch,
      displayName = "$targetRemote/$targetBranch",
      isLikelyCanonical = likelyCanonical,
      canonicalWarning = if (likelyCanonical) canonicalWarning(targetRemoteInfo, remotes) else null,
    )
    return GitPublishingStatus(
      pushTarget = target,
      aheadBehind = readAheadBehind(root, target),
      compareUrl = buildCompareUrl(root, remotes, target),
    )
  }

  private fun readRemotes(root: Path): List<RemoteInfo> {
    val namesResult = runGit(root, listOf("remote"))
    if (namesResult.exitCode != 0) {
      return emptyList()
    }
    return namesResult.stdout
      .lineSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .map { name ->
        val urlsResult = runGit(root, listOf("remote", "get-url", "--all", name))
        val urls =
          if (urlsResult.exitCode == 0) {
            urlsResult.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
          } else {
            emptyList()
          }
        val pushUrlsResult = runGit(root, listOf("remote", "get-url", "--push", "--all", name))
        val pushUrls =
          if (pushUrlsResult.exitCode == 0) {
            pushUrlsResult.stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
          } else {
            emptyList()
          }
        RemoteInfo(name = name, urls = urls, pushUrls = pushUrls)
      }
      .toList()
  }

  private fun readAheadBehind(root: Path, target: GitPushTarget): GitAheadBehind? {
    val remoteRef = "refs/remotes/${target.remoteName}/${target.branchName}"
    val remoteExists = runGit(root, listOf("show-ref", "--verify", "--quiet", remoteRef)).exitCode == 0
    if (!remoteExists) {
      return null
    }
    val result = runGit(root, listOf("rev-list", "--left-right", "--count", "HEAD...$remoteRef"))
    if (result.exitCode != 0) {
      return null
    }
    val parts = result.stdout.trim().split(Regex("\\s+"))
    val ahead = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val behind = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return GitAheadBehind(ahead = ahead, behind = behind)
  }

  private fun isLikelyCanonicalRemote(remote: RemoteInfo, remotes: List<RemoteInfo>): Boolean {
    if (remote.name != "origin") {
      return true
    }
    val upstream = remotes.firstOrNull { it.name == "upstream" } ?: return true
    val originRepos = remote.githubPushRepos()
    val upstreamRepos = upstream.githubRepos()
    if (originRepos.isEmpty() || upstreamRepos.isEmpty()) {
      return true
    }
    val everyPushDestinationIsFork = originRepos.all { originRepo ->
      upstreamRepos.any { upstreamRepo ->
        originRepo.repo == upstreamRepo.repo && originRepo.owner != upstreamRepo.owner
      }
    }
    return !everyPushDestinationIsFork
  }

  private fun canonicalWarning(remote: RemoteInfo?, remotes: List<RemoteInfo>): String =
    if (remote?.name == "origin" && remotes.none { it.name == "upstream" }) {
      "origin is the only configured remote, so it may be the canonical repository."
    } else {
      "${remote?.name ?: "This remote"} looks like a canonical remote. Confirm before pushing."
    }

  private fun buildCompareUrl(root: Path, remotes: List<RemoteInfo>, target: GitPushTarget): String? {
    val targetRemote = remotes.firstOrNull { it.name == target.remoteName }?.singleGithubPushRepo() ?: return null
    val upstreamRemote = remotes.firstOrNull { it.name == "upstream" }?.githubRepo()
    return if (
      target.remoteName == "origin" &&
      upstreamRemote != null &&
      upstreamRemote.repo == targetRemote.repo &&
      upstreamRemote.owner != targetRemote.owner
    ) {
      val baseBranch = defaultBranchForRemote(root, "upstream") ?: DEFAULT_COMPARE_BASE_BRANCH
      "${upstreamRemote.webUrl}/compare/$baseBranch...${targetRemote.owner}:${target.branchName}"
    } else {
      "${targetRemote.webUrl}/compare/${target.branchName}"
    }
  }

  private fun defaultBranchForRemote(root: Path, remoteName: String): String? {
    val result = runGit(root, listOf("symbolic-ref", "--quiet", "--short", "refs/remotes/$remoteName/HEAD"))
    if (result.exitCode != 0) {
      return null
    }
    return result.stdout.trim()
      .removePrefix("$remoteName/")
      .takeIf(String::isNotBlank)
  }

  private fun runGit(root: Path, args: List<String>, hardeningFlags: List<String> = GIT_HARDENING_FLAGS): GitResult {
    // F-S02: prepend hardening flags BEFORE the subcommand. A malicious .git/config inside a
    // user-opened repo cannot trigger arbitrary command execution (CVE-2022-24765 class) when
    // diff/pager/sshCommand/external-filter knobs are forced to safe values. --no-optional-locks
    // also avoids touching lockfiles in repos we are only reading.
    val command = mutableListOf("git", "-C", root.toString())
    command.addAll(hardeningFlags)
    command.addAll(args)
    val processBuilder = ProcessBuilder(command).redirectErrorStream(true)

    // F-S01: scrub environment variables that could redirect git to alternate dirs, configs,
    // editors, askpass helpers, or external tools. The launch environment is untrusted: a user
    // might inherit GIT_DIR or GIT_ASKPASS pointing at attacker-controlled paths. GIT_TERMINAL_PROMPT=0
    // also blocks any interactive prompts since this gateway is non-interactive.
    val env = processBuilder.environment()
    GIT_ENV_VARS_TO_REMOVE.forEach { key -> env.remove(key) }
    env["GIT_TERMINAL_PROMPT"] = "0"

    val process = processBuilder.start()

    // F-C704: drain stdout concurrently with waitFor so a full pipe (~64 KiB) never deadlocks git.
    // The reader thread terminates when stdout closes (process exit) OR when the cap is exceeded
    // (we destroy the process). F-S03: the size cap prevents an unbounded git log from exhausting
    // memory. F-C702: explicit UTF-8 decoding survives Windows cp1252 defaults.
    val capturedBytes = ByteArrayOutputStream()
    val truncated = booleanArrayOf(false)
    val readerThread = Thread {
      drainCapped(process.inputStream, capturedBytes, truncated)
    }.apply {
      isDaemon = true
      name = "RuntimeGitGateway-reader"
      start()
    }

    val completedInTime = process.waitFor(GIT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!completedInTime) {
      process.destroyForcibly()
      readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
      error("git ${args.firstOrNull().orEmpty()} timed out after ${GIT_COMMAND_TIMEOUT_SECONDS}s")
    }

    if (truncated[0]) {
      // F-S03: kill the process if it was still emitting and surface a truncation error. By the time
      // we get here waitFor returned, but destroyForcibly is harmless on an already-exited process.
      process.destroyForcibly()
      readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
      error("git ${args.firstOrNull().orEmpty()} output exceeded ${MAX_GIT_OUTPUT_BYTES} bytes and was truncated")
    }

    // Normal exit: wait briefly for the reader to flush the remainder before reading the buffer.
    readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
    val output = capturedBytes.toString(Charsets.UTF_8)
    return GitResult(exitCode = process.exitValue(), stdout = output)
  }

  // F-C704 + F-S03 helper: copy stdin into [sink] in fixed-size chunks. When the running total
  // exceeds MAX_GIT_OUTPUT_BYTES we set [truncated] and stop reading. We swallow IOException because
  // the most common cause here is the producer being killed (destroyForcibly) which closes the pipe.
  private fun drainCapped(stream: InputStream, sink: ByteArrayOutputStream, truncated: BooleanArray) {
    val buffer = ByteArray(BUFFER_BYTES)
    try {
      while (true) {
        val n = stream.read(buffer)
        if (n < 0) return
        if (sink.size() + n > MAX_GIT_OUTPUT_BYTES) {
          truncated[0] = true
          return
        }
        sink.write(buffer, 0, n)
      }
    } catch (_: java.io.IOException) {
      // Producer closed the stream (e.g. destroyForcibly). Stop reading; outer logic handles state.
    }
  }

  private data class GitResult(val exitCode: Int, val stdout: String)

  private fun describeGitFailure(result: GitResult): String {
    val output = redactCredentialedUrls(result.stdout.trim())
    return if (output.isBlank()) {
      "git exited with code ${result.exitCode}"
    } else {
      "git exited with code ${result.exitCode}: $output"
    }
  }

  private fun describePushFailure(root: Path, target: GitPushTarget, result: GitResult): String {
    val failure = describeGitFailure(result)
    val destinations = readRemotes(root)
      .firstOrNull { remote -> remote.name == target.remoteName }
      ?.effectivePushUrls()
      ?.map(::redactCredentialedUrls)
      .orEmpty()
    if (destinations.isEmpty()) {
      return failure
    }
    return "$failure\nRemote ${target.remoteName}: ${destinations.joinToString()}"
  }

  private fun describe(error: Throwable): String {
    val message = error.message
    val name = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    return if (message.isNullOrBlank()) name else "$name: $message"
  }

  companion object {
    private const val GIT_COMMAND_TIMEOUT_SECONDS = 5L

    // F-S03: cap captured output at 8 MiB. Snapshot/diff/log output on real repos stays well under
    // this; an unbounded run (malicious repo, runaway pager) is destroyed and surfaced as an error.
    private const val MAX_GIT_OUTPUT_BYTES: Int = 8 * 1024 * 1024
    private const val BUFFER_BYTES: Int = 8 * 1024
    private const val READER_JOIN_TIMEOUT_MILLIS: Long = 1_000L
    private const val DEFAULT_COMPARE_BASE_BRANCH: String = "main"

    // F-S01: environment variables that can redirect git to attacker-controlled state. The user's
    // launch environment is untrusted (inherited from shell / desktop launcher / parent processes),
    // so we strip these before every fork. GIT_TERMINAL_PROMPT=0 is set after the strip so git
    // never falls back to an interactive prompt for credentials.
    private val GIT_ENV_VARS_TO_REMOVE: Set<String> = setOf(
      "GIT_DIR",
      "GIT_WORK_TREE",
      "GIT_INDEX_FILE",
      "GIT_CONFIG",
      "GIT_CONFIG_GLOBAL",
      "GIT_CONFIG_SYSTEM",
      "GIT_CONFIG_NOSYSTEM",
      "GIT_EXTERNAL_DIFF",
      "GIT_EXTERNAL_FILTER",
      "GIT_PAGER",
      "GIT_EDITOR",
      "GIT_SSH_COMMAND",
      "GIT_ASKPASS",
      "SSH_ASKPASS",
    )

    // F-S02: global flags that must precede the subcommand. They prevent a malicious .git/config in
    // a user-opened repo from triggering arbitrary command execution via external diff/filter/pager,
    // remote SSH commands, fsmonitor hooks, or transport protocols that allow file:// URLs.
    //
    // Note on `diff.external`: setting `diff.external` to empty via `-c` makes git try to exec the
    // empty string and fail. We disable external diff at the diff-call site instead via the
    // `--no-ext-diff` flag (see readDiff). Other subcommands (status, log, add, restore, branch)
    // don't consult `diff.external` so the global override is unnecessary for them.
    private val GIT_HARDENING_FLAGS: List<String> = listOf(
      "--no-optional-locks",
      "-c",
      "core.fsmonitor=",
      "-c",
      "core.hooksPath=/dev/null",
      "-c",
      "core.pager=",
      "-c",
      "core.sshCommand=",
      "-c",
      "protocol.file.allow=user",
    )

    // Push must support normal SSH remotes (`git@github.com:owner/repo.git`). Keep the non-SSH
    // hardening from read operations, but override repo-local sshCommand with the platform ssh
    // binary instead of the empty command used to neutralize read-only Git operations.
    private val GIT_PUSH_HARDENING_FLAGS: List<String> = listOf(
      "--no-optional-locks",
      "-c",
      "core.fsmonitor=",
      "-c",
      "core.hooksPath=/dev/null",
      "-c",
      "core.pager=",
      "-c",
      "core.sshCommand=ssh -o BatchMode=yes",
      "-c",
      "protocol.file.allow=user",
    )

    // ASCII control bytes are used as field/record separators in the git log format, so subjects
    // or authors that contain tabs, pipes, or newlines do not corrupt parsing. NUL terminates path
    // records emitted by --name-only -z.
    private const val NUL: Char = ' '
    private const val FIELD_SEPARATOR: Char = '' // ASCII Record Separator (RS)
    private const val COMMIT_RECORD_SEPARATOR: Char = '' // ASCII Group Separator (GS)

    // Leading GS marker prefixes every commit's header so a single GS-split recovers one block
    // per commit (block = header || NUL-separated path records).
    private const val COMMIT_LOG_FORMAT = "%H%h%an%aI%s"
  }
}

private fun resolveRepoPath(repoPath: String): Path? {
  val trimmed = repoPath.trim()
  if (trimmed.isBlank()) {
    return null
  }
  return try {
    Path.of(trimmed).toAbsolutePath().normalize()
  } catch (_: InvalidPathException) {
    null
  }
}

private fun Path.portablePath(): String = toString().replace('\\', '/')

private data class RemoteInfo(
  val name: String,
  val urls: List<String>,
  val pushUrls: List<String>,
) {
  fun githubRepo(): GithubRepo? = urls.firstNotNullOfOrNull(::parseGithubUrl)

  fun githubRepos(): List<GithubRepo> = urls.mapNotNull(::parseGithubUrl).distinct()

  fun singleGithubPushRepo(): GithubRepo? = githubPushRepos().singleOrNull()

  fun githubPushRepos(): List<GithubRepo> = effectivePushUrls().mapNotNull(::parseGithubUrl).distinct()

  fun effectivePushUrls(): List<String> = pushUrls.ifEmpty { urls }
}

private data class GithubRepo(
  val owner: String,
  val repo: String,
) {
  val webUrl: String = "https://github.com/$owner/$repo"
}

private fun parseGithubUrl(url: String): GithubRepo? {
  val normalized = url.trim().removeSuffix(".git").withoutGithubUserInfo()
  val path = when {
    normalized.startsWith("https://github.com/") -> normalized.removePrefix("https://github.com/")
    normalized.startsWith("http://github.com/") -> normalized.removePrefix("http://github.com/")
    normalized.startsWith("git@github.com:") -> normalized.removePrefix("git@github.com:")
    normalized.startsWith("ssh://git@github.com/") -> normalized.removePrefix("ssh://git@github.com/")
    else -> return null
  }
  val parts = path.split('/').filter(String::isNotBlank)
  val owner = parts.getOrNull(0) ?: return null
  val repo = parts.getOrNull(1) ?: return null
  return GithubRepo(owner = owner, repo = repo)
}

private fun String.withoutGithubUserInfo(): String =
  replace(Regex("""^https://[^/@]+@github\.com/"""), "https://github.com/")
    .replace(Regex("""^http://[^/@]+@github\.com/"""), "http://github.com/")

private fun isSafeGitRemoteName(value: String): Boolean = isSafeGitRefPath(value)

private fun isSafeGitBranchName(value: String): Boolean = isSafeGitRefPath(value)

private fun isSafeGitRefPath(value: String): Boolean {
  if (value.isBlank() || value.startsWith("-") || value.startsWith("/") || value.endsWith("/")) {
    return false
  }
  if (value.contains("//") || value.contains("..") || value.endsWith(".lock") || value.endsWith(".")) {
    return false
  }
  return value.all { char ->
    char.code in 33..126 &&
      char !in setOf(' ', '~', '^', ':', '?', '*', '[', '\\')
  }
}

internal fun redactCredentialedUrls(message: String): String = message.replace(CREDENTIAL_URL_PATTERN) { match ->
  val scheme = match.groupValues[1]
  val username = match.groupValues[2]
  val password = match.groupValues[4]
  if (password.isBlank()) {
    "$scheme<redacted>@"
  } else {
    "$scheme$username:<redacted>@"
  }
}

private val CREDENTIAL_URL_PATTERN = Regex("""\b([A-Za-z][A-Za-z0-9+.-]*://)([^@\s/:]+)(:([^@\s/]+))?@""")
