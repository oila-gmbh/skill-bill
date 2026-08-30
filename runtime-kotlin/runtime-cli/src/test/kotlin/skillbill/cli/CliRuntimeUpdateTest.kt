package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.core.ExternalCommandResult
import skillbill.cli.model.CliRuntimeContext
import skillbill.ports.telemetry.HttpRequester
import skillbill.ports.telemetry.model.HttpResponse
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliRuntimeUpdateTest {
  @Test
  fun `update dry-run prints installer command with reuse last selection by default`() {
    val text = CliRuntime.run(listOf("update", "--dry-run"))

    assertEquals(0, text.exitCode, text.stdout)
    assertContains(text.stdout, "status: dry_run")
    assertContains(text.stdout, "command: $EXPECTED_UPDATE_COMMAND")
  }

  @Test
  fun `update dry-run json includes composed installer args`() {
    val json = CliRuntime.run(
      listOf(
        "update",
        "--dry-run",
        "--format",
        "json",
        "--release",
        "v0.2.0",
        "--clean",
      ),
    )
    val payload = decodeJsonObject(json.stdout)

    assertEquals(0, json.exitCode, json.stdout)
    assertEquals("dry_run", payload["status"])
    assertEquals(
      "$EXPECTED_UPDATE_COMMAND --release v0.2.0 --clean",
      payload["command"],
    )
    assertEquals(
      listOf("--reuse-last-selection", "--release", "v0.2.0", "--clean"),
      payload["installer_args"],
    )
  }

  @Test
  fun `update dry-run json escapes shell-quoted command arguments`() {
    val releaseTag = "tag\"\\with quote"
    val json = CliRuntime.run(
      listOf(
        "update",
        "--dry-run",
        "--format",
        "json",
        "--release",
        releaseTag,
      ),
    )
    val payload = decodeJsonObject(json.stdout)

    assertEquals(0, json.exitCode, json.stdout)
    assertEquals("dry_run", payload["status"])
    assertEquals("$EXPECTED_UPDATE_COMMAND --release 'tag\"\\with quote'", payload["command"])
    assertEquals(listOf("--reuse-last-selection", "--release", releaseTag), payload["installer_args"])
  }

  @Test
  fun `update runs installer with selected home and configured environment`() {
    val home = Files.createTempDirectory("skillbill-update-home")
    val runner = CapturingExternalCommandRunner(ExternalCommandResult(exitCode = 0, output = "installer ok\n"))
    val result = CliRuntime.run(
      listOf(
        "--home",
        home.toString(),
        "update",
        "--release",
        "v0.4.0",
        "--format",
        "json",
      ),
      CliRuntimeContext(
        environment = mapOf("HOME" to "/wrong-home", "PATH" to "/test/bin"),
        externalCommandRunner = runner,
      ),
    )
    val payload = decodeJsonObject(result.stdout)
    val command = runner.commands.single()

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("completed", payload["status"])
    assertEquals("installer ok\n", payload["installer_output"])
    assertEquals("bash", command.executable)
    assertEquals(
      listOf("-c", "$EXPECTED_UPDATE_COMMAND --release v0.4.0"),
      command.arguments,
    )
    assertEquals(home.toString(), command.environment["HOME"])
    assertEquals("/test/bin", command.environment["PATH"])
  }

  @Test
  fun `update propagates installer failure exit code and output`() {
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val runner = CapturingExternalCommandRunner(ExternalCommandResult(exitCode = 7, output = "installer failed\n"))
    val result = CliRuntime.run(
      listOf("update", "--format", "json"),
      CliRuntimeContext(
        requester = updateCheckRequester(capturedRequests),
        externalCommandRunner = runner,
      ),
    )
    val payload = decodeJsonObject(result.stdout)

    assertEquals(7, result.exitCode, result.stdout)
    assertEquals("failed", payload["status"])
    assertEquals(7, payload["exit_code"])
    assertEquals("installer failed\n", payload["installer_output"])
  }

  @Test
  fun `update skips installer when installed version is ahead of latest release`() {
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val runner = CapturingExternalCommandRunner(ExternalCommandResult(exitCode = 0, output = "should not run\n"))
    val result = CliRuntime.run(
      listOf("update", "--format", "json"),
      CliRuntimeContext(
        requester = updateCheckRequester(capturedRequests, latest = INSTALLED_BASE_TAG),
        externalCommandRunner = runner,
      ),
    )
    val payload = decodeJsonObject(result.stdout)
    val updateCheck = payload["update_check"] as Map<*, *>

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("skipped", payload["status"])
    assertEquals("ahead_of_release", updateCheck["status"])
    assertEquals("installed version is newer than the latest release", payload["reason"])
    assertTrue(runner.commands.isEmpty(), "installer must not run when local version is ahead")
    assertEquals(1, capturedRequests.size)
  }

  @Test
  fun `update-check includes prereleases and returns unknown with exit zero`() {
    val prerelease = CliRuntime.run(
      listOf("update-check", "--include-prereleases", "--format", "json"),
      CliRuntimeContext(requester = updateCheckRequester(mutableListOf(), latest = NEWER_PRERELEASE_TAG)),
    )
    val prereleasePayload = decodeJsonObject(prerelease.stdout)

    assertEquals(0, prerelease.exitCode, prerelease.stdout)
    assertEquals("update_available", prereleasePayload["status"])
    assertEquals(NEWER_PRERELEASE_TAG, prereleasePayload["latest_version"])

    val unknown = CliRuntime.run(
      listOf("update-check"),
      CliRuntimeContext(requester = HttpRequester { _, _, _, _ -> HttpResponse(429, "") }),
    )

    assertEquals(0, unknown.exitCode, unknown.stdout)
    assertContains(unknown.stdout, "status: unknown")
    assertContains(unknown.stdout, "reason:")
  }

  @Test
  fun `update-check is read only for local home and repo paths`() {
    val tempDir = Files.createTempDirectory("skillbill-update-check-read-only")
    val home = tempDir.resolve("home")
    val repo = tempDir.resolve("repo")
    Files.createDirectories(home)
    Files.createDirectories(repo)
    val before = snapshotTree(tempDir)

    val result = CliRuntime.run(
      listOf("update-check"),
      CliRuntimeContext(
        userHome = home,
        requester = updateCheckRequester(mutableListOf(), latest = "v0.3.0-SNAPSHOT"),
      ),
    )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals(before, snapshotTree(tempDir))
  }

  @Test
  fun `update-check emits text and json update results through configured requester`() {
    val capturedRequests = mutableListOf<Map<String, Any?>>()
    val context = CliRuntimeContext(
      requester = updateCheckRequester(capturedRequests),
      userHome = Files.createTempDirectory("skillbill-update-check-home"),
    )

    val text = CliRuntime.run(listOf("update-check"), context)

    assertEquals(0, text.exitCode, text.stdout)
    assertContains(text.stdout, "status: update_available")
    assertContains(text.stdout, "installed_version: $INSTALLED_VERSION")
    assertContains(text.stdout, "latest_version: $NEWER_RELEASE_TAG")
    assertContains(text.stdout, "recommended_install_command: $EXPECTED_INSTALL_COMMAND")

    val json = CliRuntime.run(listOf("update-check", "--format", "json"), context)
    val payload = decodeJsonObject(json.stdout)

    assertEquals(0, json.exitCode, json.stdout)
    assertEquals("update_available", payload["status"])
    assertEquals(INSTALLED_VERSION, payload["installed_version"])
    assertEquals(NEWER_RELEASE_TAG, payload["latest_version"])
    assertEquals("https://github.com/oila-gmbh/skill-bill/releases/tag/$NEWER_RELEASE_TAG", payload["release_url"])
    assertEquals(2, capturedRequests.size)
    assertEquals("GET", capturedRequests.first()["method"])
    assertEquals("skill-bill-update-check", (capturedRequests.first()["headers"] as Map<*, *>)["User-Agent"])
  }
}
