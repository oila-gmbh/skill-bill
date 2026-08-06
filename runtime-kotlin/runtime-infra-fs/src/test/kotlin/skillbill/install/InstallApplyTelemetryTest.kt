package skillbill.install

import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallApplyIssueKind
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallTelemetryApplyStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.runtime.InstallOperations
import skillbill.ports.telemetry.TelemetryLevelMutator
import skillbill.ports.telemetry.model.TelemetryLevelMutationResult
import skillbill.telemetry.model.TelemetrySettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallApplyTelemetryTest : InstallApplyTestSupport() {
  @Test
  fun `apply configures full telemetry as a structured success outcome`() {
    val fixture = setupApplyFixture()
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        telemetryLevel = InstallTelemetryLevel.FULL,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertEquals(InstallTelemetryLevel.FULL, result.telemetryOutcome.level)
    assertEquals(InstallTelemetryApplyStatus.SUCCESS, result.telemetryOutcome.status)
    assertEquals(fixture.home.resolve(".config/skill-bill/config.json"), result.telemetryOutcome.configPath)
    assertEquals("Telemetry level set to 'full'.", result.telemetryOutcome.message)
    assertTrue(Files.readString(fixture.home.resolve(".config/skill-bill/config.json")).contains("\"level\":\"full\""))
  }

  @Test
  fun `apply fails telemetry setup when existing config remains invalid after level write`() {
    val fixture = setupApplyFixture()
    val configPath = fixture.home.resolve(".skill-bill/config.json")
    Files.createDirectories(configPath.parent)
    Files.writeString(
      configPath,
      """{"install_id":"existing","telemetry":{"level":"anonymous","proxy_url":"","batch_size":"bad"}}""",
    )
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        telemetryLevel = InstallTelemetryLevel.FULL,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.WARNING, result.status)
    assertEquals(InstallTelemetryApplyStatus.FAILED, result.telemetryOutcome.status)
    assertEquals(configPath, result.telemetryOutcome.configPath)
    assertNotNull(result.telemetryOutcome.issue)
    assertContains(result.telemetryOutcome.issue?.message.orEmpty(), "telemetry.batch_size must be an integer.")
  }

  @Test
  fun `apply disables existing telemetry config in place and preserves install_id`() {
    val fixture = setupApplyFixture()
    val configPath = fixture.home.resolve(".skill-bill/config.json")
    Files.createDirectories(configPath.parent)
    Files.writeString(
      configPath,
      """
      |{"install_id":"existing","external_addon_sources":["/tmp/addons"],
      |"execution_matrix":{"default":"claude"},
      |"telemetry":{"level":"anonymous","proxy_url":"https://example.invalid","batch_size":10}}
      |
      """.trimMargin(),
    )
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        telemetryLevel = InstallTelemetryLevel.OFF,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertEquals(InstallTelemetryApplyStatus.SUCCESS, result.telemetryOutcome.status)
    assertEquals(InstallTelemetryLevel.OFF, result.telemetryOutcome.level)
    assertEquals("Telemetry level set to 'off'.", result.telemetryOutcome.message)
    assertTrue(Files.exists(configPath), "off must keep the existing telemetry config on disk")
    val persisted = Files.readString(configPath)
    assertContains(persisted, "\"level\":\"off\"")
    assertContains(persisted, "\"install_id\":\"existing\"")
    assertContains(persisted, "/tmp/addons")
    assertContains(persisted, "\"default\":\"claude\"")
  }

  @Test
  fun `apply skips telemetry off when there is no existing telemetry state`() {
    val fixture = setupApplyFixture()
    val configPath = fixture.home.resolve(".config/skill-bill/config.json")
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        telemetryLevel = InstallTelemetryLevel.OFF,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status)
    assertEquals(InstallTelemetryApplyStatus.SKIPPED, result.telemetryOutcome.status)
    assertEquals("Telemetry was already off.", result.telemetryOutcome.message)
    assertFalse(Files.exists(configPath), "opting out must not create a config file or mint an install_id")
  }

  @Test
  fun `apply routes telemetry off through the injected mutator instead of touching the config file`() {
    val fixture = setupApplyFixture()
    val configPath = fixture.home.resolve(".skill-bill/config.json")
    Files.createDirectories(configPath.parent)
    val seeded = """{"install_id":"existing","telemetry":{"level":"anonymous","proxy_url":"","batch_size":10}}"""
    Files.writeString(configPath, seeded)
    val mutator = RecordingTelemetryLevelMutator(clearedEvents = 3)
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        telemetryLevel = InstallTelemetryLevel.OFF,
      ),
    )

    val result = InstallOperations.applyInstall(plan, mutator)

    assertEquals(listOf("off"), mutator.levels)
    assertEquals(InstallTelemetryApplyStatus.SUCCESS, result.telemetryOutcome.status)
    assertEquals(3, result.telemetryOutcome.clearedEvents)
    assertEquals(seeded, Files.readString(configPath), "the mutator owns the write; apply must not rewrite the file")
  }

  @Test
  fun `apply maps telemetry setup failure to a structured warning outcome`() {
    val fixture = setupApplyFixture()
    val configPath = fixture.home.resolve(".skill-bill/config.json")
    Files.createDirectories(configPath.parent)
    Files.writeString(configPath, "{\n  \"telemetry\": \n")
    val sourceBefore = snapshotSource(fixture.repoRoot)
    val plan = InstallOperations.planInstall(
      fixture.request(
        agents = setOf(InstallAgent.CODEX),
        telemetryLevel = InstallTelemetryLevel.FULL,
      ),
    )

    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.WARNING, result.status)
    assertEquals(InstallTelemetryApplyStatus.FAILED, result.telemetryOutcome.status)
    assertEquals(configPath, result.telemetryOutcome.configPath)
    assertNotNull(result.telemetryOutcome.issue)
    assertTrue(
      result.warnings.any { warning ->
        warning.kind == InstallApplyIssueKind.TELEMETRY_APPLY_FAILED
      },
    )
    assertEquals("{\n  \"telemetry\": \n", Files.readString(configPath))
    assertSourceUnchanged(fixture.repoRoot, sourceBefore)
  }
}

private class RecordingTelemetryLevelMutator(
  private val clearedEvents: Int,
) : TelemetryLevelMutator {
  val levels = mutableListOf<String>()

  override fun setLevel(level: String, dbOverride: String?): TelemetryLevelMutationResult {
    levels += level
    return TelemetryLevelMutationResult(
      settings = TelemetrySettings(
        configPath = Path.of("/fake/config.json"),
        level = level,
        enabled = level != "off",
        installId = "existing",
        proxyUrl = "",
        customProxyUrl = null,
        batchSize = 10,
      ),
      clearedEvents = clearedEvents,
    )
  }
}
