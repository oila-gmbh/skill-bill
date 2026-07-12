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

class TypeScriptPlatformPackTest {
  @Test
  fun `typescript platform pack declares expected manifest and routing contract`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/typescript")
    val pack = loadPlatformPack(packRoot)

    assertEquals("typescript", pack.slug)
    assertEquals("TypeScript", pack.displayName)
    assertEquals("1.2", pack.contractVersion)
    assertEquals(packRoot.resolve("code-review/bill-typescript-code-review/content.md"), pack.declaredFiles.baseline)
    assertEquals(packRoot.resolve("quality-check/bill-typescript-code-check/content.md"), pack.declaredQualityCheckFile)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.declaredFiles.areas.keys)
    assertEquals(APPROVED_CODE_REVIEW_AREAS, pack.areaMetadata.keys)
    listOf(
      "tsconfig.json",
      "tsconfig.*.json",
      ".ts",
      "*.ts",
      ".tsx",
      "*.tsx",
      ".mts",
      "*.mts",
      ".cts",
      "*.cts",
    ).forEach { marker -> assertContains(pack.routingSignals.strong, marker) }
    listOf(
      "package.json",
      "package-lock.json",
      "yarn.lock",
      "pnpm-lock.yaml",
      "bun.lockb",
      "biome.json",
      "eslint.config.*",
      ".eslintrc*",
      "prettier.config.*",
      ".prettierrc*",
    ).forEach { marker -> assertFalse(pack.routingSignals.strong.contains(marker)) }
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("individually or combined") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("without TypeScript ownership") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("generated API clients") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("ambient *.d.ts") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("node_modules/") })
    assertTrue(pack.routingSignals.tieBreakers.any { it.contains("generated declaration") })
  }

  @Test
  fun `javascript only metadata is not strong typescript routing evidence`() {
    val pack = loadPlatformPack(repoRootFromTest().resolve("platform-packs/typescript"))
    val javascriptOnlyMarkers = listOf(
      "package.json",
      "package-lock.json",
      "yarn.lock",
      "pnpm-lock.yaml",
      "bun.lockb",
      "biome.json",
      "eslint.config.*",
      ".eslintrc*",
      "prettier.config.*",
      ".prettierrc*",
    )

    assertTrue(pack.routingSignals.strong.none(javascriptOnlyMarkers::contains))
    val ownershipBoundary = pack.routingSignals.tieBreakers.single { it.contains("contextual metadata only") }
    listOf(
      "package.json",
      "package-lock.json",
      "yarn.lock",
      "pnpm-lock.yaml",
      "bun.lockb",
      "biome.json",
      "ESLint configuration",
      "Prettier configuration",
    ).forEach { marker -> assertContains(ownershipBoundary, marker) }
    assertContains(ownershipBoundary, "individually or combined")
    assertContains(ownershipBoundary, "without TypeScript ownership")
  }

  @Test
  fun `typescript review quality and native agent sources are complete`() {
    val repoRoot = repoRootFromTest()
    val packRoot = repoRoot.resolve("platform-packs/typescript")
    val contentFiles = Files.walk(packRoot).use { stream ->
      stream.filter { path -> path.fileName.toString() == "content.md" }.sorted().toList()
    }
    assertEquals(12, contentFiles.size)
    contentFiles.forEach { contentFile ->
      val text = Files.readString(contentFile)
      assertContains(text, "internal-for:")
      assertFalse(text.contains("TODO"), "TypeScript pack source must not contain TODO placeholders: $contentFile")
    }

    assertTypeScriptReviewContentContract(packRoot)
    assertTypeScriptQualityCheckContentContract(packRoot)

    val bundlePath = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    assertContains(Files.readString(bundlePath), "contract_version: \"0.1\"")
    val agents = parseNativeAgentBundle(bundlePath)
    val expectedNames = APPROVED_CODE_REVIEW_AREAS.map { "bill-typescript-code-review-$it" }.toSet()
    assertEquals(expectedNames, agents.map { it.name }.toSet())
    agents.forEach { agent ->
      val composed = composeNativeAgentSource(repoRoot, agent)
      assertTrue(composed.body.isNotBlank(), "Expected governed content for ${agent.name}")
    }
  }

  @Test
  fun `typescript specialists expose runtime specific coverage synchronized with metadata and agents`() {
    val packRoot = repoRootFromTest().resolve("platform-packs/typescript")
    val pack = loadPlatformPack(packRoot)
    val rulesByArea = pack.declaredFiles.areas.mapValues { (_, path) -> governedRules(Files.readString(path)) }

    assertEquals(TYPESCRIPT_EXPECTED_FOCUS, pack.areaMetadata)

    TYPESCRIPT_EXPECTED_MARKERS.forEach { (area, markers) ->
      val rules = rulesByArea.getValue(area).joinToString("\n")
      markers.forEach { marker -> assertContains(rules, marker, ignoreCase = true) }
    }
    TYPESCRIPT_EXPECTED_RULE_CONTRACTS.forEach { (area, contracts) ->
      contracts.forEach { markers ->
        assertTrue(
          rulesByArea.getValue(area).any { rule -> markers.all { marker -> rule.contains(marker, ignoreCase = true) } },
          "TypeScript $area must keep coupled rule contract: ${markers.joinToString()}",
        )
      }
    }
    rulesByArea.forEach { (area, rules) ->
      assertFalse(
        rules.any { rule -> Regex("`TypeScript [^`]* APIs`").containsMatchIn(rule) },
        "TypeScript $area must name concrete evidence instead of a generic TypeScript APIs phrase",
      )
    }

    val agents = parseNativeAgentBundle(
      packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml"),
    ).associateBy { agent -> agent.name.removePrefix("bill-typescript-code-review-") }
    assertEquals(APPROVED_CODE_REVIEW_AREAS, agents.keys)
    agents.forEach { (area, agent) ->
      assertEquals(
        "TypeScript ${area.replace('-', ' ')} specialist code reviewer. " +
          "Runs against ${TYPESCRIPT_EXPECTED_FOCUS.getValue(area)}. " +
          "Returns a Risk Register in the F-XXX bullet format.",
        agent.description,
      )
    }
  }

  @Test
  fun `typescript specialists and checker pass completed pack substance and duplication gates`() {
    val report = PlatformPackSubstanceAudit.audit(repoRootFromTest())
    val typescript = report.packs.single { pack -> pack.pack == "typescript" }

    assertEquals(APPROVED_CODE_REVIEW_AREAS, typescript.physicalAreas.toSet())
    typescript.specialists.filterNot { specialist -> specialist.inherited }.forEach { specialist ->
      assertTrue(specialist.substantiveRules >= 10, "Thin TypeScript specialist: ${specialist.area}")
      assertEquals(3, specialist.failureModeClusters, "Missing failure cluster: ${specialist.area}")
      assertTrue(specialist.concreteEvidenceRules >= 10, "Missing evidence: ${specialist.area}")
      assertTrue(specialist.placeholders.isEmpty(), "Placeholder in ${specialist.area}")
    }
    assertEquals(7, typescript.qualityCheckFacets.size)
    assertTrue(typescript.sharedShingles <= Fraction(35, 100), typescript.sharedShingles.percentage())

    val typescriptPairs = report.pairs.filter { pair ->
      pair.firstFile.startsWith("platform-packs/typescript/") ||
        pair.secondFile.startsWith("platform-packs/typescript/")
    }
    assertTrue(typescriptPairs.isNotEmpty())
    typescriptPairs.forEach { pair ->
      assertTrue(pair.similarity <= Fraction(65, 100), "${pair.role}: ${pair.similarity.percentage()}")
    }
    val typescriptRustRoles = typescriptPairs.filter { pair ->
      pair.firstFile.startsWith("platform-packs/rust/") || pair.secondFile.startsWith("platform-packs/rust/")
    }.map { pair -> pair.role }.toSet()
    assertEquals(
      setOf("baseline", "quality_check") + APPROVED_CODE_REVIEW_AREAS.map { area -> "specialist:$area" },
      typescriptRustRoles,
    )
    assertFalse(report.violations.any { violation -> violation.pack == "typescript" })
  }

  @Test
  fun `typescript quality checker preserves discovered ordered repository execution`() {
    val content = Files.readString(
      repoRootFromTest().resolve("platform-packs/typescript/quality-check/bill-typescript-code-check/content.md"),
    )
    listOf(
      "repository build file, wrapper, and CI configuration",
      "formatting verification",
      "configured linting",
      "Run typechecking",
      "repository build after typechecking",
      "unit tests, then integration, contract, browser, end-to-end, and worker tests",
      "affected publishable package",
      "application-only packages",
      "*.d.ts",
      "dependency, lockfile, license, provenance, and security checks",
      "moduleResolution",
      "environmental blocker",
      "Re-run the smallest failing command",
      "full suite when targeted checks cannot establish safety",
    ).forEach { marker -> assertContains(content, marker) }
  }

  @Test
  fun `typescript pack loader preserves partial extension bundles`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-malformed-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        Regex("(?ms)  - name: bill-typescript-code-review-security\\n.*?(?=  - name:|\\z)"),
        "",
      ),
    )

    assertEquals("typescript", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `typescript pack rejects renamed governed content agents`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-all-agents-renamed-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    val canonicalNames = APPROVED_CODE_REVIEW_AREAS.map { "bill-typescript-code-review-$it" } +
      "bill-typescript-code-review"
    val renamedBundle = canonicalNames.fold(Files.readString(bundle)) { content, name ->
      content.replace("name: $name\n", "name: renamed-$name\n")
    }
    val reducedRenamedBundle = listOf(
      "renamed-bill-typescript-code-review",
      "renamed-bill-typescript-code-review-security",
    ).fold(renamedBundle) { content, name ->
      content.replace(Regex("(?ms)  - name: $name\\n.*?(?=  - name:|\\z)"), "")
    }
    Files.writeString(bundle, reducedRenamedBundle)

    val error = assertFailsWith<InvalidManifestSchemaError> { loadPlatformPack(packRoot) }
    assertContains(error.message.orEmpty(), "unknown=[renamed-bill-typescript-code-review-api-contracts")
    assertContains(error.message.orEmpty(), "renamed-bill-typescript-code-review")
  }

  @Test
  fun `typescript pack accepts custom body based native agent`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-unknown-body-agent-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle) +
        "  - name: undeclared-typescript-reviewer\n" +
        "    description: \"Custom review agent.\"\n" +
        "    body: |-\n" +
        "      Review the diff.\n",
    )

    assertEquals("typescript", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `typescript pack loader accepts a specialist-only extension bundle`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-missing-baseline-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        Regex("(?ms)  - name: bill-typescript-code-review\\n.*?(?=  - name:|\\z)"),
        "",
      ),
    )

    assertEquals("typescript", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `typescript pack reports malformed native agent yaml as typed contract failure`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-malformed-agent-yaml-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(bundle, "agents:\n  - name: [unterminated\n")

    val error = assertFailsWith<InvalidNativeAgentCompositionSchemaError> { loadPlatformPack(packRoot) }
    assertContains(error.sourceLabel, bundle.toString())
    assertContains(error.reason, "could not parse YAML")
  }

  @Test
  fun `typescript pack accepts a custom baseline native agent description`() {
    val repoRoot = repoRootFromTest()
    val tempRoot = Files.createTempDirectory("skillbill-typescript-pack-custom-baseline-description-")
    val packRoot = tempRoot.resolve("typescript")
    copyDirectory(repoRoot.resolve("platform-packs/typescript"), packRoot)
    val bundle = packRoot.resolve("code-review/bill-typescript-code-review/native-agents/agents.yaml")
    Files.writeString(
      bundle,
      Files.readString(bundle).replace(
        "TypeScript baseline code reviewer. Reviews the full owned diff before specialist findings are merged. " +
          "Returns an F-XXX Risk Register.",
        "Team-owned TypeScript baseline reviewer.",
      ),
    )

    assertEquals("typescript", loadPlatformPack(packRoot).slug)
  }

  @Test
  fun `install apply stages selected typescript pack skills and native agents`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-typescript-install-plan-home-")
    val plan = typescriptInstallPlan(repoRoot, home)

    val skillsByName = plan.skills.associateBy { it.name }
    assertContains(plan.discoveredPlatformPacks.map { it.slug }, "typescript")
    assertEquals(listOf("typescript"), plan.selectedPlatformSlugs)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-typescript-code-review").kind)
    assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-typescript-code-check").kind)
    APPROVED_CODE_REVIEW_AREAS.forEach { area ->
      assertEquals(InstallPlanSkillKind.PLATFORM_PACK, skillsByName.getValue("bill-typescript-code-review-$area").kind)
    }
    assertFalse(skillsByName.containsKey("bill-go-code-review"))
    assertFalse(skillsByName.containsKey("bill-python-code-review"))

    assertTypeScriptInstallApplied(plan)
  }

  private fun typescriptInstallPlan(repoRoot: Path, home: Path): InstallPlan {
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
          selectedSlugs = setOf("typescript"),
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

  private fun assertTypeScriptInstallApplied(plan: InstallPlan) {
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
      "bill-typescript-code-review-$area.md"
    }.toSet() + "bill-typescript-code-review.md"
    assertTrue(reviewSidecars.containsAll(expectedReviewSidecars))
    val checkSidecars = result.skills
      .single { skill -> skill.skillName == "bill-code-check" }
      .staging
      .renderedSidecarFiles
      .map { path -> path.fileName.toString() }
      .toSet()
    assertContains(checkSidecars, "bill-typescript-code-check.md")
    val linkedNativeAgents = result.nativeAgents
      .filter { nativeAgent -> nativeAgent.status == NativeAgentApplyStatus.LINKED }
      .mapNotNull { nativeAgent -> nativeAgent.path?.fileName?.toString() }
      .toSet()
    APPROVED_CODE_REVIEW_AREAS.map { area -> "bill-typescript-code-review-$area" }.forEach { agentName ->
      assertContains(linkedNativeAgents, "$agentName.toml")
    }
  }

  private fun assertTypeScriptReviewContentContract(packRoot: Path) {
    val baseline = Files.readString(packRoot.resolve("code-review/bill-typescript-code-review/content.md"))
    listOf(
      "Select at least two and at most ten specialists",
      "## Diff-Signal Routing Table",
      "## Mixed Diffs",
      "node_modules/",
      "Ambient `*.d.ts` declarations",
    ).forEach { required -> assertContains(baseline, required) }

    val specialistRequirements = mapOf(
      "bill-typescript-code-review-platform-correctness" to listOf(
        "External values must enter as `unknown`",
        "`any`, unchecked casts, non-null assertions",
        "Node, Deno, Bun, browser, worker, or edge",
      ),
      "bill-typescript-code-review-reliability" to listOf(
        "unawaited or floating promises",
        "`AbortSignal`",
        "bounded admission",
      ),
      "bill-typescript-code-review-api-contracts" to listOf(
        "never treat a TypeScript annotation as runtime validation",
        "optional, nullable, defaulted",
      ),
    )
    specialistRequirements.forEach { (skillName, requirements) ->
      val content = Files.readString(packRoot.resolve("code-review/$skillName/content.md"))
      requirements.forEach { required -> assertContains(content, required) }
    }
  }

  private fun assertTypeScriptQualityCheckContentContract(packRoot: Path) {
    val qualityCheck = Files.readString(
      packRoot.resolve("quality-check/bill-typescript-code-check/content.md"),
    )
    listOf(
      "tsc --noEmit",
      "ESLint",
      "Biome",
      "Prettier",
      "Vitest",
      "Jest",
      "npm",
      "yarn",
      "pnpm",
      "bun",
      "turbo",
      "nx",
    ).forEach { required -> assertContains(qualityCheck, required) }
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
          governed = true
          null
        }
        line.startsWith("## ") -> {
          governed = false
          null
        }
        governed && line.startsWith("- ") -> line.removePrefix("- ")
        else -> null
      }
    }.toList()
  }

  companion object {
    private val TYPESCRIPT_EXPECTED_MARKERS = mapOf(
      "api-contracts" to listOf("`unknown`", "version-skew", "idempotency-key"),
      "architecture" to listOf("project references", "conditional exports", "service-worker"),
      "performance" to listOf("event loop", "`Promise.all`", "`ReadableStream`", "hydration"),
      "persistence" to listOf("`PrismaClient`", "transaction", "mixed application versions"),
      "platform-correctness" to listOf("Generic constraints", "declaration drift", "ESM, CommonJS"),
      "reliability" to listOf("floating promises", "`AbortSignal`", "`SIGTERM`", "queue saturation"),
      "security" to listOf("CSRF-token", "prototype pollution", "lifecycle scripts"),
      "testing" to listOf("`tsd`", "Playwright", "ESM and CommonJS", "controlled promises", "regression fixture"),
      "ui" to listOf("TSX alone", "React, Vue, Svelte, Solid, Angular, Lit", "hydration"),
      "ux-accessibility" to listOf("`aria-labelledby`", "`aria-live`", "`dir=\"rtl\"`"),
    )

    private val TYPESCRIPT_EXPECTED_RULE_CONTRACTS = mapOf(
      "api-contracts" to listOf(
        listOf("runtime validation", "erased types"),
        listOf("built-tarball export matrix", "ESM, CommonJS, browser"),
        listOf("Event and message", "version-skew"),
      ),
      "architecture" to listOf(
        listOf("server, browser", "invalid runtime"),
        listOf("conditional exports", "ESM, CommonJS, browser, and worker"),
        listOf("project-reference", "changed producer", "consumers"),
      ),
      "performance" to listOf(
        listOf("event loop", "latency starvation"),
        listOf("`Promise.all`", "unbounded fan-out"),
        listOf("`ReadableStream`", "backpressure"),
        listOf("hydration", "browser"),
      ),
      "persistence" to listOf(
        listOf("transaction", "external calls", "idempotent"),
        listOf("migration", "mixed application versions"),
        listOf("`PrismaClient`", "stale client"),
        listOf("clients", "worker lifecycle"),
      ),
      "platform-correctness" to listOf(
        listOf("packed-package consumer matrix", "ESM, CommonJS"),
        listOf("production bundle output", "tree-shaking"),
        listOf("`any`, unchecked casts", "erased type boundary"),
      ),
      "reliability" to listOf(
        listOf("Retries", "idempotency-key", "duplicate"),
        listOf("`AbortSignal`", "stale work"),
        listOf("bounded admission", "downstream capacity"),
        listOf("telemetry", "queue saturation"),
      ),
      "security" to listOf(
        listOf("Browser Web Workers and Service Workers", "untrusted", "server-side"),
        listOf("browser bundles", "credential exposure"),
        listOf("lifecycle scripts", "executable code"),
      ),
      "testing" to listOf(
        listOf("Compile-time assertions", "runtime validation tests"),
        listOf("ESM and CommonJS", "built tarballs"),
        listOf("Playwright", "hydration"),
      ),
      "ui" to listOf(
        listOf("TSX alone", "proof of React"),
        listOf("Effects", "subscriptions", "cleanup"),
        listOf("hydration", "server-rendered"),
      ),
      "ux-accessibility" to listOf(
        listOf("WAI-ARIA widget", "arrow navigation", "Escape"),
        listOf("Focus", "portal teardown"),
        listOf("`aria-live`", "async feedback"),
        listOf("localization keys", "plural rules"),
      ),
    )

    private val TYPESCRIPT_EXPECTED_FOCUS = mapOf(
      "api-contracts" to
        "erased TypeScript declarations, runtime schemas, JavaScript consumers, HTTP/RPC/events, serialization, " +
        "idempotency, and version skew",
      "architecture" to
        "TypeScript workspaces, project references, package exports, build graphs, dependency direction, " +
        "runtime partitions, and lifecycle ownership",
      "performance" to
        "Node/browser event loops, promise fan-out, streams and backpressure, allocation, bundles, rendering, " +
        "hydration, and retained resources",
      "persistence" to
        "TypeScript ORM/query clients, transaction isolation and retries, migrations, mixed versions, " +
        "connection lifecycles, and durable serialization",
      "platform-correctness" to
        "TypeScript narrowing, generics and escape hatches, declaration/emission parity, ESM/CommonJS, " +
        "bundlers, targets, and runtime availability",
      "reliability" to
        "observed promises, causal errors, AbortSignal cancellation, bounded queues, retries, stream cleanup, " +
        "process shutdown, and telemetry",
      "security" to
        "browser/server identity boundaries, runtime validation, XSS/CSRF and injection sinks, client secrets, " +
        "lockfiles, build plugins, and supply chain",
      "testing" to
        "TypeScript type assertions, behavioral unit/integration/contract/browser/worker tests, package entry " +
        "points, module matrices, async races, and regression proof",
      "ui" to
        "detected DOM/TSX frameworks, state and effects, typed events and forms, async races, rendering, " +
        "routing, hydration, cleanup, and recovery",
      "ux-accessibility" to
        "framework-rendered semantics, accessible names, focus and keyboard behavior, live feedback, " +
        "localization, direction, zoom, motion, and assistive technology",
    )
  }
}
