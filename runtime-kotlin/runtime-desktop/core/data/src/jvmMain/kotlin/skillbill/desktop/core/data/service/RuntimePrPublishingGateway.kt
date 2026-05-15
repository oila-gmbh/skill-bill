package skillbill.desktop.core.data.service

import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.PrPublishingErrorType
import skillbill.desktop.core.domain.model.PrPublishingRequest
import skillbill.desktop.core.domain.model.PrPublishingResult
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.service.PrPublishingGateway
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Inject
@SingleIn(UserScope::class)
class RuntimePrPublishingGateway() : PrPublishingGateway {
  private var ghExecutableResolver: (Path) -> Path? = ::resolveGhExecutable

  internal constructor(ghExecutableResolver: (Path) -> Path?) : this() {
    this.ghExecutableResolver = ghExecutableResolver
  }

  override fun publish(request: PrPublishingRequest): PrPublishingResult {
    val root = sessionRoot(request.session) ?: return failure(
      type = PrPublishingErrorType.REMOTE,
      message = "Open a recognized Git repository before creating a pull request.",
      compareUrl = request.compareUrl,
    )
    val target = request.pushTarget ?: return failure(
      type = PrPublishingErrorType.REMOTE,
      message = "A pushed branch target is required before creating a pull request.",
      compareUrl = request.compareUrl,
    )
    val branchOwner = target.branchOwner?.takeIf(String::isNotBlank)
    val head = branchOwner?.let { owner -> "$owner:${target.branchName}" } ?: target.branchName
    val existingArgs = if (branchOwner != null) {
      listOf("pr", "list", "--search", "head:$head", "--json", "url", "--jq", ".[0].url", "--limit", "1")
    } else {
      listOf("pr", "list", "--head", target.branchName, "--json", "url", "--jq", ".[0].url", "--limit", "1")
    }
    val existing = runGh(root, existingArgs)
    if (existing.exitCode == 0 && existing.stdout.trim().startsWith("http")) {
      return PrPublishingResult.ExistingPullRequest(existing.stdout.trim())
    }
    val createArgs = buildList {
      addAll(listOf("pr", "create", "--head", head))
      if (request.draft) {
        add("--draft")
      }
      val title = request.title.trim()
      val body = request.body.trim()
      if (title.isNotBlank()) {
        addAll(listOf("--title", title, "--body", body))
      } else {
        add("--fill")
      }
    }
    val create = runGh(root, createArgs)
    if (create.exitCode == 0) {
      create.stdout.lineSequence()
        .map(String::trim)
        .firstOrNull { line -> line.startsWith("http://") || line.startsWith("https://") }
        ?.let { url -> return PrPublishingResult.CreatedDraftPullRequest(url) }
      return failure(
        type = PrPublishingErrorType.PROVIDER,
        message = "Draft PR was created but no PR URL was returned.",
        compareUrl = request.compareUrl,
      )
    }
    return failure(
      type = classifyGhFailure(create.stdout),
      message = describeGhFailure(create),
      compareUrl = request.compareUrl,
    )
  }

  private fun failure(type: PrPublishingErrorType, message: String, compareUrl: String?): PrPublishingResult =
    if (!compareUrl.isNullOrBlank()) {
      PrPublishingResult.CompareUrlFallback(url = compareUrl, reason = message)
    } else {
      PrPublishingResult.Failed(type = type, message = message)
    }

  private fun sessionRoot(session: RepoSession?): Path? {
    if (session == null || !session.isRecognizedSkillBillRepo) {
      return null
    }
    return try {
      Path.of(session.repoPath).toAbsolutePath().normalize()
    } catch (_: InvalidPathException) {
      null
    }
  }

  private fun runGh(root: Path, args: List<String>): CommandResult = runCatching {
    val executable = ghExecutableResolver(root)
      ?: return CommandResult(exitCode = 1, stdout = "GitHub CLI executable was not found on PATH.")
    val processBuilder = ProcessBuilder(listOf(executable.toString()) + args)
      .directory(root.toFile())
      .redirectErrorStream(true)
    val env = processBuilder.environment()
    env.remove("GH_CONFIG_DIR")
    env["GIT_TERMINAL_PROMPT"] = "0"
    val process = processBuilder.start()
    val capturedBytes = ByteArrayOutputStream()
    val truncated = booleanArrayOf(false)
    val readerThread = Thread {
      drainCapped(process.inputStream, capturedBytes, truncated)
    }.apply {
      isDaemon = true
      name = "RuntimePrPublishingGateway-reader"
      start()
    }
    val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!completed) {
      process.destroyForcibly()
      readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
      return CommandResult(exitCode = 124, stdout = "GitHub CLI timed out.")
    }
    readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
    val output = capturedBytes.toString(Charsets.UTF_8)
    if (truncated[0]) {
      CommandResult(exitCode = 1, stdout = "GitHub CLI output exceeded ${MAX_OUTPUT_BYTES} bytes.")
    } else {
      CommandResult(exitCode = process.exitValue(), stdout = output)
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
      while (true) {
        val count = stream.read(buffer)
        if (count < 0) return
        if (sink.size() + count > MAX_OUTPUT_BYTES) {
          truncated[0] = true
          return
        }
        sink.write(buffer, 0, count)
      }
    } catch (_: java.io.IOException) {
      return
    }
  }

  private fun describeGhFailure(result: CommandResult): String {
    val output = redactCredentialedUrls(result.stdout.trim())
    return if (output.isBlank()) "GitHub provider exited with code ${result.exitCode}." else output
  }

  private fun classifyGhFailure(output: String): PrPublishingErrorType {
    val lower = output.lowercase()
    return when {
      "authentication" in lower || "not logged" in lower || "permission" in lower -> PrPublishingErrorType.AUTH
      "network" in lower || "timeout" in lower || "could not resolve" in lower -> PrPublishingErrorType.NETWORK
      "remote" in lower || "no git remotes" in lower || "not a git repository" in lower -> PrPublishingErrorType.REMOTE
      else -> PrPublishingErrorType.PROVIDER
    }
  }

  private fun redactCredentialedUrls(value: String): String =
    value.replace(Regex("(?i)(https?://)([^\\s/@]+)@")) { match ->
      "${match.groupValues[1]}<redacted>@"
    }

  private fun resolveGhExecutable(root: Path): Path? {
    val repoRoot = root.toAbsolutePath().normalize()
    val names = executableNames("gh")
    return System.getenv("PATH")
      .orEmpty()
      .split(File.pathSeparatorChar)
      .asSequence()
      .mapNotNull { entry -> resolvePathEntry(entry) }
      .filter { directory -> directory.isAbsolute }
      .flatMap { directory -> names.asSequence().map { name -> directory.resolve(name).normalize() } }
      .firstOrNull { candidate ->
        !candidate.toAbsolutePath().normalize().startsWith(repoRoot) &&
          Files.isRegularFile(candidate) &&
          Files.isExecutable(candidate)
      }
  }

  private fun resolvePathEntry(entry: String): Path? {
    if (entry.isBlank()) {
      return null
    }
    return try {
      val path = Path.of(entry)
      if (path.isAbsolute) path.normalize() else null
    } catch (_: InvalidPathException) {
      null
    }
  }

  private fun executableNames(baseName: String): List<String> {
    val pathExt = System.getenv("PATHEXT").orEmpty()
    if (pathExt.isBlank()) {
      return listOf(baseName)
    }
    val extensions = pathExt.split(File.pathSeparatorChar, ';')
      .map { extension -> extension.trim() }
      .filter { extension -> extension.isNotEmpty() }
    return listOf(baseName) + extensions.map { extension -> baseName + extension.lowercase() }
  }

  private data class CommandResult(val exitCode: Int, val stdout: String)

  companion object {
    private const val COMMAND_TIMEOUT_SECONDS = 15L
    private const val MAX_OUTPUT_BYTES = 1024 * 1024
    private const val BUFFER_BYTES = 8 * 1024
    private const val READER_JOIN_TIMEOUT_MILLIS = 1_000L
  }
}
