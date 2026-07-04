package skillbill.desktop.core.data.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoLoadStatus
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.ScaffoldPayload
import skillbill.desktop.core.domain.model.ScaffoldRunResult
import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingRequiredSectionError
import skillbill.error.ScaffoldRollbackError
import skillbill.install.model.ExternalAddonSource
import skillbill.scaffold.model.ScaffoldResult
import skillbill.scaffold.model.command.ScaffoldCommandRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JvmRuntimeScaffoldGatewayTest {

  // F-004: Preview/Success is distinguished by the `dryRun` input flag, NOT by scanning notes.
  // SKILL-52.2 subtask 2: scaffolder seam now receives a typed `ScaffoldCommandRequest`.
  @Test
  fun `dryRun produces Preview when scaffolder is invoked with dryRun true`() = runBlocking {
    var capturedRequest: ScaffoldCommandRequest? = null
    var capturedDryRun: Boolean? = null
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { request, dryRun ->
        capturedRequest = request
        capturedDryRun = dryRun
        ScaffoldResult(
          kind = "horizontal",
          skillName = "bill-foo",
          skillPath = Path.of("/tmp/skills/bill-foo"),
          createdFiles = listOf(Path.of("/tmp/skills/bill-foo/content.md")),
          manifestEdits = emptyList(),
          symlinks = emptyList(),
          installTargets = emptyList(),
          // F-004: no dry-run marker note required for Preview classification.
          notes = emptyList(),
        )
      }
    }

    val payload = ScaffoldPayload.HorizontalSkill(name = "bill-foo")
    val result = gateway.dryRun(payload)

    val preview = result as ScaffoldRunResult.Preview
    assertEquals("horizontal", preview.planned.kind)
    assertEquals("bill-foo", preview.planned.skillName)
    assertEquals(1, preview.planned.createdFiles.size)
    assertEquals(true, capturedDryRun)
    val request = checkNotNull(capturedRequest) as ScaffoldCommandRequest.HorizontalSkill
    assertEquals("1.0", request.scaffoldPayloadVersion)
    assertEquals("bill-foo", request.name)
  }

  // F-004: execute returns Success even when the runtime ALSO emits a "dry run" marker note. The
  // distinction is the input flag the gateway carries, never note string-matching.
  @Test
  fun `execute produces Success even when runtime notes include dry-run wording`() = runBlocking {
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ ->
        ScaffoldResult(
          kind = "platform-pack",
          skillName = "bill-java-code-review",
          skillPath = Path.of("/tmp/platform-packs/java"),
          createdFiles = listOf(
            Path.of("/tmp/platform-packs/java/platform.yaml"),
            Path.of("/tmp/platform-packs/java/code-review/bill-java-code-review/content.md"),
          ),
          manifestEdits = listOf(Path.of("/tmp/platform-packs/java/platform.yaml")),
          // Deliberately include the legacy dry-run marker to assert F-004: it is IGNORED.
          notes = listOf("Dry run - no filesystem changes applied."),
        )
      }
    }

    val payload = ScaffoldPayload.PlatformPack(platform = "java")
    val result = gateway.execute(payload)

    val success = result as ScaffoldRunResult.Success
    assertEquals("platform-pack", success.result.kind)
    assertEquals(2, success.result.createdFiles.size)
    assertTrue(success.result.createdFiles.any { it.endsWith("platform.yaml") })
  }

  @Test
  fun `execute registers external add-on source after successful scaffold`() = runBlocking {
    val registered = mutableListOf<ExternalAddonSource>()
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ ->
        ScaffoldResult(
          kind = "add-on",
          skillName = "private-review",
          skillPath = Path.of("/private/addons"),
          createdFiles = listOf(Path.of("/private/addons/private-review.md")),
        )
      }
      externalAddonSourceRegistrar = { source -> registered += source }
    }

    val result = gateway.execute(
      ScaffoldPayload.AddOn(
        name = "private-review",
        platform = "kotlin",
        addonLocationPath = "/private/addons",
      ),
    )

    assertTrue(result is ScaffoldRunResult.Success)
    assertEquals(listOf(ExternalAddonSource(Path.of("/private/addons"), "kotlin")), registered)
  }

  @Test
  fun `dry-run does not register external add-on source`() = runBlocking {
    val registered = mutableListOf<ExternalAddonSource>()
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ ->
        ScaffoldResult(
          kind = "add-on",
          skillName = "private-review",
          skillPath = Path.of("/private/addons"),
          createdFiles = listOf(Path.of("/private/addons/private-review.md")),
        )
      }
      externalAddonSourceRegistrar = { source -> registered += source }
    }

    val result = gateway.dryRun(
      ScaffoldPayload.AddOn(
        name = "private-review",
        platform = "kotlin",
        addonLocationPath = "/private/addons",
      ),
    )

    assertTrue(result is ScaffoldRunResult.Preview)
    assertTrue(registered.isEmpty())
  }

  // SKILL-52.2 subtask 2: parity asserts the typed `ScaffoldCommandRequest` (not a raw map) is
  // identical across dry-run/execute for every active creation kind, preserving the original AC2
  // intent without routing retired partial scaffold kinds.
  @Test
  fun `payload parity holds between dry-run and execute for active creation kinds`() = runBlocking {
    val recorded = mutableListOf<ScaffoldCommandRequest>()
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { request, _ ->
        recorded += request
        ScaffoldResult(
          kind = "stub",
          skillName = "bill-x",
          skillPath = Path.of("/tmp/x"),
        )
      }
    }

    val payloads: List<ScaffoldPayload> = listOf(
      ScaffoldPayload.HorizontalSkill(name = "bill-horizontal"),
      ScaffoldPayload.PlatformPack(platform = "java"),
      ScaffoldPayload.AddOn(name = "bill-addon", platform = "java"),
    )
    payloads.forEach { payload ->
      gateway.dryRun(payload)
      gateway.execute(payload)
    }
    val pairs = recorded.chunked(2)
    pairs.forEach { (dryRequest, executeRequest) ->
      assertEquals(dryRequest, executeRequest, "dry-run and execute requests must be identical (AC2)")
    }
  }

  @Test
  fun `retired partial creation payloads fail before runtime request submission`() = runBlocking {
    var scaffolderInvocations = 0
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ ->
        scaffolderInvocations += 1
        ScaffoldResult(
          kind = "unexpected",
          skillName = "bill-unexpected",
          skillPath = Path.of("/tmp/unexpected"),
        )
      }
    }

    val payloads: List<ScaffoldPayload> = listOf(
      ScaffoldPayload.PlatformOverride(platform = "java", family = "code-review"),
      ScaffoldPayload.CodeReviewArea(platform = "java", area = "security"),
    )
    payloads.forEach { payload ->
      listOf(gateway.dryRun(payload), gateway.execute(payload)).forEach { result ->
        val failed = result as ScaffoldRunResult.Failed
        assertEquals("RetiredScaffoldKindError", failed.exceptionName)
        assertTrue(failed.rollbackComplete)
      }
    }

    assertEquals(0, scaffolderInvocations, "retired payloads must not reach the runtime scaffolder")
  }

  @Test
  fun `runtime exception maps to Failed with rollbackComplete true`() = runBlocking {
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ -> throw InvalidScaffoldPayloadError("bad payload") }
    }

    val result = gateway.execute(ScaffoldPayload.HorizontalSkill(name = "bill-x"))

    val failed = result as ScaffoldRunResult.Failed
    assertEquals("InvalidScaffoldPayloadError", failed.exceptionName)
    assertEquals("bad payload", failed.exceptionMessage)
    assertTrue(failed.rollbackComplete)
  }

  @Test
  fun `ScaffoldRollbackError sets rollbackComplete false`() = runBlocking {
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ -> throw ScaffoldRollbackError("rollback failed: dir x") }
    }

    val result = gateway.execute(ScaffoldPayload.HorizontalSkill(name = "bill-x"))

    val failed = result as ScaffoldRunResult.Failed
    assertEquals("ScaffoldRollbackError", failed.exceptionName)
    assertFalse(failed.rollbackComplete)
  }

  @Test
  fun `shell-content-contract exception is caught and reported`() = runBlocking {
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ -> throw MissingRequiredSectionError("missing Description") }
    }

    val result = gateway.execute(ScaffoldPayload.HorizontalSkill(name = "bill-x"))

    val failed = result as ScaffoldRunResult.Failed
    assertEquals("MissingRequiredSectionError", failed.exceptionName)
    assertTrue(failed.rollbackComplete)
  }

  // F-003/F-405: non-runtime exceptions map to Failed but `rollbackComplete = false` because we
  // cannot promise the rollback machinery ran.
  @Test
  fun `non-runtime exception maps to Failed with rollbackComplete false`() = runBlocking {
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ -> throw IllegalStateException("programmer error") }
    }

    val result = gateway.execute(ScaffoldPayload.HorizontalSkill(name = "bill-x"))

    val failed = result as ScaffoldRunResult.Failed
    assertEquals("IllegalStateException", failed.exceptionName)
    assertEquals("programmer error", failed.exceptionMessage)
    assertFalse(failed.rollbackComplete, "non-runtime exceptions cannot guarantee rollback ran")
  }

  // F-003/F-405: CancellationException must be re-thrown verbatim so coroutine cancellation works.
  // Use a dedicated coroutine scope here rather than runBlocking, because runBlocking propagates
  // CancellationException by cancelling the runner itself instead of returning it to JUnit.
  @Test
  fun `CancellationException is re-thrown verbatim`() {
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ -> throw CancellationException("cancelled") }
    }
    val cancellation = assertFailsWith<CancellationException> {
      kotlinx.coroutines.runBlocking {
        gateway.execute(ScaffoldPayload.HorizontalSkill(name = "bill-x"))
      }
    }
    assertEquals("cancelled", cancellation.message)
  }

  @Test
  fun `catalog snapshot returns static lists even without a session`() = runBlocking {
    val gateway = JvmRuntimeScaffoldGateway()
    val snapshot = gateway.catalogSnapshot(session = null)

    assertNotNull(snapshot)
    assertTrue(snapshot.approvedCodeReviewAreas.isNotEmpty())
    assertEquals("1.0", snapshot.scaffoldPayloadVersion)
    assertTrue(snapshot.preShellFamilies.contains("feature-implement"))
    assertTrue(snapshot.shelledFamilies.contains("code-review"))
    assertTrue(snapshot.platformPackPresets.any { it.platform == "java" })
    assertTrue(snapshot.pilotedPlatformPacks.isEmpty())
    assertTrue(snapshot.baselineReviewPacks.isEmpty())
  }

  @Test
  fun `catalog snapshot tolerates unrecognized session`() = runBlocking {
    val gateway = JvmRuntimeScaffoldGateway()
    val session = RepoSession(
      repoPath = "/nope",
      isRecognizedSkillBillRepo = false,
      loadStatus = RepoLoadStatus(state = RepoLoadState.INVALID, message = "bad"),
    )

    val snapshot = gateway.catalogSnapshot(session)
    assertTrue(snapshot.pilotedPlatformPacks.isEmpty())
    assertTrue(snapshot.baselineReviewPacks.isEmpty())
    assertTrue(snapshot.approvedCodeReviewAreas.isNotEmpty())
  }

  @Test
  fun `catalog snapshot maps baseline review catalog from manifests`() = runBlocking {
    val repoRoot = Files.createTempDirectory("skillbill-baseline-catalog")
    val packsRoot = repoRoot.resolve("platform-packs")
    Files.createDirectories(packsRoot.resolve("kotlin"))
    Files.createDirectories(packsRoot.resolve("docs"))
    Files.writeString(
      packsRoot.resolve("kotlin/platform.yaml"),
      """
      platform: kotlin
      contract_version: "1.1"
      display_name: Kotlin
      routing_signals:
        strong:
          - ".kt"
        tie_breakers: []
      declared_code_review_areas: []
      declared_files:
        baseline: code-review/bill-kotlin-code-review/content.md
        areas: {}
      area_metadata: {}
      """.trimIndent(),
    )
    Files.writeString(
      packsRoot.resolve("docs/platform.yaml"),
      """
      platform: docs
      contract_version: "1.1"
      display_name: Docs
      routing_signals:
        strong:
          - "docs/"
        tie_breakers: []
      declared_code_review_areas: []
      area_metadata: {}
      """.trimIndent(),
    )
    val gateway = JvmRuntimeScaffoldGateway()
    val session = RepoSession(
      repoPath = repoRoot.toString(),
      isRecognizedSkillBillRepo = true,
      loadStatus = RepoLoadStatus(state = RepoLoadState.LOADED, message = "Loaded"),
    )

    val snapshot = gateway.catalogSnapshot(session)

    assertEquals(listOf("docs", "kotlin"), snapshot.pilotedPlatformPacks.map { it.platform })
    assertEquals(listOf("kotlin"), snapshot.baselineReviewPacks.map { it.platform })
    val kotlinSkill = snapshot.baselineReviewPacks.single().skills.single { it.name == "bill-kotlin-code-review" }
    assertEquals(listOf("kmp-baseline"), kotlinSkill.supportedModes)
    assertEquals(listOf("same-review-scope"), kotlinSkill.supportedScopes)
  }

  @Test
  fun `dryRun maps manifest previews into scaffold plan`() = runBlocking {
    val gateway = JvmRuntimeScaffoldGateway().apply {
      scaffolder = { _, _ ->
        ScaffoldResult(
          kind = "platform-pack",
          skillName = "bill-kmp-code-review",
          skillPath = Path.of("/repo/platform-packs/kmp"),
          manifestPreviews = mapOf(
            Path.of("/repo/platform-packs/kmp/platform.yaml") to "code_review_composition:\n  baseline_layers: []\n",
          ),
        )
      }
    }

    val result = gateway.dryRun(ScaffoldPayload.PlatformPack(platform = "kmp"))

    val preview = result as ScaffoldRunResult.Preview
    assertEquals(1, preview.planned.manifestPreviews.size)
    assertTrue(preview.planned.manifestPreviews.single().content.contains("code_review_composition"))
  }

  @Test
  fun `payload contract version is stamped automatically`() {
    val map = ScaffoldPayload.HorizontalSkill(name = "bill-x").toContractMap()
    assertEquals("1.0", map["scaffold_payload_version"])
    assertEquals("horizontal", map["kind"])
  }
}
