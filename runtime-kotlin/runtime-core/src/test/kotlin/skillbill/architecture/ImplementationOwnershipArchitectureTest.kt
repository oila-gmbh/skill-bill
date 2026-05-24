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
      "skillbill/install/InstallOperations.kt",
      "skillbill/scaffold/ScaffoldService.kt",
      "skillbill/nativeagent/NativeAgentOperations.kt",
      "skillbill/launcher/McpRegistrationOperations.kt",
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
    val forbiddenApiDependencies = listOf(
      ":runtime-contracts",
      ":runtime-infra-fs",
      ":runtime-infra-http",
      ":runtime-infra-sqlite",
    ).filter { dependency -> runtimeCoreBuild.contains("api(project(\"$dependency\"))") }
    assertEquals(
      emptyList(),
      forbiddenApiDependencies,
      "runtime-core must not re-export contract or concrete implementation modules as adapter API.",
    )

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
    val violations = kotlinFilesUnder(runtimeRoot.resolve("runtime-core/src/main/kotlin"))
      .flatMap { sourceFile ->
        sourceFile.readText().lineSequence()
          .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
          .filter { importedName -> bannedImplementationImports.any(importedName::startsWith) }
          .map { importedName -> "${runtimeRoot.relativize(sourceFile)} imports $importedName" }
      }
      .sorted()
    assertEquals(emptyList(), violations, "runtime-core must not import moved implementation packages.")
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
    val adapterDependencies = mapOf(
      "runtime-cli/build.gradle.kts" to listOf(
        ":runtime-application",
        ":runtime-contracts",
        ":runtime-core",
        ":runtime-domain",
        ":runtime-infra-fs",
        ":runtime-infra-http",
        ":runtime-ports",
      ),
      "runtime-mcp/build.gradle.kts" to listOf(
        ":runtime-application",
        ":runtime-contracts",
        ":runtime-core",
        ":runtime-domain",
        ":runtime-infra-fs",
        ":runtime-infra-http",
        ":runtime-ports",
      ),
      "runtime-desktop/core/data/build.gradle.kts" to listOf(
        ":runtime-application",
        ":runtime-core",
        ":runtime-domain",
        ":runtime-infra-fs",
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

  private fun isRuntimeImplementationImport(importedName: String): Boolean {
    val forbiddenPrefixes = listOf(
      "skillbill.db.",
      "skillbill.infrastructure.",
      "skillbill.infrastructure.fs.",
      "skillbill.infrastructure.http.",
      "skillbill.infrastructure.sqlite.",
      "skillbill.nativeagent.",
      "skillbill.launcher.",
      "skillbill.skillremove.",
    )
    val importsForbiddenRoot = forbiddenPrefixes.any(importedName::startsWith)
    val importsInstallImplementation = importedName.startsWith("skillbill.install.") &&
      !importedName.startsWith("skillbill.install.model.")
    val importsScaffoldImplementation = importedName.startsWith("skillbill.scaffold.") &&
      !importedName.startsWith("skillbill.scaffold.model.")
    return importsForbiddenRoot || importsInstallImplementation || importsScaffoldImplementation
  }
}
