package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.ChangedFile
import skillbill.desktop.core.domain.model.ChangedFileGroup
import skillbill.desktop.core.domain.model.ChangesSnapshot
import skillbill.desktop.core.domain.model.CommitEntry
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

  private fun runGit(root: Path, args: List<String>): GitResult {
    // F-S02: prepend hardening flags BEFORE the subcommand. A malicious .git/config inside a
    // user-opened repo cannot trigger arbitrary command execution (CVE-2022-24765 class) when
    // diff/pager/sshCommand/external-filter knobs are forced to safe values. --no-optional-locks
    // also avoids touching lockfiles in repos we are only reading.
    val command = mutableListOf("git", "-C", root.toString())
    command.addAll(GIT_HARDENING_FLAGS)
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
    val firstLine = result.stdout.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (firstLine.isBlank()) {
      "git exited with code ${result.exitCode}"
    } else {
      "git exited with code ${result.exitCode}: $firstLine"
    }
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
