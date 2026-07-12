package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseArtifactLicenseVerifierTest {
  private val repoRoot: Path =
    generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
      .first { Files.isRegularFile(it.resolve("LICENSE")) }
  private val rootLicenseBytes: ByteArray = Files.readAllBytes(repoRoot.resolve("LICENSE"))

  @Test
  fun `accepts zip and tar artifacts carrying the root license and valid checksums`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val zip = fixture.resolve("runtime.zip")
    val tar = fixture.resolve("skills.tar.gz")
    writeZip(zip, listOf("runtime/LICENSE" to rootLicenseBytes))
    writeTar(tar, rootLicenseBytes)
    writeChecksum(zip)
    writeChecksum(tar)

    assertEquals(0, runVerifier(zip, tar).exitCode)
  }

  @Test
  fun `rejects a missing or byte-drifted license entry`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val absent = fixture.resolve("absent.zip")
    val drifted = fixture.resolve("drifted.zip")
    writeZip(absent, listOf("runtime/README" to "no license".encodeToByteArray()))
    writeZip(drifted, listOf("runtime/LICENSE" to "different license".encodeToByteArray()))
    writeChecksum(absent)
    writeChecksum(drifted)

    assertFailure(absent, "exactly one LICENSE entry")
    assertFailure(drifted, "LICENSE bytes differ")
  }

  @Test
  fun `rejects duplicate license entries`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("duplicate.zip")
    writeZip(
      artifact,
      listOf(
        "runtime/LICENSE" to rootLicenseBytes,
        "docs/LICENSE" to rootLicenseBytes,
      ),
    )
    writeChecksum(artifact)

    assertFailure(artifact, "exactly one LICENSE entry")
  }

  @Test
  fun `rejects an absent or mismatched checksum sidecar`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("checksummed.zip")
    writeZip(artifact, listOf("runtime/LICENSE" to rootLicenseBytes))

    assertFailure(artifact, "Missing checksum")
    Files.writeString(artifact.resolveSibling("${artifact.name}.sha256"), "${"0".repeat(64)}  ${artifact.name}\n")
    assertFailure(artifact, "FAILED")
  }

  private fun assertFailure(artifact: Path, expectedMessage: String) {
    val result = runVerifier(artifact)
    assertTrue(result.exitCode != 0, result.output)
    assertTrue(result.output.contains(expectedMessage), result.output)
  }

  private fun runVerifier(vararg artifacts: Path): ProcessResult {
    val process =
      ProcessBuilder(
        listOf("bash", repoRoot.resolve("scripts/verify_release_artifact_licenses").toString()) +
          artifacts.map(Path::toString),
      )
        .directory(repoRoot.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    return ProcessResult(process.waitFor(), output)
  }

  private fun writeZip(artifact: Path, entries: List<Pair<String, ByteArray>>) {
    ZipOutputStream(Files.newOutputStream(artifact)).use { output ->
      entries.forEach { (name, content) ->
        output.putNextEntry(ZipEntry(name))
        output.write(content)
        output.closeEntry()
      }
    }
  }

  private fun writeTar(artifact: Path, licenseBytes: ByteArray) {
    val source = Files.createTempDirectory("skillbill-artifact-tar-source")
    Files.write(source.resolve("LICENSE"), licenseBytes)
    val process =
      ProcessBuilder("tar", "-czf", artifact.toString(), "-C", source.toString(), ".")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
  }

  private fun writeChecksum(artifact: Path) {
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))
      .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    Files.writeString(artifact.resolveSibling("${artifact.name}.sha256"), "$digest  ${artifact.name}\n")
  }

  private data class ProcessResult(val exitCode: Int, val output: String)
}
