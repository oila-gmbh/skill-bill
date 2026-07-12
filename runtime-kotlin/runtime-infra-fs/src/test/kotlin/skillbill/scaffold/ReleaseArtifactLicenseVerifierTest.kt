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
    writeZip(zip, listOf("image/LICENSE" to rootLicenseBytes))
    writeTar(tar, rootLicenseBytes)
    writeChecksum(zip)
    writeChecksum(tar)

    assertEquals(0, runVerifier(zip, tar).exitCode)
  }

  @Test
  fun `uses canonical paths without requiring bash four collection helpers`() {
    val verifier = Files.readString(repoRoot.resolve("scripts/verify_release_artifact_licenses"))

    assertTrue(verifier.contains("image/LICENSE"))
    assertTrue(verifier.contains("resources/skill-bill-runtime/LICENSE"))
    assertTrue(!verifier.contains("mapfile"))
  }

  @Test
  fun `rejects a missing or byte-drifted license entry`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val absent = fixture.resolve("absent.zip")
    val drifted = fixture.resolve("drifted.zip")
    writeZip(absent, listOf("runtime/README" to "no license".encodeToByteArray()))
    writeZip(drifted, listOf("image/LICENSE" to "different license".encodeToByteArray()))
    writeChecksum(absent)
    writeChecksum(drifted)

    assertFailure(absent, "canonical image/LICENSE")
    assertFailure(drifted, "LICENSE bytes differ")
  }

  @Test
  fun `permits third party license entries beside the canonical runtime license`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("runtime.zip")
    writeZip(
      artifact,
      listOf(
        "image/LICENSE" to rootLicenseBytes,
        "image/legal/java.base/LICENSE" to "JDK license".encodeToByteArray(),
      ),
    )
    writeChecksum(artifact)

    assertEquals(0, runVerifier(artifact).exitCode)
  }

  @Test
  fun `rejects duplicate canonical skills licenses`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("skills.tar.gz")
    writeTar(artifact, rootLicenseBytes, duplicateLicense = true)
    writeChecksum(artifact)

    assertFailure(artifact, "exactly one canonical LICENSE")
  }

  @Test
  fun `rejects an absent or mismatched checksum sidecar`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("checksummed.zip")
    writeZip(artifact, listOf("image/LICENSE" to rootLicenseBytes))

    assertFailure(artifact, "Missing checksum")
    Files.writeString(artifact.resolveSibling("${artifact.name}.sha256"), "${"0".repeat(64)}  ${artifact.name}\n")
    assertFailure(artifact, "Checksum digest differs")
  }

  @Test
  fun `rejects a checksum sidecar for a sibling decoy`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("checksummed.zip")
    writeZip(artifact, listOf("image/LICENSE" to rootLicenseBytes))
    Files.writeString(
      artifact.resolveSibling("${artifact.name}.sha256"),
      "${sha256(artifact)}  decoy.zip\n",
    )

    assertFailure(artifact, "names decoy.zip")
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

  private fun writeTar(artifact: Path, licenseBytes: ByteArray, duplicateLicense: Boolean = false) {
    val source = Files.createTempDirectory("skillbill-artifact-tar-source")
    Files.write(source.resolve("LICENSE"), licenseBytes)
    Files.writeString(source.resolve("README.md"), "Skill Bill skills archive fixture\n")
    val process =
      ProcessBuilder(
        listOf("tar", "-czf", artifact.toString(), "-C", source.toString(), "LICENSE", "README.md") +
          if (duplicateLicense) listOf("LICENSE") else emptyList(),
      )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
  }

  private fun writeChecksum(artifact: Path) {
    Files.writeString(artifact.resolveSibling("${artifact.name}.sha256"), "${sha256(artifact)}  ${artifact.name}\n")
  }

  private fun sha256(artifact: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  private data class ProcessResult(val exitCode: Int, val output: String)
}
