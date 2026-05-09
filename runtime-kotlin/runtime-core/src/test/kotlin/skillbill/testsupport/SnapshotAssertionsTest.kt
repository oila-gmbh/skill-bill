package skillbill.testsupport

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotAssertionsTest {
  @Test
  fun `missing fixture failure names fixture path and update hint`() {
    withSnapshotUpdatesDisabled {
      val resourceRoot = Files.createTempDirectory("skillbill-snapshots-")
      val fixturePath = "snapshots/missing.txt"
      val expectedFixture = resourceRoot.resolve(fixturePath).toAbsolutePath().normalize().toString()

      val error = assertFailsWith<AssertionError> {
        SnapshotAssertions.assertMatchesSnapshot(fixturePath, "actual\n", resourceRoot)
      }

      assertTrue(error.message.orEmpty().contains(expectedFixture), error.message)
      assertTrue(error.message.orEmpty().contains("-Pupdate-snapshots"), error.message)
    }
  }

  @Test
  fun `stale fixture failure names fixture path and update hint`() {
    withSnapshotUpdatesDisabled {
      val resourceRoot = Files.createTempDirectory("skillbill-snapshots-")
      val fixturePath = "snapshots/stale.txt"
      val fixture = resourceRoot.resolve(fixturePath).toAbsolutePath().normalize()
      Files.createDirectories(fixture.parent)
      Files.writeString(fixture, "expected\n")

      val error = assertFailsWith<AssertionError> {
        SnapshotAssertions.assertMatchesSnapshot(fixturePath, "actual\n", resourceRoot)
      }

      assertTrue(error.message.orEmpty().contains(fixture.toString()), error.message)
      assertTrue(error.message.orEmpty().contains("-Pupdate-snapshots"), error.message)
    }
  }

  @Test
  fun `update mode writes normalized fixture output`() {
    withSnapshotUpdatesEnabled {
      val resourceRoot = Files.createTempDirectory("skillbill-snapshots-")
      val fixturePath = "snapshots/created.txt"
      val fixture = resourceRoot.resolve(fixturePath).toAbsolutePath().normalize()

      SnapshotAssertions.assertMatchesSnapshot(fixturePath, "actual\r\noutput\r", resourceRoot)

      assertTrue(Files.isRegularFile(fixture))
      assertTrue(Files.readString(fixture) == "actual\noutput\n")
    }
  }

  @Test
  fun `escaped fixture path is rejected`() {
    val resourceRoot = Files.createTempDirectory("skillbill-snapshots-")

    val error = assertFailsWith<IllegalArgumentException> {
      SnapshotAssertions.assertMatchesSnapshot("../escaped.txt", "actual\n", resourceRoot)
    }

    assertTrue(error.message.orEmpty().contains("src/test/resources"), error.message)
  }

  private fun withSnapshotUpdatesEnabled(block: () -> Unit) {
    val previous = System.getProperty("update-snapshots")
    System.setProperty("update-snapshots", "true")
    try {
      block()
    } finally {
      if (previous == null) {
        System.clearProperty("update-snapshots")
      } else {
        System.setProperty("update-snapshots", previous)
      }
    }
  }

  private fun withSnapshotUpdatesDisabled(block: () -> Unit) {
    val previous = System.getProperty("update-snapshots")
    System.clearProperty("update-snapshots")
    try {
      block()
    } finally {
      if (previous == null) {
        System.clearProperty("update-snapshots")
      } else {
        System.setProperty("update-snapshots", previous)
      }
    }
  }
}
