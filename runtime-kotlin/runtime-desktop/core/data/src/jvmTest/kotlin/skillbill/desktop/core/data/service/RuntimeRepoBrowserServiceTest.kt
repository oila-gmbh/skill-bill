package skillbill.desktop.core.data.service

import skillbill.desktop.core.domain.model.RepoLoadState
import skillbill.desktop.core.domain.model.RepoSession
import skillbill.desktop.core.domain.model.TreeItemKind
import skillbill.error.SkillBillRuntimeException
import skillbill.install.model.ExternalAddonSource
import java.nio.file.FileSystemException
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
    assertTrue(flattened.any { it.id.hasLocalId("skill:bill-alpha") && it.label == "alpha" })
    val kotlinPack = flattened.single { it.kind == TreeItemKind.PLATFORM_PACK && it.label == "kotlin" }
    assertTrue(
      kotlinPack.children.any { it.id.hasLocalId("skill:bill-kotlin-code-review") && it.label == "code-review" },
    )
    assertTrue(
      kotlinPack.children.any {
        it.id.hasLocalId("skill:bill-kotlin-code-review-architecture") && it.label == "architecture"
      },
    )
    assertTrue(flattened.any { it.kind == TreeItemKind.ADD_ON && it.label == "tracing-otel" })
    val addonGroup = tree.single { it.kind == TreeItemKind.GROUP && it.label == "Add-ons" }
    val kotlinAddons = addonGroup.children.single { it.kind == TreeItemKind.GROUP && it.label == "kotlin" }
    assertTrue(kotlinAddons.children.any { it.kind == TreeItemKind.ADD_ON && it.label == "tracing-otel" })
    assertTrue(flattened.any { it.kind == TreeItemKind.NATIVE_AGENT && it.label == "agent" })
    val platformNativeAgentId =
      "native-agent:" +
        "platform-packs/kotlin/code-review/bill-kotlin-code-review/native-agents/bill-kotlin-code-review-ui.md:" +
        "bill-kotlin-code-review-ui"
    assertTrue(
      flattened.any {
        it.id.hasLocalId(platformNativeAgentId) && it.label == "ui"
      },
    )
    val nativeAgentGroup = tree.single { it.kind == TreeItemKind.GROUP && it.label == "Native Agents" }
    val alphaNativeAgents = nativeAgentGroup.children.single { it.label == "alpha" }
    assertTrue(alphaNativeAgents.children.any { it.kind == TreeItemKind.NATIVE_AGENT && it.label == "agent" })
    val kotlinNativeAgents = nativeAgentGroup.children.single { it.label == "kotlin" }
    val kotlinCodeReviewNativeAgents = kotlinNativeAgents.children.single { it.label == "code-review" }
    assertTrue(
      kotlinCodeReviewNativeAgents.children.any {
        it.kind == TreeItemKind.NATIVE_AGENT && it.label == "ui"
      },
    )

    val generated = flattened.single { it.id.endsWith("skills/bill-alpha/SKILL.md") }
    assertEquals(TreeItemKind.GENERATED_ARTIFACT, generated.kind)
    assertFalse(generated.editable)
    assertEquals("RO", generated.readOnlyLabel)
    assertEquals("read-only", generated.status)
  }

  @Test
  fun `native agent source rows stay repo relative when selected repo path is a symlink`() {
    val repo = seedRepo("runtime-tree-symlink-target")
    val linkParent = Files.createTempDirectory("skillbill-desktop-runtime-tree-link")
    val repoLink = linkParent.resolve("repo-link")
    val symlinkCreated = try {
      Files.createSymbolicLink(repoLink, repo)
      true
    } catch (_: UnsupportedOperationException) {
      false
    } catch (_: FileSystemException) {
      false
    } catch (_: SecurityException) {
      false
    }
    if (!symlinkCreated) {
      return
    }
    val service = RuntimeRepoBrowserService()

    val session = service.open(repoLink.toString())
    assertEquals(RepoLoadState.LOADED, session.loadStatus.state, session.loadStatus.message)
    val flattened = service.treeFor(session).flatten()
    val treeSummary = flattened.joinToString("\n") { item ->
      "${item.kind} ${item.label} ${item.authoredPath.orEmpty()} ${item.id}"
    }

    val sourceAgent = assertNotNull(
      flattened.singleOrNull {
        it.id.hasLocalId("native-agent:skills/bill-alpha/native-agents/alpha-agent.md:alpha-agent")
      },
      treeSummary,
    )
    val platformAgent = assertNotNull(
      flattened.singleOrNull {
        it.id.hasLocalId(
          "native-agent:" +
            "platform-packs/kotlin/code-review/bill-kotlin-code-review/native-agents/bill-kotlin-code-review-ui.md:" +
            "bill-kotlin-code-review-ui",
        )
      },
      treeSummary,
    )
    assertEquals("agent", sourceAgent.label)
    assertEquals("skills/bill-alpha/native-agents/alpha-agent.md", sourceAgent.authoredPath)
    assertEquals("ui", platformAgent.label)
    assertEquals(
      "platform-packs/kotlin/code-review/bill-kotlin-code-review/native-agents/bill-kotlin-code-review-ui.md",
      platformAgent.authoredPath,
    )
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
  fun `saving governed skill writes exact editor text and reloads saved text`() {
    val repo = seedRepo("authoring-save")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")
    val before = service.loadDocument(session, skillId).text
    val edited = before.replace("Alpha guidance.", "Saved exact editor body.")

    val result = service.saveDocument(session, skillId, edited)
    val document = service.loadDocument(session, skillId)

    assertTrue(result.success, result.runtimeErrorMessage.orEmpty())
    assertEquals(edited, document.text)
    assertEquals(edited, Files.readString(repo.resolve("skills/bill-alpha/content.md")))
    assertFalse(document.text.contains("$before\n$edited"))
  }

  @Test
  fun `content md and governed add-on sources are editable and saveable`() {
    val repo = seedRepo("authoring-content-md-and-addons")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val flattened = service.treeFor(session).flatten()
    val skillItem = flattened.first { it.id.hasLocalId("skill:bill-alpha") }
    val addonItem = flattened.first { it.kind == TreeItemKind.ADD_ON && it.label == "tracing-otel" }
    val nativeAgentItem = flattened.first { it.kind == TreeItemKind.NATIVE_AGENT && it.label == "agent" }
    val nativeAgentBefore = Files.readString(repo.resolve("skills/bill-alpha/native-agents/alpha-agent.md"))

    val skillDocument = service.loadDocument(session, skillItem.id)
    val skillResult = service.saveDocument(
      session,
      skillItem.id,
      skillDocument.text.replace("Alpha guidance.", "Saved content.md body."),
    )
    val addonDocument = service.loadDocument(session, addonItem.id)
    val addonResult = service.saveDocument(session, addonItem.id, "# Tracing\n\nUpdated add-on guidance.\n")
    val nativeAgentBody = """
      |---
      |name: alpha-agent
      |description: Updated alpha native agent.
      |---
      |
      |Updated native agent body.
    """.trimMargin()
    val nativeAgentDocument = service.loadDocument(session, nativeAgentItem.id)
    val nativeAgentResult = service.saveDocument(session, nativeAgentItem.id, nativeAgentBody)

    assertTrue(skillDocument.editable)
    assertTrue(skillResult.success, skillResult.runtimeErrorMessage.orEmpty())
    assertTrue(Files.readString(repo.resolve("skills/bill-alpha/content.md")).contains("Saved content.md body."))
    assertTrue(addonDocument.editable)
    assertTrue(addonResult.success, addonResult.runtimeErrorMessage.orEmpty())
    assertTrue(
      Files.readString(repo.resolve("platform-packs/kotlin/addons/tracing-otel.md")).contains("Updated add-on"),
    )
    assertFalse(nativeAgentDocument.editable)
    assertFalse(nativeAgentResult.success)
    assertTrue(nativeAgentResult.runtimeErrorMessage.orEmpty().contains("Only governed content.md files and add-ons"))
    assertEquals(nativeAgentBefore, Files.readString(repo.resolve("skills/bill-alpha/native-agents/alpha-agent.md")))
  }

  @Test
  fun `external add-on appears under platform group and saves to external source`() {
    val repo = seedRepo("external-addon-visible")
    val externalDir = Files.createTempDirectory("skillbill-desktop-external-addons")
    val externalAddon = externalDir.resolve("observability.md")
    Files.writeString(externalAddon, "# Observability\n\nExternal guidance.\n")
    val service = RuntimeRepoBrowserService()
    service.externalAddonSourcesResolver = { listOf(ExternalAddonSource(externalDir, "kotlin")) }
    val session = service.open(repo.toString())

    val addonGroup = service.treeFor(session).single { it.kind == TreeItemKind.GROUP && it.label == "Add-ons" }
    val kotlinAddons = addonGroup.children.single { it.kind == TreeItemKind.GROUP && it.label == "kotlin" }
    val externalItem = kotlinAddons.children.single { it.label == "observability" }
    val document = service.loadDocument(session, externalItem.id)
    val saveResult = service.saveDocument(session, externalItem.id, "# Observability\n\nUpdated external guidance.\n")

    assertTrue(externalItem.external)
    assertTrue(externalItem.editable)
    assertEquals(TreeItemKind.ADD_ON, externalItem.kind)
    assertEquals(
      externalDir.toAbsolutePath().normalize().toString().replace('\\', '/'),
      externalItem.metadata?.externalSourcePath,
    )
    assertTrue(document.editable)
    assertEquals(externalAddon.toString(), document.authoredPath)
    assertTrue(document.text.contains("External guidance."))
    assertTrue(saveResult.success, saveResult.runtimeErrorMessage.orEmpty())
    assertTrue(Files.readString(externalAddon).contains("Updated external guidance."))
  }

  @Test
  fun `external add-on duplicate drops pack-owned add-on from tree`() {
    val repo = seedRepo("external-addon-dedup")
    val externalDir = Files.createTempDirectory("skillbill-desktop-external-addons-dedup")
    Files.writeString(externalDir.resolve("tracing-otel.md"), "# External tracing\n")
    val service = RuntimeRepoBrowserService()
    service.externalAddonSourcesResolver = { listOf(ExternalAddonSource(externalDir, "kotlin")) }
    val session = service.open(repo.toString())

    val addonGroup = service.treeFor(session).single { it.kind == TreeItemKind.GROUP && it.label == "Add-ons" }
    val kotlinAddons = addonGroup.children.single { it.kind == TreeItemKind.GROUP && it.label == "kotlin" }
    val tracingItems = kotlinAddons.children.filter { it.label == "tracing-otel" }

    assertEquals(1, tracingItems.size)
    assertTrue(tracingItems.single().external)
  }

  @Test
  fun `external add-on resolver failure keeps pack-owned tree visible`() {
    val repo = seedRepo("external-addon-resolver-failure")
    val service = RuntimeRepoBrowserService()
    service.externalAddonSourcesResolver = { throw IllegalStateException("bad external config") }

    val session = service.open(repo.toString())
    val addonGroup = service.treeFor(session).single { it.kind == TreeItemKind.GROUP && it.label == "Add-ons" }
    val kotlinAddons = addonGroup.children.single { it.kind == TreeItemKind.GROUP && it.label == "kotlin" }
    val packOwned = kotlinAddons.children.single { it.label == "tracing-otel" }

    assertEquals(RepoLoadState.LOADED, session.loadStatus.state, session.loadStatus.message)
    assertFalse(packOwned.external)
    assertTrue(packOwned.editable)
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
  fun `malformed governed skill content is rejected and source is rolled back`() {
    val repo = seedRepo("authoring-save-malformed-content")
    val before = Files.readString(repo.resolve("skills/bill-alpha/content.md"))
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val skillId = service.treeForSessionLocalId(session, "skill:bill-alpha")
    val malformed = before.replace("\n---\n\n# alpha", "\n--- dfsdf\n\n# alpha")

    val result = service.saveDocument(session, skillId, malformed)

    assertFalse(result.success)
    assertTrue(result.runtimeErrorMessage.orEmpty().contains("Validator failed after content update"))
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
      service.treeForSessionLocalId(session, "generated:skills/bill-code-check/stack-routing.md")
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
  fun `resolveGeneratedArtifactTreeItemId matches exact generated artifact tree id from snapshot`() {
    val repo = seedRepo("generated-artifact-resolve")
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val generatedId = service.treeForSessionLocalId(session, "generated:skills/bill-alpha/SKILL.md")

    val resolved = service.resolveGeneratedArtifactTreeItemId(session, "skills/bill-alpha/SKILL.md")

    assertEquals(generatedId, resolved)
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
  fun `bundled native agent rows expose individual entry content`() {
    val repo = seedRepo("bundled-native-agent")
    Files.writeString(
      repo.resolve("skills/bill-alpha/native-agents/agents.yaml"),
      """
        |agents:
        |  - name: alpha-one
        |    description: First bundled agent.
        |    body: |-
        |      First body.
        |  - name: alpha-two
        |    description: Second bundled agent.
        |    body: |-
        |      Second body.
      """.trimMargin(),
    )
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val flattened = service.treeFor(session).flatten()
    val first = flattened.first {
      it.id.hasLocalId("native-agent:skills/bill-alpha/native-agents/agents.yaml:alpha-one")
    }
    val second = flattened.first {
      it.id.hasLocalId("native-agent:skills/bill-alpha/native-agents/agents.yaml:alpha-two")
    }

    val firstDocument = service.loadDocument(session, first.id)
    val secondDocument = service.loadDocument(session, second.id)

    assertTrue(firstDocument.text.contains("name: alpha-one"))
    assertTrue(firstDocument.text.contains("First body."))
    assertFalse(firstDocument.text.contains("alpha-two"))
    assertTrue(secondDocument.text.contains("name: alpha-two"))
    assertTrue(secondDocument.text.contains("Second body."))
    assertFalse(firstDocument.text == secondDocument.text)
  }

  @Test
  fun `composed platform native agent rows expose governed content`() {
    val repo = seedRepo("composed-platform-native-agent")
    Files.writeString(
      repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/native-agents/agents.yaml"),
      """
        |agents:
        |  - name: bill-kotlin-code-review-architecture
        |    description: Kotlin architecture native agent.
        |    compose: governed-content
      """.trimMargin(),
    )
    val service = RuntimeRepoBrowserService()
    val session = service.open(repo.toString())
    val item = service.treeFor(session).flatten().first {
      it.id.hasLocalId(
        "native-agent:" +
          "platform-packs/kotlin/code-review/bill-kotlin-code-review/native-agents/agents.yaml:" +
          "bill-kotlin-code-review-architecture",
      )
    }

    val document = service.loadDocument(session, item.id)

    assertTrue(document.text.contains("name: bill-kotlin-code-review-architecture"))
    assertFalse(document.text.contains("compose: governed-content"))
    assertTrue(document.text.contains("Architecture guidance."))
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

  @Test
  fun `installed-workspace session marks only locally-modified skills in the tree`() {
    val repo = seedRepo("baseline-installed")
    val service = RuntimeRepoBrowserService()
    service.baselineModifiedResolver = { root ->
      if (root.toAbsolutePath().normalize() == repo.toAbsolutePath().normalize()) {
        setOf("skills/bill-alpha")
      } else {
        emptySet()
      }
    }

    val session = service.open(repo.toString())
    val flattened = service.treeFor(session).flatten()

    val alpha = flattened.single { it.id.hasLocalId("skill:bill-alpha") }
    assertTrue(alpha.baselineModified)
    val kotlinReview = flattened.single { it.id.hasLocalId("skill:bill-kotlin-code-review") }
    assertFalse(kotlinReview.baselineModified)
  }

  @Test
  fun `clone session shows no baseline indicators`() {
    val repo = seedRepo("baseline-clone")
    val service = RuntimeRepoBrowserService()
    // Clone sessions resolve to an empty modified set regardless of root.
    service.baselineModifiedResolver = { emptySet() }

    val session = service.open(repo.toString())
    val flattened = service.treeFor(session).flatten()

    assertTrue(flattened.filter { it.kind == TreeItemKind.SKILL }.none { it.baselineModified })
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
    writeContentSkill(repo, "bill-code-check", "Quality guidance.")
    Files.createDirectories(repo.resolve("orchestration/skill-classes"))
    Files.writeString(
      repo.resolve("orchestration/skill-classes/quality-check-shell.yaml"),
      """
        |class: quality-check-shell
        |contract_version: "1.1"
        |
        |matchers:
        |  - exact: bill-code-check
        |
        |pointers:
        |  - stack-routing
      """.trimMargin(),
    )
    Files.writeString(repo.resolve("skills/bill-code-check/stack-routing.md"), "Generated pointer\n")
  }

  private fun writePlatformPack(repo: Path) {
    val packRoot = repo.resolve("platform-packs/kotlin")
    Files.createDirectories(packRoot.resolve("code-review/bill-kotlin-code-review"))
    Files.createDirectories(packRoot.resolve("code-review/bill-kotlin-code-review/native-agents"))
    Files.createDirectories(packRoot.resolve("code-review/bill-kotlin-code-review-architecture"))
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
        |declared_code_review_areas:
        |  - architecture
        |
        |declared_files:
        |  baseline: code-review/bill-kotlin-code-review/content.md
        |  areas:
        |    architecture: code-review/bill-kotlin-code-review-architecture/content.md
        |
        |area_metadata:
        |  architecture:
        |    focus: Architecture.
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
    Files.writeString(
      packRoot.resolve("code-review/bill-kotlin-code-review/native-agents/bill-kotlin-code-review-ui.md"),
      """
        |---
        |name: bill-kotlin-code-review-ui
        |description: Kotlin review native agent.
        |---
        |
        |Native agent body.
      """.trimMargin(),
    )
    Files.writeString(
      packRoot.resolve("code-review/bill-kotlin-code-review-architecture/content.md"),
      """
        |---
        |name: bill-kotlin-code-review-architecture
        |description: Kotlin architecture review.
        |---
        |
        |# Kotlin architecture review
        |
        |Architecture guidance.
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
