package skillbill.infrastructure.fs

import skillbill.error.InvalidReviewContextSchemaError
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal fun readImmutableReviewFile(root: Path, revision: String, path: String, maxBytes: Long): ByteArray? {
  if (!immutableReviewFileExists(root, revision, path)) return null
  return readImmutableReviewCommand(root, listOf("cat-file", "blob", "$revision:$path"), maxBytes)
}

internal fun immutableReviewFileExists(root: Path, revision: String, path: String): Boolean {
  validateRepositoryMapping(root, path)
  val entry = runGitCommand(root, "--literal-pathspecs", "ls-tree", revision, "--", path)
  if (!entry.ok) throw InvalidReviewContextSchemaError("review-expansion", "Immutable revision is unavailable.")
  val row = entry.value.orEmpty()
  if (row.isBlank()) return false
  if (!row.startsWith("100644 blob ") && !row.startsWith("100755 blob ")) {
    throw InvalidReviewContextSchemaError("review-expansion", "Immutable evidence is not a regular file.")
  }
  return true
}

internal fun readImmutableReviewDelta(
  root: Path,
  base: String,
  head: String,
  path: String,
  maxBytes: Long,
): ByteArray? {
  if (!base.matches(Regex("[a-f0-9]{40,64}")) || !head.matches(Regex("[a-f0-9]{40,64}"))) return null
  validateRepositoryMapping(root, path)
  return readImmutableReviewCommand(
    root,
    listOf("diff", "--no-ext-diff", "--no-textconv", base, head, "--", ":(literal)$path"),
    maxBytes,
  )
}

internal fun readImmutableReviewCommand(root: Path, args: List<String>, maxBytes: Long): ByteArray {
  val output = Files.createTempFile("skill-bill-evidence", ".blob")
  try {
    val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args)
      .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    awaitImmutableReviewCommand(process)
    if (process.exitValue() != 0) {
      throw InvalidReviewContextSchemaError(
        "review-expansion",
        "Immutable evidence read failed.",
      )
    }
    return Files.newInputStream(output).use { it.readNBytes(minOf(maxBytes + 1, Int.MAX_VALUE.toLong()).toInt()) }
  } finally {
    Files.deleteIfExists(output)
  }
}

private fun awaitImmutableReviewCommand(process: Process) {
  val completed = try {
    process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
  } catch (error: InterruptedException) {
    process.destroyForcibly()
    Thread.currentThread().interrupt()
    throw error
  }
  if (!completed) {
    process.destroyForcibly()
    throw InvalidReviewContextSchemaError("review-expansion", "Immutable evidence read timed out.")
  }
}
