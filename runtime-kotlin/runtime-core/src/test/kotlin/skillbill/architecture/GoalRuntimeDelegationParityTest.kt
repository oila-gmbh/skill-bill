package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GoalRuntimeDelegationParityTest {
  @Test
  fun `repo-local and installed goal runtime outputs remain semantically aligned`() {
    val root = Files.createTempDirectory("skillbill-goal-runtime-parity")
    val repoRuntime = root.resolve("repo/runtime-cli/bin/runtime-cli")
    val installedRuntime = root.resolve("home/.skill-bill/runtime/runtime-cli/bin/runtime-cli")
    writeGoalStub(repoRuntime)
    writeGoalStub(installedRuntime)

    val repoOutput = runGoal(repoRuntime, home = root.resolve("home"), exportedExecutable = null)
    val installedOutput = runGoal(
      installedRuntime,
      home = root.resolve("home"),
      exportedExecutable = installedRuntime.toAbsolutePath().normalize().toString(),
    )

    val normalizedRepo = normalizeGoalSemantics(repoOutput)
    val normalizedInstalled = normalizeGoalSemantics(installedOutput)
    assertEquals(normalizedRepo, normalizedInstalled)
    assertContains(
      normalizedRepo,
      "goal SKILL-901: runtime executable=<runtime> version=0.3.0-SNAPSHOT build_id=0.3.0-SNAPSHOT",
    )
    assertContains(
      normalizedRepo,
      "goal SKILL-901: heartbeat subtask=1 step=implement liveness=durable_progress",
    )
    assertContains(
      normalizedRepo,
      "goal SKILL-901: completion confirmed complete=1 pending=0 blocked=0 pr_status=opened",
    )
    assertContains(normalizedRepo, "status: complete")
  }

  private fun writeGoalStub(runtime: Path) {
    Files.createDirectories(runtime.parent)
    Files.writeString(
      runtime,
      """
      |#!/usr/bin/env bash
      |set -euo pipefail
      |if [[ "${'$'}{1:-}" == "--home" ]]; then
      |  shift 2
      |fi
      |if [[ "${'$'}{1:-}" != "goal" ]]; then
      |  exit 2
      |fi
      |runtime_executable="${'$'}{SKILL_BILL_RUNTIME_EXECUTABLE:-${'$'}0}"
      |echo "goal SKILL-901: runtime executable=${'$'}runtime_executable version=0.3.0-SNAPSHOT build_id=0.3.0-SNAPSHOT"
      |echo "goal SKILL-901: heartbeat subtask=1 step=implement liveness=durable_progress"
      |echo "goal SKILL-901: completion confirmed complete=1 pending=0 blocked=0 pr_status=opened"
      |echo "goal: SKILL-901"
      |echo "status: complete"
      |exit 0
      """.trimMargin(),
    )
    runtime.toFile().setExecutable(true)
  }

  private fun runGoal(runtime: Path, home: Path, exportedExecutable: String?): String {
    val process = ProcessBuilder(
      runtime.toString(),
      "--home",
      home.toString(),
      "goal",
      "SKILL-901",
      "--agent",
      "codex",
      "--repo-root",
      home.toString(),
    )
      .redirectErrorStream(true)
      .apply {
        if (!exportedExecutable.isNullOrBlank()) {
          environment()["SKILL_BILL_RUNTIME_EXECUTABLE"] = exportedExecutable
        }
      }
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
    return output
  }

  private fun normalizeGoalSemantics(output: String): String =
    output.replace(Regex("""runtime executable=\S+"""), "runtime executable=<runtime>")
}
