package skillbill.scaffold

import skillbill.error.InvalidScaffoldPayloadError
import skillbill.error.MissingShellCeremonyFileError
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
    assertCodexStub(skillDir.resolve("codex-agents/foo-arch.toml"), "foo-arch")
    assertCodexStub(skillDir.resolve("codex-agents/foo-perf.toml"), "foo-perf")
    assertOpencodeStub(skillDir.resolve("opencode-agents/foo-arch.md"), "foo-arch")
    assertOpencodeStub(skillDir.resolve("opencode-agents/foo-perf.md"), "foo-perf")
    assertTrue(result.notes.any { note -> "Subagent stubs emitted: 2." in note }, result.notes.toString())
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
    assertCodexStub(baseline.resolve("codex-agents/arch.toml"), "arch")
    assertOpencodeStub(baseline.resolve("opencode-agents/perf.md"), "perf")
    assertFalse(Files.exists(qualityCheck.resolve("codex-agents")))
    assertFalse(Files.exists(qualityCheck.resolve("opencode-agents")))
    assertContains(Files.readString(baseline.resolve("content.md")), "## Subagent Spawn Runtime Notes")
  }

  @Test
  fun `platform pack custom specialist areas are approved sorted and registered`() = withIsolatedUserHome {
    val repo = seedRepo()
    val result =
      scaffold(
        payload(repo, "platform-pack", "platform" to "php") +
          mapOf("specialist_areas" to listOf("security", "architecture")),
      )
    val manifest = Files.readString(repo.resolve("platform-packs/php/platform.yaml"))

    assertEquals("platform-pack", result.kind)
    assertTrue(
      Files.isRegularFile(repo.resolve("platform-packs/php/code-review/bill-php-code-review-architecture/SKILL.md")),
    )
    assertTrue(
      Files.isRegularFile(repo.resolve("platform-packs/php/code-review/bill-php-code-review-security/SKILL.md")),
    )
    assertTrue(manifest.indexOf("  - \"architecture\"") < manifest.indexOf("  - \"security\""))
    assertContains(manifest, "architecture: \"code-review/bill-php-code-review-architecture/SKILL.md\"")
    assertContains(manifest, "security: \"code-review/bill-php-code-review-security/SKILL.md\"")
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
  fun `authoring render preserves platform display name and base shell ceremony sidecars`() = withIsolatedUserHome {
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
      baseSkill.resolve("SKILL.md"),
      renderSkillBody(
        TemplateContext("bill-code-review", "code-review", "", "", "code review"),
        "Use when reviewing code changes across stack-specific review specialists.",
      ),
    )
    Files.writeString(baseSkill.resolve("content.md"), baselineReviewContent("Base shell content."))
    Files.writeString(baseSkill.resolve("shell-ceremony.md"), "# ceremony\n")
    Files.writeString(baseSkill.resolve("shell-content-contract.md"), "# contract\n")
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
    assertFalse(hasGenerationDrift(target))
    loadPlatformPack(repo.resolve("platform-packs/foo-bar"))
  }

  @Test
  fun `feature verify render preserves audit rubrics sidecar and validates regular files`() = withIsolatedUserHome {
    val repo = seedRepo()
    val skillDir = repo.resolve("skills/bill-feature-verify")
    Files.createDirectories(skillDir)
    val context = TemplateContext("bill-feature-verify", "feature-verify", "", "", "feature verify")
    Files.writeString(skillDir.resolve("SKILL.md"), renderSkillBody(context, inferSkillDescription(context)))
    Files.writeString(skillDir.resolve("content.md"), baselineReviewContent("Feature verify content."))
    Files.writeString(skillDir.resolve("shell-ceremony.md"), "# ceremony\n")
    Files.writeString(skillDir.resolve("telemetry-contract.md"), "# telemetry\n")
    Files.writeString(skillDir.resolve("audit-rubrics.md"), "# audit\n")
    val target = resolveTarget(repo, "bill-feature-verify")
    val rendered = renderWrapper(target)

    assertContains(rendered, "audit-rubrics.md")
    Files.writeString(skillDir.resolve("SKILL.md"), rendered)
    assertEquals(emptyList(), validateTarget(target))

    Files.delete(skillDir.resolve("audit-rubrics.md"))
    Files.createDirectories(skillDir.resolve("audit-rubrics.md"))

    assertTrue(validateTarget(target).any { issue -> "audit-rubrics.md" in issue })
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
    assertContains(Files.readString(skillDir.resolve("SKILL.md")), "audit-rubrics.md")
    assertTrue(Files.isSymbolicLink(rubricLink))
    assertEquals(repo.resolve("skills/bill-feature-verify/audit-rubrics.md"), Files.readSymbolicLink(rubricLink))
  }

  @Test
  fun `no subagents opt out skips stub emission`() = withIsolatedUserHome {
    val repo = seedRepo()
    scaffold(payload(repo, "horizontal", "name" to "bill-bar-orchestrator") + mapOf("no_subagents" to true))
    val skillDir = repo.resolve("skills").resolve("bill-bar-orchestrator")

    assertFalse(Files.exists(skillDir.resolve("codex-agents")))
    assertFalse(Files.exists(skillDir.resolve("opencode-agents")))
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
    assertContains(manifest, "declared_quality_check_file: \"quality-check/bill-kotlin-quality-check/SKILL.md\"")
  }

  @Test
  fun `scaffold rollback restores manifest files symlinks and directories byte identically`() = withIsolatedUserHome {
    val repo = seedRepo()
    val unrelated = repo.resolve("notes/unrelated.txt")
    Files.createDirectories(unrelated.parent)
    Files.writeString(unrelated, "keep me")
    Files.delete(repo.resolve("orchestration/shell-content-contract/shell-ceremony.md"))
    val before = snapshotTree(repo)

    assertFailsWith<MissingShellCeremonyFileError> {
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
      baselineContentPath = "code-review/bill-kotlin-code-review/SKILL.md",
    ),
  )
  Files.writeString(baseline.resolve("SKILL.md"), renderSkillBody(context, inferSkillDescription(context)))
  Files.writeString(baseline.resolve("content.md"), renderContentBody(context, inferSkillDescription(context)))
  Files.writeString(baseline.resolve("shell-ceremony.md"), "# ceremony\n")
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
      baselineContentPath = "code-review/bill-kmp-code-review/SKILL.md",
    ),
  )
  Files.writeString(baseline.resolve("SKILL.md"), renderSkillBody(context, inferSkillDescription(context)))
  Files.writeString(baseline.resolve("content.md"), renderContentBody(context, inferSkillDescription(context)))
  Files.writeString(baseline.resolve("shell-ceremony.md"), "# ceremony\n")
}

private fun assertCodexStub(path: Path, name: String) {
  val text = Files.readString(path)
  assertContains(text, "name = \"$name\"")
  assertContains(text, "developer_instructions")
  assertContains(text, "TODO: replace this placeholder")
}

private fun assertOpencodeStub(path: Path, name: String) {
  val text = Files.readString(path)
  assertContains(text, "name: $name")
  assertContains(text, "mode: subagent")
  assertContains(text, "TODO: replace this placeholder")
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
