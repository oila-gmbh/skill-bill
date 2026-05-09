package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingContentFileError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScaffoldServiceParityTest {
  @Test
  fun `horizontal subagent specialists emit runtime notes and native stubs`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result =
      scaffold(
        payload(repo, "horizontal", "name" to "bill-foo-orchestrator") +
          mapOf("subagent_specialists" to listOf("foo-arch", "foo-perf")),
      )
    val skillDir = repo.resolve("skills").resolve("bill-foo-orchestrator")
    val content = Files.readString(skillDir.resolve("content.md"))

    assertEquals("horizontal", result.kind)
    assertContains(content, "## Subagent Spawn Runtime Notes")
    assertContains(content, "`@foo-arch`")
    assertContains(content, "`@foo-perf`")
    assertSourceStub(skillDir.resolve("native-agents/foo-arch.md"), "foo-arch")
    assertSourceStub(skillDir.resolve("native-agents/foo-perf.md"), "foo-perf")
    assertFalse(Files.exists(skillDir.resolve("codex-agents")))
    assertFalse(Files.exists(skillDir.resolve("opencode-agents")))
    assertFalse(Files.exists(skillDir.resolve("junie-agents")))
    assertNoGeneratedWrapper(skillDir)
    assertSourceSidecars(skillDir, "bill-foo-orchestrator", repo)
    assertTrue(result.notes.any { note -> "Subagent stubs emitted: 2." in note }, result.notes.toString())
  }

  @Test
  fun `horizontal scaffold creates source sidecars without generated wrapper`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result = scaffold(payload(repo, "horizontal", "name" to "bill-pr-description"))
    val skillDir = repo.resolve("skills").resolve("bill-pr-description")

    assertEquals("bill-pr-description", result.skillName)
    assertNoGeneratedWrapper(skillDir)
    assertSourceSidecars(skillDir, "bill-pr-description", repo)
  }

  @Test
  fun `platform pack subagent specialists attach only to baseline orchestrator`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result =
      scaffold(
        payload(repo, "platform-pack", "platform" to "java") +
          mapOf("skeleton_mode" to "starter", "subagent_specialists" to listOf("arch", "perf")),
      )
    val packRoot = repo.resolve("platform-packs").resolve("java")
    val baseline = packRoot.resolve("code-review/bill-java-code-review")
    val qualityCheck = packRoot.resolve("quality-check/bill-java-quality-check")

    assertEquals("platform-pack", result.kind)
    assertSourceStub(baseline.resolve("native-agents/arch.md"), "arch")
    assertSourceStub(baseline.resolve("native-agents/perf.md"), "perf")
    assertFalse(Files.exists(baseline.resolve("codex-agents")))
    assertFalse(Files.exists(baseline.resolve("opencode-agents")))
    assertFalse(Files.exists(baseline.resolve("junie-agents")))
    assertFalse(Files.exists(qualityCheck.resolve("codex-agents")))
    assertFalse(Files.exists(qualityCheck.resolve("opencode-agents")))
    assertFalse(Files.exists(qualityCheck.resolve("junie-agents")))
    assertFalse(Files.exists(qualityCheck.resolve("native-agents")))
    assertContains(Files.readString(baseline.resolve("content.md")), "## Subagent Spawn Runtime Notes")
    assertNoGeneratedWrapperOrSupportingFiles(baseline, "bill-java-code-review")
    assertNoGeneratedWrapperOrSupportingFiles(qualityCheck, "bill-java-quality-check")
  }

  @Test
  fun `platform pack custom specialist areas are approved sorted and registered`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result =
      scaffold(
        payload(repo, "platform-pack", "platform" to "java") +
          mapOf("specialist_areas" to listOf("security", "architecture")),
      )
    val manifest = Files.readString(repo.resolve("platform-packs/java/platform.yaml"))

    assertEquals("platform-pack", result.kind)
    assertTrue(
      Files.isRegularFile(repo.resolve("platform-packs/java/code-review/bill-java-code-review-architecture/content.md")),
    )
    assertTrue(
      Files.isRegularFile(repo.resolve("platform-packs/java/code-review/bill-java-code-review-security/content.md")),
    )
    assertTrue(manifest.indexOf("  - \"architecture\"") < manifest.indexOf("  - \"security\""))
    assertContains(manifest, "architecture: \"code-review/bill-java-code-review-architecture/content.md\"")
    assertContains(manifest, "security: \"code-review/bill-java-code-review-security/content.md\"")
    assertNoGeneratedWrapperOrSupportingFiles(
      repo.resolve("platform-packs/java/code-review/bill-java-code-review-architecture"),
      "bill-java-code-review-architecture",
    )
    assertNoGeneratedWrapperOrSupportingFiles(
      repo.resolve("platform-packs/java/code-review/bill-java-code-review-security"),
      "bill-java-code-review-security",
    )
    val pack = loadPlatformPack(repo.resolve("platform-packs/java"))
    assertEquals(
      repo.resolve("platform-packs/java/code-review/bill-java-code-review-architecture/content.md"),
      pack.declaredFiles.areas.getValue("architecture"),
    )
    assertEquals(
      repo.resolve("platform-packs/java/code-review/bill-java-code-review-security/content.md"),
      pack.declaredFiles.areas.getValue("security"),
    )
  }

  @Test
  fun `code review area scaffold omits generated wrapper and supporting pointer files`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result =
      scaffold(
        payload(
          repo,
          "code-review-area",
          "platform" to "kotlin",
          "area" to "performance",
          "name" to "bill-kotlin-code-review-performance",
        ),
      )
    val skillDir = repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-performance")

    assertEquals("bill-kotlin-code-review-performance", result.skillName)
    assertTrue(Files.isRegularFile(skillDir.resolve("content.md")))
    assertNoGeneratedWrapperOrSupportingFiles(skillDir, "bill-kotlin-code-review-performance")
  }

  @Test
  fun `subagent payload validation rejects invalid combinations and leaf kinds`() = withIsolatedUserHome {
    val repo = seedRepo()
    listOf(
      mapOf("subagent_specialists" to "foo") to "list of strings",
      mapOf("subagent_specialists" to listOf("")) to "non-empty strings",
      mapOf("subagent_specialists" to listOf("foo", "foo")) to "duplicate",
      mapOf("subagent_specialists" to listOf("../foo")) to "invalid name",
      mapOf("no_subagents" to "yes") to "must be a boolean",
      mapOf("subagent_specialists" to listOf("foo"), "no_subagents" to true) to "no_subagents=true",
    ).forEach { (extraPayload, expected) ->
      assertContains(
        assertFailsWith<InvalidScaffoldPayloadError> {
          scaffold(payload(repo, "horizontal", "name" to "bill-mixed-orchestrator") + extraPayload)
        }.message.orEmpty(),
        expected,
      )
    }
    assertContains(
      assertFailsWith<InvalidScaffoldPayloadError> {
        scaffold(
          payload(repo, "code-review-area", "platform" to "kotlin", "area" to "performance") +
            mapOf("name" to "bill-kotlin-code-review-performance", "subagent_specialists" to listOf("x")),
        )
      }.message.orEmpty(),
      "subagent_specialists is only valid for orchestrator kinds",
    )
    assertContains(
      assertFailsWith<InvalidScaffoldPayloadError> {
        scaffold(
          payload(repo, "add-on", "platform" to "kotlin", "name" to "review-helper") +
            mapOf("subagent_specialists" to listOf("x")),
        )
      }.message.orEmpty(),
      "subagent_specialists is only valid for orchestrator kinds",
    )
    assertContains(
      assertFailsWith<InvalidScaffoldPayloadError> {
        scaffold(payload(repo, "platform-pack", "platform" to "java") + mapOf("specialist_areas" to listOf("mobile")))
      }.message.orEmpty(),
      "unknown areas",
    )
  }

  @Test
  fun `authoring render preserves platform display name and base shell ceremony references`() = withIsolatedUserHome {
    val repo = seedRepo()
    val packSkill = repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/SKILL.md")
    val packTarget =
      AuthoringTarget(
        skillName = "bill-kotlin-code-review",
        packageName = "kotlin",
        platform = "kotlin",
        displayName = "Kotlin",
        family = "code-review",
        area = "",
        skillFile = packSkill,
        contentFile = packSkill.resolveSibling("content.md"),
      )
    val baseSkill = repo.resolve("skills/bill-code-review")
    Files.createDirectories(baseSkill)
    Files.writeString(
      baseSkill.resolve("content.md"),
      "---\nname: bill-code-review\ndescription: Base shell content.\n---\n\n" +
        baselineReviewContent("Base shell content."),
    )
    val baseTarget =
      AuthoringTarget(
        skillName = "bill-code-review",
        packageName = "base",
        platform = "",
        displayName = "code review",
        family = "code-review",
        area = "",
        skillFile = baseSkill.resolve("SKILL.md"),
        contentFile = baseSkill.resolve("content.md"),
      )

    assertContains(renderWrapper(packTarget), "Platform pack: `kotlin` (Kotlin)")
    val renderedBase = renderWrapper(baseTarget)

    assertContains(renderedBase, "shell-content-contract.md")
    assertFalse("review-orchestrator.md" in renderedBase)
  }

  @Test
  fun `authoring render and loader share multi word platform display name fallback`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result =
      scaffold(
        payload(repo, "platform-pack", "platform" to "foo-bar") +
          mapOf("skeleton_mode" to "starter", "routing_signals" to mapOf("strong" to listOf(".foobar"))),
      )
    val target = resolveTarget(repo, "bill-foo-bar-code-review")
    val rendered = renderWrapper(target)

    assertEquals("platform-pack", result.kind)
    assertContains(rendered, "Platform pack: `foo-bar` (Foo Bar)")
    loadPlatformPack(repo.resolve("platform-packs/foo-bar"))
  }

  @Test
  fun `feature verify render preserves audit rubrics sidecar and validates regular files`() = withIsolatedUserHome {
    val repo = seedRepo()
    val skillDir = repo.resolve("skills/bill-feature-verify")
    Files.createDirectories(skillDir)
    val context = TemplateContext("bill-feature-verify", "feature-verify", "", "", "feature verify")
    Files.writeString(
      skillDir.resolve("content.md"),
      "---\nname: bill-feature-verify\ndescription: Feature verify content.\n---\n\n" +
        baselineReviewContent("Feature verify content."),
    )
    Files.createSymbolicLink(
      skillDir.resolve("shell-ceremony.md"),
      repo.resolve("orchestration/shell-content-contract/shell-ceremony.md"),
    )
    Files.createSymbolicLink(
      skillDir.resolve("telemetry-contract.md"),
      repo.resolve("orchestration/telemetry-contract/PLAYBOOK.md"),
    )
    Files.writeString(skillDir.resolve("audit-rubrics.md"), "# audit\n")
    val target = resolveTarget(repo, "bill-feature-verify")
    val rendered = renderWrapper(target)

    assertContains(rendered, "audit-rubrics.md")
    assertEquals(emptyList(), validateTarget(target, repo))

    Files.delete(skillDir.resolve("audit-rubrics.md"))

    assertTrue(validateTarget(target, repo).any { issue -> "audit-rubrics.md" in issue })
  }

  @Test
  fun `feature verify override links shared audit rubrics supporting file`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result =
      scaffold(
        payload(
          repo,
          "platform-override-piloted",
          "platform" to "kmp",
          "family" to "feature-verify",
          "name" to "bill-kmp-feature-verify",
        ),
      )
    val skillDir = repo.resolve("skills/kmp/bill-kmp-feature-verify")
    val rubricLink = skillDir.resolve("audit-rubrics.md")

    assertEquals("bill-kmp-feature-verify", result.skillName)
    assertContains(renderWrapper(resolveTarget(repo, "bill-kmp-feature-verify")), "audit-rubrics.md")
    assertTrue(Files.isSymbolicLink(rubricLink))
    assertEquals(repo.resolve("skills/bill-feature-verify/audit-rubrics.md"), Files.readSymbolicLink(rubricLink))
    assertSourceSidecars(skillDir, "bill-kmp-feature-verify", repo)
  }

  @Test
  fun `no subagents opt out skips stub emission`() = withIsolatedUserHome {
    val repo = seedRepo()
    scaffold(payload(repo, "horizontal", "name" to "bill-bar-orchestrator") + mapOf("no_subagents" to true))
    val skillDir = repo.resolve("skills").resolve("bill-bar-orchestrator")

    assertFalse(Files.exists(skillDir.resolve("codex-agents")))
    assertFalse(Files.exists(skillDir.resolve("opencode-agents")))
    assertFalse(Files.exists(skillDir.resolve("junie-agents")))
    assertFalse(Files.exists(skillDir.resolve("native-agents")))
    assertFalse("## Subagent Spawn Runtime Notes" in Files.readString(skillDir.resolve("content.md")))
  }

  @Test
  fun `quality check override registers declared manifest file`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result =
      scaffold(
        payload(
          repo,
          "platform-override-piloted",
          "platform" to "kotlin",
          "family" to "quality-check",
          "name" to "bill-kotlin-quality-check",
        ),
      )
    val manifest = Files.readString(repo.resolve("platform-packs/kotlin/platform.yaml"))

    assertEquals("bill-kotlin-quality-check", result.skillName)
    assertContains(manifest, "declared_quality_check_file: \"quality-check/bill-kotlin-quality-check/content.md\"")
    assertNoGeneratedWrapperOrSupportingFiles(
      repo.resolve("platform-packs/kotlin/quality-check/bill-kotlin-quality-check"),
      "bill-kotlin-quality-check",
    )
    val pack = loadPlatformPack(repo.resolve("platform-packs/kotlin"))
    assertEquals(
      repo.resolve("platform-packs/kotlin/quality-check/bill-kotlin-quality-check/content.md"),
      loadQualityCheckContent(pack),
    )
  }

  @Test
  fun `scaffold rollback restores manifest files symlinks and directories byte identically`() = withIsolatedUserHome {
    val repo = seedRepo()
    val unrelated = repo.resolve("notes/unrelated.txt")
    Files.createDirectories(unrelated.parent)
    Files.writeString(unrelated, "keep me")
    Files.delete(repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md"))
    val before = snapshotTree(repo)

    assertFailsWith<MissingContentFileError> {
      scaffold(
        payload(
          repo,
          "code-review-area",
          "platform" to "kotlin",
          "area" to "performance",
          "name" to "bill-kotlin-code-review-performance",
        ),
      )
    }

    assertEquals(before, snapshotTree(repo))
    assertEquals("keep me", Files.readString(unrelated))
    assertFalse(Files.exists(repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review-performance")))
  }
}

private fun payload(repo: Path, kind: String, vararg pairs: Pair<String, Any?>): Map<String, Any?> =
  mapOf("scaffold_payload_version" to "1.0", "kind" to kind, "repo_root" to repo.toString()) + pairs

private fun seedRepo(): Path {
  val repo = Files.createTempDirectory("skillbill-scaffold-repo")
  supportingFileTargets(repo).values.forEach { target ->
    Files.createDirectories(target.parent)
    Files.writeString(target, "# ${target.fileName}\n")
  }
  seedKotlinPack(repo)
  seedKmpPack(repo)
  return repo
}

private fun seedKotlinPack(repo: Path) {
  val packRoot = repo.resolve("platform-packs/kotlin")
  val baseline = packRoot.resolve("code-review/bill-kotlin-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kotlin-code-review", "code-review", "kotlin", "", "Kotlin")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      platform = "kotlin",
      displayName = "Kotlin",
      strongSignals = listOf(".kt"),
      baselineContentPath = "code-review/bill-kotlin-code-review/content.md",
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
}

private fun seedKmpPack(repo: Path) {
  val packRoot = repo.resolve("platform-packs/kmp")
  val baseline = packRoot.resolve("code-review/bill-kmp-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kmp-code-review", "code-review", "kmp", "", "KMP")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      platform = "kmp",
      displayName = "KMP",
      strongSignals = listOf(".kt"),
      baselineContentPath = "code-review/bill-kmp-code-review/content.md",
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
}

private fun assertSourceStub(path: Path, name: String) {
  val text = Files.readString(path)
  assertContains(text, "name: $name")
  assertContains(text, "description:")
  assertContains(text, "TODO: replace this placeholder with the specialist briefing.")
  val frontmatterEndIndex = text.indexOf("\n---\n", startIndex = 4)
  assertTrue(frontmatterEndIndex > 0, "Expected closing frontmatter fence in $path")
  assertFalse("mode: subagent" in text)
}

private fun assertNoGeneratedWrapperOrSupportingFiles(skillDir: Path, skillName: String) {
  assertNoGeneratedWrapper(skillDir)
  requiredSupportingFilesForSkill(skillName).forEach { fileName ->
    assertFalse(
      Files.exists(skillDir.resolve(fileName)),
      "scaffold must not create source pointer/supporting file '$fileName' at $skillDir",
    )
  }
}

private fun assertNoGeneratedWrapper(skillDir: Path) {
  assertFalse(Files.exists(skillDir.resolve("SKILL.md")), "scaffold must not create source SKILL.md at $skillDir")
}

private fun assertSourceSidecars(skillDir: Path, skillName: String, repo: Path) {
  requiredSupportingFilesForSkill(skillName).forEach { fileName ->
    val sidecar = skillDir.resolve(fileName)
    assertTrue(Files.isSymbolicLink(sidecar), "scaffold must create source sidecar symlink '$fileName' at $skillDir")
    assertEquals(supportingFileTargets(repo).getValue(fileName), Files.readSymbolicLink(sidecar))
  }
}

private fun withIsolatedUserHome(block: () -> Unit) {
  val originalHome = System.getProperty("user.home")
  val tempHome = Files.createTempDirectory("skillbill-no-agents-home")
  try {
    System.setProperty("user.home", tempHome.toString())
    block()
  } finally {
    System.setProperty("user.home", originalHome)
  }
}

private fun snapshotTree(root: Path): Map<String, String> {
  if (!Files.exists(root)) {
    return emptyMap()
  }
  return Files.walk(root).use { stream ->
    stream
      .filter { path -> path != root }
      .sorted()
      .toList()
      .associate { path ->
        val key = root.relativize(path).toString()
        val value = when {
          Files.isSymbolicLink(path) -> "symlink:${Files.readSymbolicLink(path)}"
          Files.isDirectory(path) -> "dir"
          else -> "file:${Files.readString(path)}"
        }
        key to value
      }
  }
}
