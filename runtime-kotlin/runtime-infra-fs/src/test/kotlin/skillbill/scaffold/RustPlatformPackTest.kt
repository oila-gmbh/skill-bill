package skillbill.scaffold

import skillbill.error.InvalidManifestSchemaError
import skillbill.error.InvalidNativeAgentCompositionSchemaError
import skillbill.install.model.InstallAgent
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.InstallPlan
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallSkillStagingStatus
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.NativeAgentApplyStatus
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.install.runtime.InstallOperations
import skillbill.nativeagent.composition.composeNativeAgentSource
import skillbill.nativeagent.composition.parseNativeAgentBundle
import skillbill.scaffold.platformpack.loadPlatformPack
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.substance.Fraction
import skillbill.scaffold.substance.PlatformPackSubstanceAudit
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RustPlatformPackTest {
  @Test
  fun `rust platform pack declares expected manifest and routing contract`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/rust")
    val pack = loadPlatformPack(packRoot)

    assertEquals("rust", pack.slug)
    assertEquals("Rust", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(packRoot.resolve("code-review/bill-rust-code-review/content.md"), pack.declaredFiles.baseline)
    assertEquals(packRoot.resolve("quality-check/bill-rust-code-check/content.md"), pack.declaredQualityCheckFile)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)
    listOf(
      "Cargo.toml",
      "Cargo.lock",
      ".rs",
      "*.rs",
      "build.rs",
      "rust-toolchain.toml",
      "rustfmt.toml",
      "clippy.toml",
      "deny.toml",
      ".cargo/config.toml",
    ).forEach { marker -> assertContains(pack.routingSignals.strong, marker) }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("FFI bindings") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("wasm") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("target/") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("vendored") })
  }

  @Test
  fun `rust review quality and native agent sources are complete`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/rust")
    val contentFiles = Files.walk(packRoot).use { stream ->
      stream.filter { path -> path.fileName.toString() == "content.md" }.sorted().toList()
    }
    assertEquals(12, contentFiles.size)
    contentFiles.forEach { contentFile ->
      val text = Files.readString(contentFile)
      assertContains(text, "internal-for:")
      assertFalse(text.contains("TODO"), "Rust pack source must not contain TODO placeholders: $contentFile")
      assertTrue(text.lines().size > 18, "Rust pack source should contain substantive guidance: $contentFile")
    }

    val bundlePath = packRoot.resolve("code-review/bill-rust-code-review/native-agents/agents.yaml")
    assertContains(Files.readString(bundlePath), "contract_version: \"0.1\"")
    val agents = parseNativeAgentBundle(bundlePath)
    val expectedNames = APPROVED_CODE_REVIEW_AREAS.map { "bill-rust-code-review-$it" }.toSet()
    assertEquals(expectedNames, agents.map { it.name }.toSet())
    agents.forEach { agent ->
      val composed = composeNativeAgentSource(repoRoot, agent)
      assertTrue(composed.body.isNotBlank(), "Expected governed content for ${agent.name}")
    }
  }

  @Test
  fun `rust specialists expose concrete coverage synchronized with metadata and agents`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/rust")
    val pack = loadPlatformPack(packRoot)
    val governedRulesByArea = pack.declaredFiles.areas.mapValues { (_, path) ->
      governedRules(Files.readString(path))
    }

    RUST_EXPECTED_MARKERS.forEach { (area, markers) ->
      val rules = governedRulesByArea.getValue(area).joinToString("\n")
      markers.forEach { marker -> assertContains(rules, marker) }
    }
    RUST_EXPECTED_RULE_CONTRACTS.forEach { (area, contracts) ->
      val rules = governedRulesByArea.getValue(area)
      contracts.forEach { markers ->
        assertTrue(
          rules.any { rule -> markers.all(rule::contains) },
          "Rust $area must keep coupled rule contract: ${markers.joinToString()}",
        )
      }
    }
    governedRulesByArea.forEach { (area, rules) ->
      assertFalse(
        rules.any { rule -> Regex("`Rust [^`]* APIs`").containsMatchIn(rule) },
        "Rust $area must name concrete evidence instead of a generic Rust APIs phrase",
      )
    }

    val agents = parseNativeAgentBundle(
      packRoot.resolve("code-review/bill-rust-code-review/native-agents/agents.yaml"),
    ).associateBy { agent -> agent.name.removePrefix("bill-rust-code-review-") }
    assertEquals(APPROVED_CODE_REVIEW_AREAS, agents.keys)
    agents.forEach { (area, agent) ->
      assertEquals(
        "${pack.displayName} ${area.replace('-', ' ')} specialist code reviewer. " +
          "Runs against ${pack.areaMetadata.getValue(area)}. " +
          "Returns a Risk Register in the F-XXX bullet format.",
        agent.description,
      )
    }
  }

  @Test
  fun `rust specialists and checker pass completed pack substance and duplication gates`() {
    val report = PlatformPackSubstanceAudit.audit(repoRootFromTest())
    val rust = report.packs.single { pack -> pack.pack == "rust" }

    assertEquals(APPROVED_CODE_REVIEW_AREAS, rust.physicalAreas.toSet())
    rust.specialists.filterNot { specialist -> specialist.inherited }.forEach { specialist ->
      assertTrue(specialist.substantiveRules >= 10, "Thin Rust specialist: ${specialist.area}")
      assertEquals(3, specialist.failureModeClusters, "Missing failure cluster: ${specialist.area}")
      assertTrue(specialist.concreteEvidenceRules >= 10, "Missing evidence: ${specialist.area}")
      assertTrue(specialist.placeholders.isEmpty(), "Placeholder in ${specialist.area}")
    }
    assertEquals(7, rust.qualityCheckFacets.size)
    assertTrue(rust.sharedShingles <= Fraction(35, 100), rust.sharedShingles.percentage())

    val rustPairs = report.pairs.filter { pair ->
      pair.firstFile.startsWith("platform-packs/rust/") || pair.secondFile.startsWith("platform-packs/rust/")
    }
    assertTrue(rustPairs.isNotEmpty())
    rustPairs.forEach { pair ->
      assertTrue(pair.similarity <= Fraction(65, 100), "${pair.role}: ${pair.similarity.percentage()}")
    }
    val rustTypeScriptRoles = rustPairs.filter { pair ->
      pair.firstFile.startsWith("platform-packs/typescript/") ||
        pair.secondFile.startsWith("platform-packs/typescript/")
    }.map { pair -> pair.role }.toSet()
    assertEquals(
      setOf("baseline", "quality_check") + APPROVED_CODE_REVIEW_AREAS.map { area -> "specialist:$area" },
      rustTypeScriptRoles,
    )
    assertFalse(report.violations.any { violation -> violation.pack == "rust" })
  }

  @Test
  fun `rust quality checker preserves conditional ordered repository execution`() {
    val content = Files.readString(
      repoRootFromTest().resolve("platform-packs/rust/quality-check/bill-rust-code-check/content.md"),
    )
    listOf(
      "repository wrapper, and CI configuration",
      "cargo fmt --all --check",
      "cargo clippy --workspace --all-targets -- -D warnings",
      "cargo check --workspace --all-targets",
      "cargo build --workspace",
      "cargo test --workspace",
      "cargo test --doc",
      "--no-default-features",
      "cargo deny check",
      "cargo audit",
      "cbindgen",
      "cargo miri test",
      "scoped work",
      "environmental blocker",
      "full suite",
    ).forEach { marker -> assertContains(content, marker) }
    listOf(
      "Do not require mutually exclusive all-feature combinations",
      "Run them only when required by the repository and already provisioned",
      "Do not regenerate or require binding tools when the project does not configure that path",
    ).forEach { marker -> assertContains(content, marker) }

    val orderedMarkers = listOf(
      "Verify formatting first",
      "Run configured linting",
      "Run type and conditional-compilation checks",
      "Run behavior checks",
      "Verify dependency and lockfile policy",
      "When generated bindings are owned",
      "When changed unsafe code",
      "Attribute each failure",
      "Re-run the smallest affected package",
    )
    val orderedPositions = orderedMarkers.map(content::indexOf)
    assertTrue(orderedPositions.all { position -> position >= 0 })
    assertTrue(orderedPositions.zipWithNext().all { (first, second) -> first < second })
  }

  @Test
  fun `rust pack loader preserves partial extension bundles`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-rust-pack-malformed-")
    val packRoot = tempRoot.resolve("rust")
    copyDirectory(repoRoot.resolve("platform-packs/rust"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-rust-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        Regex("(?ms)  - name: bill-rust-code-review-security\\n.*?(?=  - name:|\\z)"),
        "",
      ),
    )

    assertEquals("rust", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `rust pack rejects renamed governed content agents`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-rust-pack-all-agents-renamed-")
    val packRoot = tempRoot.resolve("rust")
    copyDirectory(repoRoot.resolve("platform-packs/rust"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-rust-code-review/native-agents/agents.yaml")
    val canonicalNames = APPROVED_CODE_REVIEW_AREAS.map { "bill-rust-code-review-$it" } +
      "bill-rust-code-review"
    val renamedBundle = canonicalNames.fold(Files.readString(bundle)) { content, name ->
      content.replace("name: $name\n", "name: renamed-$name\n")
    }
    val reducedRenamedBundle = listOf(
      "renamed-bill-rust-code-review",
      "renamed-bill-rust-code-review-security",
    ).fold(renamedBundle) { content, name ->
      content.replace(Regex("(?ms)  - name: $name\\n.*?(?=  - name:|\\z)"), "")
    }
    Files.writeString(bundle, reducedRenamedBundle)

    val error = assertFailsWith<InvalidManifestSchemaError> { loadPlatformPack(packRoot) }
    assertContains(error.message.orEmpty(), "unknown=[renamed-bill-rust-code-review-api-contracts")
    assertContains(error.message.orEmpty(), "renamed-bill-rust-code-review")
  }

  @Test
  fun `rust pack accepts custom body based native agent`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-rust-pack-unknown-body-agent-")
    val packRoot = tempRoot.resolve("rust")
    copyDirectory(repoRoot.resolve("platform-packs/rust"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-rust-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle) +
        "  - name: undeclared-rust-reviewer\n" +
        "    description: \"Custom review agent.\"\n" +
        "    body: |-\n" +
        "      Review the diff.\n",
    )

    assertEquals("rust", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `rust pack loader accepts a specialist-only extension bundle`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-rust-pack-missing-baseline-")
    val packRoot = tempRoot.resolve("rust")
    copyDirectory(repoRoot.resolve("platform-packs/rust"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-rust-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        Regex("(?ms)  - name: bill-rust-code-review\\n.*?(?=  - name:|\\z)"),
        "",
      ),
    )

    assertEquals("rust", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `rust pack reports malformed native agent yaml as typed contract failure`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-rust-pack-malformed-agent-yaml-")
    val packRoot = tempRoot.resolve("rust")
    copyDirectory(repoRoot.resolve("platform-packs/rust"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-rust-code-review/native-agents/agents.yaml")
    Files.writeString(bundle, "agents:\n  - name: [unterminated\n")

    val error = assertFailsWith<InvalidNativeAgentCompositionSchemaError> { loadPlatformPack(packRoot) }
    assertContains(error.sourceLabel, bundle.toString())
    assertContains(error.reason, "could not parse YAML")
  }

  @Test
  fun `rust pack accepts a custom baseline native agent description`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-rust-pack-custom-baseline-description-")
    val packRoot = tempRoot.resolve("rust")
    copyDirectory(repoRoot.resolve("platform-packs/rust"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-rust-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        "Rust baseline code reviewer. Runs the governed baseline review across the full owned diff before " +
          "specialist findings are merged. Returns a Risk Register in the F-XXX bullet format.",
        "Team-owned Rust baseline reviewer.",
      ),
    )

    assertEquals("rust", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `install apply stages selected rust pack skills and native agents`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-rust-install-plan-home-")
    val plan = rustInstallPlan(repoRoot, home)

    val skillsByName = plan.skills.associateBy { it.name }
    assertContains(plan.discoveredPlatformPacks.map { it.slug }, "rust")
    assertEquals(listOf("rust"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-rust-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-rust-code-check").kind)
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-rust-code-review-$area").kind)
    }
    assertFalse(skillsByName.containsKey("bill-go-code-review"))
    assertFalse(skillsByName.containsKey("bill-python-code-review"))

    assertRustInstallApplied(plan)
  }

  private fun rustInstallPlan(repoRoot: Path, home: Path): InstallPlan {
    val runtimeInstallRoot = home.resolve(".skill-bill/runtime")
    return InstallOperations.planInstall(
      InstallPlanRequest(
        repoRoot = repoRoot,
        home = home,
        agentSelection = InstallAgentSelection(
          mode = InstallAgentSelectionMode.MANUAL,
          manualAgents = setOf(InstallAgent.CODEX),
        ),
        platformPackSelection = PlatformPackSelection(
          mode = PlatformPackSelectionMode.SELECTED,
          selectedSlugs = setOf("rust"),
        ),
        telemetryLevel = InstallTelemetryLevel.ANONYMOUS,
        mcpRegistrationChoice = McpRegistrationChoice(
          register = false,
          runtimeMcpBin = runtimeInstallRoot.resolve("runtime-mcp/bin/runtime-mcp"),
        ),
        runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = runtimeInstallRoot),
        targetPaths = InstallationTargetPaths(
          skillsRoot = repoRoot.resolve("skills"),
          platformPacksRoot = repoRoot.resolve("platform-packs"),
          agentTargets = emptyList(),
        ),
        windowsSymlinkPreflight = WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
      ),
    )
  }

  private fun assertRustInstallApplied(plan: InstallPlan) {
    val result = InstallOperations.applyInstall(plan)

    assertEquals(InstallApplyStatus.SUCCESS, result.status, result.failures.joinToString { it.message })
    assertTrue(result.skills.all { skill -> skill.staging.status == InstallSkillStagingStatus.STAGED })
    val reviewSidecars = result.skills
      .single { skill -> skill.skillName == "bill-code-review" }
      .staging
      .renderedSidecarFiles
      .map { path -> path.fileName.toString() }
      .toSet()
    val expectedReviewSidecars = APPROVED_CODE_REVIEW_AREAS.map { area ->
      "bill-rust-code-review-$area.md"
    }.toSet() + "bill-rust-code-review.md"
    assertTrue(reviewSidecars.containsAll(expectedReviewSidecars))
    val checkSidecars = result.skills
      .single { skill -> skill.skillName == "bill-code-check" }
      .staging
      .renderedSidecarFiles
      .map { path -> path.fileName.toString() }
      .toSet()
    assertContains(checkSidecars, "bill-rust-code-check.md")
    val linkedNativeAgents = result.nativeAgents
      .filter { nativeAgent -> nativeAgent.status == NativeAgentApplyStatus.LINKED }
      .mapNotNull { nativeAgent -> nativeAgent.path?.fileName?.toString() }
      .toSet()
    APPROVED_CODE_REVIEW_AREAS.map { area -> "bill-rust-code-review-$area" }.forEach { agentName ->
      assertContains(linkedNativeAgents, "$agentName.toml")
    }
  }

  private fun copyDirectory(source: Path, target: Path) {
    Files.walk(source).use { stream ->
      stream.forEach { path ->
        val destination = target.resolve(source.relativize(path))
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination)
        } else {
          Files.createDirectories(destination.parent)
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    }
  }

  private fun governedRules(content: String): List<String> {
    var governed = false
    return content.lineSequence().mapNotNull { line ->
      when {
        line.startsWith("### ") -> {
          governed = line.contains("Rules")
          null
        }
        line.startsWith("## ") -> {
          governed = false
          null
        }
        governed && line.startsWith("- ") -> line
        else -> null
      }
    }.toList()
  }
}

private val RUST_EXPECTED_MARKERS = mapOf(
  "api-contracts" to listOf("T: Send + Sync + 'static", "#[serde(rename)]", "idempotency key", "#[repr(C)]"),
  "architecture" to listOf("[workspace]", "dyn Trait", "dep:name", "build.rs"),
  "performance" to listOf("Vec::with_capacity", "MutexGuard", "spawn_blocking", "buffer_unordered"),
  "persistence" to listOf("sqlx::Transaction", "SELECT ... FOR UPDATE", "durable outbox", "chrono::DateTime<Utc>"),
  "platform-correctness" to listOf("RefCell<T>", "unsafe impl Send", "Pin<&mut T>", "Drop::drop"),
  "reliability" to listOf("tokio::time::timeout", "Semaphore", "JoinSet", "CancellationToken"),
  "security" to listOf("catch_unwind", "serde_json::from_slice", "std::process::Command::arg", "Path::canonicalize"),
  "testing" to listOf("trybuild", "cargo fuzz", "cargo miri test", "loom::model"),
  "ui" to listOf("wasm_bindgen", "hydrate", "egui", "ratatui"),
  "ux-accessibility" to listOf("aria-describedby", "aria-live", "prefers-reduced-motion", "UnicodeWidthStr"),
)

private val RUST_EXPECTED_RULE_CONTRACTS = mapOf(
  "api-contracts" to listOf(listOf("#[serde(rename)]", "payload incompatibility"), listOf("extern \"C\"", "double frees")),
  "architecture" to listOf(listOf("[features]", "compile_error!"), listOf("Arc<dyn Trait + Send + Sync>", "concurrency contract")),
  "performance" to listOf(listOf("spawn_blocking", "shutdown"), listOf("mpsc::channel", "backpressure")),
  "persistence" to listOf(listOf("rollback", "invalid state"), listOf("bounded `LIMIT` batches", "restart data loss")),
  "platform-correctness" to listOf(listOf("unsafe", "provenance", "undefined-behavior"), listOf(".await", "deadlock")),
  "reliability" to listOf(listOf("CancellationToken", "dependency order"), listOf("idempotency key", "duplicates")),
  "security" to listOf(listOf("Command::arg", "injection"), listOf("Path::canonicalize", "traversal")),
  "testing" to listOf(listOf("cargo miri test", "memory-safety"), listOf("loom::model", "concurrency race")),
  "ui" to listOf(listOf("hydrate", "duplicate events"), listOf("ratatui", "corrupted terminal")),
  "ux-accessibility" to listOf(listOf("KeyCode", "pointer-only"), listOf("UnicodeWidthStr", "cursor placement")),
)
