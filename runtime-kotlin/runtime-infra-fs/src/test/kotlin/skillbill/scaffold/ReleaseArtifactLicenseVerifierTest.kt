package skillbill.scaffold

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    assertTrue(verifier.contains("cpio -it --quiet"))
    assertTrue(verifier.contains("lessmsi x"))
    assertFalse(verifier.contains("cpio -idm"))
    assertFalse(verifier.contains("msiexec.exe"))
    assertFalse(verifier.contains("hdiutil detach \"\${mount_dir}\" >/dev/null 2>&1 || true"))
    assertTrue(verifier.contains("Could not detach mounted disk image"))
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
  fun `rejects missing and byte-drifted skills tar licenses`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val absent = fixture.resolve("absent.tar.gz")
    val drifted = fixture.resolve("drifted.tar.gz")
    writeTar(absent, null)
    writeTar(drifted, "different license".encodeToByteArray())
    writeChecksum(absent)
    writeChecksum(drifted)

    assertFailure(absent, "exactly one canonical LICENSE")
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
  fun `exercises every desktop verifier branch with valid absent and byte-drifted archive fixtures`() {
    listOf("deb", "rpm", "dmg", "msi").forEach(::assertDesktopLicenseVerification)
  }

  @Test
  fun `fails when a mounted dmg cannot be detached`() {
    val fixture = Files.createTempDirectory("skillbill-dmg-detach-verifier")
    val artifact = fixture.resolve("valid.dmg")
    writeArchiveBackedDesktopFixture(artifact, rootLicenseBytes)
    writeChecksum(artifact)

    val result = runDesktopVerifier(artifact, mapOf("SKILLBILL_TEST_DETACH_FAILURE" to "true"))

    assertTrue(result.exitCode != 0, result.output)
    assertTrue(result.output.contains("Could not detach mounted disk image"), result.output)
  }

  @Test
  fun `rejects unsafe rpm archive entries and canonical license symlinks`() {
    val fixture = Files.createTempDirectory("skillbill-rpm-verifier-safety")
    val unsafe = fixture.resolve("unsafe.rpm")
    val symlink = fixture.resolve("symlink.rpm")
    writeArchiveBackedDesktopFixture(unsafe, rootLicenseBytes, unsafePath = true)
    writeArchiveBackedDesktopFixture(symlink, rootLicenseBytes, canonicalLicenseIsSymlink = true)
    writeChecksum(unsafe)
    writeChecksum(symlink)

    assertDesktopFailure(unsafe, "Unsafe archive entry")
    assertDesktopFailure(symlink, "must be one regular file")
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

  private fun assertDesktopLicenseVerification(extension: String) {
    val fixture = Files.createTempDirectory("skillbill-desktop-artifact-verifier")
    val valid = fixture.resolve("valid.$extension")
    val absent = fixture.resolve("absent.$extension")
    val drifted = fixture.resolve("drifted.$extension")
    writeArchiveBackedDesktopFixture(valid, rootLicenseBytes)
    writeArchiveBackedDesktopFixture(absent, null)
    writeArchiveBackedDesktopFixture(drifted, "different license".encodeToByteArray())
    listOf(valid, absent, drifted).forEach(::writeChecksum)

    val validResult = runDesktopVerifier(valid)
    assertEquals(0, validResult.exitCode, validResult.output)
    assertDesktopFailure(absent, "canonical resources/skill-bill-runtime/LICENSE")
    assertDesktopFailure(drifted, "LICENSE bytes differ")
  }

  private fun assertDesktopFailure(artifact: Path, expectedMessage: String) {
    val result = runDesktopVerifier(artifact)
    assertTrue(result.exitCode != 0, result.output)
    assertTrue(result.output.contains(expectedMessage), result.output)
  }

  private fun runVerifier(vararg artifacts: Path): ProcessResult {
    return runVerifier(artifacts.toList())
  }

  private fun runDesktopVerifier(artifact: Path, environment: Map<String, String> = emptyMap()): ProcessResult {
    val shims = Files.createTempDirectory("skillbill-verifier-shims")
    writeDesktopToolDoubles(shims)
    return runVerifier(
      listOf(artifact),
      environment + ("PATH" to "$shims:${System.getenv("PATH")}"),
    )
  }

  private fun runVerifier(artifacts: List<Path>, environment: Map<String, String> = emptyMap()): ProcessResult {
    val processBuilder =
      ProcessBuilder(
        listOf("bash", repoRoot.resolve("scripts/verify_release_artifact_licenses").toString()) +
          artifacts.map(Path::toString),
      )
        .directory(repoRoot.toFile())
        .redirectErrorStream(true)
    processBuilder.environment().putAll(environment)
    val process = processBuilder.start()
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

  private fun writeTar(artifact: Path, licenseBytes: ByteArray?, duplicateLicense: Boolean = false) {
    val source = Files.createTempDirectory("skillbill-artifact-tar-source")
    Files.writeString(source.resolve("README.md"), "Skill Bill skills archive fixture\n")
    if (licenseBytes != null) {
      Files.write(source.resolve("LICENSE"), licenseBytes)
    }
    val entries = if (licenseBytes == null) {
      listOf("README.md")
    } else {
      listOf("LICENSE", "README.md") + if (duplicateLicense) listOf("LICENSE") else emptyList()
    }
    val process =
      ProcessBuilder(
        listOf("tar", "-czf", artifact.toString(), "-C", source.toString()) + entries,
      )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
  }

  private fun writeArchiveBackedDesktopFixture(
    artifact: Path,
    licenseBytes: ByteArray?,
    unsafePath: Boolean = false,
    canonicalLicenseIsSymlink: Boolean = false,
  ) {
    val source = Files.createTempDirectory("skillbill-desktop-artifact-source")
    val license = source.resolve("SkillBill/resources/skill-bill-runtime/LICENSE")
    if (licenseBytes != null) {
      Files.createDirectories(license.parent)
      if (canonicalLicenseIsSymlink) {
        val target = source.resolve("license-target")
        Files.write(target, licenseBytes)
        Files.createSymbolicLink(license, Path.of("../../../${target.fileName}"))
      } else {
        Files.write(license, licenseBytes)
      }
    } else {
      Files.createDirectories(source.resolve("SkillBill/resources"))
      Files.writeString(source.resolve("SkillBill/resources/README.md"), "no license\n")
    }
    val command = mutableListOf("tar", "-cf", artifact.toString())
    if (unsafePath) {
      command += "--transform=s#^\\./#../#"
    }
    command += listOf("-C", source.toString(), ".")
    val process = ProcessBuilder(command)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
  }

  private fun writeDesktopToolDoubles(shims: Path) {
    writeExecutable(shims.resolve("dpkg-deb"), "#!/usr/bin/env bash\nset -euo pipefail\ncat \"${'$'}2\"\n")
    writeExecutable(shims.resolve("rpm2cpio"), "#!/usr/bin/env bash\nset -euo pipefail\ncat \"${'$'}1\"\n")
    writeExecutable(
      shims.resolve("cpio"),
      """
      #!/usr/bin/env bash
      set -euo pipefail
      if [[ " ${'$'}* " == *" -itv "* ]]; then
        tar -tvf -
      elif [[ " ${'$'}* " == *" --to-stdout "* ]]; then
        tar -xOf - "${'$'}{!#}"
      else
        tar -tf -
      fi
      """.trimIndent() + "\n",
    )
    writeExecutable(
      shims.resolve("hdiutil"),
      """
      #!/usr/bin/env bash
      set -euo pipefail
      if [[ "${'$'}1" == "detach" ]]; then
        if [[ "${'$'}{SKILLBILL_TEST_DETACH_FAILURE:-}" == "true" ]]; then
          exit 1
        fi
        exit 0
      fi
      mountpoint=""
      args=("${'$'}@")
      for ((index = 0; index < ${'$'}{#args[@]}; index++)); do
        if [[ "${'$'}{args[index]}" == "-mountpoint" ]]; then
          mountpoint="${'$'}{args[index + 1]}"
        fi
      done
      tar -xf "${'$'}{args[${'$'}{#args[@]} - 1]}" -C "${'$'}mountpoint"
      """.trimIndent() + "\n",
    )
    writeExecutable(
      shims.resolve("lessmsi"),
      "#!/usr/bin/env bash\nset -euo pipefail\ntar -xf \"${'$'}2\" -C \"${'$'}3\"\n",
    )
  }

  private fun writeExecutable(path: Path, contents: String) {
    Files.writeString(path, contents)
    assertTrue(path.toFile().setExecutable(true), "Could not mark $path executable")
  }

  private fun writeChecksum(artifact: Path) {
    Files.writeString(artifact.resolveSibling("${artifact.name}.sha256"), "${sha256(artifact)}  ${artifact.name}\n")
  }

  private fun sha256(artifact: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  private data class ProcessResult(val exitCode: Int, val output: String)
}
