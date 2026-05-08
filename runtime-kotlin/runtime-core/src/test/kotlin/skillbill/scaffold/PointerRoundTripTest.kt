package skillbill.scaffold

import org.junit.jupiter.api.Assumptions
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PointerRoundTripTest {
  @Test
  fun `every declared pointer round-trips byte-equal with the rendered output`() {
    val repoRoot = findRepoRoot() ?: run {
      Assumptions.assumeTrue(
        false,
        "Skipping pointer round-trip test: could not locate repo root " +
          "(set SKILL_BILL_REPO_ROOT or run from inside repo)",
      )
      return
    }
    val packsRoot = repoRoot.resolve("platform-packs")
    if (!Files.isDirectory(packsRoot)) {
      Assumptions.assumeTrue(false, "platform-packs/ not present; skipping round-trip")
      return
    }
    val packs = discoverPlatformPackManifests(packsRoot)
    val checked = mutableListOf<Path>()
    packs.forEach { pack ->
      pack.pointers.forEach { spec ->
        val pointerFile = pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name)
        // Pointer files are checked in as symlinks (mode 120000); on Linux/macOS they are real
        // symlinks, on Windows fallback (core.symlinks=false) they are regular text files
        // containing the symlink target string. Both forms must round-trip the renderer output.
        assertTrue(
          Files.isSymbolicLink(pointerFile) ||
            Files.isRegularFile(pointerFile, LinkOption.NOFOLLOW_LINKS),
          "Declared pointer is missing on disk: $pointerFile",
        )
        val rendered = renderPointer(repoRoot, pack.packRoot, spec)
        val onDisk = if (Files.isSymbolicLink(pointerFile)) {
          Files.readSymbolicLink(pointerFile).toString().replace(File.separatorChar, '/')
        } else {
          Files.readString(pointerFile).trimEnd('\n', '\r')
        }
        assertEquals(
          rendered.trimEnd('\n'),
          onDisk,
          "Pointer drift at $pointerFile: declared target='${spec.target}'",
        )
        checked.add(pointerFile)
      }
    }
    assertTrue(checked.isNotEmpty(), "Expected to round-trip at least one declared pointer")
  }

  private fun findRepoRoot(): Path? {
    val envRoot = System.getenv("SKILL_BILL_REPO_ROOT")
      ?.takeIf { it.isNotBlank() }
      ?.let { Path.of(it).toAbsolutePath().normalize() }
      ?.takeIf(::looksLikeRepoRoot)
    var current: Path? = envRoot ?: Path.of("").toAbsolutePath().normalize()
    var found: Path? = envRoot
    while (found == null && current != null) {
      if (looksLikeRepoRoot(current)) {
        found = current
      } else {
        current = current.parent
      }
    }
    return found
  }

  private fun looksLikeRepoRoot(candidate: Path): Boolean {
    val hasSettings = Files.isRegularFile(candidate.resolve("settings.gradle.kts")) ||
      Files.isRegularFile(candidate.resolve("runtime-kotlin/settings.gradle.kts"))
    val hasSkills = Files.isDirectory(candidate.resolve("skills"))
    return hasSettings && hasSkills
  }
}
