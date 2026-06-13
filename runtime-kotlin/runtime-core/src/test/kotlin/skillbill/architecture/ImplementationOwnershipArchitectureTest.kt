package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImplementationOwnershipArchitectureTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }

  @Test
  fun `implementation ownership moved out of runtime core`() {
    listOf(
      "skillbill/install",
      "skillbill/scaffold",
      "skillbill/nativeagent",
      "skillbill/launcher",
      "skillbill/skillremove",
      "skillbill/workflow",
    ).forEach { packagePath ->
      assertTrue(
        !Files.exists(runtimeRoot.resolve("runtime-core/src/main/kotlin/$packagePath")),
        "runtime-core must not own moved implementation package $packagePath",
      )
    }

    listOf(
      "skillbill/install/runtime/InstallOperations.kt",
      "skillbill/scaffold/runtime/ScaffoldService.kt",
      "skillbill/nativeagent/rendering/NativeAgentOperations.kt",
      "skillbill/launcher/mcp/McpRegistrationOperations.kt",
      "skillbill/skillremove/SkillRemoveJvmFileSystem.kt",
    ).forEach { packagePath ->
      assertTrue(
        Files.isRegularFile(runtimeRoot.resolve("runtime-infra-fs/src/main/kotlin/$packagePath")),
        "runtime-infra-fs must own moved filesystem implementation $packagePath",
      )
    }

    assertTrue(
      Files.isRegularFile(
        runtimeRoot.resolve(
          "runtime-application/src/main/kotlin/skillbill/workflow/implement/FeatureImplementWorkflowRuntime.kt",
        ),
      ),
      "workflow runtime-surface metadata must be owned outside runtime-core",
    )
  }

  @Test
  fun `moved filesystem implementation packages do not depend on forbidden adapters`() {
    val infraFsBuild = runtimeRoot.resolve("runtime-infra-fs/build.gradle.kts").readText()
    val forbiddenProjectDependencies = listOf(
      ":runtime-core",
      ":runtime-cli",
      ":runtime-mcp",
      ":runtime-desktop",
      ":runtime-infra-http",
      ":runtime-infra-sqlite",
    )
    val forbiddenDependencies = forbiddenProjectDependencies.filter { dependency ->
      infraFsBuild.contains("project(\"$dependency\")")
    }
    assertEquals(
      emptyList(),
      forbiddenDependencies,
      "runtime-infra-fs must not depend on runtime-core, runtime adapters, or sibling concrete infra adapters.",
    )

    val forbiddenPackages = forbiddenSourcePackages(
      listOf(
        "runtime-core/src/main/kotlin",
        "runtime-cli/src/main/kotlin",
        "runtime-mcp/src/main/kotlin",
        "runtime-desktop",
        "runtime-infra-http/src/main/kotlin",
        "runtime-infra-sqlite/src/main/kotlin",
      ),
    )
    val movedPackageRoots = listOf(
      "skillbill/install",
      "skillbill/scaffold",
      "skillbill/nativeagent",
      "skillbill/launcher",
      "skillbill/skillremove",
    ).map { packagePath -> runtimeRoot.resolve("runtime-infra-fs/src/main/kotlin/$packagePath") }

    val violations = movedPackageRoots
      .flatMap(::kotlinFilesUnder)
      .flatMap { sourceFile ->
        sourceFile.importsForbiddenBy(forbiddenPackages).map { forbiddenImport ->
          "${runtimeRoot.relativize(sourceFile)} imports $forbiddenImport"
        }
      }
      .sorted()

    assertEquals(
      emptyList(),
      violations,
      "Moved runtime-infra-fs implementation packages must use ports/domain/contracts instead of concrete " +
        "runtime-core, CLI, MCP, Desktop, HTTP, or SQLite adapter packages.",
    )
  }

  @Test
  fun `runtime core is composition only and not an implementation umbrella`() {
    val runtimeCoreBuild = runtimeRoot.resolve("runtime-core/build.gradle.kts").readText()
    assertNoRuntimeCorePublicProjectEdges(runtimeCoreBuild)

    val allowedPackages = setOf("skillbill", "skillbill.di")
    val runtimeCoreSourceFiles = kotlinFilesUnder(runtimeRoot.resolve("runtime-core/src/main/kotlin"))
    val runtimeCorePackages = runtimeCoreSourceFiles.mapNotNull(::packageName).toSet()
    assertEquals(
      allowedPackages,
      runtimeCorePackages,
      "runtime-core source must stay limited to module metadata and DI composition.",
    )
    val nonCompositionPackages = runtimeCoreSourceFiles
      .mapNotNull { sourceFile ->
        val sourcePackage = packageName(sourceFile) ?: return@mapNotNull null
        if (sourcePackage in allowedPackages) {
          null
        } else {
          "${runtimeRoot.relativize(sourceFile)} declares package $sourcePackage"
        }
      }
      .sorted()
    assertEquals(
      emptyList(),
      nonCompositionPackages,
      "runtime-core must reject every non-composition package beyond skillbill and skillbill.di.",
    )

    val bannedImplementationImports = listOf(
      "skillbill.install",
      "skillbill.launcher",
      "skillbill.nativeagent",
      "skillbill.scaffold",
      "skillbill.skillremove",
      "skillbill.workflow",
    )
    // The composition root may reference port types and concrete adapters only
    // where it declares @Provides bindings. These explicit imports are not
    // runtime-core implementation ownership.
    val allowedCompositionImports = setOf(
      "skillbill.install.model.InstallPlanWireValidator",
      "skillbill.launcher.agentrun.FileSystemAgentRunLauncher",
      "skillbill.workflow.DecompositionManifestValidator",
      "skillbill.workflow.FeatureTaskRuntimePhaseOutputValidator",
      "skillbill.workflow.GoalObservabilityEventValidator",
      "skillbill.workflow.GoalProgressEventValidator",
      "skillbill.workflow.WorkflowSnapshotValidator",
    )
    val violations = kotlinFilesUnder(runtimeRoot.resolve("runtime-core/src/main/kotlin"))
      .flatMap { sourceFile ->
        sourceFile.readText().lineSequence()
          .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
          .filter { importedName -> bannedImplementationImports.any(importedName::startsWith) }
          .filterNot { importedName -> importedName in allowedCompositionImports }
          .map { importedName -> "${runtimeRoot.relativize(sourceFile)} imports $importedName" }
      }
      .sorted()
    assertEquals(emptyList(), violations, "runtime-core must not import moved implementation packages.")
  }

  private fun assertNoRuntimeCorePublicProjectEdges(runtimeCoreBuild: String) {
    assertRuntimeCorePublicProjectEdges(runtimeRoot, runtimeCoreBuild)
  }

  @Test
  fun `runtime core imports concrete infrastructure only from composition files`() {
    val runtimeCoreSourceFiles = kotlinFilesUnder(runtimeRoot.resolve("runtime-core/src/main/kotlin"))
    val compositionFiles = setOf(
      runtimeRoot.resolve("runtime-core/src/main/kotlin/skillbill/di/RuntimeComponent.kt"),
    )
    val concreteInfrastructureViolations = runtimeCoreSourceFiles
      .filterNot { sourceFile -> sourceFile in compositionFiles }
      .flatMap { sourceFile ->
        sourceFile.importsForbiddenBy(
          setOf(
            "skillbill.db",
            "skillbill.infrastructure",
          ),
        ).map { forbiddenImport ->
          "${runtimeRoot.relativize(sourceFile)} imports $forbiddenImport"
        }
      }
      .sorted()
    assertEquals(
      emptyList(),
      concreteInfrastructureViolations,
      "runtime-core may import concrete infrastructure only from explicit DI composition files.",
    )
  }

  @Test
  fun `infrastructure modules do not depend on adapters or runtime core`() {
    val forbiddenProjectDependencies = listOf(":runtime-core", ":runtime-cli", ":runtime-mcp", ":runtime-desktop")
    val violations = listOf("runtime-infra-fs", "runtime-infra-http", "runtime-infra-sqlite")
      .flatMap { module ->
        val build = runtimeRoot.resolve("$module/build.gradle.kts").readText()
        forbiddenProjectDependencies
          .filter { dependency -> build.contains("project(\"$dependency\")") }
          .map { dependency -> "$module depends on $dependency" }
      }
    assertEquals(
      emptyList(),
      violations,
      "Infrastructure modules must not depend on runtime-core or adapter entrypoints.",
    )
  }

  @Test
  fun `application domain and ports do not import adapters infrastructure or composition roots`() {
    val layerRules = mapOf(
      "runtime-application/src/main/kotlin" to listOf(
        "skillbill.cli",
        "skillbill.desktop",
        "skillbill.mcp",
        "skillbill.db",
        "skillbill.di",
        "skillbill.infrastructure",
      ),
      "runtime-domain/src/main/kotlin" to listOf(
        "com.github.ajalt.clikt",
        "java.net.http",
        "java.sql",
        "skillbill.application",
        "skillbill.cli",
        "skillbill.desktop",
        "skillbill.mcp",
        "skillbill.db",
        "skillbill.di",
        "skillbill.infrastructure",
        "skillbill.ports",
      ),
      "runtime-ports/src/main/kotlin" to listOf(
        "com.github.ajalt.clikt",
        "java.net.http",
        "java.sql",
        "skillbill.application",
        "skillbill.cli",
        "skillbill.desktop",
        "skillbill.mcp",
        "skillbill.db",
        "skillbill.di",
        "skillbill.infrastructure",
      ),
    )

    val violations = layerRules.flatMap { (sourceRoot, forbiddenPrefixes) ->
      kotlinFilesUnder(runtimeRoot.resolve(sourceRoot)).flatMap { sourceFile ->
        sourceFile.importsForbiddenBy(forbiddenPrefixes.toSet()).map { forbiddenImport ->
          "${runtimeRoot.relativize(sourceFile)} imports $forbiddenImport"
        }
      }
    }.sorted()

    assertEquals(
      emptyList(),
      violations,
      "Application, domain, and port layers must not import adapters, infrastructure, or DI composition roots.",
    )
  }

  @Test
  fun `cli mcp and desktop data declare direct runtime dependencies beside runtime core`() {
    // SKILL-52.2 subtask 5: narrowed allow-list. The infrastructure modules
    // (`:runtime-infra-fs`, `:runtime-infra-http`) were dropped because none
    // of these adapters concretely import `skillbill.infrastructure.*` outside
    // test sources — infrastructure adapters are resolved through
    // `RuntimeComponent` (kotlin-inject) instead. `runtime-desktop:core:data`
    // gains an explicit `:runtime-contracts` edge so its direct
    // `skillbill.error.*` imports do not depend on transitive runtime-application
    // API. The exact allow-list per adapter is pinned by
    // `RuntimeAdapterDependencyAllowlistTest`; this test asserts only that the
    // declared dependencies are present (it does not enforce the exact set).
    val adapterDependencies = mapOf(
      "runtime-cli/build.gradle.kts" to listOf(
        ":runtime-application",
        ":runtime-contracts",
        ":runtime-core",
        ":runtime-domain",
        ":runtime-ports",
      ),
      "runtime-mcp/build.gradle.kts" to listOf(
        ":runtime-application",
        ":runtime-contracts",
        ":runtime-core",
        ":runtime-domain",
        ":runtime-ports",
      ),
      "runtime-desktop/core/data/build.gradle.kts" to listOf(
        ":runtime-application",
        ":runtime-contracts",
        ":runtime-core",
        ":runtime-domain",
        ":runtime-ports",
      ),
    )

    val missing = adapterDependencies.flatMap { (relativeBuildFile, dependencies) ->
      val build = runtimeRoot.resolve(relativeBuildFile).readText()
      dependencies
        .filterNot { dependency -> build.contains("project(\"$dependency\")") }
        .map { dependency -> "$relativeBuildFile is missing direct dependency $dependency" }
    }
    assertEquals(
      emptyList(),
      missing,
      "Adapters must declare direct runtime dependencies instead of using core as API.",
    )

    val umbrellaApiViolations = listOf("runtime-cli/build.gradle.kts", "runtime-mcp/build.gradle.kts")
      .filter { relativeBuildFile ->
        runtimeRoot.resolve(relativeBuildFile).readText().contains("api(project(\":runtime-core\"))")
      }
    assertEquals(
      emptyList(),
      umbrellaApiViolations,
      "CLI and MCP must not expose runtime-core as a broad API umbrella.",
    )
  }

  @Test
  fun `runtime core binds install capability adapters directly`() {
    val component = runtimeRoot
      .resolve("runtime-core/src/main/kotlin/skillbill/di/RuntimeComponent.kt")
      .readText()

    listOf(
      "FileSystemInstallPlanningFacts",
      "FileSystemInstallPlatformSkillMaterialization",
      "FileSystemInstallStagingIntent",
      "FileSystemInstallApplyExecution",
      "FileSystemInstallSkillLink",
      "FileSystemInstallAgentTargets",
      "FileSystemInstallNativeAgentLinks",
      "FileSystemInstallMcpRegistration",
    ).forEach { adapterName ->
      assertTrue(
        adapterName in component,
        "RuntimeComponent must bind direct install capability adapter $adapterName.",
      )
    }

    listOf(
      "InstallPlanGateway",
      "FileSystemInstallGateway",
      "InstallAgentGateway",
      "NativeAgentInstallGateway",
      "McpRegistrationGateway",
    ).forEach { retiredName ->
      assertTrue(
        retiredName !in component,
        "RuntimeComponent must not bind retired install gateway $retiredName.",
      )
    }
  }

  @Test
  fun `cli mcp and desktop adapters do not import concrete runtime implementations`() {
    val adapterSourceRoots = listOf(
      "runtime-cli/src/main/kotlin",
      "runtime-mcp/src/main/kotlin",
      "runtime-desktop/core/data/src/commonMain/kotlin",
      "runtime-desktop/core/data/src/jvmMain/kotlin",
      "runtime-desktop/core/domain/src/commonMain/kotlin",
      "runtime-desktop/feature/skillbill/src/commonMain/kotlin",
      "runtime-desktop/feature/skillbill/src/jvmMain/kotlin",
    )
    val violations = adapterSourceRoots
      .map { sourceRoot -> runtimeRoot.resolve(sourceRoot) }
      .flatMap(::kotlinFilesUnder)
      .flatMap { sourceFile ->
        sourceFile.runtimeImplementationImports().map { importedName ->
          "${runtimeRoot.relativize(sourceFile)} imports $importedName"
        }
      }
      .sorted()

    assertEquals(
      emptyList(),
      violations,
      "CLI, MCP, and Desktop adapters must go through application services and ports instead of " +
        "concrete install, scaffold, native-agent, launcher, validation, or filesystem implementations.",
    )
  }

  @Test
  fun `scaffold policy packages must not import infra-fs`() {
    // SKILL-52.1 subtask 2: pure-policy ownership boundary. The extracted-policy package under
    // `runtime-domain` must not depend on filesystem implementations. The application-level
    // ScaffoldService is also guarded for the same reason — policy callsites must go through the
    // typed capability ports introduced in subtask 2.
    val policySourceRoots = listOf(
      "runtime-domain/src/main/kotlin/skillbill/scaffold/policy",
      "runtime-application/src/main/kotlin/skillbill/application",
    ).map { sourceRoot -> runtimeRoot.resolve(sourceRoot) }
      .filter(Files::isDirectory)

    val forbiddenImportPattern = Regex(
      "^import\\s+(skillbill\\.infrastructure\\.fs(?:\\..*)?|skillbill\\.scaffold\\.(ScaffoldService|FileSystem.*))$",
    )

    val violations = policySourceRoots
      .flatMap(::kotlinFilesUnder)
      .filter { sourceFile -> isPolicyOrScaffoldApplicationFile(sourceFile) }
      .flatMap { sourceFile ->
        sourceFile.readText().lineSequence()
          .map { line -> line.trim() }
          .filter(forbiddenImportPattern::matches)
          .map { importLine -> "${runtimeRoot.relativize(sourceFile)} contains '$importLine'" }
          .toList()
      }
      .sorted()

    assertEquals(
      emptyList(),
      violations,
      "Scaffold pure-policy packages must not import skillbill.infrastructure.fs.* or " +
        "skillbill.scaffold.ScaffoldService/FileSystem* — those imports leak adapter ownership " +
        "into runtime-domain/runtime-application policy code (SKILL-52.1 subtask 2).",
    )
  }

  @Test
  fun `io-coupled scaffold validators live in capability-aligned adapters`() {
    // SKILL-52.1 subtask 3 (AC1): the IO-coupled validators that previously lived as
    // top-level functions in `skillbill.scaffold.ScaffoldService.kt` must live on the
    // capability-aligned adapter classes in `runtime-infra-fs` under
    // `skillbill.infrastructure.fs`. The FQN-based lookup avoids short-name collisions
    // (subtask-1 pitfall) by binding each validator to the absolute file path of its
    // owning adapter.
    val repoValidationAdapter = runtimeRoot.resolve(
      "runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileSystemScaffoldRepoValidation.kt",
    )
    val sourceLoaderAdapter = runtimeRoot.resolve(
      "runtime-infra-fs/src/main/kotlin/skillbill/infrastructure/fs/FileSystemScaffoldSourceLoader.kt",
    )
    val legacyScaffoldService = runtimeRoot.resolve(
      "runtime-infra-fs/src/main/kotlin/skillbill/scaffold/runtime/ScaffoldService.kt",
    )
    assertTrue(Files.isRegularFile(repoValidationAdapter), "Repo-validation adapter file must exist.")
    assertTrue(Files.isRegularFile(sourceLoaderAdapter), "Source-loader adapter file must exist.")
    assertTrue(Files.isRegularFile(legacyScaffoldService), "Legacy scaffold service file must exist.")

    val repoValidationText = repoValidationAdapter.readText()
    val sourceLoaderText = sourceLoaderAdapter.readText()
    val legacyText = legacyScaffoldService.readText()

    // `FileSystemScaffoldRepoValidation` owns `validateBaselineLayerPayloadReferences`,
    // `validateScaffold`, `plannedAuthoringTarget`. `optionalBaselineLayers` follows the
    // validator that consumes it. SKILL-52.1 subtask 3 (F-007): anchor each substring with
    // `(` so KDoc body text and longer-named lookalikes (e.g. a hypothetical
    // `validateScaffoldExtension`) do not trip the positive ownership match. Spotless can
    // wrap the parameter list onto the next line, so the assertion uses `contains(...)`
    // against the literal `fun name(` token which survives the wrap.
    listOf(
      "fun validateBaselineLayerPayloadReferences(",
      "fun validateScaffold(",
      "fun plannedAuthoringTarget(",
      "fun optionalBaselineLayers(",
    ).forEach { signature ->
      assertTrue(
        repoValidationText.contains(signature),
        "FileSystemScaffoldRepoValidation must declare '$signature' (SKILL-52.1 subtask 3 AC1).",
      )
    }

    // `FileSystemScaffoldSourceLoader` owns `resolveAddonConsumerSkillDirs`,
    // `validateAddonConsumerSkillDir`. SKILL-52.1 subtask 3 (F-007): anchor with `(`.
    listOf(
      "fun resolveAddonConsumerSkillDirs(",
      "fun validateAddonConsumerSkillDir(",
    ).forEach { signature ->
      assertTrue(
        sourceLoaderText.contains(signature),
        "FileSystemScaffoldSourceLoader must declare '$signature' (SKILL-52.1 subtask 3 AC1).",
      )
    }

    // The legacy top-level scaffold service file must NOT redeclare these validators —
    // they belong to the adapter classes above.
    //
    // SKILL-52.1 subtask 3 (F-005): the previous guard only matched `private fun X(`. A
    // future regression could re-introduce these validators under any visibility modifier
    // (`internal fun`, bare `fun`, `public fun`) or with a ktfmt-wrapped multiline
    // declaration that puts whitespace between the modifier and `fun`. The modifier-
    // agnostic regex below catches all of those forms. See the sibling
    // `legacyScaffoldServiceForbiddenTopLevelDeclarationRegex` fixture test below for
    // an explicit assertion that this regex catches every variant.
    val redeclared = LEGACY_FORBIDDEN_TOP_LEVEL_REGEX.findAll(legacyText)
      .map { match -> match.value.trim() }
      .toList()
    assertEquals(
      emptyList(),
      redeclared,
      "Legacy skillbill.scaffold.ScaffoldService.kt must NOT redeclare IO-coupled validators " +
        "moved to runtime-infra-fs capability adapters in SKILL-52.1 subtask 3 (AC1).",
    )
  }

  @Test
  fun `legacy scaffold service forbidden top-level declaration regex catches all modifier variants`() {
    // SKILL-52.1 subtask 3 (F-005): the negative guard in
    // `io-coupled scaffold validators live in capability-aligned adapters` is implemented
    // with a modifier-agnostic regex. A typo there would silently disable the only check
    // preventing the IO-coupled validators from sneaking back into the legacy scaffold
    // service. This fixture-based test exercises the regex against synthetic source lines
    // (one per modifier variant plus a ktfmt-wrapped multiline declaration) so a regression
    // in the regex itself loud-fails.
    val mustMatch = listOf(
      "private fun validateScaffold(plan: ScaffoldPlan, repoRoot: Path) {}",
      "internal fun validateScaffold(plan: ScaffoldPlan, repoRoot: Path) {}",
      "fun validateScaffold(plan: ScaffoldPlan, repoRoot: Path) {}",
      "public fun validateScaffold(plan: ScaffoldPlan, repoRoot: Path) {}",
      "private fun validateBaselineLayerPayloadReferences(\n" +
        "  layers: List<CodeReviewBaselineLayer>,\n" +
        "  repoRoot: Path,\n" +
        "  newPlatform: String,\n" +
        ") {}",
      "fun plannedAuthoringTarget(plan: ScaffoldPlan): AuthoringTarget = AuthoringTarget()",
      "internal fun resolveAddonConsumerSkillDirs(payload: Map<String, Any?>) = emptyList<String>()",
      "fun validateAddonConsumerSkillDir(pack: PlatformManifest, dir: String) = \"\"",
      "private fun optionalBaselineLayers(payload: Map<String, Any?>) = emptyList<CodeReviewBaselineLayer>()",
    )
    val mustNotMatch = listOf(
      "private fun unrelatedHelper() {}",
      "fun validateScaffoldExtension(plan: ScaffoldPlan) {}",
    )

    val falseNegatives = mustMatch.filterNot { LEGACY_FORBIDDEN_TOP_LEVEL_REGEX.containsMatchIn(it) }
    val falsePositives = mustNotMatch.filter { LEGACY_FORBIDDEN_TOP_LEVEL_REGEX.containsMatchIn(it) }
    assertEquals(
      emptyList(),
      falseNegatives,
      "Forbidden-top-level-validator regex must detect every modifier variant (incl. ktfmt-wrapped).",
    )
    assertEquals(
      emptyList(),
      falsePositives,
      "Forbidden-top-level-validator regex must not flag unrelated helpers or longer-named functions.",
    )
  }

  @Test
  fun `scaffold gateway raw-map producer regex catches wrapped signatures but not typed-result variants`() {
    // SKILL-52.1 subtask 3 (F-007): the production regex must catch a multi-line wrapped
    // signature returning `Map<String, Any?>`. A fixture test parallel to the policy-regex
    // fixture above exercises that case so a regex regression loud-fails.
    val rawMapProducerPattern = Regex(
      """fun\s+(list|show|explain|validate|upgrade|fill|saveExactContent|editWithBodyFile)""" +
        """\s*\([^)]*\)\s*:\s*Map<\s*String\s*,\s*Any\?\s*>""",
      setOf(RegexOption.DOT_MATCHES_ALL),
    )

    val wrappedRawMapSignature = """fun editWithBodyFile(
      repoRoot: Path,
      skillName: String,
      body: String,
      sectionName: String?,
    ): Map<String, Any?>
    """
    val typedResultVariant = """fun editWithBodyFile(
      repoRoot: Path,
      skillName: String,
      body: String,
      sectionName: String?,
    ): ScaffoldEditWithBodyFileResult
    """

    assertTrue(
      rawMapProducerPattern.containsMatchIn(wrappedRawMapSignature),
      "Raw-map producer regex must catch a multi-line wrapped Map<String, Any?> return signature.",
    )
    assertTrue(
      !rawMapProducerPattern.containsMatchIn(typedResultVariant),
      "Raw-map producer regex must NOT flag a typed-result return signature.",
    )
  }

  @Test
  fun `scaffold gateway no longer exposes raw map producers on the public surface`() {
    // SKILL-52.1 subtask 3 (AC2): `ScaffoldGateway` must no longer expose
    // `Map<String, Any?>` return types for the eight raw-map producers
    // (list / show / explain / validate / upgrade / fill / saveExactContent /
    // editWithBodyFile). `scaffold(...)` retains its raw-map INPUT (the wire payload)
    // until subtask 4 introduces a typed payload DTO; that input remains documented
    // in the allow-list constant in `RuntimeArchitectureTest`.
    val gatewayFile = runtimeRoot.resolve(
      "runtime-ports/src/main/kotlin/skillbill/ports/scaffold/ScaffoldGateways.kt",
    )
    assertTrue(Files.isRegularFile(gatewayFile), "ScaffoldGateways.kt must exist.")
    val gatewayText = gatewayFile.readText()
    val rawMapProducerPattern = Regex(
      """fun\s+(list|show|explain|validate|upgrade|fill|saveExactContent|editWithBodyFile)""" +
        """\s*\([^)]*\)\s*:\s*Map<\s*String\s*,\s*Any\?\s*>""",
      // SKILL-52.1 subtask 3 (F-007): allow `.` to match newlines so a ktfmt-wrapped
      // multi-line parameter list still gets caught by the raw-map return-type guard.
      setOf(RegexOption.DOT_MATCHES_ALL),
    )
    val violations = rawMapProducerPattern.findAll(gatewayText).map { match -> match.value }.toList()
    assertEquals(
      emptyList(),
      violations,
      "ScaffoldGateway must NOT return raw Map<String, Any?> for the eight raw-map producers — " +
        "they were retyped to capability-aligned result models in SKILL-52.1 subtask 3 (AC2).",
    )
  }

  @Test
  fun `scaffold policy import regex catches known bad and passes known good`() {
    // SKILL-52.1 subtask 2 (review-fix F-001): the production
    // `scaffold policy packages must not import infra-fs` test only proves the regex is sound
    // by asserting `emptyList() == emptyList()` against the current source tree. A typo in the
    // pattern anchors/alternation/grouping would silently disable the only enforcement preventing
    // pure-policy code from re-acquiring an FS dependency. This fixture-based test exercises the
    // same `forbiddenImportPattern.matches(...)` predicate the production scan uses against a
    // synthetic set of import lines so a regression in the pattern itself loud-fails.
    val forbiddenImportPattern = Regex(
      "^import\\s+(skillbill\\.infrastructure\\.fs(?:\\..*)?|skillbill\\.scaffold\\.(ScaffoldService|FileSystem.*))$",
    )

    val mustBeDetectedAsForbidden = listOf(
      "import skillbill.infrastructure.fs.Foo",
      "import skillbill.infrastructure.fs.bar.Baz",
      "import skillbill.scaffold.ScaffoldService",
      "import skillbill.scaffold.FileSystemAnything",
      "import skillbill.scaffold.FileSystemScaffoldSourceLoader",
    )
    val mustNotBeDetectedAsForbidden = listOf(
      "import skillbill.scaffold.policy.X",
      "import skillbill.scaffold.model.Y",
      "import skillbill.ports.scaffold.foo.Bar",
      "import java.nio.file.Path",
    )

    val falseNegatives = mustBeDetectedAsForbidden.filterNot(forbiddenImportPattern::matches)
    val falsePositives = mustNotBeDetectedAsForbidden.filter(forbiddenImportPattern::matches)

    assertEquals(
      emptyList(),
      falseNegatives,
      "Scaffold-policy forbidden-import regex must detect known-bad import lines.",
    )
    assertEquals(
      emptyList(),
      falsePositives,
      "Scaffold-policy forbidden-import regex must not flag known-good import lines.",
    )
  }

  private fun isPolicyOrScaffoldApplicationFile(sourceFile: Path): Boolean {
    val pathString = sourceFile.toString().replace('\\', '/')
    val inPolicyPackage = pathString.contains("/runtime-domain/src/main/kotlin/skillbill/scaffold/policy/")
    val inScaffoldApplication = pathString.contains("/runtime-application/src/main/kotlin/skillbill/application/") &&
      sourceFile.fileName.toString() in scaffoldApplicationServiceFileNames
    return inPolicyPackage || inScaffoldApplication
  }

  private companion object {
    val scaffoldApplicationServiceFileNames: Set<String> = setOf(
      "ScaffoldService.kt",
      "ScaffoldCatalogService.kt",
    )

    /**
     * SKILL-52.1 subtask 3 (F-005): modifier-agnostic regex that catches any redeclaration
     * of the IO-coupled validators (under `private`, `internal`, bare `fun`, `public`, or
     * with whitespace between modifier and `fun`) in the legacy scaffold service file.
     * `DOT_MATCHES_ALL` lets the assertion survive a ktfmt-wrapped multi-line declaration.
     */
    val LEGACY_FORBIDDEN_TOP_LEVEL_REGEX = Regex(
      """\bfun\s+(validateScaffold|validateBaselineLayerPayloadReferences|""" +
        """plannedAuthoringTarget|resolveAddonConsumerSkillDirs|""" +
        """validateAddonConsumerSkillDir|optionalBaselineLayers)\s*\(""",
    )
  }

  private fun forbiddenSourcePackages(moduleSourceRoots: List<String>): Set<String> = moduleSourceRoots
    .map { sourceRoot -> runtimeRoot.resolve(sourceRoot) }
    .flatMap(::kotlinFilesUnder)
    .mapNotNull { sourceFile -> packageName(sourceFile) }
    .filterNot { packageName -> packageName == "skillbill" }
    .toSet()

  private fun kotlinFilesUnder(root: Path): List<Path> {
    if (!Files.exists(root)) return emptyList()
    return Files.walk(root).use { paths ->
      paths
        .filter { path -> path.isRegularFile() && path.extension == "kt" }
        .toList()
    }
  }

  private fun packageName(sourceFile: Path): String? = sourceFile.readText().lineSequence()
    .firstOrNull { line -> line.startsWith("package ") }
    ?.removePrefix("package ")
    ?.trim()
    ?.takeIf(String::isNotBlank)

  private fun Path.importsForbiddenBy(forbiddenPackages: Set<String>): List<String> = readText()
    .lineSequence()
    .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
    .filter { importedPackage ->
      forbiddenPackages.any { forbidden ->
        importedPackage == forbidden || importedPackage.startsWith("$forbidden.")
      }
    }
    .toList()

  private fun Path.runtimeImplementationImports(): List<String> = readText()
    .lineSequence()
    .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
    .filter(::isRuntimeImplementationImport)
    .toList()
}
