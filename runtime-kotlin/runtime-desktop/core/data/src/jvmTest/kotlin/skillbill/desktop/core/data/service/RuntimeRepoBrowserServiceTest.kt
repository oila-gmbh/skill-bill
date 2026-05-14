package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.RenderRunState
import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.desktop.core.domain.model.ValidationRunState
import skillbill.error.SkillBillRuntimeException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.relativeTo
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeRepoBrowserServiceTest {
  @Test
  fun `valid repo loads runtime-backed tree and read-only generated artifacts`() {
    val repo = seedRepo("runtime-tree")
    val service = RuntimeRepoBrowserService()

    val session = service.open(repo.toString())
    val tree = service.treeFor(session)
    val flattened = tree.flatten()

    assertEquals(RepoLoadState.LOADED, session.loadStatus.state)
    assertTrue(flattened.any { it.id.hasLocalId("skill:bill-alpha") })
    assertTrue(flattened.any { it.kind == TreeItemKind.PLATFORM_PACK && it.label == "kotlin" })
    assertTrue(flattened.any { it.kind == TreeItemKind.ADD_ON && it.label == "tracing-otel" })
    assertTrue(flattened.any { it.kind == TreeItemKind.NATIVE_AGENT && it.label == "alpha-agent" })

    val generated = flattened.single { it.id.endsWith("skills/bill-alpha/SKILL.md") }
    assertEquals(TreeItemKind.GENERATED_ARTIFACT, generated.kind)
    assertFalse(generated.editable)
    assertEquals("RO", generated.readOnlyLabel)
    assertEquals("read-only", generated.status)
  }

  @Test
  fun `selected skill detail maps name kind authored path status and generated metadata`() {
    val repo = seedRepo("runtime-selection")
    val service = RuntimeRepoBrowserService()

    val session = service.open(repo.toString())
    val detail = service.describeSelection(service.treeForSessionLocalId(session, "skill:bill-alpha"))

    assertEquals("bill-alpha", detail.skillName)
    assertEquals("horizontal skill", detail.kind)
    assertEquals("skills/bill-alpha/content.md", detail.authoredPath)
    assertEquals("complete", detail.status)
    assertTrue(detail.content.orEmpty().contains("Alpha guidance."))
    assertTrue(detail.generatedArtifacts.any { it.path == "skills/bill-alpha/SKILL.md" })
    assertTrue(detail.editable)
  }

  @Test
  fun `selected governed skill loads full editable content document`() {
    val repo = seedRepo("authoring-document")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")

    val document = service.loadDocument(session, skillId)

    assertTrue(document.editable)
    assertEquals("bill-alpha", document.skillName)
    assertEquals("skills/bill-alpha/content.md", document.authoredPath)
    assertTrue(document.text.contains("Alpha guidance."))
    assertNull(document.readOnlyReason)
  }

  @Test
  fun `saving governed skill uses authoring operation and reloads saved text`() {
    val repo = seedRepo("authoring-save")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")

    val result = service.saveDocument(session, skillId, "# Rewritten\n\nSaved body.\n")
    val document = service.loadDocument(session, skillId)

    assertTrue(result.success, result.runtimeErrorMessage.orEmpty())
    assertTrue(document.text.contains("Saved body."))
    assertTrue(Files.readString(repo.resolve("skills/bill-alpha/content.md")).contains("Saved body."))
  }

  @Test
  fun `save failure returns runtime message and leaves source intact`() {
    val repo = seedRepo("authoring-save-failure")
    val before = Files.readString(repo.resolve("skills/bill-alpha/content.md"))
    val service = RuntimeRepoBrowserService()
    service.authoringSaver = { _, _, _ -> throw SkillBillRuntimeException("runtime said no") }
    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")

    val result = service.saveDocument(session, skillId, "broken")

    assertFalse(result.success)
    assertEquals("runtime said no", result.runtimeErrorMessage)
    assertEquals(before, Files.readString(repo.resolve("skills/bill-alpha/content.md")))
  }

  @Test
  fun `generated artifact document is read-only and cannot be saved`() {
    val repo = seedRepo("authoring-generated-read-only")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val generatedId = service.treeForSessionLocalId(session, "generated:skills/bill-alpha/SKILL.md")

    val document = service.loadDocument(session, generatedId)
    val result = service.saveDocument(session, generatedId, "attempt")

    assertFalse(document.editable)
    assertTrue(document.text.contains("Generated wrapper"))
    assertTrue(document.readOnlyReason.orEmpty().contains("Generated runtime wrapper"))
    assertFalse(result.success)
    assertTrue(result.runtimeErrorMessage.orEmpty().contains("Generated runtime wrapper"))
  }

  @Test
  fun `generated support pointer and provider-native output are read-only and cannot be saved`() {
    val repo = seedRepo("authoring-generated-pointers-read-only")
    writeQualityCheckWithGeneratedSupportPointer(repo)
    Files.createDirectories(repo.resolve("skills/bill-alpha/claude-agents"))
    Files.writeString(repo.resolve("skills/bill-alpha/claude-agents/alpha-agent.md"), "Generated provider output\n")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val supportPointerId =
      service.treeForSessionLocalId(session, "generated:skills/bill-quality-check/stack-routing.md")
    val providerOutputId = service.treeForSessionLocalId(
      session,
      "generated:skills/bill-alpha/claude-agents/alpha-agent.md",
    )

    val supportPointer = service.loadDocument(session, supportPointerId)
    val supportSave = service.saveDocument(session, supportPointerId, "attempt")
    val providerOutput = service.loadDocument(session, providerOutputId)
    val providerSave = service.saveDocument(session, providerOutputId, "attempt")

    assertFalse(supportPointer.editable)
    assertTrue(supportPointer.readOnlyReason.orEmpty().contains("Generated support pointer"))
    assertFalse(supportSave.success)
    assertTrue(supportSave.runtimeErrorMessage.orEmpty().contains("Generated support pointer"))
    assertFalse(providerOutput.editable)
    assertTrue(providerOutput.readOnlyReason.orEmpty().contains("Generated provider-specific native-agent output"))
    assertFalse(providerSave.success)
    assertTrue(providerSave.runtimeErrorMessage.orEmpty().contains("Generated provider-specific native-agent output"))
  }

  @Test
  fun `wrapper-only SKILL md is not exposed as authored skill`() {
    val repo = seedRepo("wrapper-only")
    val wrapperOnlyDir = repo.resolve("skills/bill-wrapper-only")
    Files.createDirectories(wrapperOnlyDir)
    Files.writeString(wrapperOnlyDir.resolve("SKILL.md"), "Legacy wrapper only\n")
    val service = RuntimeRepoBrowserService()

    val flattened = service.treeFor(service.open(repo.toString())).flatten()

    assertFalse(flattened.any { it.id.hasLocalId("skill:bill-wrapper-only") })
  }

  @Test
  fun `generated artifact selection returns read-only editor contract`() {
    val repo = seedRepo("generated-selection")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())

    val generatedId = service.treeForSessionLocalId(session, "generated:skills/bill-alpha/SKILL.md")
    val detail = service.describeSelection(generatedId)

    assertEquals("SKILL.md", detail.title)
    assertEquals("generated artifact", detail.kind)
    assertEquals("skills/bill-alpha/SKILL.md", detail.authoredPath)
    assertEquals("read-only", detail.status)
    assertEquals("RO", detail.readOnlyLabel)
    assertFalse(detail.editable)
    assertTrue(detail.detail.contains("Generated runtime wrapper"))
  }

  @Test
  fun `invalid repo selection returns clear error without tree`() {
    val service = RuntimeRepoBrowserService()
    val missing = Files.createTempDirectory("skillbill-desktop-missing").resolve("missing")

    val session = service.open(missing.toString())

    assertEquals(RepoLoadState.INVALID, session.loadStatus.state)
    assertTrue(session.loadStatus.message.contains("not a directory"))
    assertTrue(service.treeFor(session).isEmpty())
  }

  @Test
  fun `invalid repo selection after valid open clears stale tree and selection`() {
    val repo = seedRepo("invalid-clears-stale")
    val service = RuntimeRepoBrowserService()
    val loadedSession = service.open(repo.toString())
    val loadedSkillId = service.treeForSessionLocalId(loadedSession, "skill:bill-alpha")
    val missing = repo.resolve("missing")

    val invalidSession = service.open(missing.toString())

    assertEquals(RepoLoadState.INVALID, invalidSession.loadStatus.state)
    assertTrue(service.treeFor(invalidSession).isEmpty())
    assertTrue(service.treeFor(loadedSession).isEmpty())
    assertEquals("No source selected", service.describeSelection(loadedSkillId).title)
  }

  @Test
  fun `blank and invalid platform path text return invalid status`() {
    val service = RuntimeRepoBrowserService()

    val blankSession = service.open("   ")
    val invalidSession = service.open("\u0000")

    assertEquals(RepoLoadState.INVALID, blankSession.loadStatus.state)
    assertTrue(blankSession.loadStatus.message.contains("Select a Skill Bill repository path"))
    assertEquals(RepoLoadState.INVALID, invalidSession.loadStatus.state)
    assertTrue(invalidSession.loadStatus.message.contains("Select a Skill Bill repository path"))
  }

  @Test
  fun `malformed native-agent source is visible as invalid tree item`() {
    val repo = seedRepo("malformed-native-agent")
    Files.writeString(
      repo.resolve("skills/bill-alpha/native-agents/bad-agent.md"),
      "missing frontmatter",
    )
    val service = RuntimeRepoBrowserService()

    val flattened = service.treeFor(service.open(repo.toString())).flatten()

    val invalidNativeAgent = flattened.single {
      it.id.hasLocalId("native-agent-error:skills/bill-alpha/native-agents/bad-agent.md")
    }
    assertEquals(TreeItemKind.NATIVE_AGENT, invalidNativeAgent.kind)
    assertEquals("invalid", invalidNativeAgent.status)
    assertFalse(invalidNativeAgent.editable)
  }

  @Test
  fun `selection remains robust when authored file disappears before detail read`() {
    val repo = seedRepo("selection-race")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")
    Files.delete(repo.resolve("skills/bill-alpha/content.md"))

    val detail = service.describeSelection(skillId)

    assertEquals("bill-alpha", detail.skillName)
    assertNull(detail.content)
  }

  @Test
  fun `selection id from previous repo does not resolve after another repo opens`() {
    val firstRepo = seedRepo("first-repo")
    val secondRepo = seedRepo("second-repo")
    val service = RuntimeRepoBrowserService()
    val firstSession = service.open(firstRepo.toString())
    val firstSkillId = service.treeForSessionLocalId(firstSession, "skill:bill-alpha")

    service.open(secondRepo.toString())
    val detail = service.describeSelection(firstSkillId)

    assertEquals("No source selected", detail.title)
  }

  @Test
  fun `reopening repo reflects added authored content`() {
    val repo = seedRepo("runtime-refresh")
    val service = RuntimeRepoBrowserService()

    val initial = service.treeFor(service.open(repo.toString())).flatten()
    assertFalse(initial.any { it.id.hasLocalId("skill:bill-beta") })

    writeContentSkill(repo, "bill-beta", "Beta guidance.")

    val refreshed = service.treeFor(service.open(repo.toString())).flatten()
    assertTrue(refreshed.any { it.id.hasLocalId("skill:bill-beta") })
  }

  @Test
  fun `reopening repo reflects deleted authored content and status count changes`() {
    val repo = seedRepo("runtime-refresh-delete")
    val service = RuntimeRepoBrowserService()

    val initialSession = service.open(repo.toString())
    val initialSkillCount = initialSession.loadStatus.skillCount
    writeContentSkill(repo, "bill-beta", "Beta guidance.")

    val addedSession = service.open(repo.toString())
    assertEquals(initialSkillCount + 1, addedSession.loadStatus.skillCount)
    assertTrue(service.treeFor(addedSession).flatten().any { it.id.hasLocalId("skill:bill-beta") })

    Files.delete(repo.resolve("skills/bill-beta/content.md"))
    Files.delete(repo.resolve("skills/bill-beta"))
    val deletedSession = service.open(repo.toString())
    assertEquals(initialSkillCount, deletedSession.loadStatus.skillCount)
    assertFalse(service.treeFor(deletedSession).flatten().any { it.id.hasLocalId("skill:bill-beta") })
  }

  @Test
  fun `open refresh and navigation do not modify repository files`() {
    val repo = seedRepo("read-only-browser")
    val before = repoFileSnapshot(repo)
    val service = RuntimeRepoBrowserService()

    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")
    service.describeSelection(skillId)
    service.open(repo.toString())
    service.treeFor(session)

    assertEquals(before, repoFileSnapshot(repo))
  }

  @Test
  fun `validate returns unavailable when no session is open`() {
    val service = RuntimeRepoBrowserService()
    val summary = service.validate(session = null)

    assertEquals(ValidationRunState.UNAVAILABLE, summary.state)
  }

  @Test
  fun `validate returns failed with structured issues for a non-passing repo`() {
    val repo = seedRepo("validate-failed")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())

    val summary = service.validate(session)

    // Seeded repo is missing README, override files, etc., so validation must fail.
    assertEquals(ValidationRunState.FAILED, summary.state)
    assertTrue(summary.issues.isNotEmpty())
    // At least one issue should be tied to an actual source path under skills/ or platform-packs/.
    val pathBearingIssue = summary.issues.firstOrNull { issue ->
      !issue.sourcePath.isNullOrBlank()
    }
    assertNotNull(pathBearingIssue, "Expected at least one issue with a source path")
  }

  @Test
  fun `validate maps unrecognized session to unavailable`() {
    val service = RuntimeRepoBrowserService()
    val missing = Files.createTempDirectory("skillbill-desktop-validate-missing").resolve("nope")
    val invalidSession = service.open(missing.toString())

    val summary = service.validate(invalidSession)

    assertEquals(ValidationRunState.UNAVAILABLE, summary.state)
  }

  @Test
  fun `resolveTreeItemIdForSource maps known authored path to its selection id`() {
    val repo = seedRepo("validate-resolve")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val skillTreeId = service.treeForSessionLocalId(session, "skill:bill-alpha")

    val resolved = service.resolveTreeItemIdForSource(session, "skills/bill-alpha/content.md")

    assertEquals(skillTreeId, resolved)
  }

  @Test
  fun `resolveTreeItemIdForSource returns null for unknown source paths`() {
    val repo = seedRepo("validate-resolve-unknown")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())

    val resolved = service.resolveTreeItemIdForSource(session, "skills/does-not-exist/content.md")

    assertNull(resolved)
  }

  @Test
  fun `resolveTreeItemIdForSource matches SKILL md sibling against authored content md`() {
    // F-108: validation reports often surface the generated SKILL.md path even though the
    // authored selection lives next to it as content.md. The parent-directory fallback must
    // make those two paths resolve to the same tree item.
    val repo = seedRepo("validate-resolve-parent")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())

    val contentResolved = service.resolveTreeItemIdForSource(session, "skills/bill-alpha/content.md")
    val siblingResolved = service.resolveTreeItemIdForSource(session, "skills/bill-alpha/SKILL.md")

    assertNotNull(contentResolved)
    assertEquals(contentResolved, siblingResolved)
  }

  @Test
  fun `resolveGeneratedArtifactTreeItemId matches exact generated artifact tree id from snapshot`() {
    val repo = seedRepo("generated-artifact-resolve")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val generatedId = service.treeForSessionLocalId(session, "generated:skills/bill-alpha/SKILL.md")

    val resolved = service.resolveGeneratedArtifactTreeItemId(session, "skills/bill-alpha/SKILL.md")

    assertEquals(generatedId, resolved)
  }

  @Test
  fun `resolveGeneratedArtifactTreeItemId does not use source sibling remapping`() {
    val repo = seedRepo("generated-artifact-no-source-remap")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val authoredId = service.resolveTreeItemIdForSource(session, "skills/bill-alpha/SKILL.md")
    val generatedId = service.resolveGeneratedArtifactTreeItemId(session, "skills/bill-alpha/SKILL.md")

    assertNotNull(authoredId)
    assertNotNull(generatedId)
    assertTrue(generatedId != authoredId)
    assertNull(service.resolveGeneratedArtifactTreeItemId(session, "skills/bill-alpha/content.md"))
  }

  @Test
  fun `resolveGeneratedArtifactTreeItemId returns null for unknown artifact path`() {
    val repo = seedRepo("generated-artifact-resolve-unknown")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())

    val resolved = service.resolveGeneratedArtifactTreeItemId(session, "skills/does-not-exist/SKILL.md")

    assertNull(resolved)
  }

  @Test
  fun `validate failure inside runtime is surfaced as FAILED with runtime exception fields`() {
    // F-107: when the underlying validation runtime throws, the service must report a FAILED
    // summary that carries the runtime exception name and message so the UI can render the
    // failure without losing the cause.
    val repo = seedRepo("validate-runtime-failure")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    service.validator = { _ -> error("simulated runtime crash") }

    val summary = service.validate(session)

    assertEquals(ValidationRunState.FAILED, summary.state)
    assertNotNull(summary.runtimeExceptionName)
    assertNotNull(summary.runtimeExceptionMessage)
    assertTrue(summary.runtimeExceptionMessage!!.contains("simulated runtime crash"))
    assertTrue(summary.issues.isEmpty())
  }

  @Test
  fun `render returns unavailable when session is null`() {
    val service = RuntimeRepoBrowserService()
    val summary = service.render(session = null, treeItemId = "anything")

    assertEquals(RenderRunState.UNAVAILABLE, summary.state)
  }

  @Test
  fun `render returns unavailable for invalid session`() {
    val service = RuntimeRepoBrowserService()
    val missing = Files.createTempDirectory("skillbill-desktop-render-missing").resolve("nope")
    val invalidSession = service.open(missing.toString())

    val summary = service.render(invalidSession, "any")

    assertEquals(RenderRunState.UNAVAILABLE, summary.state)
  }

  @Test
  fun `render returns unavailable for unknown tree item id`() {
    val repo = seedRepo("render-unknown")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())

    val summary = service.render(session, "missing-id")

    assertEquals(RenderRunState.UNAVAILABLE, summary.state)
  }

  @Test
  fun `render passes for an authored skill and derives generated artifacts from block headers`() {
    val repo = seedRepo("render-skill-pass")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")

    val summary = service.render(session, skillId)

    assertEquals(RenderRunState.PASSED, summary.state)
    assertTrue(summary.blocks.isNotEmpty())
    val wrapper = summary.blocks.first()
    assertTrue(wrapper.header.startsWith("===== SKILL.md:"))
    assertTrue(summary.generatedArtifacts.any { it.path == "skills/bill-alpha/SKILL.md" })
    assertTrue(summary.durationMillis >= 0L)
  }

  @Test
  fun `render passes for a native agent selection`() {
    val repo = seedRepo("render-native-agent")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val flattened = service.treeFor(session).flatten()
    val agentItem = flattened.first {
      it.kind == TreeItemKind.NATIVE_AGENT && it.label == "alpha-agent"
    }

    val summary = service.render(session, agentItem.id)

    assertEquals(RenderRunState.PASSED, summary.state)
    assertEquals(1, summary.blocks.size)
    val block = summary.blocks.single()
    assertTrue(block.header.startsWith("===== native-agent:"))
    assertTrue(block.header.endsWith(":alpha-agent ====="))
    assertTrue(block.content.contains("name: alpha-agent"))
    // Native agents do not emit generated artifacts in the desktop render preview.
    assertTrue(summary.generatedArtifacts.isEmpty())
  }

  @Test
  fun `render passes for an add-on selection by reading content file`() {
    val repo = seedRepo("render-addon")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val addonItem = service.treeFor(session).flatten()
      .first { it.kind == TreeItemKind.ADD_ON && it.label == "tracing-otel" }

    val summary = service.render(session, addonItem.id)

    assertEquals(RenderRunState.PASSED, summary.state)
    assertEquals(1, summary.blocks.size)
    val block = summary.blocks.single()
    assertTrue(block.header.startsWith("===== addon:"))
    assertTrue(block.content.contains("Tracing"))
  }

  @Test
  fun `render returns failed with runtime exception fields when renderer throws`() {
    val repo = seedRepo("render-runtime-failure")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")
    service.renderer = { _, _ -> error("simulated render crash") }

    val summary = service.render(session, skillId)

    assertEquals(RenderRunState.FAILED, summary.state)
    assertNotNull(summary.runtimeExceptionName)
    assertNotNull(summary.runtimeExceptionMessage)
    assertTrue(summary.runtimeExceptionMessage!!.contains("simulated render crash"))
    assertTrue(summary.blocks.isEmpty())
  }

  @Test
  fun `render is dry-run and does not modify repository files`() {
    val repo = seedRepo("render-dry-run")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val before = repoFileSnapshot(repo)
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")
    val agentItem = service.treeFor(session).flatten()
      .first { it.kind == TreeItemKind.NATIVE_AGENT && it.label == "alpha-agent" }
    val addonItem = service.treeFor(session).flatten()
      .first { it.kind == TreeItemKind.ADD_ON && it.label == "tracing-otel" }

    service.render(session, skillId)
    service.render(session, agentItem.id)
    service.render(session, addonItem.id)

    assertEquals(before, repoFileSnapshot(repo))
  }

  @Test
  fun `failed open does not modify repository files`() {
    val repo = seedRepo("failed-open-snapshot")
    val before = repoFileSnapshot(repo)
    val service = RuntimeRepoBrowserService()
    val missing = repo.resolve("missing-subdir")

    service.open(missing.toString())
    val recoveredSession = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(recoveredSession, "skill:bill-alpha")
    service.treeFor(recoveredSession)
    service.describeSelection(skillId)

    assertEquals(before, repoFileSnapshot(repo))
  }

  private fun seedRepo(name: String): Path {
    val repo = Files.createTempDirectory("skillbill-desktop-$name")
    writeContentSkill(repo, "bill-alpha", "Alpha guidance.")
    Files.writeString(repo.resolve("skills/bill-alpha/SKILL.md"), "Generated wrapper\n")
    Files.createDirectories(repo.resolve("skills/bill-alpha/native-agents"))
    Files.writeString(
      repo.resolve("skills/bill-alpha/native-agents/alpha-agent.md"),
      """
        |---
        |name: alpha-agent
        |description: Alpha native agent.
        |---
        |
        |Native agent body.
      """.trimMargin(),
    )
    writePlatformPack(repo)
    return repo
  }

  private fun writeContentSkill(repo: Path, skillName: String, body: String) {
    val skillDir = repo.resolve("skills").resolve(skillName)
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      """
        |---
        |name: $skillName
        |description: $skillName description.
        |---
        |
        |# ${skillName.removePrefix("bill-")}
        |
        |$body
      """.trimMargin(),
    )
  }

  private fun writeQualityCheckWithGeneratedSupportPointer(repo: Path) {
    writeContentSkill(repo, "bill-quality-check", "Quality guidance.")
    Files.writeString(repo.resolve("skills/bill-quality-check/stack-routing.md"), "Generated pointer\n")
  }

  private fun writePlatformPack(repo: Path) {
    val packRoot = repo.resolve("platform-packs/kotlin")
    Files.createDirectories(packRoot.resolve("code-review/bill-kotlin-code-review"))
    Files.createDirectories(packRoot.resolve("addons"))
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      """
        |platform: kotlin
        |contract_version: "1.1"
        |display_name: Kotlin
        |
        |routing_signals:
        |  strong:
        |    - ".kt"
        |  tie_breakers: []
        |
        |declared_code_review_areas: []
        |
        |declared_files:
        |  baseline: code-review/bill-kotlin-code-review/content.md
        |  areas: {}
        |
        |area_metadata: {}
      """.trimMargin(),
    )
    Files.writeString(
      packRoot.resolve("code-review/bill-kotlin-code-review/content.md"),
      """
        |---
        |name: bill-kotlin-code-review
        |description: Kotlin review.
        |---
        |
        |# Kotlin review
        |
        |Review guidance.
      """.trimMargin(),
    )
    Files.writeString(packRoot.resolve("addons/tracing-otel.md"), "# Tracing\n")
  }
}

// F-408: hashing file content (not mtime) is required to verify AC11 dry-run on filesystems with
// coarse mtime granularity (NTFS via WSL, ext4 with noatime) where a write within a single mtime
// tick would otherwise be invisible to the snapshot.
private fun repoFileSnapshot(repo: Path): Map<String, String> = Files.walk(repo).use { paths ->
  paths
    .filter(Files::isRegularFile)
    .sorted()
    .toList()
    .associate { path ->
      path.relativeTo(
        repo,
      ).toString().replace('\\', '/') to sha256Hex(Files.readAllBytes(path))
    }
}

private fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

private fun List<skillbill.desktop.core.domain.model.SkillBillTreeItem>.flatten():
  List<skillbill.desktop.core.domain.model.SkillBillTreeItem> =
  flatMap { item -> listOf(item) + item.children.flatten() }

private fun String.hasLocalId(localId: String): Boolean = endsWith("|$localId")

private fun RuntimeRepoBrowserService.treeForSessionLocalId(session: RepoSession, localId: String): String {
  val item = treeFor(session).flatten().singleOrNull { it.id.hasLocalId(localId) }
  assertNotNull(item, "Expected tree item with local id $localId")
  return item.id
}
