package dev.skillbill.runtime.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class RuntimeImageLicenseTest {
  @Test
  fun `refuses to stage an absent repository license`() {
    val absent = Files.createTempDirectory("skillbill-missing-license").resolve("LICENSE")
    val staged = Files.createTempDirectory("skillbill-staged-license").resolve("LICENSE")

    val failure = assertThrows<IllegalArgumentException> {
      RuntimeImageLicense.stage(absent, listOf(staged))
    }

    assertEquals("Repository LICENSE is missing at $absent.", failure.message)
    assertFalse(Files.exists(staged))
  }

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
