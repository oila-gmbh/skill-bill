package skillbill.testsupport

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.fail

object SnapshotAssertions {
  private const val UPDATE_PROPERTY = "update-snapshots"
  private const val UPDATE_HINT = "-Pupdate-snapshots"

  fun assertMatchesSnapshot(fixturePath: String, actual: String, resourceRoot: Path = defaultResourceRoot()) {
    require(Path.of(fixturePath).isAbsolute.not()) {
      "Snapshot fixture path must be relative to src/test/resources: $fixturePath"
    }
    val normalizedResourceRoot = resourceRoot.toAbsolutePath().normalize()
    val fixture = normalizedResourceRoot.resolve(fixturePath).normalize()
    require(fixture.startsWith(normalizedResourceRoot)) {
      "Snapshot fixture path must stay under src/test/resources: $fixturePath"
    }
    val normalizedActual = normalizeLineEndings(actual)
    if (updateSnapshotsEnabled()) {
      Files.createDirectories(fixture.parent)
      Files.writeString(fixture, normalizedActual)
      return
    }
    if (!Files.isRegularFile(fixture)) {
      fail("Snapshot fixture '$fixture' is missing. Re-run with $UPDATE_HINT to create it.")
    }
    val expected = normalizeLineEndings(Files.readString(fixture))
    if (expected != normalizedActual) {
      fail("Snapshot fixture '$fixture' does not match rendered output. Re-run with $UPDATE_HINT to update it.")
    }
  }

  fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

  internal fun updateSnapshotsEnabled(): Boolean = System.getProperty(UPDATE_PROPERTY) != null

  private fun defaultResourceRoot(): Path = System.getProperty("skillbill.snapshotResourceRoot")
    ?.takeIf { it.isNotBlank() }
    ?.let(Path::of)
    ?: Path.of("src/test/resources")
}
