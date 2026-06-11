@file:Suppress("TooGenericExceptionCaught")

package skillbill.desktop.core.data.service

import kotlinx.coroutines.CancellationException
import me.tatarka.inject.annotations.Inject
import skillbill.desktop.core.common.di.UserScope
import skillbill.desktop.core.domain.model.ProvisionResult
import skillbill.desktop.core.domain.service.InstalledWorkspaceGitProvisioner
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * JVM implementation of [InstalledWorkspaceGitProvisioner].
 *
 * Provisioning logic:
 * 1. Run `git rev-parse --show-toplevel` inside `workspaceRoot`. If the resolved toplevel equals
 *    `workspaceRoot`, the repo is already self-rooted → return [ProvisionResult.AlreadyProvisioned].
 * 2. If git exits non-zero (i.e. the directory is not inside any git repo) **or** if the resolved
 *    toplevel is a parent of `workspaceRoot` (e.g. a `$HOME`-rooted dotfiles worktree), initialise
 *    a new repo directly inside `workspaceRoot`.
 * 3. Write `.gitignore` excluding runtime artefacts; only `skills/` and `platform-packs/` are
 *    tracked.
 * 4. Stage `skills/` and `platform-packs/` and create an initial commit.
 *
 * The same git-runner conventions as [RuntimeGitGateway] are used (hardening flags, environment
 * scrubbing, timeout, output cap). Constants are intentionally duplicated here rather than imported
 * from [RuntimeGitGateway] to respect the KSP/ABI boundary rule.
 *
 * An [IOException] from [ProcessBuilder.start] (missing git binary) returns
 * [ProvisionResult.GitUnavailable]. All other failures return [ProvisionResult.Failed].
 */
@Inject
@SingleIn(UserScope::class)
class JvmInstalledWorkspaceGitProvisioner : InstalledWorkspaceGitProvisioner {
  /**
   * Injectable seam for the process runner. Tests replace this to simulate a missing binary or
   * to inject a real git binary at a controlled path.
   *
   * The seam receives a fully-built [ProcessBuilder] with command, environment, and
   * `redirectErrorStream(true)` already configured. It must start the process and return it.
   * Throwing [IOException] signals that the binary is unavailable.
   */
  internal var runnerFactory: (ProcessBuilder) -> Process = { pb -> pb.start() }

  override fun provision(workspaceRoot: String): ProvisionResult {
    val root = try {
      Path.of(workspaceRoot).toAbsolutePath().normalize()
    } catch (_: Exception) {
      return ProvisionResult.Failed("Invalid workspace root path: $workspaceRoot")
    }
    return runCatching { doProvision(root) }
      .fold(
        onSuccess = { result -> result },
        onFailure = { error ->
          if (error is CancellationException) throw error
          ProvisionResult.Failed(describe(error))
        },
      )
  }

  private fun doProvision(root: Path): ProvisionResult {
    // Step 1: detect whether the workspace is already a self-rooted git repository.
    val topLevelResult = try {
      runGit(root, listOf("rev-parse", "--show-toplevel"))
    } catch (_: IOException) {
      // git binary not found or not executable.
      return ProvisionResult.GitUnavailable(
        "git is not available on this system. " +
          "The installed workspace is open, but change history is unavailable.",
      )
    }
    if (topLevelResult.exitCode == 0) {
      val resolvedTopLevel = try {
        Path.of(topLevelResult.stdout.trim()).toAbsolutePath().normalize()
      } catch (_: Exception) {
        null
      }
      if (resolvedTopLevel == root) {
        // The workspace directory IS the git root — already provisioned.
        return ProvisionResult.AlreadyProvisioned
      }
      // The workspace is inside a parent worktree (e.g. a $HOME-rooted dotfiles repo).
      // Fall through to provision a new repo directly inside `root`.
    }
    // else: git exited non-zero — the directory is not inside any git repo.

    // Step 2: git init
    val initResult = try {
      runGit(root, listOf("init", "-q"))
    } catch (_: IOException) {
      return ProvisionResult.GitUnavailable(
        "git is not available on this system. " +
          "The installed workspace is open, but change history is unavailable.",
      )
    }
    if (initResult.exitCode != 0) {
      return ProvisionResult.Failed(
        "git init failed: ${initResult.stdout.trim().ifBlank { "exit code ${initResult.exitCode}" }}",
      )
    }

    // Step 3: write .gitignore — only skills/ and platform-packs/ are tracked.
    val gitignoreContent = buildString {
      appendLine("# Installed workspace .gitignore — managed by skill-bill.")
      appendLine("# Only skills/ and platform-packs/ are version-controlled.")
      appendLine("runtime/")
      appendLine("installed-skills/")
      appendLine("native-agents/")
      appendLine("orchestration/")
      appendLine("review-metrics.db")
      appendLine("config.json")
      appendLine("install-selection.json")
      appendLine("baseline-manifest.json")
      appendLine("desktop.properties")
    }
    Files.writeString(root.resolve(".gitignore"), gitignoreContent)

    // Step 4: configure a local identity so the commit succeeds even without a global git config.
    runCatching { runGit(root, listOf("config", "user.email", "skill-bill@local")) }
    runCatching { runGit(root, listOf("config", "user.name", "skill-bill")) }

    // Step 5: stage only skills/, platform-packs/, and .gitignore (when they exist).
    // We add each existing path individually so that a freshly-created workspace with no content
    // directories still produces a valid commit (the --allow-empty flag in step 6 handles that).
    val pathsToAdd = buildList {
      add(".gitignore")
      if (Files.isDirectory(root.resolve("skills"))) add("skills/")
      if (Files.isDirectory(root.resolve("platform-packs"))) add("platform-packs/")
    }
    val addArgs = mutableListOf("add", "--") + pathsToAdd
    val addResult = try {
      runGit(root, addArgs)
    } catch (_: IOException) {
      return ProvisionResult.GitUnavailable(
        "git is not available on this system. " +
          "The installed workspace is open, but change history is unavailable.",
      )
    }
    if (addResult.exitCode != 0) {
      return ProvisionResult.Failed(
        "git add failed: ${addResult.stdout.trim().ifBlank { "exit code ${addResult.exitCode}" }}",
      )
    }

    // Step 6: create the initial commit (allow-empty in case skills/ and platform-packs/ do not
    // exist yet — the workspace might be freshly created).
    val commitResult = try {
      runGit(root, listOf("commit", "--allow-empty", "-m", "Initial skill-bill workspace"))
    } catch (_: IOException) {
      return ProvisionResult.GitUnavailable(
        "git is not available on this system. " +
          "The installed workspace is open, but change history is unavailable.",
      )
    }
    if (commitResult.exitCode != 0) {
      return ProvisionResult.Failed(
        "git commit failed: ${commitResult.stdout.trim().ifBlank { "exit code ${commitResult.exitCode}" }}",
      )
    }
    return ProvisionResult.Provisioned
  }

  private fun runGit(root: Path, args: List<String>): GitRunnerResult {
    val command = mutableListOf("git", "-C", root.toString())
    command.addAll(GIT_PROVISIONER_HARDENING_FLAGS)
    command.addAll(args)
    val processBuilder = ProcessBuilder(command).redirectErrorStream(true)

    val env = processBuilder.environment()
    GIT_PROVISIONER_ENV_VARS_TO_REMOVE.forEach { key -> env.remove(key) }
    env["GIT_TERMINAL_PROMPT"] = "0"

    // runnerFactory may throw IOException when the git binary is missing.
    val process = runnerFactory(processBuilder)

    val capturedBytes = ByteArrayOutputStream()
    val truncated = booleanArrayOf(false)
    val readerThread = Thread {
      drainCapped(process.inputStream, capturedBytes, truncated)
    }.apply {
      isDaemon = true
      name = "JvmInstalledWorkspaceGitProvisioner-reader"
      start()
    }

    val completedInTime = process.waitFor(GIT_PROVISIONER_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!completedInTime) {
      process.destroyForcibly()
      readerThread.interrupt()
      readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
      error("git ${args.firstOrNull().orEmpty()} timed out after ${GIT_PROVISIONER_COMMAND_TIMEOUT_SECONDS}s")
    }
    if (truncated[0]) {
      process.destroyForcibly()
      readerThread.interrupt()
      readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
      error("git ${args.firstOrNull().orEmpty()} output exceeded $MAX_GIT_OUTPUT_BYTES bytes and was truncated")
    }
    // F-MAJOR-2: guard against a data race on ByteArrayOutputStream. After the bounded join, the
    // reader thread may still be alive (e.g. blocked in a final read() before detecting EOF). If so,
    // interrupt it and perform a short final join so the buffer is quiescent before toString().
    readerThread.join(READER_JOIN_TIMEOUT_MILLIS)
    if (readerThread.isAlive) {
      readerThread.interrupt()
      readerThread.join(READER_JOIN_TIMEOUT_MILLIS_FINAL)
    }
    val output = synchronized(capturedBytes) { capturedBytes.toString(Charsets.UTF_8) }
    return GitRunnerResult(exitCode = process.exitValue(), stdout = output)
  }

  private fun drainCapped(stream: InputStream, sink: ByteArrayOutputStream, truncated: BooleanArray) {
    val buffer = ByteArray(BUFFER_BYTES)
    try {
      while (true) {
        val n = stream.read(buffer)
        if (n < 0) return
        // F-MAJOR-2: synchronize writes to sink so that the main thread's synchronized read in
        // runGit() sees a consistent buffer state (happens-before the final join/interrupt).
        synchronized(sink) {
          if (sink.size() + n > MAX_GIT_OUTPUT_BYTES) {
            truncated[0] = true
            return
          }
          sink.write(buffer, 0, n)
        }
      }
    } catch (_: IOException) {
      // Producer closed the stream (e.g. destroyForcibly). Stop reading.
    }
  }

  private fun describe(error: Throwable): String {
    val message = error.message
    val name = error::class.simpleName ?: error::class.qualifiedName ?: "Throwable"
    return if (message.isNullOrBlank()) name else "$name: $message"
  }

  private data class GitRunnerResult(val exitCode: Int, val stdout: String)

  companion object {
    private const val GIT_PROVISIONER_COMMAND_TIMEOUT_SECONDS = 5L
    private const val MAX_GIT_OUTPUT_BYTES: Int = 8 * 1024 * 1024
    private const val BUFFER_BYTES: Int = 8 * 1024
    private const val READER_JOIN_TIMEOUT_MILLIS: Long = 1_000L

    // F-MAJOR-2: final join after interrupt to ensure the reader thread is quiescent before the
    // ByteArrayOutputStream buffer is read.
    private const val READER_JOIN_TIMEOUT_MILLIS_FINAL: Long = 200L

    private val GIT_PROVISIONER_ENV_VARS_TO_REMOVE: Set<String> = setOf(
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

    private val GIT_PROVISIONER_HARDENING_FLAGS: List<String> = listOf(
      "--literal-pathspecs",
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
  }
}
