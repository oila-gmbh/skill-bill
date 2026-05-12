package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.TreeItemKind
import java.nio.file.Files
import java.nio.file.Path
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
    assertFalse(detail.editable)
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

private fun repoFileSnapshot(repo: Path): Map<String, Long> = Files.walk(repo).use { paths ->
  paths
    .filter(Files::isRegularFile)
    .sorted()
    .toList()
    .associate { path ->
      path.relativeTo(
        repo,
      ).toString().replace('\\', '/') to Files.getLastModifiedTime(path).toMillis()
    }
}

private fun List<skillbill.desktop.core.domain.model.SkillBillTreeItem>.flatten():
  List<skillbill.desktop.core.domain.model.SkillBillTreeItem> =
  flatMap { item -> listOf(item) + item.children.flatten() }

private fun String.hasLocalId(localId: String): Boolean = endsWith("|$localId")

private fun RuntimeRepoBrowserService.treeForSessionLocalId(session: RepoSession, localId: String): String {
  val item = treeFor(session).flatten().singleOrNull { it.id.hasLocalId(localId) }
  assertNotNull(item, "Expected tree item with local id $localId")
  return item.id
}
