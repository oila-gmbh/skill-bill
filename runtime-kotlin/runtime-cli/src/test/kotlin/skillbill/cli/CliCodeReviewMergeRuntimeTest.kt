package skillbill.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliCodeReviewMergeRuntimeTest {
  @Test
  fun `code-review-merge command is registered and shows help`() {
    val result = CliRuntime.run(listOf("code-review-merge", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "--lane1-agent")
    assertContains(result.stdout, "--lane2-agent")
    assertContains(result.stdout, "--lane1")
    assertContains(result.stdout, "--lane2")
  }

  @Test
  fun `code-review-merge merges two lane files with provenance labels`() {
    val lane1 = writeTempFile("- [F-001] Major | High | Foo.kt:10 | null check missing")
    val lane2 = writeTempFile(
      "- [F-001] Major | High | Foo.kt:10 | null check missing\n" +
        "- [F-002] Minor | Low | Bar.kt:5 | unused import",
    )

    val result = CliRuntime.run(
      listOf(
        "code-review-merge",
        "--lane1-agent", "claude", "--lane1", lane1,
        "--lane2-agent", "codex", "--lane2", lane2,
      ),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "[claude, codex]")
    assertContains(result.stdout, "[codex]")
  }

  @Test
  fun `code-review-merge produces empty output when both lanes have no parseable findings`() {
    val lane1 = writeTempFile("No findings here.")
    val lane2 = writeTempFile("Also no findings.")

    val result = CliRuntime.run(
      listOf(
        "code-review-merge",
        "--lane1-agent", "claude", "--lane1", lane1,
        "--lane2-agent", "codex", "--lane2", lane2,
      ),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("", result.stdout.trim())
  }

  @Test
  fun `code-review-merge exits 1 when lane1 file does not exist`() {
    val lane2 = writeTempFile("- [F-001] Minor | Low | Foo.kt:1 | issue")

    val result = CliRuntime.run(
      listOf(
        "code-review-merge",
        "--lane1-agent", "claude", "--lane1", "/nonexistent/lane1.txt",
        "--lane2-agent", "codex", "--lane2", lane2,
      ),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
  }

  @Test
  fun `code-review-merge exits 1 when lane2 file does not exist`() {
    val lane1 = writeTempFile("- [F-001] Major | High | Foo.kt:1 | issue")

    val result = CliRuntime.run(
      listOf(
        "code-review-merge",
        "--lane1-agent", "claude", "--lane1", lane1,
        "--lane2-agent", "codex", "--lane2", "/nonexistent/lane2.txt",
      ),
      CliRuntimeContext(),
    )

    assertEquals(1, result.exitCode, result.stdout)
  }

  @Test
  fun `code-review-merge coalesced finding appears before single-lane finding within the same tier`() {
    val lane1 = writeTempFile(
      "- [F-001] Major | High | Foo.kt:10 | shared issue\n" +
        "- [F-002] Major | Medium | Only.kt:1 | lane1 only",
    )
    val lane2 = writeTempFile("- [F-001] Major | High | Foo.kt:10 | shared issue")

    val result = CliRuntime.run(
      listOf(
        "code-review-merge",
        "--lane1-agent", "claude", "--lane1", lane1,
        "--lane2-agent", "codex", "--lane2", lane2,
      ),
      CliRuntimeContext(),
    )

    assertEquals(0, result.exitCode, result.stdout)
    val lines = result.stdout.lines().filter(String::isNotBlank)
    // coalesced finding comes first
    assertContains(lines[0], "[claude, codex]")
    assertContains(lines[1], "[claude]")
  }
}

private fun writeTempFile(content: String): String {
  val file = Files.createTempFile("code-review-merge-test", ".txt")
  file.toFile().writeText(content)
  return file.toString()
}
