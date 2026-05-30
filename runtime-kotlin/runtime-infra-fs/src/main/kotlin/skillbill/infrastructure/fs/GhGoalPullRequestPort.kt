package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.goalrunner.GoalPullRequestPort
import skillbill.ports.goalrunner.model.GoalPullRequestRequest
import skillbill.ports.goalrunner.model.GoalPullRequestResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Inject
class GhGoalPullRequestPort() : GoalPullRequestPort {
  private var ghExecutableResolver: (Path) -> Path? = ::resolveGhExecutable

  internal constructor(ghExecutableResolver: (Path) -> Path?) : this() {
    this.ghExecutableResolver = ghExecutableResolver
  }

  override fun open(request: GoalPullRequestRequest): GoalPullRequestResult =
    request.headBranch.takeIf(String::isNotBlank)
      ?.let { head -> openWithHead(request, head) }
      ?: GoalPullRequestResult.Failed("A head branch is required before creating the goal pull request.")

  private fun openWithHead(request: GoalPullRequestRequest, head: String): GoalPullRequestResult {
    val root = request.repoRoot.toAbsolutePath().normalize()
    val existing = runGh(
      root,
      listOf("pr", "list", "--head", head, "--json", "url", "--jq", ".[0].url", "--limit", "1"),
    )
    return if (existing.exitCode == 0 && existing.stdout.trim().startsWith("http")) {
      GoalPullRequestResult.Existing(existing.stdout.trim())
    } else {
      createPullRequest(root, request, head)
    }
  }

  private fun createPullRequest(root: Path, request: GoalPullRequestRequest, head: String): GoalPullRequestResult {
    val create = runGh(root, createArgs(request, head))
    return if (create.exitCode == 0) {
      create.stdout.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        ?.let(GoalPullRequestResult::Opened)
        ?: GoalPullRequestResult.Failed("Goal pull request was created but no PR URL was returned.")
    } else {
      GoalPullRequestResult.Failed(describeGhFailure(create))
    }
  }

  private fun createArgs(request: GoalPullRequestRequest, head: String): List<String> = listOf(
    "pr",
    "create",
    "--head",
    head,
    "--base",
    request.baseBranch,
    "--draft",
    "--title",
    request.title,
    "--body",
    request.body,
  )

  private fun runGh(root: Path, args: List<String>): CommandResult = runCatching {
    val executable = ghExecutableResolver(root)
      ?: return CommandResult(exitCode = 1, stdout = "GitHub CLI executable was not found on PATH.")
    val processBuilder = ProcessBuilder(listOf(executable.toString()) + args)
      .directory(root.toFile())
      .redirectErrorStream(true)
    processBuilder.environment()["GIT_TERMINAL_PROMPT"] = "0"
    val process = processBuilder.start()
    val capturedBytes = ByteArrayOutputStream()
    val truncated = booleanArrayOf(false)
    val readerThread = Thread {
      drainCapped(process.inputStream, capturedBytes, truncated)
    }.apply {
      isDaemon = true
      name = "GhGoalPullRequestPort-reader"
      start()
    }
    val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!completed) {
      process.destroyForcibly()
      readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
      return CommandResult(exitCode = 124, stdout = "GitHub CLI timed out.")
    }
    readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
    if (truncated[0]) {
      CommandResult(exitCode = 1, stdout = "GitHub CLI output exceeded $MAX_OUTPUT_BYTES bytes.")
    } else {
      CommandResult(exitCode = process.exitValue(), stdout = capturedBytes.toString(Charsets.UTF_8))
    }
  }.getOrElse { error ->
    CommandResult(
      exitCode = 1,
      stdout = error.message?.let { "${error::class.simpleName}: $it" } ?: (error::class.simpleName ?: "Error"),
    )
  }

  private fun drainCapped(stream: InputStream, sink: ByteArrayOutputStream, truncated: BooleanArray) {
    val buffer = ByteArray(BUFFER_BYTES)
    try {
      var done = false
      while (!done) {
        val count = stream.read(buffer)
        if (count < 0) {
          done = true
        } else if (sink.size() + count > MAX_OUTPUT_BYTES) {
          truncated[0] = true
          done = true
        } else {
          sink.write(buffer, 0, count)
        }
      }
    } catch (_: java.io.IOException) {
      // Reader shutdown is best-effort after the process exits.
    }
  }

  private fun describeGhFailure(result: CommandResult): String {
    val output = result.stdout.trim().replace(Regex("(?i)(https?://)([^\\s/@]+)@")) { match ->
      "${match.groupValues[1]}<redacted>@"
    }
    return if (output.isBlank()) "GitHub provider exited with code ${result.exitCode}." else output
  }

  private fun resolveGhExecutable(root: Path): Path? {
    val names = executableNames("gh")
    return System.getenv("PATH")
      .orEmpty()
      .split(java.io.File.pathSeparator)
      .asSequence()
      .mapNotNull { raw -> raw.takeIf(String::isNotBlank)?.let(Path::of) }
      .flatMap { directory -> names.asSequence().map(directory::resolve) }
      .firstOrNull { candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate) }
      ?: names.asSequence()
        .map(root::resolve)
        .firstOrNull { candidate -> Files.isRegularFile(candidate) && Files.isExecutable(candidate) }
  }

  private fun executableNames(base: String): List<String> =
    if (System.getProperty("os.name").contains("windows", ignoreCase = true)) {
      listOf("$base.exe", "$base.cmd", "$base.bat", base)
    } else {
      listOf(base)
    }
}

private data class CommandResult(
  val exitCode: Int,
  val stdout: String,
)

private const val COMMAND_TIMEOUT_SECONDS: Long = 30
private const val READER_JOIN_TIMEOUT_MILLIS: Long = 1_000
private const val BUFFER_BYTES: Int = 4096
private const val MAX_OUTPUT_BYTES: Int = 64 * 1024
