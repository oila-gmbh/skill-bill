package dev.skillbill.runtime.buildlogic

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuntimeImageLicenseTest {
  @Test
  fun `reports absent staged license`() {
    val root = Files.createTempFile("skillbill-license", ".txt")
    Files.writeString(root, "root license\n")
    val absent = root.parent.resolve("missing-license")

    assertFalse(RuntimeImageLicense.matches(root, absent))
  }

  @Test
  fun `stages root license byte for byte`() {
    val root = Files.createTempFile("skillbill-license", ".txt")
    val staged = Files.createTempDirectory("skillbill-staged-license").resolve("LICENSE")
    Files.writeString(root, "root license\n")

    RuntimeImageLicense.stage(root, listOf(staged))

    assertTrue(RuntimeImageLicense.matches(root, staged))
  }

  @Test
  fun `reports byte drift`() {
    val root = Files.createTempFile("skillbill-license", ".txt")
    val staged = Files.createTempFile("skillbill-staged-license", ".txt")
    Files.writeString(root, "root license\n")
    Files.writeString(staged, "altered license\n")

    assertFalse(RuntimeImageLicense.matches(root, staged))
  }
}
