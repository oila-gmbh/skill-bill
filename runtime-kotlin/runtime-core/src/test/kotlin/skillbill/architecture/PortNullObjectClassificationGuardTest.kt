package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.Test
import kotlin.test.assertEquals

class PortNullObjectClassificationGuardTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) workingDir.parent else workingDir
    }

  private val scanRoots: List<Path> = listOf(
    runtimeRoot.resolve("runtime-ports/src/main"),
    runtimeRoot.resolve("runtime-domain/src/main"),
    runtimeRoot.resolve("runtime-application/src/main"),
  )

  private val objectPattern =
    Regex("""(?<!data )object\s+((?:Unavailable|Noop|Empty|Unconfigured)\w*)""")

  @Test
  fun `every port null object in main source is classified`() {
    val discovered = scanRoots
      .filter { Files.isDirectory(it) }
      .flatMap { root ->
        Files.walk(root).use { paths ->
          paths
            .filter { Files.isRegularFile(it) && it.extension == "kt" }
            .map { path -> Files.readString(path) }
            .toList()
        }
      }
      .flatMap { content -> objectPattern.findAll(content).map { it.groupValues[1] } }
      .toSet()

    assertEquals(
      PortNullObjectClassification.classifiedObjects.keys,
      discovered,
      "Port null-object census drifted from PortNullObjectClassification. Add the new object with " +
        "total refusal, recording null object, or diagnostic sink classification, then update " +
        "ARCHITECTURE.md and RecordingNullObjectDiagnosticsTest when the object records swallows.",
    )
  }
}
