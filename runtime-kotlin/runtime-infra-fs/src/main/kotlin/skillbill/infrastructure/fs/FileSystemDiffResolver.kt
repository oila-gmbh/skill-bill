package skillbill.infrastructure.fs

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diff.DiffResolverPort
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

private val diffResolverLog: Logger = Logger.getLogger(FileSystemDiffResolver::class.java.name)

@Inject
class FileSystemDiffResolver : DiffResolverPort {
  override fun readDiff(path: Path, maxBytes: Long): String? = path.takeIf(Files::isRegularFile)
    ?.takeIf { Files.size(it) <= maxBytes }
    ?.let(Files::readString)
    ?.takeIf(String::isNotBlank)

  // Output is redirected to a temp file rather than drained from the pipe so a large diff cannot
  // deadlock against the OS pipe buffer while we wait, and waitFor is bounded so a stalled git/gh
  // process cannot block the review indefinitely — it is force-killed and reported as a failure.
  override fun runProcess(args: List<String>, workDir: Path): String? {
    val outputFile = Files.createTempFile("skillbill-diff", ".out")
    return try {
      val process = ProcessBuilder(args)
        .directory(workDir.toFile())
        .redirectErrorStream(true)
        .redirectOutput(outputFile.toFile())
        .start()
      val completed = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
      if (!completed) {
        process.destroyForcibly()
        null
      } else if (process.exitValue() in setOf(0, 1)) {
        val sizeBytes = Files.size(outputFile)
        if (sizeBytes > MAX_DIFF_BYTES) return null
        Files.readString(outputFile)
      } else {
        null
      }
    } catch (error: IOException) {
      diffResolverLog.log(
        Level.WARNING,
        "runProcess failed args=$args workDir=$workDir",
        error,
      )
      null
    } catch (error: InterruptedException) {
      Thread.currentThread().interrupt()
      null
    } finally {
      try {
        Files.deleteIfExists(outputFile)
      } catch (_: IOException) {
      }
    }
  }

  private companion object {
    const val PROCESS_TIMEOUT_SECONDS = 120L
    const val MAX_DIFF_BYTES = 50L * 1024 * 1024 // 50 MiB cap before reading into heap
  }
}
